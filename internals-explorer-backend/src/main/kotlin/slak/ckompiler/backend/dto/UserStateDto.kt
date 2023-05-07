package slak.ckompiler.backend.dto

import slak.ckompiler.backend.entities.UserState
import slak.ckompiler.backend.entities.ViewState

data class UserStateDto(val id: String, val autosaveViewState: ViewState?) {
  constructor(entity: UserState) : this(entity.id, entity.autosaveViewState)
}
