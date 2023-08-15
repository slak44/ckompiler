package slak.ckompiler.backend.services

import org.springframework.stereotype.Service
import slak.ckompiler.backend.dto.ViewStateDto
import slak.ckompiler.backend.dto.ViewStateListingDto
import slak.ckompiler.backend.entities.ViewState
import slak.ckompiler.backend.entities.ViewStateRepository
import java.util.*

@Service
class ViewStateService(val repository: ViewStateRepository, val userStateService: UserStateService) {
  fun save(viewStateDto: ViewStateDto): ViewState {
    return repository.save(ViewState(viewStateDto))
  }

  fun saveAutosave(viewStateDto: ViewStateDto): ViewState {
    val viewState = repository.save(ViewState(viewStateDto))
    userStateService.markAutosaveViewState(viewState)
    return viewState
  }

  fun findById(id: String): ViewState {
    return repository.findById(UUID.fromString(id)).orElseThrow()
  }

  fun deleteById(id: String) {
    return repository.deleteById(UUID.fromString(id))
  }

  fun getViewStates(owner: String): List<ViewStateListingDto> {
    return repository.findAllByOwner(owner).map { ViewStateListingDto(it.id!!.toString(), it.name, it.createdAt, it.publicShareEnabled) }
  }

  fun configurePublicShare(id: String, isEnabled: Boolean) {
    val viewState = repository.getReferenceById(UUID.fromString(id))
    viewState.publicShareEnabled = isEnabled
    repository.save(viewState)
  }
}
