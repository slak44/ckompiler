package slak.test

import slak.ckompiler.*
import java.lang.IllegalArgumentException
import kotlin.test.assertEquals

internal fun Lexer.assertNoDiagnostics() = assertEquals(emptyList<Diagnostic>(), inspections)
internal fun Parser.assertNoDiagnostics() = assertEquals(emptyList<Diagnostic>(), diags)
internal val <T : Any> T.source get() = "<test/${javaClass.simpleName}>"

internal fun prepareCode(s: String, source: SourceFileName): Parser {
  val lexer = Lexer(s, source)
  lexer.assertNoDiagnostics()
  return Parser(lexer.tokens, source, s, lexer.tokStartIdxes)
}

internal val int = RealDeclarationSpecifier(typeSpecifier = TypeSpecifier.SIGNED_INT)
internal fun int(i: Long): IntegerConstantNode = IntegerConstantNode(i, IntegralSuffix.NONE)
internal val double = RealDeclarationSpecifier(typeSpecifier = TypeSpecifier.DOUBLE)
internal fun double(f: Double): FloatingConstantNode = FloatingConstantNode(f, FloatingSuffix.NONE)

internal infix fun <LHS, RHS> LHS.assertEquals(rhs: RHS) {
  if (this is ASTNode && rhs is ASTNode) return assertEquals(this, rhs as ASTNode)
  if (this is ASTNode && rhs is EitherNode<*>) return assertEquals(this.wrap(), rhs)
  if (this is EitherNode<*> && rhs is ASTNode) return assertEquals(this, rhs.wrap())
  if (this is EitherNode<*> && rhs is EitherNode<*>) return assertEquals(this, rhs as EitherNode<*>)
  throw IllegalArgumentException("Bad types")
}

internal fun name(s: String): EitherNode<IdentifierNode> = IdentifierNode(s).wrap()

internal infix fun String.assign(value: Expression) =
    InitDeclarator(name(this), value.wrap())

internal infix fun String.assign(value: ErrorNode) = InitDeclarator(name(this), value)

internal infix fun DeclarationSpecifier.declare(decl: InitDeclarator) =
    Declaration(this, listOf(decl)).wrap()
internal infix fun DeclarationSpecifier.declare(list: List<InitDeclarator>) =
    Declaration(this, list).wrap()
internal infix fun DeclarationSpecifier.func(decl: InitDeclarator) = Declaration(this, listOf(decl))
internal infix fun DeclarationSpecifier.declare(s: String): EitherNode<Declaration> {
  return Declaration(this, listOf(InitDeclarator(name(s)))).wrap()
}

internal infix fun DeclarationSpecifier.param(s: String) = ParameterDeclaration(this, name(s))

internal infix fun String.withParams(params: List<ParameterDeclaration>): InitDeclarator {
  return InitDeclarator(FunctionDeclarator(name(this), params).wrap())
}

internal infix fun Declaration.body(s: EitherNode<CompoundStatement>): FunctionDefinition {
  if (this.declaratorList.size != 1) throw IllegalArgumentException("Not function")
  val d = this.declaratorList[0].declarator
  if (d is EitherNode.Value) {
    if (d.value !is FunctionDeclarator) throw IllegalArgumentException("Not function")
    val fd = d.value as FunctionDeclarator
    return FunctionDefinition(this.declSpecs, fd.wrap(), s)
  }
  return FunctionDefinition(this.declSpecs, ErrorNode(), s)
}

internal infix fun Declaration.body(s: CompoundStatement) = this body s.wrap()
internal infix fun Declaration.body(list: List<BlockItem>) = this body list.compound()

internal fun ifSt(e: Expression, success: () -> Statement) =
    IfStatement(e.wrap(), success().wrap(), null)

internal fun ifSt(e: ErrorNode, success: () -> Statement) =
    IfStatement(e, success().wrap(), null)

internal infix fun IfStatement.elseSt(failure: () -> Statement) =
    IfStatement(this.cond, this.success, failure().wrap())

internal fun returnSt(e: Expression) = ReturnStatement(e.wrap())

internal fun List<BlockItem>.compound() = CompoundStatement(this.map { it.wrap() })

internal class BinaryBuilder {
  var lhs: Expression? = null
  var rhs: Expression? = null
  fun build(op: Operators): BinaryNode {
    return BinaryNode(op, lhs!!.wrap(), rhs!!.wrap())
  }
}

internal fun Operators.with(block: BinaryBuilder.() -> Unit): BinaryNode {
  val b = BinaryBuilder()
  b.block()
  return b.build(this)
}

internal infix fun <LHS, RHS> LHS.add(that: RHS) = this to that with Operators.ADD
internal infix fun <LHS, RHS> LHS.sub(that: RHS) = this to that with Operators.SUB
internal infix fun <LHS, RHS> LHS.mul(that: RHS) = this to that with Operators.MUL
internal infix fun <LHS, RHS> LHS.div(that: RHS) = this to that with Operators.DIV

internal infix fun <LHS, RHS> Pair<LHS, RHS>.with(op: Operators): BinaryNode {
  if (first is Expression && second is Expression) {
    return BinaryNode(op, (first as Expression).wrap(), (second as Expression).wrap())
  }
  if (first is Int && second is Int) {
    return BinaryNode(op, int((first as Int).toLong()).wrap(),
        int((second as Int).toLong()).wrap())
  }
  if (first is Int && second is Expression) {
    return BinaryNode(op, int((first as Int).toLong()).wrap(),
        (second as Expression).wrap())
  }
  if (first is Expression && second is Int) {
    return BinaryNode(op, (first as Expression).wrap(),
        int((second as Int).toLong()).wrap())
  }
  throw IllegalArgumentException("Bad types")
}
