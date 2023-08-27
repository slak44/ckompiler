package slak.ckompiler.backend.presentation.broadcast

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.messaging.handler.annotation.DestinationVariable
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.annotation.SubscribeMapping
import org.springframework.stereotype.Controller
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import slak.ckompiler.backend.dto.ViewStateNonMetadataDeltaDto
import java.security.Principal

// TODO: method to get subscriber list - probably separate "topic" with same broadcastId
// TODO: evaluate performance with real latency and consider a proper binary protocol with actual deltas
@Controller
class BroadcastController {
  // TODO: limit who can publish to creator
  // TODO: endpoint to create "broadcast", generate id + remember owner (in-memory?)
  // TODO: rate limit even for publisher?
  @MessageMapping("/broadcast/{broadcastId}")
  @SendTo("/subscribe/broadcast/{broadcastId}")
  fun broadcastState(
      @DestinationVariable broadcastId: String,
      @Payload viewState: ViewStateNonMetadataDeltaDto
  ): ViewStateNonMetadataDeltaDto {
    return viewState
  }

  // TODO: store latest source code per broadcast, and send it to the subscriber on (re)subscribe, otherwise delta doesn't make sense
  @SubscribeMapping("/broadcast/{broadcastId}")
  fun newBroadcastSubscription(@DestinationVariable broadcastId: String, principal: Principal) {
    logger.info("New subscription to broadcast {} by user {}", broadcastId, principal.name)
  }

  @EventListener
  fun onClientDisconnect(event: SessionDisconnectEvent) {
    when (event.closeStatus) {
      CloseStatus.NORMAL -> logger.info("Client disconnect by user {} (session id: {})", event.user?.name, event.sessionId)
      CloseStatus.GOING_AWAY -> logger.info("User {} navigated away or refreshed (session id: {})", event.user?.name, event.sessionId)
      else -> logger.warn(
          "Abnormal client disconnect by user {} (session id: {}). Close status was {} with reason {}",
          event.user?.name,
          event.sessionId,
          event.closeStatus.code,
          event.closeStatus.reason
      )
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(BroadcastController::class.java)
  }
}
