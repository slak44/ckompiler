package slak.test.backend

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.ExitCodes
import slak.ckompiler.SourceFileName
import slak.ckompiler.backend.nasmX64.NasmGenerator
import slak.test.*
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Execution(ExecutionMode.SAME_THREAD)
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
    assertTrue(File("a.out").exists())
    val process = ProcessBuilder("./a.out")
        .inheritIO()
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .start()
    val stdout = process.inputStream.bufferedReader().readText()
    return process.waitFor() to stdout
  }

  private val stdin = System.`in`

  @AfterEach
  fun restoreStdin() {
    System.setIn(stdin)
  }

  /**
   * Compiles and executes code. Returns exit code and stdout of executed code.
   */
  private fun compileAndRun(code: String, args: List<String> = emptyList()): Pair<Int, String> {
    System.setIn(code.byteInputStream())
    val (_, compilerExitCode) = cli("-")
    assertEquals(ExitCodes.NORMAL, compilerExitCode)
    assertTrue(File("a.out").exists())
    val process = ProcessBuilder("./a.out", *args.toTypedArray())
        .inheritIO()
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .start()
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

  @ParameterizedTest
  @ValueSource(strings = ["1", "1 2", "1 2 3", "1 2 3 4"])
  fun `Returns Argc`(cmdLine: String) {
    val args = cmdLine.split(" ")
    assertEquals(args.size + 1 to "", compileAndRun("int main(int argc) { return argc; }", args))
  }

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
    assertEquals(0 to "Hello World!\n", compileAndRun(resource("e2e/helloWorld.c")))
  }

  @Test
  fun `Float Ops Test`() {
    assertEquals(0 to "", compileAndRun(resource("e2e/floatOps.c")))
  }

  @ParameterizedTest
  @ValueSource(strings = ["!0", "!(1-1)"])
  fun `Unary Not`(code: String) {
    assertEquals(1 to "", compileAndRun("int main() { return $code; }"))
  }

  @ParameterizedTest
  @ValueSource(strings = ["(int) 1.1F", "(int) 1.0F", "(int) 1.99F"])
  fun `Float Cast To Int`(code: String) {
    assertEquals(1 to "", compileAndRun("int main() { return $code; }"))
  }

  @ParameterizedTest
  @ValueSource(strings = ["1", "123", "-1"])
  fun `Int Cast To Float`(int: String) {
    assertEquals(0 to "$int.00", compileAndRun("""
      extern int printf(const char*, ...);
      int main() {
        printf("%.2f", (float) $int);
        return 0;
      }
    """.trimIndent()))
  }

  @Test
  fun `Int Pointers Referencing And Dereferencing`() {
    assertEquals(12 to "", compileAndRun("""
      int main() {
        int a = 12;
        int* b = &a;
        int c = *b;
        return c;
      }
    """.trimIndent()))
  }

  @Disabled("fix dereferencing and synthetic store targets first")
  @Test
  fun `Simple Array Usage`() {
    assertEquals(12 to "", compileAndRun("""
      int main() {
        int a[2];
        a[0] = 12;
        a[1] = 13;
        return a[0];
      }
    """.trimIndent()))
  }

  @Test
  fun `Ternary Test`() {
    assertEquals(13 to "", compileAndRun(resource("e2e/ternaryOps.c")))
  }
}
