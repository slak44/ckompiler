package slak.test

import slak.ckompiler.*
import java.lang.IllegalArgumentException
import kotlin.test.assertEquals

internal fun Lexer.assertNoDiagnostics() = assertEquals(emptyList<Diagnostic>(), inspections)
internal fun Parser.assertNoDiagnostics() = assertEquals(emptyList<Diagnostic>(), diags)
internal val <T : Any> T.source get() = "<test/${javaClass.simpleName}>"

internal val int = RealDeclarationSpecifier(typeSpecifier = TypeSpecifier.SIGNED_INT)
internal fun int(i: Long): IntegerConstantNode = IntegerConstantNode(i, IntegralSuffix.NONE)
internal val double = RealDeclarationSpecifier(typeSpecifier = TypeSpecifier.DOUBLE)
internal fun double(f: Double): FloatingConstantNode = FloatingConstantNode(f, FloatingSuffix.NONE)

internal infix fun <LHS, RHS> LHS.assertEquals(rhs: RHS) {
  if (this is ASTNode && rhs is ASTNode) return assertEquals(this, rhs as ASTNode)
  if (this is ASTNode && rhs is EitherNode<*>) return assertEquals(this.asEither(), rhs)
  if (this is EitherNode<*> && rhs is ASTNode) return assertEquals(this, rhs.asEither())
  if (this is EitherNode<*> && rhs is EitherNode<*>) return assertEquals(this, rhs as EitherNode<*>)
  throw IllegalArgumentException("Bad types")
}

internal fun name(s: String): EitherNode<IdentifierNode> = IdentifierNode(s).asEither()

internal infix fun String.assign(value: ASTNode) = InitDeclarator(name(this), value)

internal infix fun DeclarationSpecifier.declare(decl: InitDeclarator) =
    Declaration(this, listOf(decl)).asEither()
internal infix fun DeclarationSpecifier.declare(list: List<InitDeclarator>) =
    Declaration(this, list).asEither()
internal infix fun DeclarationSpecifier.func(decl: InitDeclarator) = Declaration(this, listOf(decl))
internal infix fun DeclarationSpecifier.declare(s: String): EitherNode<Declaration> {
  return Declaration(this, listOf(InitDeclarator(name(s)))).asEither()
}

internal infix fun DeclarationSpecifier.param(s: String) = ParameterDeclaration(this, name(s))

internal infix fun String.withParams(params: List<ParameterDeclaration>): InitDeclarator {
  return InitDeclarator(FunctionDeclarator(name(this), params).asEither())
}

internal class BinaryBuilder {
  var lhs: ASTNode? = null
  var rhs: ASTNode? = null
  fun build(op: Operators): BinaryNode {
    return BinaryNode(op, lhs!!, rhs!!)
  }
}

internal fun Operators.with(block: BinaryBuilder.() -> Unit): BinaryNode {
  val b = BinaryBuilder()
  b.block()
  return b.build(this)
}

internal infix fun <LHS, RHS> Pair<LHS, RHS>.with(op: Operators): BinaryNode {
  if (first is ASTNode && second is ASTNode) {
    return BinaryNode(op, first as ASTNode, second as ASTNode)
  }
  if (first is Int && second is Int) {
    return BinaryNode(op, int((first as Int).toLong()), int((second as Int).toLong()))
  }
  if (first is Int && second is ASTNode) {
    return BinaryNode(op, int((first as Int).toLong()), second as ASTNode)
  }
  if (first is ASTNode && second is Int) {
    return BinaryNode(op, first as ASTNode, int((second as Int).toLong()))
  }
  throw IllegalArgumentException("Bad types")
}
