package slak.ckompiler

import kotlinx.cli.*
import mu.KotlinLogging
import slak.ckompiler.analysis.*
import slak.ckompiler.analysis.external.CodePrintingMethods
import slak.ckompiler.analysis.external.GraphvizOptions
import slak.ckompiler.analysis.external.createGraphviz
import slak.ckompiler.analysis.external.exportCFG
import slak.ckompiler.backend.*
import slak.ckompiler.lexer.CLIDefines
import slak.ckompiler.lexer.IncludePaths
import slak.ckompiler.lexer.Preprocessor
import slak.ckompiler.parser.Declaration
import slak.ckompiler.parser.FunctionDefinition
import slak.ckompiler.parser.Parser
import java.io.File

enum class ExitCodes(val int: Int) {
  NORMAL(0), ERROR(1), EXECUTION_FAILED(2), BAD_COMMAND(4)
}

/**
 * The command line interface for the compiler.
 */
class CLI : IDebugHandler by DebugHandler("CLI", "<command line>", "") {

  private val currentDir = File(System.getProperty("user.dir")!!)

  private fun fileFrom(path: String): File {
    val file = File(path)
    return if (file.isAbsolute) file else File(currentDir, path)
  }

  private val cli = object : CommandLineInterface("ckompiler", "ckompiler [options] [-] FILES...", """
    A C compiler written in Kotlin.
    This command line interface tries to stay consistent with gcc and clang as much as possible.
    """.trimIndent(), "See project on GitHub: https://github.com/slak44/ckompiler"),
      BlackHoleBuilder {
    override val blackHole = PositionalBlackHole()

    init {
      addPositionalArgument(blackHole)
    }
  }

  private val isPrintVersion by cli.flagArgument("--version", "Print compiler version")

  init {
    cli.helpSeparator()
  }

  private var output: String? = null

  init {
    cli.flagValueAction("-o", "OUTFILE", "Place output in the specified file") {
      output = it
    }
  }

  private val stdin by cli.flagArgument("-", "Read translation unit from standard input")
  private val files by cli.positionalList("FILES...",
      "Translation units to be compiled", priority = -1) {
    if (it.startsWith("-")) {
      diagnostic {
        id = DiagnosticId.BAD_CLI_OPTION
        formatArgs(it)
      }
      null
    } else {
      fileFrom(it)
    }
  }

  init {
    cli.helpSeparator()
  }

  val defines: CLIDefines by cli.flagValueArgumentMap("-D", "<macro>=<value>",
      "Define <macro> with <value>, or 1 if value is ommited") {
    if ('=' in it) {
      val (name, value) = it.split("=")
      name to value
    } else {
      it to "1"
    }
  }

  init {
    cli.helpGroup("Operation modes")
  }

  private val isPreprocessOnly by cli.flagArgument("-E", "Preprocess only")
  private val isCFGOnly by cli.flagArgument("--cfg-mode",
      "Create the program's control flow graph only, don't compile")
  private val isMIDebugOnly by cli.flagArgument("--mi-debug",
      "Print MI/RegAlloc debug data, don't assemble")
  private val isCompileOnly by cli.flagArgument("-S", "Compile only, don't assemble")
  private val isAssembleOnly by cli.flagArgument("-c", "Assemble only, don't link")

  init {
    cli.helpGroup("Debug options")
  }

  private val debug by cli.flagArgument("-g", "Add debug data")

  init {
    cli.helpGroup("Feature toggles")
  }

  private val useTrigraphs by cli.toggleableFlagArgument("-ftrigraphs", "-fno-trigraphs",
      "Control trigraph usage in source files", initialValue = false)
  private val useColorDiags by cli.toggleableFlagArgument(
      "-fcolor-diagnostics", "-fno-color-diagnostics",
      "Control colors in diagnostic messages", initialValue = true)

  // FIXME: stub:
  private val positionIndependentCode by cli.toggleableFlagArgument("-fPIC", "-fno-PIC",
      "Generate position independent code", initialValue = true)

  init {
    cli.helpGroup("Warning control")
  }

  // FIXME: stub:
  private val wAll by cli.flagArgument("-Wall", "Currently a no-op (TODO)")
  private val wExtra by cli.flagArgument("-Wextra", "Currently a no-op (TODO)")
  private val pedantic by cli.flagArgument("-pedantic", "Currently a no-op (TODO)")

  init {
    cli.helpGroup("Include path management")
  }

  private val includeBarrier by cli.flagArgument(listOf("-I-", "--include-barrier"),
      "Remove current directory from include list", false, flagValue = false)

  private val generalIncludes: List<File> by cli.flagOrPositionalArgumentList(
      flags = listOf("-I", "--include-directory"),
      valueSyntax = "DIR",
      help = "Directory to add to include search path",
      positionalPredicate = { it.startsWith("-I") },
      mapping = { fileFrom(it.removePrefix("-I")) }
  )

  private val userIncludes: List<File> by cli.flagValueArgumentList("-iquote", "DIR",
      "Directory to add to \"...\" include search path", ::fileFrom)

  private val systemIncludes: List<File> by cli.flagValueArgumentList("-isystem", "DIR",
      "Directory to add to <...> search path", ::fileFrom)
  private val systemIncludesAfter: List<File> by cli.flagValueArgumentList("-isystem-after", "DIR",
      "Directory to add to the end of the <...> search path", ::fileFrom)

  init {
    cli.helpGroup("Preprocessor options")
  }

  // FIXME: stubs:
  private val md by cli.flagArgument("-MD", "Currently a no-op (TODO)")
  private val mt by cli.flagValueArgument("-MT", "FILE", "Currently a no-op (TODO)")
  private val mf by cli.flagValueArgument("-MF", "FILE", "Currently a no-op (TODO)")

  init {
    cli.helpGroup("Linker options")
  }

  private val linkLibNames: List<String> by cli.flagOrPositionalArgumentList(
      flags = listOf("-l"),
      valueSyntax = "LIB",
      help = "Library name to link with",
      positionalPredicate = { it.startsWith("-l") },
      mapping = { it.removePrefix("-l") }
  )

  private val linkLibDirNames: List<String> by cli.flagOrPositionalArgumentList(
      flags = listOf("-L"),
      valueSyntax = "DIR",
      help = "Directory to search for libraries in",
      positionalPredicate = { it.startsWith("-L") },
      mapping = { it.removePrefix("-L") }
  )

  init {
    cli.helpGroup("Optimization options")
  }

  private val baseTargetOpts = object : TargetOptions {
    override val omitFramePointer by cli.toggleableFlagArgument(
        "-fomit-frame-pointer", "-fno-omit-frame-pointer",
        "Do not maintain a frame pointer if possible", initialValue = true)
  }

  init {
    cli.helpGroup("Compilation target options")
  }

  private val isaType by cli.flagValueArgument("--target", "TARGET",
      "Select the desired compilation target", ISAType.X64, ISAType::fromOptionsString)

  private val targetSpecific: List<String> by cli.flagOrPositionalArgumentList(
      flags = listOf("-m"),
      valueSyntax = "VALUE",
      help = "Each target has its specific options, with their own syntax",
      positionalPredicate = { it.startsWith("-m") },
      mapping = { it.removePrefix("-m") }
  )

  init {
    cli.helpGroup("Graphviz options (require --cfg-mode)")
  }

  private val displayGraph by cli.flagArgument("--display-graph",
      "Run dot and display the created graph")
  private val targetFunction by cli.flagValueArgument("--target-function", "FUNC_NAME",
      "Choose which function to create a graph of", initialValue = "main")
  private val printingMethod by cli.flagValueArgument("--printing-type", "TYPE",
      """
        |Which text to put in the CFG blocks
        |    SOURCE_SUBSTRING (default), print the original source in blocks
        |    EXPR_TO_STRING, use Expression.toString
        |    IR_TO_STRING, use IRInstruction.toString
        |    MI_TO_STRING, use MachineInstruction.toString
        |    ASM_TO_STRING use AsmInstruction.toString
      """.trimMargin(),
      CodePrintingMethods.SOURCE_SUBSTRING, CodePrintingMethods::valueOf)
  private val forceAllNodes by cli.flagArgument("--force-all-nodes",
      "Force displaying the entire control flow graph")
  private val forceUnreachable by cli.flagArgument("--force-unreachable",
      "Force displaying of unreachable basic blocks and impossible edges")
  private val noAllocOnlySpill by cli.flagArgument("--cfg-only-spill",
      "Run the spiller, then show the CFG without running register allocation")

  init {
    cli.helpGroup("CFG debug options (require --cfg-mode)")
  }

  private val exportCFGAsJSON by cli.flagArgument("--export-cfg", "Export CFG contents as JSON")

  init {
    cli.helpGroup("MI debug options (require --mi-debug)")
  }

  private val miDebugFuncName by cli.flagValueArgument("--mi-function", "FUNC_NAME",
      "Choose which function to print debug data for", initialValue = "main")

  private val showDummies by cli.flagArgument("--show-dummies", "Show all dummy allocations")
  private val miHtmlOutput by cli.flagArgument("--mi-html", "Generate pretty HTML debug view")
  private val spillOutput by cli.flagArgument("--mi-spill", "Generate spill debug info")

  init {
    cli.helpGroup("Compiler debug options")
  }

  private val printLinkerComm by cli.flagArgument("--print-linker-comm", "Print linker commands")
  private val printAsmComm by cli.flagArgument("--print-asm-comm", "Print assembler commands")

  private val srcFiles: List<File> by lazy {
    files.mapNotNull {
      val file = it
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
      "linker" to OSOption("ld", "link.exe", "ld")
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

  private fun assemble(objFile: File, asmFile: File): Unit = when (isaType) {
    ISAType.X64 -> invokeNasm(objFile, asmFile)
    ISAType.MIPS32 -> TODO("MIPS")
  }

  private fun invokeNasm(objFile: File, asmFile: File) {
    val args = mutableListOf("nasm")
    args += listOf("-f", pickOsOption("obj-format"))
    if (debug) args += listOf("-g", "-F", "dwarf")
    args += listOf("-o", objFile.absolutePath, asmFile.absolutePath)
    if (printAsmComm) println(args.joinToString(" "))
    ProcessBuilder(args).inheritIO().start().waitFor()
  }

  private fun link(objFiles: List<File>) {
    if (pickOsOption("linker") == "link.exe") invokeLink(objFiles)
    else invokeLd(objFiles)
  }

  private fun invokeLink(objFiles: List<File>) {
    val args = mutableListOf("link.exe")
    args += listOf("/opt", fileFrom(output ?: "a.out").absolutePath)
    args += listOf("msvcrt.dll")
    args += listOf("/entry", "_start")
    args += linkLibDirNames.map { "/LIBPATH:\"$it\"" }
    args += linkLibNames
    args += objFiles.map(File::getAbsolutePath)
    if (printLinkerComm) println(args.joinToString(" "))
    ProcessBuilder(args).inheritIO().start().waitFor()
  }

  private fun invokeLd(objFiles: List<File>) {
    val args = mutableListOf("ld")
    args += listOf("-o", fileFrom(output ?: "a.out").absolutePath)
    args += listOf("-L/lib", "-lc")
    args += listOf("-dynamic-linker", "/lib/ld-linux-x86-64.so.2")
    args += listOf("-e", "_start")
    args += linkLibDirNames.map { "-L$it" }
    args += linkLibNames.map { "-l$it" }
    args += objFiles.map(File::getAbsolutePath)
    if (printLinkerComm) println(args.joinToString(" "))
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

  private fun List<FunctionDefinition>.findNamedFunction(funcName: String): FunctionDefinition? {
    val function = firstOrNull { it.name == funcName }
    if (function == null) {
      diagnostic {
        id = DiagnosticId.CFG_NO_SUCH_FUNCTION
        formatArgs(funcName)
      }
      return null
    }
    return function
  }

  private var executionFailed = false

  private fun compile(
      text: String,
      relPath: String,
      baseName: String,
      parentDir: File,
  ): File? {
    val includePaths = IncludePaths.defaultPaths +
        IncludePaths(generalIncludes.map(::FSPath), (systemIncludes + systemIncludesAfter).map(::FSPath), userIncludes.map(::FSPath))
    includePaths.includeBarrier = includeBarrier
    val pp = Preprocessor(
        sourceText = text,
        srcFileName = relPath,
        currentDir = FSPath(parentDir),
        cliDefines = defines,
        includePaths = includePaths,
        ignoreTrigraphs = !useTrigraphs,
        targetData = isaType.machineTargetData
    )

    pp.diags.forEach { it.print() }

    if (pp.diags.errors().isNotEmpty()) {
      executionFailed = true
      return null
    }
    if (isPreprocessOnly) {
      // FIXME
//      println(pp.alteredSourceText)
      return null
    }

    val p = Parser(pp.tokens, relPath, text, isaType.machineTargetData)

    p.diags.forEach { it.print() }

    if (p.diags.errors().isNotEmpty()) {
      executionFailed = true
      return null
    }

    val allFuncs = p.root.decls.mapNotNull { it as? FunctionDefinition }

    val target = createMachineTarget(isaType, baseTargetOpts, targetSpecific)

    if (isMIDebugOnly) {
      val function = allFuncs.findNamedFunction(miDebugFuncName) ?: return null
      val miText = generateMIDebug(
          target,
          relPath,
          text,
          showDummies = showDummies,
          generateHtml = miHtmlOutput,
          spillOutput = spillOutput
      ) {
        val options = CFGOptions(forceReturnZero = function.name == "main")

        val cfg = CFG(
            f = function,
            targetData = target.machineTargetData,
            srcFileName = relPath,
            srcText = text,
            cfgOptions = options,
        )
        cfg.diags.forEach { it.print() }

        return@generateMIDebug cfg
      }
      if (output != null) {
        fileFrom(output!!).writeText(miText)
      } else {
        println(miText)
      }
      return null
    }

    if (isCFGOnly) {
      val function = allFuncs.findNamedFunction(targetFunction) ?: return null
      val cfgOptions = CFGOptions(forceAllNodes = forceAllNodes, forceReturnZero = function.name == "main")
      val cfg = CFG(
          f = function,
          targetData = target.machineTargetData,
          srcFileName = relPath,
          srcText = text,
          cfgOptions = cfgOptions,
      )
      cfg.diags.forEach { it.print() }

      if (exportCFGAsJSON) {
        val json = exportCFG(cfg)
        when (output) {
          null -> println(json)
          else -> fileFrom(output!!).writeText(json)
        }

        return null
      }

      val options = GraphvizOptions(
          print = printingMethod,
          reachableOnly = !forceUnreachable,
          noAllocOnlySpill = noAllocOnlySpill,
          isaType = isaType,
          targetOpts = target.options
      )
      val graphviz = createGraphviz(cfg, text, options)

      when {
        displayGraph -> {
          val src = createTemp("dot_temp", ".tmp")
          src.writeText(graphviz)
          val dest = createTemp("dot_out", ".png")
          invokeDot(src, dest)
          openFileDefault(dest)
        }
        output == null -> println(graphviz)
        else -> fileFrom(output!!).writeText(graphviz)
      }

      return null
    }

    val main = allFuncs.firstOrNull { it.name == "main" }
    val allDecls = (p.root.decls - allFuncs).map { it as Declaration }
    // FIXME: only add declarations marked 'extern'
    val declNames = allDecls.flatMap { it.idents(p.root.scope) }.map { it.name }
    val functionsEmit = (allFuncs - main).map {
      val cfg = CFG(
          f = it!!,
          targetData = target.machineTargetData,
          srcFileName = relPath,
          srcText = text,
      )
      cfg.diags.forEach(Diagnostic::print)

      createTargetFunGenerator(cfg, target)
    }
    val mainEmit = main?.let {
      val cfg = CFG(
          f = it,
          targetData = target.machineTargetData,
          srcFileName = relPath,
          srcText = text,
          cfgOptions = CFGOptions(forceReturnZero = true),
      )
      cfg.diags.forEach(Diagnostic::print)

      createTargetFunGenerator(cfg, target)
    }

    val asm = try {
      createAsmEmitter(isaType, declNames, functionsEmit, mainEmit).emitAsm()
    } catch (e: Exception) {
      logger.error("Internal compiler error when compiling file: $relPath")
      throw e
    }

    if (isCompileOnly) {
      val asmFile = fileFrom(output ?: "$baseName.s")
      asmFile.writeText(asm)
      return null
    }

    val asmFile = createTemp("asm_temp", ".s")
    asmFile.deleteOnExit()
    asmFile.writeText(asm)

    if (isAssembleOnly) {
      val objFile = fileFrom(output ?: "$baseName.o")
      assemble(objFile, asmFile)
      return null
    }

    val objFile = File(parentDir, "$baseName.o")
    assemble(objFile, asmFile)
    asmFile.delete()
    return objFile
  }

  private fun compileFile(file: File) = compile(
      file.readText(),
      file.path,
      file.nameWithoutExtension,
      file.absoluteFile.parentFile
  )

  /**
   * @param args argv for this execution
   * @param readStdin what text to use for the `-` argument; should read from [System. in] for main
   */
  fun parse(args: Array<String>, readStdin: () -> String): ExitCodes {
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
    Diagnostic.useColors = useColorDiags
    val sourceCount = srcFiles.size + if (stdin) 1 else 0
    if (sourceCount == 0) diagnostic {
      id = DiagnosticId.NO_INPUT_FILES
      executionFailed = true
    }
    val isNotLinking =
        isMIDebugOnly || isCFGOnly || isPreprocessOnly || isCompileOnly || isAssembleOnly
    if (output != null && isNotLinking && sourceCount > 1) {
      diagnostic { id = DiagnosticId.MULTIPLE_FILES_PARTIAL }
      return ExitCodes.EXECUTION_FAILED
    }
    val stdinObjFile = if (stdin) {
      compile(readStdin(), "-", "-", currentDir)
    } else {
      null
    }
    val objFiles = srcFiles.mapNotNull(this::compileFile)
    val allObjFiles = stdinObjFile?.let { objFiles + it } ?: objFiles
    if (executionFailed) return ExitCodes.EXECUTION_FAILED
    if (!isNotLinking) {
      link(allObjFiles)
      for (objFile in allObjFiles) objFile.delete()
      fileFrom(output ?: "a.out").setExecutable(true)
    }
    return if (diags.errors().isNotEmpty()) ExitCodes.EXECUTION_FAILED else ExitCodes.NORMAL
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
