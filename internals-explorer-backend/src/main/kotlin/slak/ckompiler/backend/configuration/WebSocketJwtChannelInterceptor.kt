package slak.ckompiler.backend.configuration

import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider

/**
 * Authorize users based on a signed OAuth JWT - using [JwtAuthenticationProvider] from Spring Security.
 *
 * The JWT is expected on the `passcode` header for the STOMP message with a CONNECT command. The DISCONNECT command will end the session
 * and clear the [SecurityContext].
 */
class WebSocketJwtChannelInterceptor(jwtDecoder: JwtDecoder) : ChannelInterceptor {
  private val securityContexts = mutableMapOf<String, SecurityContext>()

  private val jwtAuthProvider = JwtAuthenticationProvider(jwtDecoder)

  private val anonymous = AnonymousAuthenticationToken(
      "key",
      "anonymous",
      AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")
  )

  private fun getOrCreateContext(sessionId: String): SecurityContext {
    val existing = securityContexts[sessionId]
    if (existing != null) {
      return existing
    }
    val newContext = SecurityContextHolder.createEmptyContext()
    securityContexts[sessionId] = newContext
    return newContext
  }

  override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
    val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java) ?: StompHeaderAccessor.wrap(message)
    val sessionId = accessor.sessionId ?: return message
    val context = getOrCreateContext(sessionId)
    SecurityContextHolder.setContext(context)

    when (accessor.command) {
      StompCommand.CONNECT -> {
        val token = accessor.getPasscode()
        if (token != null) {
          context.authentication = jwtAuthProvider.authenticate(BearerTokenAuthenticationToken(token))
        } else {
          context.authentication = anonymous
        }
      }
      StompCommand.DISCONNECT -> {
        securityContexts.remove(sessionId)
      }
      else -> {}
    }

    accessor.user = context.authentication ?: return message

    if (accessor.isMutable) {
      accessor.setLeaveMutable(true)
    }
    return MessageBuilder.createMessage(message.payload, accessor.messageHeaders)
  }
}
