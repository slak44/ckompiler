package slak.ckompiler.backend.dto

enum class BroadcastMessageType {
  VIEW_STATE,
  SUBSCRIBER_CHANGE,
  BROADCAST_CLOSE,
}

sealed class BroadcastMessage(val type: BroadcastMessageType)

data class ViewStateMessage(val viewState: ViewStateNonMetadataDeltaDto) : BroadcastMessage(BroadcastMessageType.VIEW_STATE)
data class SubscriberChangeMessage(val subscribers: List<String>) : BroadcastMessage(BroadcastMessageType.SUBSCRIBER_CHANGE)
data object BroadcastCloseMessage : BroadcastMessage(BroadcastMessageType.BROADCAST_CLOSE)
