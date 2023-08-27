package slak.ckompiler.backend

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessageDeliveryException
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.authorization.SpringAuthorizationEventPublisher
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.messaging.access.intercept.AuthorizationChannelInterceptor
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager
import org.springframework.security.messaging.context.AuthenticationPrincipalArgumentResolver
import org.springframework.security.messaging.context.SecurityContextChannelInterceptor
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler

@Configuration
class SchedulerConfiguration {
  @Bean(destroyMethod = "shutdown", initMethod = "initialize")
  fun heartbeatScheduler() = ThreadPoolTaskScheduler()
}

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfiguration(
    @Value("\${ckompiler.allowed-origins:}") val allowedOriginList: List<String>,
    val applicationEventPublisher: ApplicationEventPublisher,
    jwtDecoder: JwtDecoder,
    val heartbeatScheduler: ThreadPoolTaskScheduler,
) : WebSocketMessageBrokerConfigurer {
  private val tokenChannelInterceptor = object : ChannelInterceptor {
    private val jwtAuthProvider = JwtAuthenticationProvider(jwtDecoder)

    private val anonymous = AnonymousAuthenticationToken(
        "key",
        "anonymous",
        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")
    )

    private val sessions = mutableMapOf<String, SecurityContext>()

    private fun getContext(sessionId: String): SecurityContext {
      val existing = sessions[sessionId]
      if (existing != null) {
        return existing
      }
      val newContext = SecurityContextHolder.createEmptyContext()
      sessions[sessionId] = newContext
      return newContext
    }

    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
      val accessor = StompHeaderAccessor.wrap(message)
      val sessionId = accessor.sessionId ?: return message
      val context = getContext(sessionId)
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
          sessions.remove(sessionId)
        }
        else -> {}
      }

      accessor.user = context.authentication ?: return message

      accessor.setLeaveMutable(true)
      return MessageBuilder.createMessage(message.payload, accessor.messageHeaders)
    }
  }

  override fun registerStompEndpoints(registry: StompEndpointRegistry) {
    registry.addEndpoint(WEBSOCKET_ENDPOINT)
        .setAllowedOrigins(*allowedOriginList.toTypedArray())

    registry.setErrorHandler(object : StompSubProtocolErrorHandler() {
      override fun handleClientMessageProcessingError(clientMessage: Message<ByteArray>?, ex: Throwable): Message<ByteArray>? {
        val cause = ex.cause
        return if (ex is MessageDeliveryException && cause != null) {
          logger.error(cause.stackTraceToString())
          super.handleClientMessageProcessingError(clientMessage, cause)
        } else {
          super.handleClientMessageProcessingError(clientMessage, ex)
        }
      }
    })
  }

  override fun configureMessageBroker(registry: MessageBrokerRegistry) {
    registry.enableSimpleBroker("/subscribe")
        .setTaskScheduler(heartbeatScheduler)
    registry.setApplicationDestinationPrefixes("/publish")
  }

  /**
   * Use the configuration here instead of @EnableWebSocketSecurity, which doesn't support disabling CSRF (for now).
   *
   * [Spring Security Websockets](https://docs.spring.io/spring-security/reference/servlet/integrations/websocket.html)
   */
  override fun configureClientInboundChannel(registration: ChannelRegistration) {
    val manager: AuthorizationManager<Message<*>> = MessageMatcherDelegatingAuthorizationManager.builder()
        .nullDestMatcher().authenticated()
        .simpSubscribeDestMatchers("/subscribe/**").authenticated()
        .simpDestMatchers("/publish/**").authenticated()
        .anyMessage().denyAll()
        .build()
    val authChannelInterceptor = AuthorizationChannelInterceptor(manager)
    authChannelInterceptor.setAuthorizationEventPublisher(SpringAuthorizationEventPublisher(applicationEventPublisher))
    registration.interceptors(tokenChannelInterceptor, authChannelInterceptor)
  }

  override fun addArgumentResolvers(argumentResolvers: MutableList<HandlerMethodArgumentResolver>) {
    argumentResolvers.add(AuthenticationPrincipalArgumentResolver())
  }

  companion object {
    const val WEBSOCKET_ENDPOINT = "/api/broadcast/ws"

    private val logger = LoggerFactory.getLogger(WebSocketConfiguration::class.java)
  }
}
