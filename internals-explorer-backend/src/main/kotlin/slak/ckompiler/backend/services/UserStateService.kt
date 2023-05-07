package slak.ckompiler.backend.services

import org.springframework.stereotype.Service
import slak.ckompiler.backend.dto.UserStateDto
import slak.ckompiler.backend.entities.UserState
import slak.ckompiler.backend.entities.UserStateRepository
import slak.ckompiler.backend.entities.ViewState
import slak.ckompiler.backend.entities.ViewStateRepository
import kotlin.jvm.optionals.getOrNull

@Service
class UserStateService(val userStateRepository: UserStateRepository, val viewStateRepository: ViewStateRepository) {
  fun findById(id: String): UserStateDto {
    return userStateRepository.findById(id).map(::UserStateDto).orElseGet { UserStateDto(id, null) }
  }

  fun markAutosaveViewState(viewState: ViewState) {
    val userState = userStateRepository.findById(viewState.owner).orElse(null)
    if (userState == null) {
      userStateRepository.save(UserState(viewState.owner, viewState))
    } else {
      val oldAutosave = userState.autosaveViewState
      userState.autosaveViewState = viewState
      userStateRepository.save(userState)
      viewStateRepository.delete(oldAutosave)
    }
  }
}
