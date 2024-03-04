package slak.ckompiler.backend.presentation

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import slak.ckompiler.backend.dto.UserStateDto
import slak.ckompiler.backend.services.UserStateService
import slak.ckompiler.backend.services.broadcast.BroadcastService
import java.security.Principal

@RestController
@RequestMapping("/api/userstate")
class UserStateController(
    val userStateService: UserStateService,
    val broadcastService: BroadcastService,
) {
  @GetMapping
  fun getUserState(principal: Principal): UserStateDto {
    return userStateService.findById(principal.name, principal as JwtAuthenticationToken).copy(
        activeBroadcast = broadcastService.getBroadcastByPresenterId(principal.name)
    )
  }
}
