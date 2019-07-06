package slak.test.backend

import org.junit.Test
import slak.ckompiler.SourceFileName
import slak.ckompiler.backend.nasm_x86_64.NasmGenerator
import slak.test.assertNoDiagnostics
import slak.test.prepareCFG
import slak.test.source

class NasmTests {
  private fun prepareNasm(src: String, source: SourceFileName): String {
    val cfg = prepareCFG(src, source)
    cfg.assertNoDiagnostics()
    val asm = NasmGenerator(cfg, true).nasm
    println(asm)
    return asm
  }

  @Test
  fun `Main That Returns 0`() {
    prepareNasm("int main() { return 0; }", source)
  }

  @Test
  fun `Main That Returns 1+1`() {
    prepareNasm("int main() { return 1 + 1; }", source)
  }

  @Test
  fun `Local Variable`() {
    prepareNasm("int main() { int a; return 0; }", source)
  }

  @Test
  fun `Local Variable Assignment`() {
    prepareNasm("int main() { int a = 123; return 0; }", source)
  }

  @Test
  fun `Local Variable Usage`() {
    prepareNasm("int main() { int a = 123; return a; }", source)
  }
}
