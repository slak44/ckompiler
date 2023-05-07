package slak.ckompiler.backend.presentation

import org.springframework.web.bind.annotation.*
import slak.ckompiler.backend.dto.UserStateDto
import slak.ckompiler.backend.services.UserStateService
import java.security.Principal

@RestController
@RequestMapping("/api/userstate")
class UserStateController(
    val userStateService: UserStateService,
) {
  @GetMapping
  fun getUserState(principal: Principal): UserStateDto {
    return userStateService.findById(principal.name)
  }
}
