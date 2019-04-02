package slak.ckompiler

import kotlinx.cli.*
import mu.KotlinLogging
import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.createGraphviz
import slak.ckompiler.lexer.Lexer
import slak.ckompiler.parser.FunctionDefinition
import slak.ckompiler.parser.Parser
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  val logger = KotlinLogging.logger("CLI")
  val cli = CommandLineInterface("ckompiler")
  val ppOnly by cli.flagArgument("-E", "Preprocess only")
  val isPrintCFGMode by cli.flagArgument("--print-cfg-graphviz",
      "Print the program's control flow graph to stdout instead of compiling")
  val forceAllNodes by cli.flagArgument("--force-all-nodes",
      "Force displaying the entire control flow graph (requires --print-cfg-graphviz)")
  val forceUnreachable by cli.flagArgument("--force-unreachable", "Force displaying of " +
      "unreachable basic blocks and impossible edges (requires --print-cfg-graphviz)")
  val disableColorDiags by cli.flagArgument("-fno-color-diagnostics",
      "Disable colors in diagnostic messages")
  val files by cli.positionalArgumentsList(
      "FILES...", "Translation units to be compiled", minArgs = 1)
  try {
    cli.parse(args)
  } catch (err: Exception) {
    if (err is HelpPrintedException) exitProcess(3)
    if (err is CommandLineException) exitProcess(4)
    logger.error(err) { "Failed to parse CLI args" }
    exitProcess(1)
  }
  Diagnostic.useColors = !disableColorDiags
  files.map { File(it) }.forEach {
    val text = it.readText()
    val pp = Preprocessor(text, it.absolutePath)
    if (pp.diags.isNotEmpty()) {
      return@forEach
    }
    if (ppOnly) {
      println(pp.alteredSourceText)
      return@forEach
    }
    val l = Lexer(pp.alteredSourceText, it.absolutePath)
    if (l.diags.isNotEmpty()) {
      return@forEach
    }
    val p = Parser(l.tokens, it.absolutePath, text)
    if (p.diags.isNotEmpty()) {
      return@forEach
    }
    if (isPrintCFGMode) {
      // FIXME: this is incomplete
      val dh = DebugHandler("CFG", it.absolutePath, text)
      val firstFun = p.root.decls.first { d -> d is FunctionDefinition } as FunctionDefinition
      val cfg = CFG(firstFun, dh, forceAllNodes)
      println(createGraphviz(cfg, text, !forceUnreachable))
      return
    }
  }
}
