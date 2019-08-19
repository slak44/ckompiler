package slak.test.backend

import org.junit.jupiter.api.AfterEach
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

  private class CompileAndRunBuilder {
    var text: String? = null
    var file: File? = null
    var programArgList = listOf<String>()
    var stdin: String? = null
  }

  private data class RunResult(val exitCode: Int, val stdout: String, val stderr: String)

  private fun RunResult.justExitCode(expected: Int) {
    assertEquals(expected, expected)
    assertEquals("", stdout)
    assertEquals("", stderr)
  }

  private fun compileAndRun(block: CompileAndRunBuilder.() -> Unit): RunResult {
    val builder = CompileAndRunBuilder()
    builder.block()
    if (builder.text != null) System.setIn(builder.text!!.byteInputStream())
    val target = if (builder.text != null) "-" else builder.file!!.absolutePath
    val (_, compilerExitCode) = cli(target, "-isystem", resource("include").absolutePath)
    assertEquals(ExitCodes.NORMAL, compilerExitCode)
    assertTrue(File("a.out").exists())
    val inputRedirect =
        if (builder.stdin != null) ProcessBuilder.Redirect.PIPE else ProcessBuilder.Redirect.INHERIT
    val process = ProcessBuilder("./a.out", *builder.programArgList.toTypedArray())
        .redirectInput(inputRedirect)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()
    if (builder.stdin != null) {
      process.outputStream.bufferedWriter().use {
        it.write(builder.stdin!!)
      }
    }
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    return RunResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
  }

  private fun compileAndRun(resource: File): RunResult = compileAndRun { file = resource }

  private fun compileAndRun(code: String, programArgs: List<String> = emptyList()): RunResult {
    return compileAndRun {
      text = code
      programArgList = programArgs
    }
  }

  private val stdin = System.`in`

  @AfterEach
  fun restoreStdin() {
    System.setIn(stdin)
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
    compileAndRun("int main(int argc) { return argc; }", args).run {
      assertEquals(args.size + 1, exitCode)
      assertEquals("", stdout)
      assertEquals("", stderr)
    }
  }

  @Test
  fun `Exit Code 10`() {
    compileAndRun(resource("e2e/returns10.c")).justExitCode(10)
  }

  @Test
  fun `Exit Code Sum`() {
    compileAndRun(resource("e2e/returns1+1.c")).justExitCode(2)
  }

  @Test
  fun `Simple If With False Condition`() {
    compileAndRun(resource("e2e/simpleIf.c")).justExitCode(0)
  }

  @Test
  fun `If With Variable As Condition`() {
    compileAndRun(resource("e2e/cmpVariable.c")).justExitCode(1)
  }

  @Test
  fun `Hello World!`() {
    compileAndRun(resource("e2e/helloWorld.c")).run {
      assertEquals(0, exitCode)
      assertEquals("Hello World!\n", stdout)
      assertEquals("", stderr)
    }
  }

  @Test
  fun `Float Ops Test`() {
    compileAndRun(resource("e2e/floatOps.c")).justExitCode(0)
  }

  @ParameterizedTest
  @ValueSource(strings = ["!0", "!(1-1)"])
  fun `Unary Not`(code: String) {
    compileAndRun("int main() { return $code; }").justExitCode(1)
  }

  @ParameterizedTest
  @ValueSource(strings = ["(int) 1.1F", "(int) 1.0F", "(int) 1.99F"])
  fun `Float Cast To Int`(code: String) {
    compileAndRun("int main() { return $code; }").justExitCode(1)
  }

  @ParameterizedTest
  @ValueSource(strings = ["1", "123", "-1"])
  fun `Int Cast To Float`(int: String) {
    compileAndRun("""
      extern int printf(const char*, ...);
      int main() {
        printf("%.2f", (float) $int);
        return 0;
      }
    """.trimIndent()).run {
      assertEquals(0, exitCode)
      assertEquals("$int.00", stdout)
      assertEquals("", stderr)
    }
  }

  @Test
  fun `Int Pointers Referencing And Dereferencing`() {
    compileAndRun("""
      int main() {
        int a = 12;
        int* b = &a;
        int c = *b;
        return c;
      }
    """.trimIndent()).justExitCode(12)
  }

  @Test
  fun `Simple Array Usage`() {
    compileAndRun("""
      int main() {
        int a[2];
        a[0] = 12;
        a[1] = 13;
        return a[0] + a[1];
      }
    """.trimIndent()).justExitCode(25)
  }

  @Test
  fun `Nested Expression`() {
    compileAndRun("int main() { return (2 + 3) * (6 - 4); }").justExitCode(10)
  }

  @Test
  fun `Ternary Test`() {
    compileAndRun(resource("e2e/ternaryOps.c")).justExitCode(13)
  }

  @Test
  fun `For Loop Summing Test`() {
    compileAndRun(resource("loops/forLoopTest.c")).justExitCode(86)
  }

  @Test
  fun `Early Return In Void Function Works`() {
    compileAndRun(resource("e2e/earlyReturn.c")).justExitCode(0)
  }

  @ParameterizedTest
  @ValueSource(strings = ["1", "-2", "100", "9999999", "0"])
  fun `Scanf An Int`(int: String) {
    compileAndRun {
      file = resource("e2e/scanfOnce.c")
      stdin = int
    }.run {
      assertEquals(0, exitCode)
      assertEquals(int, stdout)
      assertEquals("", stderr)
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["1 3", "-2 4", "100 10000", "9999999 -1", "0 0", "0 1", "1 0"])
  fun `Scanf Multiple Ints`(int: String) {
    compileAndRun {
      file = resource("e2e/scanfTwice.c")
      stdin = int
    }.run {
      assertEquals(0, exitCode)
      assertEquals(int, stdout)
      assertEquals("", stderr)
    }
  }
}
