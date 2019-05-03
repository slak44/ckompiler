package slak.test.backend

import org.junit.Test
import slak.ckompiler.backend.CodeGenerator
import slak.test.assertNoDiagnostics
import slak.test.prepareCFG
import slak.test.source

class CodegenTests {
  @Test
  fun `Main That Returns 0`() {
    val cfg = prepareCFG("int main() { return 0; }", source)
    cfg.assertNoDiagnostics()
    val asm = CodeGenerator(cfg).getNasm()
    println(asm)
  }
}
