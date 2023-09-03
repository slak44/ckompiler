package slak.ckompiler.backend.presentation

import org.springframework.web.bind.annotation.*
import slak.ckompiler.backend.dto.UserStateDto
import slak.ckompiler.backend.services.broadcast.BroadcastService
import slak.ckompiler.backend.services.UserStateService
import java.security.Principal

@RestController
@RequestMapping("/api/userstate")
class UserStateController(
    val userStateService: UserStateService,
    val broadcastService: BroadcastService,
) {
  @GetMapping
  fun getUserState(principal: Principal): UserStateDto {
    return userStateService.findById(principal.name).copy(
        activeBroadcast = broadcastService.getBroadcastByPresenterId(principal.name)
    )
  }
}
