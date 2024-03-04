package slak.ckompiler.backend.dto

enum class BroadcastMessageType {
  VIEW_STATE,
  BROADCAST_METADATA,
  SUBSCRIBER_CHANGE,
  BROADCAST_CLOSE,
}

/**
 * The values are normalized coordinates, not pixels.
 */
data class MousePosition(val x: Double, val y: Double)

sealed class BroadcastMessage(val type: BroadcastMessageType)

data class ViewStateMessage(
    val viewState: ViewStateNonMetadataDeltaDto?,
    val pos: MousePosition,
) : BroadcastMessage(BroadcastMessageType.VIEW_STATE) {
  constructor(dto: ViewStateMessageDto) : this(dto.state, dto.pos)
}

data class BroadcastMetadataMessage(val broadcasterName: String) : BroadcastMessage(BroadcastMessageType.BROADCAST_METADATA)

data class SubscriberChangeMessage(val subscribers: List<String>) : BroadcastMessage(BroadcastMessageType.SUBSCRIBER_CHANGE)

data object BroadcastCloseMessage : BroadcastMessage(BroadcastMessageType.BROADCAST_CLOSE)
