package slak.test

import org.junit.After
import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.ExitCodes
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CLITests {
  private val stdin = System.`in`

  @After
  fun restoreStdin() {
    System.setIn(stdin)
  }

  @After
  fun removeCompilerOutput() {
    File("a.out").delete()
    File(".").listFiles()!!.filter { it.extension == "o" }.forEach { it.delete() }
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

  @Test
  fun `Explicit Output With Multiple Outputs Should Fail`() {
    val (cli, exitCode) = cli(
        "-S", "-o", "/tmp/bla",
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
    assertTrue(resource("e2e/returns10.o").exists())
    resource("e2e/returns10.o").delete()
  }

  @Test
  fun `Assemble Only With Output`() {
    val (cli, exitCode) = cli("-o", "bla.o", resource("e2e/returns10.c").absolutePath)
    cli.assertNoDiagnostics()
    assertEquals(ExitCodes.NORMAL, exitCode)
    assertFalse(File("a.out").exists())
    assertTrue(File("bla.o").exists())
  }

  @Test
  fun `Bad Option`() {
    val (cli, exitCode) = cli("--dfghbnjgned-not-an-actual-option")
    cli.assertDiags(DiagnosticId.BAD_CLI_OPTION, DiagnosticId.NO_INPUT_FILES)
    assertEquals(ExitCodes.EXECUTION_FAILED, exitCode)
    assertFalse(File("a.out").exists())
  }
}
