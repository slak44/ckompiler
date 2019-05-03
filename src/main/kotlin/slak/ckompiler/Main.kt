package slak.ckompiler

import kotlinx.cli.*
import mu.KotlinLogging
import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.createGraphviz
import slak.ckompiler.backend.CodeGenerator
import slak.ckompiler.lexer.Lexer
import slak.ckompiler.parser.FunctionDefinition
import slak.ckompiler.parser.Parser
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  val logger = KotlinLogging.logger("CLI")
  val dh = DebugHandler("CLI", "ckompiler", "")
  val cli = CommandLineInterface("ckompiler")
  val ppOnly by cli.flagArgument("-E", "Preprocess only")
  val compileOnly by cli.flagArgument("-S", "Compile only, don't assemble")
  val assembleOnly by cli.flagArgument("-c", "Assemble only, don't link")
  val output by cli.flagValueArgument("-o", "OUTFILE",
      "Place output in the specified file", "a.out")
  val disableColorDiags by cli.flagArgument("-fno-color-diagnostics",
      "Disable colors in diagnostic messages")
  val files by cli.positionalArgumentsList(
      "FILES...", "Translation units to be compiled", minArgs = 1)

  cli.helpEntriesGroup("Graphviz options")
  val isPrintCFGMode by cli.flagArgument("--print-cfg-graphviz",
      "Print the program's control flow graph to stdout instead of compiling")
  val forceAllNodes by cli.flagArgument("--force-all-nodes",
      "Force displaying the entire control flow graph (requires --print-cfg-graphviz)")
  val forceUnreachable by cli.flagArgument("--force-unreachable", "Force displaying of " +
      "unreachable basic blocks and impossible edges (requires --print-cfg-graphviz)")

  try {
    cli.parse(args)
  } catch (err: Exception) {
    if (err is HelpPrintedException) exitProcess(3)
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
    val pp = Preprocessor(text, file.absolutePath)
    if (pp.diags.isNotEmpty()) continue
    if (ppOnly) {
      println(pp.alteredSourceText)
      continue
    }
    val l = Lexer(pp.alteredSourceText, file.absolutePath)
    if (l.diags.isNotEmpty()) continue
    val p = Parser(l.tokens, file.absolutePath, text)
    if (p.diags.isNotEmpty()) continue
    if (isPrintCFGMode) {
      // FIXME: this is incomplete
      val firstFun = p.root.decls.first { d -> d is FunctionDefinition } as FunctionDefinition
      val cfg = CFG(firstFun, file.absolutePath, text, forceAllNodes)
      println(createGraphviz(cfg, text, !forceUnreachable))
      continue
    }
    // FIXME: this is incomplete
    val firstFun = p.root.decls.first { d -> d is FunctionDefinition } as FunctionDefinition
    val cfg = CFG(firstFun, file.absolutePath, text, false)
    val asmFile = File(file.parent, file.nameWithoutExtension + ".s")
    asmFile.writeText(CodeGenerator(cfg).getNasm())
    if (compileOnly) continue
    val objFile = File(file.parent, file.nameWithoutExtension + ".o")
    ProcessBuilder("nasm", "-f", "elf64", "-o", objFile.absolutePath, asmFile.absolutePath)
        .inheritIO().start().waitFor()
    objFiles += objFile
    asmFile.delete()
  }
  if (assembleOnly || compileOnly) return
  ProcessBuilder("ld", "-o", File(output).absolutePath, "-L/lib", "-lc", "-dynamic-linker",
      "/lib/ld-linux-x86-64.so.2", "-e", "main",
      *objFiles.map(File::getAbsolutePath).toTypedArray())
      .inheritIO().start().waitFor()
  File(output).setExecutable(true)
}
