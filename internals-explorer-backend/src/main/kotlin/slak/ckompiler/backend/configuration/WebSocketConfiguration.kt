package slak.ckompiler.backend.configuration

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.Message
import org.springframework.messaging.MessageDeliveryException
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.authorization.SpringAuthorizationEventPublisher
import org.springframework.security.messaging.access.intercept.AuthorizationChannelInterceptor
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager
import org.springframework.security.messaging.context.AuthenticationPrincipalArgumentResolver
import org.springframework.security.oauth2.jwt.JwtDecoder
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
  private val tokenChannelInterceptor = WebSocketJwtChannelInterceptor(jwtDecoder)

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
    registry.setUserDestinationPrefix("/user")
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
        .simpSubscribeDestMatchers("/user/**").authenticated()
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
