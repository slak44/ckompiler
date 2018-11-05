import java.lang.IllegalArgumentException
import kotlin.test.assertEquals

internal fun Lexer.assertNoDiagnostics() = assertEquals(emptyList<Diagnostic>(), inspections)
internal fun Parser.assertNoDiagnostics() = assertEquals(emptyList<Diagnostic>(), diags)
internal val <T : Any> T.source get() = "<test/${javaClass.simpleName}>"

internal fun int(i: Long): IntegerConstantNode = IntegerConstantNode(i, IntegralSuffix.NONE)

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

internal val intDecl = RealDeclarationSpecifier(typeSpecifier = TypeSpecifier.SIGNED_INT)

