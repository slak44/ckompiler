package slak.ckompiler.backend.presentation

import org.springframework.security.access.prepost.PostAuthorize
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import slak.ckompiler.backend.dto.ViewStateDto
import slak.ckompiler.backend.dto.ViewStateListingDto
import slak.ckompiler.backend.services.ViewStateService
import java.security.Principal

@RestController
@RequestMapping("/api/viewstate")
class ViewStateController(
    val viewStateService: ViewStateService,
) {
  @PostMapping
  @PreAuthorize("#viewState.owner == null")
  fun save(@RequestBody viewState: ViewStateDto, principal: Principal): ViewStateDto {
    viewState.owner = principal.name
    return ViewStateDto(viewStateService.save(viewState))
  }

  @PostMapping("/autosave")
  @PreAuthorize("#viewState.owner == null")
  fun saveAutosave(@RequestBody viewState: ViewStateDto, principal: Principal): ViewStateDto {
    viewState.owner = principal.name
    return ViewStateDto(viewStateService.saveAutosave(viewState))
  }

  @GetMapping("/list")
  fun getViewStates(principal: Principal): List<ViewStateListingDto> {
    return viewStateService.getViewStates(principal.name)
  }

  @GetMapping("/{id}")
  @PostAuthorize("returnObject.owner == authentication.name")
  fun getViewState(@PathVariable id: String): ViewStateDto {
    return ViewStateDto(viewStateService.findById(id))
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("@viewStateService.findById(#id).owner == authentication.name")
  fun deleteViewState(@PathVariable id: String) {
    return viewStateService.deleteById(id)
  }
}
