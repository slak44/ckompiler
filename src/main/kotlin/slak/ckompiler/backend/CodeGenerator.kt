package slak.ckompiler.backend

import mu.KotlinLogging
import slak.ckompiler.parser.*

typealias Instructions = List<String>

/**
 * Generate [NASM](https://www.nasm.us/) code.
 */
class CodeGenerator() {
  private val prelude = mutableListOf<String>()
  private val text = mutableListOf<String>()
  private val data = mutableListOf<String>()

  companion object {
    private val logger = KotlinLogging.logger("CodeGenerator")
  }

  private fun Instructions.joinInstructions(): String {
    return joinToString("\n") { "  $it" }
  }

  private fun genBinExpr(e: BinaryExpression): String {
    val instr = mutableListOf<String>()
    instr += "push eax"
    // FIXME: implement
    instr += "pop eax"
    return instr.joinInstructions()
  }

  private fun genFunction(f: FunctionDefinition) {
    prelude += "global ${f.name}"
    val instr = mutableListOf<String>()
    instr += "push rbp"
    // FIXME
    instr += "pop rbp"
    text += "${f.name}:"
    text += instr.joinInstructions()
  }

  fun getNasm(): String {
    return prelude.joinToString("\n") +
        "\nsection .data\n" +
        data.joinToString("\n") +
        "\nsection .text\n" +
        text.joinToString("\n")
  }
}
