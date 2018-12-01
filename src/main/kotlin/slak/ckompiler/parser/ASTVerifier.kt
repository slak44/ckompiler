package slak.ckompiler.parser

import slak.ckompiler.Keywords
import java.util.*

data class TypedExpression(val expr: Expression, val type: TypeSpecifier)
data class Variable(val declSpec: DeclarationSpecifier, val declarator: Declarator)

data class Function(val declSpec: DeclarationSpecifier,
                    val name: IdentifierNode,
                    val params: List<Pair<DeclarationSpecifier, Declarator>>,
                    val block: CompoundStatement)

class InvalidTreeException : Exception()

/**
 * FIXME missing type specifiers (A.2.2/6.7.2):
 * 1. atomic-type-specifier (6.7.2.4)
 * 2. struct-or-union-specifier (6.7.2.1)
 * 3. enum-specifier (6.7.2.2)
 * 4. typedef-name (6.7.8)
 */
private fun parseTypeSpecifier(typeSpec: List<Keywords>): TypeSpecifier? {
  //private fun diagDuplicate(k: Keywords) = parserDiagnostic {
//  id = DiagnosticId.DUPLICATE_DECL_SPEC
//  formatArgs(k.keyword)
//  errorOn(safeToken(0))
//}
//
//private fun diagIncompat(k: Keywords) = parserDiagnostic {
//  id = DiagnosticId.INCOMPATIBLE_DECL_SPEC
//  formatArgs(k.keyword)
//  errorOn(safeToken(0))
//}
//
//private fun diagNotSigned(k: Keywords) = parserDiagnostic {
//  id = DiagnosticId.TYPE_NOT_SIGNED
//  formatArgs(k)
//  errorOn(safeToken(0))
//}

  if (typeSpec.isEmpty()) {
//    parserDiagnostic {
//      id = DiagnosticId.MISSING_TYPE_SPEC
//      errorOn(safeToken(0))
//    }
    return null
  }

//  Keywords.COMPLEX -> {
//    parserDiagnostic {
//      id = DiagnosticId.UNSUPPORTED_COMPLEX
//      errorOn(safeToken(0))
//    }
//    hitError = true
//  }

  // FIXME we are now going to pretend this implementation is finished, correct, complete,
  // standards-compliant, and reports sensible errors (lmao)

  val isSigned = typeSpec.contains(Keywords.SIGNED)
  val isUnsigned = typeSpec.contains(Keywords.UNSIGNED)
  if (isSigned && isUnsigned) {
//    diagIncompat(Keywords.SIGNED)
    return null
  }
  if (typeSpec.contains(Keywords.VOID)) return TypeSpecifier.VOID
  if (typeSpec.contains(Keywords.FLOAT)) return TypeSpecifier.FLOAT
  if (typeSpec.contains(Keywords.LONG) && typeSpec.contains(Keywords.DOUBLE))
    return TypeSpecifier.LONG_DOUBLE
  if (typeSpec.contains(Keywords.DOUBLE)) return TypeSpecifier.DOUBLE

  if (typeSpec.contains(Keywords.CHAR)) {
    return if (isUnsigned) TypeSpecifier.UNSIGNED_CHAR
    else TypeSpecifier.SIGNED_CHAR
  }
  if (typeSpec.contains(Keywords.SHORT)) {
    return if (isUnsigned) TypeSpecifier.UNSIGNED_SHORT
    else TypeSpecifier.SIGNED_SHORT
  }
  // RIP long long
  if (typeSpec.contains(Keywords.LONG)) {
    return if (isUnsigned) TypeSpecifier.UNSIGNED_LONG
    else TypeSpecifier.SIGNED_LONG
  }
  if (typeSpec.contains(Keywords.INT)) {
    return if (isUnsigned) TypeSpecifier.UNSIGNED_INT
    else TypeSpecifier.SIGNED_INT
  }
  return null // Sure why not
}


fun validateTranslationUnit(ast: RootNode): Nothing? {
  val functions = mutableListOf<Function>()
  val scope = Stack<MutableList<Variable>>()
  scope.push(mutableListOf())
  val validDecls: List<ExternalDeclaration>
  try {
//    validDecls = ast.decls.mapNotNull {
//      if (it is ErrorNode) return@mapNotNull null
//      convert(it.asVal()) as ExternalDeclaration
//    }
  } catch (e: InvalidTreeException) {
    return null
  }
//  if (ast.decls.size != validDecls.size) return null
  TODO()
}

private fun <T : ASTNode> convert(it: T): ASTNode = when (it) {
  is FunctionDefinition -> convertFuncDef(it)
  else -> TODO()
}

private fun convertFuncDef(it: FunctionDefinition): FunctionDefinition {
  val ds = it.declSpec as? DeclarationSpecifier ?: throw InvalidTreeException()
  // FIXME: validate declspecs according to function rules
  val functionDeclarator = it.declarator.convert() as? FunctionDeclarator
      ?: throw InvalidTreeException()
  val functionBlock = it.block.orNull() ?: throw InvalidTreeException()
  functionDeclarator.params.forEach {
    val paramSpec = it.declSpec as? DeclarationSpecifier ?: throw InvalidTreeException()
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
      val params = d.params.map { }
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
