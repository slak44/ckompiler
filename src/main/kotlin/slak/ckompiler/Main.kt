package slak.ckompiler

import kotlinx.cli.*
import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.createGraphviz
import slak.ckompiler.backend.nasm_x86_64.NasmGenerator
import slak.ckompiler.lexer.IncludePaths
import slak.ckompiler.lexer.Preprocessor
import slak.ckompiler.parser.FunctionDefinition
import slak.ckompiler.parser.Parser
import java.io.File
import kotlin.system.exitProcess

enum class ExitCodes(val int: Int) {
  NORMAL(0), ERROR(1), EXECUTION_FAILED(2), BAD_COMMAND(4)
}

class CLI : IDebugHandler by DebugHandler("CLI", "<command line>", "") {
  private fun CommandLineInterface.helpGroup(description: String) {
    addHelpEntry(object : HelpEntry {
      override fun printHelp(helpPrinter: HelpPrinter) {
        helpPrinter.printSeparator()
        helpPrinter.printText(description)
      }
    })
  }

  private val cli = CommandLineInterface("ckompiler", "ckompiler", """
    A C compiler written in Kotlin.
    This command line interface tries to stay consistent with gcc and clang as much as possible.
    """.trimIndent(), "See project on GitHub: https://github.com/slak44/ckompiler")

  private val output by cli.flagValueArgument("-o", "OUTFILE",
      "Place output in the specified file", "a.out")
  private val files by cli.positionalArgumentsList(
      "FILES...", "Translation units to be compiled", minArgs = 1)

  init {
    cli.helpSeparator()
  }

  val defines = mutableMapOf<String, String>()

  init {
    cli.flagValueAction("-D", "<macro>=<value>",
        "Define <macro> with <value>, or 1 if value is ommited") {
      defines += if ('=' in it) {
        val (name, value) = it.split("=")
        name to value
      } else {
        it to "1"
      }
    }
    cli.helpGroup("Operation modes")
  }

  private val isPreprocessOnly by cli.flagArgument("-E", "Preprocess only")
  private val isCompileOnly by cli.flagArgument("-S", "Compile only, don't assemble")
  private val isAssembleOnly by cli.flagArgument("-c", "Assemble only, don't link")
  private val isPrintCFGMode by cli.flagArgument("--print-cfg-graphviz",
      "Print the program's control flow graph to stdout instead of compiling")

  init {
    cli.helpGroup("Debug options")
  }

  private val debug by cli.flagArgument("-g", "Add debug data")

  init {
    cli.helpGroup("Feature toggles")
  }

  // FIXME: maybe add versions without no- that override each other
  private val disableTrigraphs by cli.flagArgument("-fno-trigraphs",
      "Ignore trigraphs in source files")
  private val disableColorDiags by cli.flagArgument("-fno-color-diagnostics",
      "Disable colors in diagnostic messages")

  init {
    cli.helpGroup("Include path management")
  }

  private val includeBarrier by cli.flagArgument(listOf("-I-", "--include-barrier"),
      "Remove current directory from include list", false, flagValue = false)

  private val generalIncludes = mutableListOf<File>()

  init {
    cli.flagValueAction(listOf("-I", "--include-directory"), "DIR",
        "Directory to add to include search path") { generalIncludes += File(it) }
  }

  private val userIncludes = mutableListOf<File>()

  init {
    cli.flagValueAction("-iquote", "DIR",
        "Directory to add to \"...\" include search path") { userIncludes += File(it) }
  }

  private val systemIncludes = mutableListOf<File>()

  init {
    cli.flagValueAction("-isystem", "DIR",
        "Directory to add to <...> search path") { systemIncludes.add(0, File(it)) }
    cli.flagValueAction("-isystem-after", "DIR",
        "Directory to add to the end of the <...> search path") { systemIncludes += File(it) }

    cli.helpGroup("Graphviz options (require --print-cfg-graphviz)")
  }

  private val forceToString by cli.flagArgument("--force-to-string",
      "Force using ASTNode.toString instead of printing the original expression source")
  private val forceAllNodes by cli.flagArgument("--force-all-nodes",
      "Force displaying the entire control flow graph")
  private val forceUnreachable by cli.flagArgument("--force-unreachable",
      "Force displaying of unreachable basic blocks and impossible edges")

  private val srcFiles: List<File> by lazy {
    val badOptions = files.filter { it.startsWith("--") }
    for (option in badOptions) diagnostic {
      id = DiagnosticId.BAD_CLI_OPTION
      formatArgs(option)
    }
    (files - badOptions).mapNotNull {
      val file = File(it)
      if (!file.exists()) {
        diagnostic {
          id = DiagnosticId.FILE_NOT_FOUND
          formatArgs(it)
        }
        return@mapNotNull null
      }
      if (file.isDirectory) {
        diagnostic {
          id = DiagnosticId.FILE_IS_DIRECTORY
          formatArgs(it)
        }
        return@mapNotNull null
      }
      return@mapNotNull file
    }
  }

  private fun invokeNasm(objFile: File, asmFile: File) {
    val args = mutableListOf("nasm")
    args += listOf("-f", "elf64")
    if (debug) args += listOf("-g", "-F", "dwarf")
    args += listOf("-o", objFile.absolutePath, asmFile.absolutePath)
    ProcessBuilder(args).inheritIO().start().waitFor()
  }

  private fun invokeLd(objFiles: List<File>) {
    ProcessBuilder("ld", "-o", File(output).absolutePath, "-L/lib", "-lc", "-dynamic-linker",
        "/lib/ld-linux-x86-64.so.2", "-e", "main",
        *objFiles.map(File::getAbsolutePath).toTypedArray())
        .inheritIO().start().waitFor()
  }

  private fun List<Diagnostic>.errors() = filter { it.id.kind == DiagnosticKind.ERROR }

  private var executionFailed = false

  private fun compileFile(file: File): File? {
    val text = file.readText()
    val srcFileName = file.path
    val currentDir = file.absoluteFile.parentFile
    val includePaths =
        IncludePaths.defaultPaths + IncludePaths(generalIncludes, systemIncludes, userIncludes)
    includePaths.includeBarrier = includeBarrier
    val pp = Preprocessor(
        sourceText = text,
        srcFileName = srcFileName,
        currentDir = currentDir,
        cliDefines = defines,
        includePaths = includePaths,
        ignoreTrigraphs = disableTrigraphs
    )
    if (pp.diags.errors().isNotEmpty()) {
      executionFailed = true
      return null
    }
    if (isPreprocessOnly) {
      // FIXME
//      println(pp.alteredSourceText)
      return null
    }

    val p = Parser(pp.tokens, srcFileName, text)
    if (p.diags.errors().isNotEmpty()) {
      executionFailed = true
      return null
    }

    // FIXME: this is incomplete
    val firstFun = p.root.decls.first { d -> d is FunctionDefinition } as FunctionDefinition

    if (isPrintCFGMode) {
      val cfg = CFG(firstFun, srcFileName, text, forceAllNodes)
      println(createGraphviz(cfg, text, !forceUnreachable, forceToString))
      return null
    }

    val cfg = CFG(firstFun, srcFileName, text, false)
    val asmFile = File(currentDir, file.nameWithoutExtension + ".s")
    asmFile.writeText(NasmGenerator(cfg, true).getNasm())
    if (isCompileOnly) return null

    val objFile = File(currentDir, file.nameWithoutExtension + ".o")
    invokeNasm(objFile, asmFile)
    asmFile.delete()
    return objFile
  }

  fun parse(args: Array<String>): ExitCodes {
    try {
      cli.parse(args)
    } catch (err: Exception) {
      if (err is HelpPrintedException) return ExitCodes.NORMAL
      if (err is CommandLineException) return ExitCodes.BAD_COMMAND
      logger.error(err) { "Failed to parse CLI args" }
      return ExitCodes.ERROR
    }
    Diagnostic.useColors = !disableColorDiags
    val objFiles = srcFiles.mapNotNull(this::compileFile)
    if (srcFiles.isEmpty()) diagnostic {
      id = DiagnosticId.NO_INPUT_FILES
      executionFailed = true
    }
    if (executionFailed) return ExitCodes.EXECUTION_FAILED
    if (isAssembleOnly || isCompileOnly || isPrintCFGMode) return ExitCodes.NORMAL
    invokeLd(objFiles)
    File(output).setExecutable(true)
    return if (diags.errors().isNotEmpty()) ExitCodes.EXECUTION_FAILED else ExitCodes.NORMAL
  }
}

fun main(args: Array<String>) {
  val cli = CLI()
  val exitCode = cli.parse(args)
  cli.diags.forEach(Diagnostic::print)
  exitProcess(exitCode.int)
}
