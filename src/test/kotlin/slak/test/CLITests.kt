package slak.test

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.BuildProperties
import slak.ckompiler.DiagnosticId
import slak.ckompiler.ExitCodes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Execution(ExecutionMode.SAME_THREAD)
class CLITests {
  private val stdin = System.`in`
  private val stdout = System.out

  @AfterEach
  fun restorePipes() {
    System.setIn(stdin)
    System.setOut(stdout)
  }

  @AfterEach
  fun removeCompilerOutput() {
    File("a.out").delete()
    File(".").listFiles()!!.filter { it.extension == "o" }.forEach { it.delete() }
  }

  @Test
  fun `Ckompiler Properties File Exists`() {
    val fileExists = resource(BuildProperties.propFileName).exists()
    if (!fileExists) {
      System.err.println("${BuildProperties.propFileName} file is missing; aborting test process")
      exitProcess(-1)
    }
  }

  @Test
  fun `Help Is Printed And Has Exit Code 0`() {
    val (cli, exitCode) = cli("-h")
    cli.assertNoDiagnostics()
    assertEquals(ExitCodes.NORMAL, exitCode)
  }

  @Test
  fun `Multiple Macro Defines`() {
    val (cli, exitCode) = cliCmd("-D TEST -D BLA -D ASD=123")
    cli.assertDiags(DiagnosticId.NO_INPUT_FILES)
    assertEquals(mutableMapOf("TEST" to "1", "BLA" to "1", "ASD" to "123"), cli.defines)
    assertEquals(ExitCodes.EXECUTION_FAILED, exitCode)
  }

  @Test
  fun `No Input Files Doesn't Trigger On Empty CLI`() {
    val (cli, exitCode) = cliCmd(null)
    cli.assertNoDiagnostics()
    assertEquals(ExitCodes.NORMAL, exitCode)
  }

  @Test
  fun `Missing File`() {
    val (cli, exitCode) = cli("/this/file/doesnt/exist.c")
    cli.assertDiags(DiagnosticId.FILE_NOT_FOUND, DiagnosticId.NO_INPUT_FILES)
    assertEquals(ExitCodes.EXECUTION_FAILED, exitCode)
  }

  @Test
  fun `File Is Directory`() {
    val (cli, exitCode) = cli("/")
    cli.assertDiags(DiagnosticId.FILE_IS_DIRECTORY, DiagnosticId.NO_INPUT_FILES)
    assertEquals(ExitCodes.EXECUTION_FAILED, exitCode)
  }

  @Test
  fun `Allow Compilation With Warnings`() {
    val (_, exitCode) = cli(resource("codeWithWarning.c").absolutePath)
    assertEquals(ExitCodes.NORMAL, exitCode)
    assertTrue(File("a.out").exists())
  }

  @Test
  fun `Compile From Stdin`() {
    System.setIn("int main() {return 0;}".byteInputStream())
    val (_, exitCode) = cli("-")
    assertEquals(ExitCodes.NORMAL, exitCode)
    assertTrue(File("a.out").exists())
  }

  @ParameterizedTest
  @ValueSource(strings = ["-S", "-c", "-E", "--cfg-mode"])
  fun `Explicit Output With Multiple Outputs Should Fail`(option: String) {
    val (cli, exitCode) = cli(
        option, "-o", "/tmp/bla",
        resource("e2e/returns10.c").absolutePath,
        resource("e2e/returns1+1.c").absolutePath
    )
    cli.assertDiags(DiagnosticId.MULTIPLE_FILES_PARTIAL)
    assertEquals(ExitCodes.EXECUTION_FAILED, exitCode)
    assertFalse(File("a.out").exists())
  }

  @Test
  fun `Assemble Only No Output`() {
    val (cli, exitCode) = cli("-c", resource("e2e/returns10.c").absolutePath)
    cli.assertNoDiagnostics()
    assertEquals(ExitCodes.NORMAL, exitCode)
    assertFalse(File("a.out").exists())
    assertTrue(File("returns10.o").exists())
    File("returns10.o").delete()
  }

  @Test
  fun `Assemble Only With Output`() {
    val (cli, exitCode) = cli("-o", "bla.o", resource("e2e/returns10.c").absolutePath)
    cli.assertNoDiagnostics()
    assertEquals(ExitCodes.NORMAL, exitCode)
    assertFalse(File("a.out").exists())
    assertTrue(File("bla.o").exists())
  }

  @ParameterizedTest
  @ValueSource(strings = ["--dfghbnjgned-not-an-actual-option", "--include-directory/tmp"])
  fun `Bad Option`(opt: String) {
    val (cli, exitCode) = cli(opt)
    cli.assertDiags(DiagnosticId.BAD_CLI_OPTION, DiagnosticId.NO_INPUT_FILES)
    assertEquals(ExitCodes.EXECUTION_FAILED, exitCode)
    assertFalse(File("a.out").exists())
  }

  @Test
  fun `CFG No Such Function`() {
    val (cli, exitCode) =
        cli("--cfg-mode", "--target-function", "foo", resource("e2e/returns10.c").absolutePath)
    cli.assertDiags(DiagnosticId.CFG_NO_SUCH_FUNCTION)
    assertEquals(ExitCodes.EXECUTION_FAILED, exitCode)
    assertFalse(File("a.out").exists())
  }

  @Test
  fun `CFG Prints To Stdout By Default`() {
    val outStream = ByteArrayOutputStream()
    System.setOut(PrintStream(outStream))
    val (cli, exitCode) = cli("--cfg-mode", resource("e2e/returns10.c").absolutePath)
    cli.assertNoDiagnostics()
    assertEquals(ExitCodes.NORMAL, exitCode)
    assertFalse(File("a.out").exists())
    assert("return 10;" in outStream.toString())
  }

  @Test
  fun `Compile No Artifacts`() {
    System.setIn("int main() {return 0;}".byteInputStream())
    val (cli, exitCode) = cli("-")
    cli.assertNoDiagnostics()
    assertEquals(ExitCodes.NORMAL, exitCode)
    assertTrue(File("a.out").exists())
    assertFalse(File("-.o").exists())
  }

  @Test
  fun `Output Is Relative To Current Directory`() {
    File("/tmp/bla.s").delete()
    File("/tmp/testing.c").writeText(resource("e2e/returns10.c").readText())
    val (cli, exitCode) = cli("-S", "-o", "bla.s", "/tmp/testing.c")
    cli.assertNoDiagnostics()
    assertEquals(ExitCodes.NORMAL, exitCode)
    assertFalse(File("a.out").exists())
    assertFalse(File("/tmp/bla.s").exists())
    assertTrue(File("./bla.s").exists())
    File("./bla.s").deleteOnExit()
  }

  @Test
  fun `Prints Version`() {
    val (cli, exitCode) = cli("--version")
    cli.assertNoDiagnostics()
    assertEquals(ExitCodes.NORMAL, exitCode)
    assertFalse(File("a.out").exists())
  }

  @ParameterizedTest
  @ValueSource(strings = ["-L/tmp/foo", "-L /tmp/foo"])
  fun `Linker -L Flag Works`(arg: String) {
    val outStream = ByteArrayOutputStream()
    System.setOut(PrintStream(outStream))
    val (cli, exitCode) = cli(
        *arg.split(" ").toTypedArray(),
        "--print-linker-comm",
        resource("e2e/returns10.c").absolutePath
    )
    cli.assertNoDiagnostics()
    assertEquals(ExitCodes.NORMAL, exitCode)
    stdout.println(outStream)
    assert("-L/tmp/foo" in outStream.toString())
  }
}
