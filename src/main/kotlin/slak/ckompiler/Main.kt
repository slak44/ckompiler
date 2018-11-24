package slak.ckompiler

import kotlinx.cli.CommandLineInterface
import kotlinx.cli.HelpPrintedException
import kotlinx.cli.parse
import kotlinx.cli.positionalArgumentsList
import mu.KotlinLogging
import slak.ckompiler.parser.Parser
import java.io.File
import java.lang.Exception
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  val logger = KotlinLogging.logger("CLI")
  val cli = CommandLineInterface("ckompiler")
  val files by cli.positionalArgumentsList(
      "FILES...", "Translation units to be compiled", minArgs = 1)
  try {
    cli.parse(args)
  } catch (err: Exception) {
    if (err is HelpPrintedException) exitProcess(1)
    logger.error(err) { "Failed to parse CLI args" }
    exitProcess(1)
  }
  files.map { File(it) }.forEach {
    val text = it.readText()
    val l = Lexer(text, it.absolutePath)
    if (l.inspections.isNotEmpty()) {
      return@forEach
    }
    val p = Parser(l.tokens, it.absolutePath, text)
    if (p.diags.isNotEmpty()) {
      return@forEach
    }
  }
}
