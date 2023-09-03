package slak.ckompiler.backend.presentation.broadcast

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.event.EventListener
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.handler.annotation.DestinationVariable
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.messaging.AbstractSubProtocolEvent
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import org.springframework.web.socket.messaging.SessionSubscribeEvent
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent
import slak.ckompiler.backend.dto.BroadcastCloseMessage
import slak.ckompiler.backend.dto.SubscriberChangeMessage
import slak.ckompiler.backend.dto.ViewStateMessage
import slak.ckompiler.backend.dto.ViewStateNonMetadataDeltaDto
import slak.ckompiler.backend.services.BroadcastId
import slak.ckompiler.backend.services.BroadcastService
import java.security.Principal

// TODO: evaluate performance with real latency and consider a proper binary protocol with actual deltas
@Controller
class BroadcastController(
    val broadcastService: BroadcastService,
    val simpMessagingTemplate: SimpMessagingTemplate,
    @Qualifier("clientOutboundChannel") val clientOutboundChannel: MessageChannel,
) {
  private val broadcastDestinationRegex = Regex("/subscribe/broadcast/([^/\\s]+)$")

  @PostMapping("/api/broadcast/create")
  @ResponseBody
  fun createBroadcast(principal: Principal): BroadcastId {
    val broadcast = broadcastService.createBroadcast(principal.name)
    logger.info("Created broadcast ${broadcast.id} (presenter: ${principal.name})")
    return broadcast.id
  }

  @PostMapping("/api/broadcast/{broadcastId}/close")
  @ResponseBody
  @PreAuthorize("@broadcastService.isPresenter(#broadcastId, authentication.name)")
  fun closeBroadcast(@PathVariable broadcastId: BroadcastId) {
    logger.info("Broadcast $broadcastId closed")

    broadcastService.closeBroadcast(broadcastId)

    simpMessagingTemplate.convertAndSend("/subscribe/broadcast/$broadcastId", BroadcastCloseMessage)
  }

  @MessageMapping("/broadcast/{broadcastId}")
  @SendTo("/subscribe/broadcast/{broadcastId}")
  @PreAuthorize("@broadcastService.isPresenter(#broadcastId, #principal.name)")
  fun broadcastState(
      @DestinationVariable broadcastId: String,
      @Payload viewState: ViewStateNonMetadataDeltaDto,
      principal: Principal,
  ): ViewStateMessage {
    broadcastService.updateCurrentState(broadcastId, viewState)

    return ViewStateMessage(viewState)
  }

  @EventListener
  fun onSubscribe(event: SessionSubscribeEvent) {
    val (broadcastId, userId, sessionId) = extractBroadcastMetadata(event) ?: return
    logger.info("New subscription to broadcast $broadcastId by user $userId")

    val target = "/subscribe/broadcast/$broadcastId"

    val broadcastExists = broadcastService.markSubscription(broadcastId, userId)

    if (!broadcastExists) {
      val message = "Broadcast $broadcastId doesn't exist"
      logger.error(message)
      replyWithError(sessionId!!, message)
      return
    }

    // Echo the full current state to the new subscriber, so he's ready to receive deltas
    logger.debug("Sending full current state to new subscriber (session ID: $sessionId)")
    val viewState = ViewStateMessage(broadcastService.getCurrentState(broadcastId))
    simpMessagingTemplate.convertAndSendToUser(userId, target, viewState)

    // Alert the other subscribers that a new subscriber joined this topic
    val subscriberChangeMessage = SubscriberChangeMessage(broadcastService.getActiveSubscribers(broadcastId))
    simpMessagingTemplate.convertAndSend(target, subscriberChangeMessage)
  }

  @EventListener
  fun onUnsubscribe(event: SessionUnsubscribeEvent) {
    // FIXME: this event has no destination
    val (broadcastId, userId) = extractBroadcastMetadata(event) ?: return
    logger.info("User $userId unsubscribed from broadcast $broadcastId")

    val broadcastExists = broadcastService.markUnsubscription(broadcastId, userId)

    if (!broadcastExists) {
      logger.warn("Broadcast $broadcastId doesn't exist")
    }
  }

  @EventListener
  fun onClientDisconnect(event: SessionDisconnectEvent) {
    val userId = event.user?.name

    if (userId != null) {
      broadcastService.markDisconnect(userId)
    } else {
      logger.error("Missing user on disconnect - session id: ${event.sessionId}")
    }

    when (event.closeStatus) {
      CloseStatus.NORMAL -> logger.info("Client disconnect by user $userId (session id: ${event.sessionId})")
      CloseStatus.GOING_AWAY -> logger.info("User $userId navigated away or refreshed (session id: ${event.sessionId})")
      else -> logger.warn(
          "Abnormal client disconnect by user $userId (session id: $event.sessionId). " +
              "Close status was ${event.closeStatus.code} with reason ${event.closeStatus.reason}"
      )
    }
  }

  private fun extractBroadcastMetadata(event: AbstractSubProtocolEvent): Triple<BroadcastId, String, String?>? {
    val headerAccessor = StompHeaderAccessor.wrap(event.message)
    val destination = headerAccessor.destination
    if (destination?.startsWith("/user") == true) {
      // Not a message we care about
      return null
    }
    val broadcastId = destination?.let { broadcastDestinationRegex.matchEntire(it)?.groupValues?.getOrNull(1) }
    if (broadcastId == null) {
      logger.error("${event.javaClass.name} destination does not match broadcast pattern or is null: $destination")
      return null
    }

    val userId = event.user?.name
    if (userId == null) {
      logger.error("${event.javaClass.name} to $destination has no user ID")
      return null
    }

    return Triple(broadcastId, userId, headerAccessor.sessionId)
  }

  private fun replyWithError(sessionId: String, errorMessage: String) {
    val headerAccessor = StompHeaderAccessor.create(StompCommand.ERROR)
    headerAccessor.sessionId = sessionId
    headerAccessor.message = errorMessage
    val msg = MessageBuilder.createMessage(ByteArray(0), headerAccessor.messageHeaders)
    clientOutboundChannel.send(msg)
  }

  companion object {
    private val logger = LoggerFactory.getLogger(BroadcastController::class.java)
  }
}
