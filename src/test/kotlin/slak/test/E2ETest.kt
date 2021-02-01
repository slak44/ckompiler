package slak.test

import slak.ckompiler.ExitCodes
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

internal class CompileAndRunBuilder {
  var text: String? = null
  var file: File? = null
  var programArgList = listOf<String>()
  var cliArgList = listOf<String>()
  var stdin: String? = null
}

internal data class RunResult(val exitCode: Int, val stdout: String, val stderr: String)

internal fun RunResult.justExitCode(expected: Int) = expect(exitCode = expected)

internal fun RunResult.expect(exitCode: Int = 0, stdout: String = "", stderr: String = "") {
  assertEquals(exitCode, this.exitCode, "Exit code is wrong;")
  assertEquals(stdout, this.stdout, "stdout is wrong;")
  assertEquals(stderr, this.stderr, "stderr is wrong;")
}

// kill -l gets this list
// Not all of the signals make sense as a return code, but include them for completion
private val signalMap = mapOf(
    1 to "SIGHUP",
    2 to "SIGINT",
    3 to "SIGQUIT",
    4 to "SIGILL",
    5 to "SIGTRAP",
    6 to "SIGABRT",
    7 to "SIGBUS",
    8 to "SIGFPE",
    9 to "SIGKILL",
    10 to "SIGUSR1",
    11 to "SIGSEGV",
    12 to "SIGUSR2",
    13 to "SIGPIPE",
    14 to "SIGALRM",
    15 to "SIGTERM",
    16 to "SIGSTKFLT",
    17 to "SIGCHLD",
    18 to "SIGCONT",
    19 to "SIGSTOP",
    20 to "SIGTSTP",
    21 to "SIGTTIN",
    22 to "SIGTTOU",
    23 to "SIGURG",
    24 to "SIGXCPU",
    25 to "SIGXFSZ",
    26 to "SIGVTALRM",
    27 to "SIGPROF",
    28 to "SIGWINCH",
    29 to "SIGIO",
    30 to "SIGPWR",
    31 to "SIGSYS",
)

internal fun <T : Any> T.compileAndRun(block: CompileAndRunBuilder.() -> Unit): RunResult {
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
      "-o", executable.absolutePath,
      *builder.cliArgList.toTypedArray()
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

  // Signals are unix stuff
  val os = System.getProperty("os.name")
  val isUnix = os == "Linux" || os.startsWith("Mac OS")
  if (isUnix && exitCode > 128) {
    fail("Execution finished with large exit code $exitCode (name: ${signalMap[exitCode - 128]})")
  }

  return RunResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
}

internal fun <T : Any> T.compileAndRun(resource: File): RunResult = compileAndRun { file = resource }

internal fun <T : Any> T.compileAndRun(code: String, programArgs: List<String> = emptyList()): RunResult {
  return compileAndRun {
    text = code
    programArgList = programArgs
  }
}
