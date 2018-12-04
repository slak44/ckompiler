package slak.ckompiler

import mu.KotlinLogging
import slak.ckompiler.parser.*

/**
 * Generate [NASM](https://www.nasm.us/) code from a [RootNode].
 *
 * **NOTE**: This class assumes that the [ast] and all its sub-nodes are valid; it calls
 * [EitherNode.asVal] unconditionally, and performs no correctness checks. For example, a
 * [BinaryExpression] cannot have an [IntegralConstant] lhs and a [FloatingConstant] rhs; the
 * necessary conversion is not performed here.
 */
class CodeGenerator(val ast: RootNode) {
  private val before = mutableListOf<String>()
  private val text = mutableListOf<String>()
  private val data = mutableListOf<String>()

  companion object {
    private val logger = KotlinLogging.logger("CodeGenerator")
  }

  init {
//    // FIXME: remove filter
//    ast.decls.map { it.asVal() }
//        .filter { it is FunctionDefinition }
//        .forEach { genFunction(it as FunctionDefinition) }
  }

//  /**
//   * Coerces an [EitherNode] to the concrete value of type [N].
//   * @throws InternalCompilerError if called on an [ErrorNode]
//   * @return [EitherNode.Value.value]
//   */
//  private fun <N : ASTNode> EitherNode<N>.asVal(): N {
//    if (this is ErrorNode) {
//      logger.throwICE("An error node was coerced to a real node") { this }
//    }
//    return (this as EitherNode.Value).value
//  }

  private fun List<String>.joinInstructions(): String {
    return joinToString("\n") { "  $it" }
  }

  private fun genBinExpr(e: BinaryExpression): String {
    val instr = mutableListOf<String>()
    instr.add("push eax")
    // FIXME: implement
    instr.add("pop eax")
    return instr.joinInstructions()
  }

  private fun genStatement(f: FunctionDefinition, it: Statement): String = when (it) {
    // FIXME: implement this
    is ReturnStatement -> {
      if (it.expr == null) {
        "ret"
      } else {
        ""
      }
    }
    else -> TODO()
  }

//  private fun genFunction(f: FunctionDefinition) {
//    // FIXME: the declarator can also be a pointer+ident combo
//    val name = (f.declarator.asVal().declarator.asVal() as IdentifierNode).name
//    before.add("global $name")
//    val instr = mutableListOf<String>()
//    instr.add("push rbp")
//    f.block.asVal().items.map { it.asVal() }.forEach {
//      val item = when (it) {
//        is Declaration -> TODO()
//        is Statement -> genStatement(f, it)
//        else -> TODO()
//      }
//      instr.add(item)
//    }
//    instr.add("pop rbp")
//    text.add("$name:")
//    text.add(instr.joinInstructions())
//  }

  fun getNasm(): String {
    return before.joinToString("\n") +
        "\nsection .data\n" +
        data.joinToString("\n") +
        "\nsection .text\n" +
        text.joinToString("\n")
  }
}
