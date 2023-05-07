package slak.ckompiler.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication

@SpringBootApplication
@EntityScan("slak.ckompiler.backend.entities")
class InternalsExplorerBackendApplication

fun main(args: Array<String>) {
  runApplication<InternalsExplorerBackendApplication>(*args)
}
