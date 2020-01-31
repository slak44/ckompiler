package slak.ckompiler

import kotlinx.cli.*
import org.apache.logging.log4j.LogManager
import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.CodePrintingMethods
import slak.ckompiler.analysis.createGraphviz
import slak.ckompiler.backend.regAlloc
import slak.ckompiler.backend.stringify
import slak.ckompiler.backend.x64.NasmEmitter
import slak.ckompiler.backend.x64.X64Generator
import slak.ckompiler.backend.x64.X64Target
import slak.ckompiler.lexer.IncludePaths
import slak.ckompiler.lexer.Preprocessor
import slak.ckompiler.parser.Declaration
import slak.ckompiler.parser.FunctionDefinition
import slak.ckompiler.parser.Parser
import java.io.File
import java.io.InputStream
import java.util.*

enum class ExitCodes(val int: Int) {
  NORMAL(0), ERROR(1), EXECUTION_FAILED(2), BAD_COMMAND(4)
}

typealias CLIDefines = Map<String, String>

/**
 * The command line interface for the compiler.
 * @param stdinStream which stream to use for the `-` argument; should be [System. in] for main
 */
class CLI(private val stdinStream: InputStream) :
    IDebugHandler by DebugHandler("CLI", "<command line>", "") {

  private val currentDir = File(System.getProperty("user.dir")!!)

  private fun fileFrom(path: String): File {
    val file = File(path)
    return if (file.isAbsolute) file else File(currentDir, path)
  }

  private val cli = object : CommandLineInterface("ckompiler", "ckompiler", """
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

  private var output: Optional<String> = Optional.empty()

  init {
    cli.flagValueAction("-o", "OUTFILE", "Place output in the specified file") {
      output = Optional.of(it)
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
  private val isInterferenceOnly by cli.flagArgument("--interference-mode",
      "Print variable live-range interference in a function, don't assemble")
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

  init {
    cli.helpGroup("Interference options (require --interference-mode)")
  }

  private val interferenceFuncName by cli.flagValueArgument("--interference-function", "FUNC_NAME",
      "Choose which function to print variable interference for", initialValue = "main")

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
    args += listOf("/opt", fileFrom(output.orElse("a.out")).absolutePath)
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
    args += listOf("-o", fileFrom(output.orElse("a.out")).absolutePath)
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

  private fun List<Diagnostic>.errors() = filter { it.id.kind == DiagnosticKind.ERROR }

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
      parentDir: File
  ): File? {
    val includePaths = IncludePaths.defaultPaths +
        IncludePaths(generalIncludes, systemIncludes + systemIncludesAfter, userIncludes)
    includePaths.includeBarrier = includeBarrier
    val pp = Preprocessor(
        sourceText = text,
        srcFileName = relPath,
        currentDir = parentDir,
        cliDefines = defines,
        includePaths = includePaths,
        ignoreTrigraphs = !useTrigraphs,
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

    val p = Parser(pp.tokens, relPath, text, MachineTargetData.x64)
    if (p.diags.errors().isNotEmpty()) {
      executionFailed = true
      return null
    }

    val allFuncs = p.root.decls.mapNotNull { it as? FunctionDefinition }

    if (isInterferenceOnly) {
      val function = allFuncs.findNamedFunction(interferenceFuncName) ?: return null
      val cfg = CFG(
          f = function,
          targetData = MachineTargetData.x64,
          srcFileName = relPath,
          srcText = text,
          forceAllNodes = false,
          forceReturnZero = function.name == "main"
      )
      val gen = X64Generator(cfg)
      val selected = gen.instructionSelection()
      val alloc = X64Target.regAlloc(cfg, selected)
      val (newLists, allocation, _) = alloc
      for ((block, list) in newLists) {
        println(block)
        println(list.stringify())
        println()
      }
      for ((value, register) in allocation) {
        println("allocate $value to $register")
      }
      println()
      val final = gen.applyAllocation(alloc)
      for ((block, list) in final) {
        println(block)
        println(list.joinToString("\n"))
        println()
      }
      return null
    }

    if (isCFGOnly) {
      val function = allFuncs.findNamedFunction(targetFunction) ?: return null
      val cfg = CFG(
          f = function,
          targetData = MachineTargetData.x64,
          srcFileName = relPath,
          srcText = text,
          forceAllNodes = forceAllNodes,
          forceReturnZero = function.name == "main"
      )
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
        else -> fileFrom(output.get()).writeText(graphviz)
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
          targetData = MachineTargetData.x64,
          srcFileName = relPath,
          srcText = text,
          forceReturnZero = false,
          forceAllNodes = false
      )
      // FIXME: this is X64 only:
      val gen = X64Generator(cfg)
      val selected = gen.instructionSelection()
      gen to X64Target.regAlloc(cfg, selected)
    }
    val mainEmit = main?.let {
      val cfg = CFG(
          f = it,
          targetData = MachineTargetData.x64,
          srcFileName = relPath,
          srcText = text,
          forceReturnZero = true,
          forceAllNodes = false
      )
      // FIXME: this is X64 only:
      val gen = X64Generator(cfg)
      val selected = gen.instructionSelection()
      gen to X64Target.regAlloc(cfg, selected)
    }

    val nasm = NasmEmitter(declNames, functionsEmit, mainEmit).emitAsm()

    if (isCompileOnly) {
      val asmFile = fileFrom(output.orElse("$baseName.s"))
      asmFile.writeText(nasm)
      return null
    }

    val asmFile = createTemp("asm_temp", ".s")
    asmFile.deleteOnExit()
    asmFile.writeText(nasm)

    if (isAssembleOnly) {
      val objFile = fileFrom(output.orElse("$baseName.o"))
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
      file.absoluteFile.parentFile
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
    Diagnostic.useColors = useColorDiags
    val sourceCount = srcFiles.size + if (stdin) 1 else 0
    if (sourceCount == 0) diagnostic {
      id = DiagnosticId.NO_INPUT_FILES
      executionFailed = true
    }
    val isNotLinking =
        isInterferenceOnly || isCFGOnly || isPreprocessOnly || isCompileOnly || isAssembleOnly
    if (output.isPresent && isNotLinking && sourceCount > 1) {
      diagnostic { id = DiagnosticId.MULTIPLE_FILES_PARTIAL }
      return ExitCodes.EXECUTION_FAILED
    }
    val stdinObjFile = if (stdin) {
      val inText = stdinStream.bufferedReader().readText()
      compile(inText, "-", "-", currentDir)
    } else {
      null
    }
    val objFiles = srcFiles.mapNotNull(this::compileFile)
    val allObjFiles = stdinObjFile?.let { objFiles + it } ?: objFiles
    if (executionFailed) return ExitCodes.EXECUTION_FAILED
    if (!isNotLinking) {
      link(allObjFiles)
      for (objFile in allObjFiles) objFile.delete()
      fileFrom(output.orElse("a.out")).setExecutable(true)
    }
    return if (diags.errors().isNotEmpty()) ExitCodes.EXECUTION_FAILED else ExitCodes.NORMAL
  }

  companion object {
    private val logger = LogManager.getLogger()
  }
}
