package slak.ckompiler

import mu.KotlinLogging
import slak.ckompiler.parser.*

typealias Instructions = List<String>

/**
 * Generate [NASM](https://www.nasm.us/) code from a [RootNode].
 *
 * Warning: the AST is presumed to be valid; instances of [ErrorNode] will likely cause crashes.
 */
class CodeGenerator(val ast: RootNode) {
  private val prelude = mutableListOf<String>()
  private val text = mutableListOf<String>()
  private val data = mutableListOf<String>()

  private data class Register(val name: String, var itemName: String)

  private val registers = List(6) { Register("r${it + 10}", "") }

  companion object {
    private val logger = KotlinLogging.logger("CodeGenerator")
  }

  init {
    ast.decls.forEach {
      if (it is FunctionDefinition) genFunction(it)
      else TODO("ext decls")
    }
  }

  private fun Instructions.joinInstructions(): String {
    return joinToString("\n") { "  $it" }
  }

  private val FunctionDefinition.name get() = functionDeclarator.name
  private val FunctionDefinition.block get() = compoundStatement as CompoundStatement

  private val DeclarationItem.it get() = declaration as RealDeclaration

  private val Declarator.name get() = name()!!.name

  private fun genBinExpr(e: BinaryExpression): String {
    val instr = mutableListOf<String>()
    instr.add("push eax")
    // FIXME: implement
    instr.add("pop eax")
    return instr.joinInstructions()
  }

  private fun genExpr(e: Expression): Register {
    // FIXME: implement
    return Register("r8", "temp")
  }

  private fun declRunInitializers(d: RealDeclaration): Instructions {
    val instr = mutableListOf<String>()
    if (d.declSpecs.typeSpec != TypeSpecifier.INT) TODO("only ints are implemented for now")
    val inits = d.declaratorList.mapNotNull { it as? InitDeclarator }
    if (inits.size > 6) TODO("all values are in registers, and there are not many of those")
    inits.forEach {
      val regIdx = registers.indexOfFirst { reg -> reg.itemName.isEmpty() }
      if (regIdx == -1) TODO("out of registers?")
      registers[regIdx].itemName = it.name
      val resRegister = genExpr(it.initializer)
      instr.add("mov ${registers[regIdx].name}, ${resRegister.name}")
    }
    return instr
  }

  private fun genBlock(block: CompoundStatement): Instructions {
    val statements = block.items.mapNotNull { (it as? StatementItem)?.statement }
    val directDecls = block.items.mapNotNull { (it as? DeclarationItem)?.it }
    val nestedDecls = statements.mapNotNull {
      when {
        it is ForStatement && it.init is DeclarationInitializer -> it.init.value as RealDeclaration
        // FIXME
        else -> null
      }
    }
    val decls = directDecls + nestedDecls
    return decls.map { declRunInitializers(it) }.reduce { ac, it -> ac + it }
    // FIXME: the rest of this function
  }

  private fun genFunction(f: FunctionDefinition) {
    // FIXME: the declarator can also be a pointer+ident combo
    prelude.add("global ${f.name}")
    val instr = mutableListOf<String>()
    instr.add("push rbp")
    instr += genBlock(f.block)
    instr.add("pop rbp")
    text.add("${f.name}:")
    text.add(instr.joinInstructions())
  }

  fun getNasm(): String {
    return prelude.joinToString("\n") +
        "\nsection .data\n" +
        data.joinToString("\n") +
        "\nsection .text\n" +
        text.joinToString("\n")
  }
}
