package slak.ckompiler.backend.presentation

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/viewstate")
class ViewStateController {
  @GetMapping
  fun test(): String {
    return """{"hello": "world!"}"""
  }
}
