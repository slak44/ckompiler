package slak.test.backend

import org.junit.Test
import slak.ckompiler.backend.nasm_x86_64.NasmGenerator
import slak.test.assertNoDiagnostics
import slak.test.prepareCFG
import slak.test.source

class NasmTests {
  @Test
  fun `Main That Returns 0`() {
    val cfg = prepareCFG("int main() { return 0; }", source)
    cfg.assertNoDiagnostics()
    val asm = NasmGenerator(cfg, true).getNasm()
    println(asm)
  }
}
