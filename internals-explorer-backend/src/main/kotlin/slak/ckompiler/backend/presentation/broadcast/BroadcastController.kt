package slak.ckompiler.backend.presentation.broadcast

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.messaging.handler.annotation.DestinationVariable
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import slak.ckompiler.backend.dto.BroadcastCloseMessage
import slak.ckompiler.backend.dto.ViewStateMessage
import slak.ckompiler.backend.dto.ViewStateNonMetadataDeltaDto
import slak.ckompiler.backend.services.broadcast.BroadcastId
import slak.ckompiler.backend.services.broadcast.BroadcastService
import java.security.Principal

// TODO: evaluate performance with real latency and consider a proper binary protocol with actual deltas
@Controller
class BroadcastController(
    val broadcastService: BroadcastService,
    val simpMessagingTemplate: SimpMessagingTemplate,
) {
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
          "Abnormal client disconnect by user $userId (session id: ${event.sessionId}). " +
              "Close status was ${event.closeStatus.code} with reason ${event.closeStatus.reason}"
      )
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(BroadcastController::class.java)
  }
}
