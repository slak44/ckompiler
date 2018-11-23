package slak.ckompiler

/**
 * Generate [NASM](https://www.nasm.us/) code from a [RootNode].
 *
 * **NOTE**: This class assumes that the [ast] and all its sub-nodes are valid; it calls
 * [EitherNode.asVal] unconditionally, and performs no checks.
 */
class CodeGenerator(val ast: RootNode) {
  init {
    // FIXME: remove filter
    ast.decls.map { it.asVal() }
        .filter { it is FunctionDefinition }
        .forEach { genFunction(it as FunctionDefinition) }
  }

  private val before = mutableListOf<String>()
  private val text = mutableListOf<String>()
  private val data = mutableListOf<String>()

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

  private fun genFunction(f: FunctionDefinition) {
    // FIXME: the declarator can also be a pointer+ident combo
    val name = f.declarator.asVal().declarator.asVal() as IdentifierNode
    before.add("global $name")
    val instr = mutableListOf<String>()
    f.block.asVal().items.map { it.asVal() }.forEach {
      val item = when (it) {
        is Declaration -> TODO()
        is Statement -> genStatement(f, it)
        else -> TODO()
      }
      instr.add(item)
    }
    text.add(instr.joinToString("\n") { "  $it" })
  }

  fun getNasm(): String {
    return before.joinToString("\n") +
        "\nsection .data\n" +
        data.joinToString("\n") +
        "\nsection .text\n" +
        text.joinToString("\n")
  }
}
