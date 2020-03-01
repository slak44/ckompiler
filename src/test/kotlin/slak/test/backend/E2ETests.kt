package slak.test.backend

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.ExitCodes
import slak.test.cli
import slak.test.resource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class E2ETests {
  private class CompileAndRunBuilder {
    var text: String? = null
    var file: File? = null
    var programArgList = listOf<String>()
    var stdin: String? = null
  }

  private data class RunResult(val exitCode: Int, val stdout: String, val stderr: String)

  private fun RunResult.justExitCode(expected: Int) = expect(exitCode = expected)

  private fun RunResult.expect(exitCode: Int = 0, stdout: String = "", stderr: String = "") {
    assertEquals(exitCode, this.exitCode, "Exit code is wrong;")
    assertEquals(stdout, this.stdout, "stdout is wrong;")
    assertEquals(stderr, this.stderr, "stderr is wrong;")
  }

  private fun compileAndRun(block: CompileAndRunBuilder.() -> Unit): RunResult {
    val builder = CompileAndRunBuilder()
    builder.block()
    require(builder.text != null || builder.file != null) { "Specify either text or file" }
    if (builder.file == null) {
      builder.file = File.createTempFile("input", ".c")
      builder.file!!.deleteOnExit()
      builder.file!!.writeText(builder.text!!)
    }
    if (builder.text == null) {
      val otherInput = File.createTempFile("input", ".c")
      otherInput.deleteOnExit()
      otherInput.writeText(builder.file!!.readText())
      builder.file = otherInput
    }
    val executable = File.createTempFile("exe", ".out")
    executable.deleteOnExit()
    val (_, compilerExitCode) = cli(
        builder.file!!.absolutePath,
        "-isystem", resource("include").absolutePath,
        "-o", executable.absolutePath
    )
    assertEquals(ExitCodes.NORMAL, compilerExitCode, "CLI reported a failure")
    assertTrue(executable.exists(), "Expected executable to exist")
    val inputRedirect =
        if (builder.stdin != null) ProcessBuilder.Redirect.PIPE else ProcessBuilder.Redirect.INHERIT
    val process = ProcessBuilder(executable.absolutePath, *builder.programArgList.toTypedArray())
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

  @ParameterizedTest
  @ValueSource(strings = ["1", "1 2", "1 2 3", "1 2 3 4"])
  fun `Returns Argc`(cmdLine: String) {
    val args = cmdLine.split(" ")
    compileAndRun("int main(int argc) { return argc; }", args).justExitCode(args.size + 1)
  }

  @Test
  fun `Empty Main Returns 0`() {
    compileAndRun(resource("e2e/emptyMain.c")).justExitCode(0)
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
    compileAndRun(resource("e2e/helloWorld.c")).expect(stdout = "Hello World!\n")
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
    """.trimIndent()).expect(stdout = "$int.00")
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
  fun `Int Pointers Referencing And Dereferencing Between Blocks`() {
    compileAndRun("""
      int main() {
        int a = 12;
        int* b = &a;
        if (1) {
          a = 33;
        } else {
          a = 44;
        }
        return a;
      }
    """.trimIndent()).justExitCode(33)
  }

  @Test
  fun `Return Int Via Pointer`() {
    compileAndRun(resource("e2e/retViaPointer.c")).justExitCode(42)
  }

  @Test
  fun `Identical String Literals Compare Equal Because Deduplication Or Folding`() {
    compileAndRun("""
      int main() {
        return "asdfg" == "asdfg";
      }
    """.trimIndent()).justExitCode(1)
  }

  @ParameterizedTest
  @ValueSource(strings = [
    "a:.><", "       ", "\uFEFE", ">>>>>>>>>>",
    "-------", "#define", "#", "!", "?", "'", "'''''''''''"
  ])
  fun `Printf Random Non-Alphanumerics`(str: String) {
    compileAndRun("""
      #include <stdio.h>
      int main() {
        printf("$str");
        return 0;
      }
    """.trimIndent()).expect(stdout = str)
  }

  @Disabled("arrays are broken in codegen")
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
  fun `Multiple Declarators`() {
    compileAndRun("int main() { int a = 1, b = 2; return a + b; }").justExitCode(3)
  }

  @Test
  fun `Ternary Test`() {
    compileAndRun(resource("e2e/ternaryOps.c")).justExitCode(13)
  }

  @Test
  fun `Shortcircuiting Test`() {
    compileAndRun(resource("e2e/shortCircuiting.c")).justExitCode(55)
  }


  @Test
  fun `For Loop Summing Test`() {
    compileAndRun(resource("loops/forLoopTest.c")).justExitCode(86)
  }

  @Test
  fun `Looped Printf`() {
    compileAndRun(resource("loops/loopedPrintf.c")).expect(stdout = "0 1 4 9 16 25 36 49 64 81 \n")
  }

  @Test
  fun `Early Return In Void Function Works`() {
    compileAndRun(resource("e2e/earlyReturn.c")).justExitCode(0)
  }

  @ParameterizedTest
  @ValueSource(strings = ["1", "-2", "100", "9999999", "0"])
  fun `Scanf An Int`(int: String) {
    compileAndRun {
      file = resource("e2e/scanfIntOnce.c")
      stdin = int
    }.expect(stdout = int)
  }

  @ParameterizedTest
  @ValueSource(strings = ["1 3", "-2 4", "100 10000", "9999999 -1", "0 0", "0 1", "1 0"])
  fun `Scanf Multiple Ints`(int: String) {
    compileAndRun {
      file = resource("e2e/scanfIntTwice.c")
      stdin = int
    }.expect(stdout = int)
  }

  @ParameterizedTest
  @ValueSource(strings = [
    "1 454 786 -345 0 5 34 -11 99 1010 -444444",
    "0 0 0 0 0 0 0 0 0 0 0",
    "-1 -1 -1 -1 -1 -1 -1 -1 -1 -1 -1"
  ])
  fun `Lots Of Int Parameters`(int: String) {
    compileAndRun {
      file = resource("e2e/manyIntParameters.c")
      stdin = int
    }.expect(stdout = int)
  }

  @ParameterizedTest
  @ValueSource(strings = [
    "-1.0000", "-2.0000", "100.0000", "9999999.0000", "0.0000",
    "-1.2000", "23.2351", "1.1000", "0.3000"
  ])
  fun `Scanf A Float`(flt: String) {
    compileAndRun {
      file = resource("e2e/scanfFloatOnce.c")
      stdin = flt
    }.expect(stdout = flt)
  }

  @Suppress("unused")
  enum class InfNaNTestCases(val expr: String, val output: String) {
    INF_EXPR("1.0 / 0.0", "inf"),
    NAN_EXPR("0.0 / 0.0", "nan"),
    NEG_NAN_EXPR("-0.0 / 0.0", "nan"),
    NEG_INF_EXPR("-1.0 / 0.0", "-inf"),
    MACRO_INF_EXPR("INFINITY", "inf"),
    NEG_MACRO_INF_EXPR("-INFINITY", "-inf"),
  }

  @ParameterizedTest
  @EnumSource(InfNaNTestCases::class)
  fun `Handle Infinities And NaNs`(test: InfNaNTestCases) {
    compileAndRun("""
      #include <stdio.h>
      #include <math.h>
      int main() {
        double res = ${test.expr};
        printf("%f", res);
        return 0;
      }
    """.trimIndent()).expect(stdout = test.output)
  }

  companion object {
    private const val A = 5
    private const val B = 23
  }

  @Suppress("unused")
  enum class IntOpsTestCases(val code: String, private val result: Int) {
    INT_ADD("res = a + b;", A + B),
    INT_SUB("res = a - b;", A - B),
    INT_MUL("res = a * b;", A * B),
    INT_DIV("res = b / a;", B / A),
    INT_REM("res = b % a;", B % A),

    INT_NEG("res = -b;", -B),
    INT_NOT("res = !b;", 0),

    INT_EQ("res = a == b;", 0),
    INT_NEQ("res = a != b;", 1),
    INT_LEQ("res = a <= b;", 1);

    val output get() = result.toString()
  }

  @ParameterizedTest
  @EnumSource(IntOpsTestCases::class)
  fun `Int Operation Tests`(test: IntOpsTestCases) {
    compileAndRun("""
      #include <stdio.h>
      int main() {
        int a = $A;
        int b = $B;
        int res;
        ${test.code}
        printf("%d", res);
        return 0;
      }
    """.trimIndent()).expect(stdout = test.output)
  }
}