package slak.test.backend

import org.junit.After
import org.junit.Test
import slak.ckompiler.ExitCodes
import slak.ckompiler.SourceFileName
import slak.ckompiler.backend.nasm_x86_64.NasmGenerator
import slak.test.*
import java.io.File
import kotlin.test.assertEquals

class NasmTests {
  private fun prepareNasm(src: String, source: SourceFileName): String {
    val cfg = prepareCFG(src, source)
    cfg.assertNoDiagnostics()
    val asm = NasmGenerator(cfg, true).nasm
    println(asm)
    return asm
  }

  private fun compileAndRun(resource: File): Int {
    val (_, compilerExitCode) = cli(resource.absolutePath)
    assertEquals(ExitCodes.NORMAL, compilerExitCode)
    return ProcessBuilder("./a.out").inheritIO().start().waitFor()
  }

  @After
  fun removeCompilerOutput() {
    File("a.out").delete()
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

  @Test
  fun `Simple If With Compare`() {
    prepareNasm("""
      int main() {
        int a = 1;
        if (a < 55) {
          return 1;
        }
        return 0;
      }
    """.trimIndent(), source)
  }

  @Test
  fun `Exit Code 10`() {
    assertEquals(10, compileAndRun(resource("e2e/returns10.c")))
  }

  @Test
  fun `Exit Code Sum`() {
    assertEquals(2, compileAndRun(resource("e2e/returns1+1.c")))
  }

  @Test
  fun `Simple If With False Condition`() {
    assertEquals(0, compileAndRun(resource("e2e/simpleIf.c")))
  }

  @Test
  fun `If With Variable As Condition`() {
    assertEquals(1, compileAndRun(resource("e2e/cmpVariable.c")))
  }
}
