package slak.ckompiler

import kotlinx.cli.*
import mu.KotlinLogging
import slak.ckompiler.analysis.BasicBlock.Companion.createGraphFor
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
      val startBlock = createGraphFor(p.root.decls.mapNotNull { d -> d as? FunctionDefinition }[0])
      println(createGraphviz(startBlock, text))
      return
    }
  }
}
