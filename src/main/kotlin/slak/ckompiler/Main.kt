package slak.ckompiler

import kotlinx.cli.*
import org.apache.logging.log4j.LogManager
import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.createGraphviz
import slak.ckompiler.backend.CodeGenerator
import slak.ckompiler.lexer.IncludePaths
import slak.ckompiler.lexer.Preprocessor
import slak.ckompiler.parser.FunctionDefinition
import slak.ckompiler.parser.Parser
import java.io.File
import kotlin.system.exitProcess

fun CommandLineInterface.helpGroup(description: String) {
  addHelpEntry(object : HelpEntry {
    override fun printHelp(helpPrinter: HelpPrinter) {
      helpPrinter.printSeparator()
      helpPrinter.printText(description)
    }
  })
}

fun main(args: Array<String>) {
  val logger = LogManager.getLogger("CLI")
  val dh = DebugHandler("CLI", "ckompiler", "")
  val cli = CommandLineInterface("ckompiler", "ckompiler", """
    A C compiler written in Kotlin.
    This command line interface tries to stay consistent with gcc and clang as much as possible.
    """.trimIndent(), "See project on GitHub: https://github.com/slak44/ckompiler")

  val output by cli.flagValueArgument("-o", "OUTFILE",
      "Place output in the specified file", "a.out")
  val files by cli.positionalArgumentsList(
      "FILES...", "Translation units to be compiled", minArgs = 1)

  cli.helpSeparator()

  val defines = mutableMapOf<String, String>()
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
  val isPreprocessOnly by cli.flagArgument("-E", "Preprocess only")
  val isCompileOnly by cli.flagArgument("-S", "Compile only, don't assemble")
  val isAssembleOnly by cli.flagArgument("-c", "Assemble only, don't link")
  val isPrintCFGMode by cli.flagArgument("--print-cfg-graphviz",
      "Print the program's control flow graph to stdout instead of compiling")

  cli.helpGroup("Feature toggles")
  // FIXME: maybe add versions without no- that override each other
  val disableTrigraphs by cli.flagArgument("-fno-trigraphs", "Ignore trigraphs in source files")
  val disableColorDiags by cli.flagArgument("-fno-color-diagnostics",
      "Disable colors in diagnostic messages")

  cli.helpGroup("Include path management")
  val includeBarrier by cli.flagArgument(listOf("-I-", "--include-barrier"),
      "Remove current directory from include list", false, flagValue = false)

  val generalIncludes = mutableListOf<File>()
  cli.flagValueAction(listOf("-I", "--include-directory"), "DIR",
      "Directory to add to include search path") { generalIncludes += File(it) }

  val userIncludes = mutableListOf<File>()
  cli.flagValueAction("-iquote", "DIR",
      "Directory to add to \"...\" include search path") { userIncludes += File(it) }

  val systemIncludes = mutableListOf<File>()
  cli.flagValueAction("-isystem", "DIR",
      "Directory to add to <...> search path") { systemIncludes.add(0, File(it)) }
  cli.flagValueAction("-isystem-after", "DIR",
      "Directory to add to the end of the <...> search path") { systemIncludes += File(it) }

  cli.helpGroup("Graphviz options (require --print-cfg-graphviz)")
  val forceToString by cli.flagArgument("--force-to-string",
      "Force using ASTNode.toString instead of printing the original expression source")
  val forceAllNodes by cli.flagArgument("--force-all-nodes",
      "Force displaying the entire control flow graph")
  val forceUnreachable by cli.flagArgument("--force-unreachable",
      "Force displaying of unreachable basic blocks and impossible edges")

  try {
    cli.parse(args)
  } catch (err: Exception) {
    if (err is HelpPrintedException) exitProcess(0)
    if (err is CommandLineException) exitProcess(4)
    logger.error(err) { "Failed to parse CLI args" }
    exitProcess(1)
  }

  Diagnostic.useColors = !disableColorDiags
  val badOptions = files.filter { it.startsWith("--") }
  for (option in badOptions) dh.diagnostic {
    id = DiagnosticId.BAD_CLI_OPTION
    formatArgs(option)
  }
  for (diag in dh.diags) diag.print()
  val objFiles = mutableListOf<File>()
  for (file in (files - badOptions).map(::File)) {
    val text = file.readText()
    val includePaths =
        IncludePaths.defaultPaths + IncludePaths(generalIncludes, systemIncludes, userIncludes)
    includePaths.includeBarrier = includeBarrier
    val pp = Preprocessor(text, file.absolutePath, defines, includePaths, disableTrigraphs)
    if (pp.diags.isNotEmpty()) continue
    if (isPreprocessOnly) {
      // FIXME
//      println(pp.alteredSourceText)
      continue
    }
    val p = Parser(pp.tokens, file.absolutePath, text)
    if (p.diags.isNotEmpty()) continue
    if (isPrintCFGMode) {
      // FIXME: this is incomplete
      val firstFun = p.root.decls.first { d -> d is FunctionDefinition } as FunctionDefinition
      val cfg = CFG(firstFun, file.absolutePath, text, forceAllNodes)
      println(createGraphviz(cfg, text, !forceUnreachable, forceToString))
      continue
    }
    // FIXME: this is incomplete
    val firstFun = p.root.decls.first { d -> d is FunctionDefinition } as FunctionDefinition
    val cfg = CFG(firstFun, file.absolutePath, text, false)
    val asmFile = File(file.parent, file.nameWithoutExtension + ".s")
    asmFile.writeText(CodeGenerator(cfg, true).getNasm())
    if (isCompileOnly) continue
    val objFile = File(file.parent, file.nameWithoutExtension + ".o")
    ProcessBuilder("nasm", "-f", "elf64", "-o", objFile.absolutePath, asmFile.absolutePath)
        .inheritIO().start().waitFor()
    objFiles += objFile
    asmFile.delete()
  }
  if (isAssembleOnly || isCompileOnly || isPrintCFGMode) return
  ProcessBuilder("ld", "-o", File(output).absolutePath, "-L/lib", "-lc", "-dynamic-linker",
      "/lib/ld-linux-x86-64.so.2", "-e", "main",
      *objFiles.map(File::getAbsolutePath).toTypedArray())
      .inheritIO().start().waitFor()
  File(output).setExecutable(true)
}
