package slak.test.backend

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
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
    val asm = NasmGenerator(emptyList(), emptyList(), cfg).nasm
    println(asm)
    return asm
  }

  /**
   * Compiles and executes code. Returns exit code and stdout of executed code.
   */
  private fun compileAndRun(resource: File): Pair<Int, String> {
    val (_, compilerExitCode) = cli(resource.absolutePath)
    assertEquals(ExitCodes.NORMAL, compilerExitCode)
    val process = ProcessBuilder("./a.out")
        .inheritIO().redirectOutput(ProcessBuilder.Redirect.PIPE).start()
    val stdout = process.inputStream.bufferedReader().readText()
    return process.waitFor() to stdout
  }

  @AfterEach
  fun removeCompilerOutput() {
    File("a.out").delete()
  }

  @Test
  fun `Main That Returns 0`() {
    prepareNasm("int main() { return 0; }", source)
  }

  @Test
  fun `Multiple Functions`() {
    prepareNasm("int f() { return 0; } int main() { return 0; }", source)
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

  // FIXME: doesn't check if it worked
  @Test
  fun `String Data`() {
    prepareNasm("int main() { \"asdfg\"; return 1; }", source)
  }

  // FIXME: doesn't check if it worked
  @Test
  fun `String Data Not Duplicated`() {
    prepareNasm("int main() { \"asdfg\"; \"asdfg\"; return 1; }", source)
  }

  // FIXME: doesn't check if it worked
  @Test
  fun `String Names Are Alphanumeric`() {
    prepareNasm("int main() { \"a:.><\"; return 1; }", source)
  }

  // FIXME: doesn't check if it worked
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

  // FIXME: stdin in CLI
//  @Test
//  fun `Returns Argc`() {
//    assertEquals(0 to "", compileAndRun("int main(int argc) { return argc; }"))
//  }

  @Test
  fun `Exit Code 10`() {
    assertEquals(10 to "", compileAndRun(resource("e2e/returns10.c")))
  }

  @Test
  fun `Exit Code Sum`() {
    assertEquals(2 to "", compileAndRun(resource("e2e/returns1+1.c")))
  }

  @Test
  fun `Simple If With False Condition`() {
    assertEquals(0 to "", compileAndRun(resource("e2e/simpleIf.c")))
  }

  @Test
  fun `If With Variable As Condition`() {
    assertEquals(1 to "", compileAndRun(resource("e2e/cmpVariable.c")))
  }

  @Test
  fun `Hello World!`() {
    assertEquals(0 to "Hello World!", compileAndRun(resource("e2e/helloWorld.c")))
  }
}
