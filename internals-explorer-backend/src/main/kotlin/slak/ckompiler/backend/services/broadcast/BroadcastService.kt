package slak.ckompiler.backend.services.broadcast

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import slak.ckompiler.backend.dto.ViewStateMessageDto
import java.util.*
import java.util.concurrent.ConcurrentHashMap

typealias BroadcastId = String

data class ActiveBroadcast(
    val id: BroadcastId,
    val presenterUserId: String,
    val subscribers: List<String>,
)

@Service
class BroadcastService {
  private val activeBroadcasts: MutableMap<String, ActiveBroadcast> = ConcurrentHashMap()

  /**
   * This cannot be accessed concurrently because we only have a single presenter. If the presenter sends concurrent messages, that's his
   * fault.
   */
  private val activeBroadcastState: MutableMap<String, ViewStateMessageDto> = mutableMapOf()

  fun getBroadcastByPresenterId(presenterUserId: String): ActiveBroadcast? {
    return activeBroadcasts.values.find { it.presenterUserId == presenterUserId }
  }

  fun createBroadcast(presenterUserId: String): ActiveBroadcast {
    val existingBroadcast = getBroadcastByPresenterId(presenterUserId)
    if (existingBroadcast != null) {
      throw IllegalStateException(
          "A single concurrent broadcast per user is permitted. " +
              "Please close the existing broadcast first. " +
              "ActiveBroadcast: $existingBroadcast"
      )
    }

    val id = UUID.randomUUID().toString()
    val broadcast = ActiveBroadcast(id, presenterUserId, listOf())

    activeBroadcasts[id] = broadcast

    return broadcast
  }

  fun closeBroadcast(broadcastId: BroadcastId) {
    activeBroadcastState.remove(broadcastId)
    val closedBroadcast = activeBroadcasts.remove(broadcastId)
    if (closedBroadcast == null) {
      logger.warn("Trying to close broadcast with ID $broadcastId that doesn't exist")
    }
  }

  fun updateCurrentState(broadcastId: BroadcastId, delta: ViewStateMessageDto) {
    val oldState = activeBroadcastState[broadcastId]?.state
    val newState = delta.state

    if (oldState == null) {
      activeBroadcastState[broadcastId] = delta
      return
    }

    if (newState == null) {
      activeBroadcastState[broadcastId] = ViewStateMessageDto(oldState, delta.pos)
      return
    }

    val sourceCode = delta.state.sourceCode ?: oldState.sourceCode
    activeBroadcastState[broadcastId] = delta.copy(state = newState.copy(sourceCode = sourceCode))
  }

  fun getCurrentState(broadcastId: BroadcastId): ViewStateMessageDto {
    return activeBroadcastState[broadcastId] ?: throw IllegalStateException("Broadcast state $broadcastId doesn't exist")
  }

  fun getActiveSubscribers(broadcastId: BroadcastId): List<String> {
    val broadcast = activeBroadcasts[broadcastId] ?: throw IllegalStateException("Broadcast state $broadcastId doesn't exist")
    return broadcast.subscribers - broadcast.presenterUserId
  }

  fun getPresenterId(broadcastId: BroadcastId): String {
    val broadcast = activeBroadcasts[broadcastId] ?: throw IllegalStateException("Broadcast state $broadcastId doesn't exist")
    return broadcast.presenterUserId
  }

  fun markSubscription(broadcastId: BroadcastId, subscriberId: String): Boolean {
    val broadcast = activeBroadcasts[broadcastId] ?: return false
    activeBroadcasts[broadcastId] = broadcast.copy(subscribers = broadcast.subscribers + subscriberId)
    return true
  }

  fun markUnsubscription(broadcastId: BroadcastId, subscriberId: String): Boolean {
    val broadcast = activeBroadcasts[broadcastId] ?: return false
    activeBroadcasts[broadcastId] = broadcast.copy(subscribers = broadcast.subscribers - subscriberId)
    return true
  }

  fun markDisconnect(subscriberId: String) {
    for (activeBroadcast in activeBroadcasts.values.toList()) {
      activeBroadcasts[activeBroadcast.id] = activeBroadcast.copy(subscribers = activeBroadcast.subscribers - subscriberId)
    }
  }

  fun isPresenter(broadcastId: BroadcastId, userId: String): Boolean {
    return activeBroadcasts[broadcastId]?.presenterUserId == userId
  }

  companion object {
    private val logger = LoggerFactory.getLogger(BroadcastService::class.java)
  }
}
