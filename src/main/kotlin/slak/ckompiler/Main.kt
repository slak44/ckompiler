package slak.ckompiler

import kotlin.system.exitProcess

fun main(args: Array<String>) {
  val cli = CLI()
  val exitCode = cli.parse(args) { System.`in`.bufferedReader().readText() }
  cli.diags.forEach(Diagnostic::print)
  exitProcess(exitCode.int)
}
