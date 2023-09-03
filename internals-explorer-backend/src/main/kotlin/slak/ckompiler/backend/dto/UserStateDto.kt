package slak.ckompiler.backend.dto

import slak.ckompiler.backend.entities.UserState
import slak.ckompiler.backend.entities.ViewState
import slak.ckompiler.backend.services.broadcast.ActiveBroadcast

data class UserStateDto(val id: String, val autosaveViewState: ViewState?, val activeBroadcast: ActiveBroadcast? = null) {
  constructor(entity: UserState) : this(entity.id, entity.autosaveViewState)
}
