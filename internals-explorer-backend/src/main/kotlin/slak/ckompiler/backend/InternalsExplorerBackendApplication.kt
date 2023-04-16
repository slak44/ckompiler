package slak.ckompiler.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class InternalsExplorerBackendApplication

fun main(args: Array<String>) {
  runApplication<InternalsExplorerBackendApplication>(*args)
}
