package slak.ckompiler

import kotlin.system.exitProcess

fun main(args: Array<String>) {
  val cli = CLI(System.`in`)
  val exitCode = cli.parse(args)
  cli.diags.forEach(Diagnostic::print)
  exitProcess(exitCode.int)
}
