package slak.test

import org.junit.After
import org.junit.Test
import slak.ckompiler.CLI
import slak.ckompiler.Diagnostic
import slak.ckompiler.DiagnosticId
import slak.ckompiler.ExitCodes
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CLITests {
  private fun cli(commandLine: String?): Pair<CLI, ExitCodes> {
    val cli = CLI()
    val exitCode = cli.parse(commandLine?.split(" ")?.toTypedArray() ?: emptyArray())
    cli.diags.forEach(Diagnostic::print)
    return cli to exitCode
  }

  @After
  fun removeCompilerOutput() {
    File("a.out").delete()
  }

  @Test
  fun `Help Is Printed And Has Exit Code 0`() {
    val (cli, exitCode) = cli("-h")
    cli.assertNoDiagnostics()
    assertEquals(ExitCodes.NORMAL, exitCode)
  }

  @Test
  fun `Multiple Macro Defines`() {
    val (cli, exitCode) = cli("-D TEST -D BLA -D ASD=123")
    cli.assertNoDiagnostics()
    assertEquals(mutableMapOf("TEST" to "1", "BLA" to "1", "ASD" to "123"), cli.defines)
    assertEquals(ExitCodes.BAD_COMMAND, exitCode)
  }

  @Test
  fun `No Input Files Doesn't Trigger On Empty CLI`() {
    val (cli, exitCode) = cli(null)
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
}
