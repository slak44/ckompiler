package slak.ckompiler.backend.presentation

import org.springframework.security.access.prepost.PostAuthorize
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
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
    return ViewStateDto(viewStateService.saveAutosave(viewState, principal as JwtAuthenticationToken))
  }

  @GetMapping("/list")
  fun getViewStates(principal: Principal): List<ViewStateListingDto> {
    return viewStateService.getViewStates(principal.name)
  }

  @GetMapping("/{id}")
  @PostAuthorize("returnObject.owner == authentication.name || returnObject.publicShareEnabled")
  fun getViewState(@PathVariable id: String): ViewStateDto {
    return ViewStateDto(viewStateService.findById(id))
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("@viewStateService.findById(#id).owner == authentication.name")
  fun deleteViewState(@PathVariable id: String) {
    return viewStateService.deleteById(id)
  }

  @PatchMapping("/{id}/share-config")
  @PreAuthorize("@viewStateService.findById(#id).owner == authentication.name")
  fun configurePublicShare(@PathVariable id: String, @RequestBody isEnabled: Boolean) {
    viewStateService.configurePublicShare(id, isEnabled)
  }
}
