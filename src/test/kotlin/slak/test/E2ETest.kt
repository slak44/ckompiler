package slak.test

import slak.ckompiler.ExitCodes
import slak.ckompiler.backend.ISAType
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

internal class CompileAndRunBuilder {
  var text: String? = null
  var file: File? = null
  var programArgList = listOf<String>()
  var cliArgList = listOf<String>()
  var stdin: String? = null
  var targets = listOf(ISAType.X64, ISAType.MIPS32).dropLast(1)
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
private val unixSignalMap = mapOf(
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

  return builder.targets.map {
    when (it) {
      ISAType.X64 -> compileAndRunX64(builder)
      ISAType.MIPS32 -> compileAndRunMIPS32(builder)
    }
  }.reduce { left, right ->
    assertEquals(left, right, "Results are different between compilation targets")

    return left
  }
}

private fun <T : Any> T.compileAndRunMIPS32(builder: CompileAndRunBuilder): RunResult {
  val assemblyFile = File.createTempFile("mips_asm", ".s")
  assemblyFile.deleteOnExit()

  val (_, compilerExitCode) = cli(
      builder.file!!.absolutePath,
      "-isystem", resource("include").absolutePath,
      "-S",
      "-o", assemblyFile.absolutePath,
      "--target", "mips32",
      *builder.cliArgList.toTypedArray()
  )
  assertEquals(ExitCodes.NORMAL, compilerExitCode, "CLI reported a failure")
  assertTrue(assemblyFile.exists(), "Expected assembly file to exist")
  val inputRedirect =
      if (builder.stdin != null) ProcessBuilder.Redirect.PIPE else ProcessBuilder.Redirect.INHERIT
  val process = ProcessBuilder("spim", "-quiet", "-file", assemblyFile.absolutePath, *builder.programArgList.toTypedArray())
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
  val didExit = process.waitFor(1, TimeUnit.SECONDS)

  if (!didExit) {
    process.destroyForcibly()
    fail("Process execution timed out after 1 second; process killed")
  }

  val exitCode = process.exitValue()

  if (
    "Instruction references undefined symbol" in stderr ||
    "spim: (parser) syntax error" in stderr ||
    "[Bad data address]  occurred and ignored" in stderr ||
    "Address error in inst/data fetch" in stdout
  ) {
    fail("$stdout\nstderr: $stderr\nexit code: $exitCode")
  }

  return RunResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
}

private fun <T : Any> T.compileAndRunX64(builder: CompileAndRunBuilder): RunResult {
  val executable = File.createTempFile("exe", ".out")
  executable.deleteOnExit()

  val (_, compilerExitCode) = cli(
      builder.file!!.absolutePath,
      "-isystem", resource("include").absolutePath,
      "-o", executable.absolutePath,
      "--target", "x86_64",
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
    fail("Execution finished with large exit code $exitCode (name: ${unixSignalMap[exitCode - 128]})")
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
