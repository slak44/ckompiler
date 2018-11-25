package slak.ckompiler.parser

import java.util.*

data class TypedExpression(val expr: Expression, val type: TypeSpecifier)
data class Variable(val declSpec: RealDeclarationSpecifier, val declarator: Declarator)

data class Function(val declSpec: RealDeclarationSpecifier,
                    val name: IdentifierNode,
                    val params: List<Pair<RealDeclarationSpecifier, Declarator>>,
                    val block: CompoundStatement)

class InvalidTreeException : Exception()

fun validateTranslationUnit(ast: RootNode): Nothing? {
  val functions = mutableListOf<Function>()
  val scope = Stack<MutableList<Variable>>()
  scope.push(mutableListOf())
  val validDecls: List<ExternalDeclaration>
  try {
    validDecls = ast.decls.mapNotNull {
      if (it is ErrorNode) return@mapNotNull null
      convert(it.asVal()) as ExternalDeclaration
    }
  } catch (e: InvalidTreeException) {
    return null
  }
  if (ast.decls.size != validDecls.size) return null
  TODO()
}

private fun <T : ASTNode> convert(it: T): ASTNode = when (it) {
  is FunctionDefinition -> convertFuncDef(it)
  else -> TODO()
}

private fun convertFuncDef(it: FunctionDefinition): FunctionDefinition {
  val ds = it.declSpec as? RealDeclarationSpecifier ?: throw InvalidTreeException()
  // FIXME: validate declspecs according to function rules
  val functionDeclarator = it.declarator.convert() as? FunctionDeclarator ?: throw InvalidTreeException()
  val functionBlock = it.block.orNull() ?: throw InvalidTreeException()
  functionDeclarator.params.forEach {
    val paramSpec = it.declSpec as? RealDeclarationSpecifier ?: throw InvalidTreeException()
    val paramDecl = it.declarator.convert()
  }
//  functions.add(Function(ds, functionDeclarator.name(), , functionBlock))
  TODO()
}

private fun EitherNode<Declarator>.convert(): Declarator? {
  val d = this.orNull() ?: return null
  when (d) {
    is IdentifierNode -> return d
    is FunctionDeclarator -> {
      val innerDecl = d.declarator.convert() ?: return null
      val params = d.params.map {  }
    }
    is InitDeclarator -> {
      val innerDecl = d.declarator.convert() ?: return null
      val init =
          if (d.initializer == null) null
          else (d.initializer.orNull() ?: return null)
    }
  }
  TODO()
}
