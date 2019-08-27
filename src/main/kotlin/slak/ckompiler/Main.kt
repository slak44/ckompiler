package slak.ckompiler

import kotlinx.cli.*
import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.CodePrintingMethods
import slak.ckompiler.analysis.createGraphviz
import slak.ckompiler.backend.nasmX64.NasmGenerator
import slak.ckompiler.lexer.IncludePaths
import slak.ckompiler.lexer.Preprocessor
import slak.ckompiler.parser.Declaration
import slak.ckompiler.parser.FunctionDefinition
import slak.ckompiler.parser.Parser
import java.io.File
import java.io.InputStream
import java.util.*
import kotlin.system.exitProcess

enum class ExitCodes(val int: Int) {
  NORMAL(0), ERROR(1), EXECUTION_FAILED(2), BAD_COMMAND(4)
}

typealias CLIDefines = Map<String, String>

/**
 * The command line interface for the compiler.
 * @param stdinStream which stream to use for the `-` argument; should be [System.in] for main
 */
class CLI(private val stdinStream: InputStream) :
    IDebugHandler by DebugHandler("CLI", "<command line>", "") {
  private fun CommandLineInterface.helpGroup(description: String) {
    addHelpEntry(object : HelpEntry {
      override fun printHelp(helpPrinter: HelpPrinter) {
        helpPrinter.printSeparator()
        helpPrinter.printText(description)
      }
    })
  }

  private data class SimpleHelpEntry(val name: String, val help: String) : HelpEntry {
    override fun printHelp(helpPrinter: HelpPrinter) {
      helpPrinter.printEntry(name, help)
    }
  }

  private class PositionalArgumentsActionHandler : PositionalArgument {
    override val maxArgs = Int.MAX_VALUE
    override val minArgs = 0
    override val name: String
      get() = throw IllegalStateException("Should never call this")

    private val actions = mutableListOf<(String) -> Boolean>()
    private val leftoverArguments = mutableListOf<String>()

    override val action = object : ArgumentAction {
      override fun invoke(argument: String) {
        val wasConsumed = actions.any { it(argument) }
        if (!wasConsumed) leftoverArguments += argument
      }
    }

    fun positionalAction(
        cli: CommandLineBuilder,
        name: String,
        help: String,
        action: (String) -> Boolean
    ) {
      cli.addUsageEntry(name)
      cli.addHelpEntry(SimpleHelpEntry(name, help))
      actions += action
    }

    fun getLeftover(): List<String> = leftoverArguments
  }

  private val cli = CommandLineInterface("ckompiler", "ckompiler", """
    A C compiler written in Kotlin.
    This command line interface tries to stay consistent with gcc and clang as much as possible.
    """.trimIndent(), "See project on GitHub: https://github.com/slak44/ckompiler")

  private val posHandler = PositionalArgumentsActionHandler()

  init {
    cli.addPositionalArgument(posHandler)
  }

  private val isPrintVersion by cli.flagArgument("--version", "Print compiler version")

  init {
    cli.helpSeparator()
  }

  private var output: Optional<String> = Optional.empty()

  init {
    cli.flagValueAction("-o", "OUTFILE", "Place output in the specified file") {
      output = Optional.of(it)
    }
  }

  private val stdin by cli.flagArgument("-", "Read translation unit from standard input")

  init {
    cli.addHelpEntry(SimpleHelpEntry("FILES...", "Translation units to be compiled"))

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
  private val isCFGOnly by cli.flagArgument("--cfg-mode",
      "Create the program's control flow graph only, don't compile")
  private val isCompileOnly by cli.flagArgument("-S", "Compile only, don't assemble")
  private val isAssembleOnly by cli.flagArgument("-c", "Assemble only, don't link")

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
    cli.helpGroup("Warning control")
  }

  // FIXME: these:
  private val wAll by cli.flagArgument("-Wall", "Currently a no-op (TODO)")
  private val wExtra by cli.flagArgument("-Wextra", "Currently a no-op (TODO)")
  private val pedantic by cli.flagArgument("-pedantic", "Currently a no-op (TODO)")

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
  }

  private val linkerFlags = mutableListOf<String>()

  init {
    cli.helpGroup("Linker options")
    posHandler.positionalAction(cli, "-lLIB", "Library name to link with") {
      if (!it.startsWith("-l")) return@positionalAction false
      linkerFlags += "${pickOsOption("linker-add-library-option")}${it.removePrefix("-l")}"
      return@positionalAction true
    }
    posHandler.positionalAction(cli, "-L DIR", "Directory to search for libraries in") {
      if (!it.startsWith("-L")) return@positionalAction false
      linkerFlags += "${pickOsOption("linker-libpath-option")} ${it.removePrefix("-L")}"
      return@positionalAction true
    }
  }
  init {
    cli.helpGroup("Graphviz options (require --cfg-mode)")
  }

  private val displayGraph by cli.flagArgument("--display-graph",
      "Run dot and display the created graph")
  private val targetFunction by cli.flagValueArgument("--target-function", "FUNC_NAME",
      "Choose which function to create a graph of", initialValue = "main")
  private val printingMethod by cli.flagValueArgument("--printing-type", "TYPE",
      "TYPE can be: SOURCE_SUBSTRING (default), print the original source in blocks" +
          "; EXPRESSION_TO_STRING, use Expression.toString" +
          "; IR_EXPRESSION_TO_STRING, use IRExpression.toString",
      CodePrintingMethods.SOURCE_SUBSTRING, CodePrintingMethods::valueOf)
  private val forceAllNodes by cli.flagArgument("--force-all-nodes",
      "Force displaying the entire control flow graph")
  private val forceUnreachable by cli.flagArgument("--force-unreachable",
      "Force displaying of unreachable basic blocks and impossible edges")

  private val files: List<String> by lazy { posHandler.getLeftover() }

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

  private data class OSOption(val linux: String, val windows: String, val mac: String)

  private val osOptions = mapOf(
      "file-opener" to OSOption("xdg-open", "start", "open"),
      "obj-format" to OSOption("elf64", "win64", "elf64"),
      "linker" to OSOption("ld", "link.exe", "ld"),
      "linker-libpath-option" to OSOption("-L", "/LIBPATH", "-L"),
      "linker-add-library-option" to OSOption("-l", "", "-l")
  )

  private val osName = System.getProperty("os.name")

  private fun pickOsOption(optionName: String): String {
    val osOpts = osOptions[optionName] ?: logger.throwICE("No such OS option found") { optionName }
    return when {
      osName == "Linux" -> osOpts.linux
      osName.startsWith("Windows") -> osOpts.windows
      osName.startsWith("Mac OS") -> osOpts.mac
      else -> {
        logger.warn("Unrecognized OS, assuming linux")
        osOpts.linux
      }
    }
  }

  private fun invokeNasm(objFile: File, asmFile: File) {
    val args = mutableListOf("nasm")
    args += listOf("-f", pickOsOption("obj-format"))
    if (debug) args += listOf("-g", "-F", "dwarf")
    args += listOf("-o", objFile.absolutePath, asmFile.absolutePath)
    ProcessBuilder(args).inheritIO().start().waitFor()
  }

  private fun link(objFiles: List<File>) {
    if (pickOsOption("linker") == "link.exe") invokeLink(objFiles)
    else invokeLd(objFiles)
  }

  private fun invokeLink(objFiles: List<File>) {
    val args = mutableListOf("link.exe")
    args += listOf("/opt", File(output.orElse("a.out")).absolutePath)
    args += listOf("msvcrt.dll")
    args += listOf("/entry", "_start")
    args += linkerFlags
    args += objFiles.map(File::getAbsolutePath)
    ProcessBuilder(args).inheritIO().start().waitFor()
  }

  private fun invokeLd(objFiles: List<File>) {
    val args = mutableListOf("ld")
    args += listOf("-o", File(output.orElse("a.out")).absolutePath)
    args += listOf("-L/lib", "-lc")
    args += listOf("-dynamic-linker", "/lib/ld-linux-x86-64.so.2")
    args += listOf("-e", "_start")
    args += linkerFlags
    args += objFiles.map(File::getAbsolutePath)
    ProcessBuilder(args).inheritIO().start().waitFor()
  }

  private fun invokeDot(source: File, target: File) {
    ProcessBuilder("dot", "-Tpng")
        .redirectInput(source)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(target)
        .start()
        .waitFor()
  }

  private fun openFileDefault(target: File) {
    ProcessBuilder(pickOsOption("file-opener"), target.absolutePath).inheritIO().start().waitFor()
  }

  private fun createTemp(prefix: String, suffix: String): File {
    return File.createTempFile(prefix, suffix, File(System.getProperty("java.io.tmpdir")))
  }

  private fun List<Diagnostic>.errors() = filter { it.id.kind == DiagnosticKind.ERROR }

  private var executionFailed = false

  private fun compile(
      text: String,
      relPath: String,
      baseName: String,
      parentDir: File,
      currentDir: File
  ): File? {
    val includePaths =
        IncludePaths.defaultPaths + IncludePaths(generalIncludes, systemIncludes, userIncludes)
    includePaths.includeBarrier = includeBarrier
    val pp = Preprocessor(
        sourceText = text,
        srcFileName = relPath,
        currentDir = parentDir,
        cliDefines = defines,
        includePaths = includePaths,
        ignoreTrigraphs = disableTrigraphs,
        targetData = MachineTargetData.x64
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

    val p = Parser(pp.tokens, relPath, text)
    if (p.diags.errors().isNotEmpty()) {
      executionFailed = true
      return null
    }

    val allFuncs = p.root.decls.mapNotNull { it as? FunctionDefinition }

    if (isCFGOnly) {
      val function = allFuncs.firstOrNull { it.name == targetFunction }
      if (function == null) {
        diagnostic {
          id = DiagnosticId.CFG_NO_SUCH_FUNCTION
          formatArgs(targetFunction)
        }
        return null
      }
      val cfg = CFG(function, relPath, text, forceAllNodes)
      val graphviz = createGraphviz(cfg, text, !forceUnreachable, printingMethod)
      when {
        displayGraph -> {
          val src = createTemp("dot_temp", ".tmp")
          src.writeText(graphviz)
          val dest = createTemp("dot_out", ".png")
          invokeDot(src, dest)
          openFileDefault(dest)
        }
        output.isEmpty -> println(graphviz)
        else -> File(output.get()).writeText(graphviz)
      }

      return null
    }

    val main = allFuncs.firstOrNull { it.name == "main" }
    val allDecls = (p.root.decls - allFuncs).map { it as Declaration }
    // FIXME: only add declarations marked 'extern'
    val declNames = allDecls.flatMap { it.idents(p.root.scope) }.map { it.name }
    val funcsCfgs = (allFuncs - main).map { CFG(it!!, relPath, text, false) }
    val mainCfg = main?.let { CFG(it, relPath, text, false) }

    val nasm = NasmGenerator(declNames, funcsCfgs, mainCfg, MachineTargetData.x64).nasm

    if (isCompileOnly) {
      val asmFile = File(currentDir, output.orElse("$baseName.s"))
      asmFile.writeText(nasm)
      return null
    }

    val asmFile = createTemp("asm_temp", ".s")
    asmFile.deleteOnExit()
    asmFile.writeText(nasm)

    if (isAssembleOnly) {
      val objFile = File(currentDir, output.orElse("$baseName.o"))
      invokeNasm(objFile, asmFile)
      return null
    }

    val objFile = File(parentDir, "$baseName.o")
    invokeNasm(objFile, asmFile)
    asmFile.delete()
    return objFile
  }

  private fun compileFile(file: File) = compile(
      file.readText(),
      file.path,
      file.nameWithoutExtension,
      file.absoluteFile.parentFile,
      File(".").absoluteFile
  )

  fun parse(args: Array<String>): ExitCodes {
    @Suppress("TooGenericExceptionCaught")
    try {
      cli.parse(args)
    } catch (err: Exception) {
      if (err is HelpPrintedException) return ExitCodes.NORMAL
      if (err is CommandLineException) return ExitCodes.BAD_COMMAND
      logger.error(err) { "Failed to parse CLI args" }
      return ExitCodes.ERROR
    }
    if (isPrintVersion) {
      println("ckompiler version ${BuildProperties.version}")
      return ExitCodes.NORMAL
    }
    Diagnostic.useColors = !disableColorDiags
    val sourceCount = srcFiles.size + if (stdin) 1 else 0
    if (sourceCount == 0) diagnostic {
      id = DiagnosticId.NO_INPUT_FILES
      executionFailed = true
    }
    val isNotLinking = isCFGOnly || isPreprocessOnly || isCompileOnly || isAssembleOnly
    if (output.isPresent && isNotLinking && sourceCount > 1) {
      diagnostic { id = DiagnosticId.MULTIPLE_FILES_PARTIAL }
      return ExitCodes.EXECUTION_FAILED
    }
    val stdinObjFile = if (stdin) {
      val inText = stdinStream.bufferedReader().readText()
      compile(inText, "-", "-", File(".").absoluteFile, File(".").absoluteFile)
    } else {
      null
    }
    val objFiles = srcFiles.mapNotNull(this::compileFile)
    val allObjFiles = stdinObjFile?.let { objFiles + it } ?: objFiles
    if (executionFailed) return ExitCodes.EXECUTION_FAILED
    if (!isNotLinking) {
      link(allObjFiles)
      for (objFile in allObjFiles) objFile.delete()
      File(output.orElse("a.out")).setExecutable(true)
    }
    return if (diags.errors().isNotEmpty()) ExitCodes.EXECUTION_FAILED else ExitCodes.NORMAL
  }
}

fun main(args: Array<String>) {
  val cli = CLI(System.`in`)
  val exitCode = cli.parse(args)
  cli.diags.forEach(Diagnostic::print)
  exitProcess(exitCode.int)
}
