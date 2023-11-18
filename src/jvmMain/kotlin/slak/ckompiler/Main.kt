package slak.ckompiler

import kotlin.system.exitProcess

fun main(args: Array<String>) {
  val cli = CLI()
  val exitCode = cli.parse(args) { System.`in`.readAllBytes() }
  cli.diags.forEach(Diagnostic::print)
  exitProcess(exitCode.int)
}
