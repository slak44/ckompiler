package slak.ckompiler.backend.services.broadcast

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.event.EventListener
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Service
import org.springframework.web.socket.messaging.AbstractSubProtocolEvent
import org.springframework.web.socket.messaging.SessionSubscribeEvent
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent
import slak.ckompiler.backend.dto.SubscriberChangeMessage
import slak.ckompiler.backend.dto.ViewStateMessage

@Service
class BroadcastSubscriptionManager(
    val broadcastService: BroadcastService,
    val simpMessagingTemplate: SimpMessagingTemplate,
    @Qualifier("clientOutboundChannel") val clientOutboundChannel: MessageChannel,
) {
  private val subscriptionIdToBroadcastId = mutableMapOf<String, BroadcastId>()

  @EventListener
  fun onSubscribe(event: SessionSubscribeEvent) {
    val (broadcastId, userId, sessionId) = extractSubscriptionMetadata(event) ?: return
    logger.info("New subscription to broadcast $broadcastId by user $userId")

    val target = "/subscribe/broadcast/$broadcastId"

    val broadcastExists = broadcastService.markSubscription(broadcastId, userId)

    if (!broadcastExists) {
      val message = "Broadcast $broadcastId doesn't exist"
      logger.error(message)
      replyWithError(sessionId, message)
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
    val (broadcastId, userId) = extractSubscriptionMetadata(event) ?: return
    logger.info("User $userId unsubscribed from broadcast $broadcastId")

    val broadcastExists = broadcastService.markUnsubscription(broadcastId, userId)

    if (!broadcastExists) {
      // Most likely, the unsubscribe event was sent after the disconnect event
      logger.debug("Broadcast $broadcastId doesn't exist")
    }
  }

  private fun extractSubscriptionMetadata(event: AbstractSubProtocolEvent): Triple<BroadcastId, String, String>? {
    val headerAccessor = StompHeaderAccessor.wrap(event.message)

    val sessionId = headerAccessor.sessionId
    if (sessionId == null) {
      logger.error("${event.javaClass.name} has null session id")
      return null
    }

    val subscriptionId = headerAccessor.subscriptionId
    if (subscriptionId == null) {
      logger.error("${event.javaClass.name} is not a subscription event or has no subscription id header")
      return null
    }

    val destination = headerAccessor.destination
    if (destination != null && !destination.startsWith("/subscribe")) {
      // Not a message we care about
      // In particular, this filters /user/**
      return null
    }

    val broadcastId = if (destination != null) {
      val broadcastId = broadcastDestinationRegex.matchEntire(destination)?.groupValues?.getOrNull(1)
      if (broadcastId == null) {
        logger.error("${event.javaClass.name} destination does not match broadcast pattern: $destination")
        return null
      }

      subscriptionIdToBroadcastId["$sessionId-$subscriptionId"] = broadcastId

      broadcastId
    } else {
      val id = subscriptionIdToBroadcastId.remove("$sessionId-$subscriptionId")

      if (id == null) {
        // This is usually unsubscribing from /user/**
        logger.debug("Subscription ID $subscriptionId for session $sessionId has no registered broadcast ID")
        return null
      }

      id
    }

    val userId = event.user?.name
    if (userId == null) {
      logger.error("${event.javaClass.name} to $destination has no user ID")
      return null
    }

    return Triple(broadcastId, userId, sessionId)
  }

  private fun replyWithError(sessionId: String, errorMessage: String) {
    val headerAccessor = StompHeaderAccessor.create(StompCommand.ERROR)
    headerAccessor.sessionId = sessionId
    headerAccessor.message = errorMessage
    val msg = MessageBuilder.createMessage(ByteArray(0), headerAccessor.messageHeaders)
    clientOutboundChannel.send(msg)
  }

  companion object {
    private val logger = LoggerFactory.getLogger(BroadcastSubscriptionManager::class.java)

    private val broadcastDestinationRegex = Regex("/subscribe/broadcast/([^/\\s]+)$")
  }
}
