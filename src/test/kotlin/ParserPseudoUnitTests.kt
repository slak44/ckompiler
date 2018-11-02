import org.junit.Test
import java.lang.IllegalArgumentException
import kotlin.test.assertEquals

/**
 * Similarly to [LexerPseudoUnitTests], these are not strictly unit tests.
 * @see LexerPseudoUnitTests
 */
class ParserPseudoUnitTests {
  private fun prepareCode(s: String): Parser {
    val lexer = Lexer(s, source)
    lexer.assertNoDiagnostics()
    return Parser(lexer.tokens, source)
  }

  private fun int(i: Long): IntegerConstantNode = IntegerConstantNode(i, IntegralSuffix.NONE)

  private class Builder {
    var lhs: ASTNode? = null
    var rhs: ASTNode? = null
    fun build(op: Operators): BinaryNode {
      return BinaryNode(op, lhs!!, rhs!!)
    }
  }

  private fun Operators.with(block: Builder.() -> Unit): BinaryNode {
    val b = Builder()
    b.block()
    return b.build(this)
  }

  private infix fun <LHS, RHS> Pair<LHS, RHS>.with(op: Operators): BinaryNode {
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

  @Test
  fun declarationBasic() {
    val p = prepareCode("int a;")
    p.assertNoDiagnostics()
    val expected = Declaration(listOf(Keywords.INT),
        listOf(InitDeclarator(IdentifierNode("a"))))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationWithMultipleDeclSpecs() {
    val p = prepareCode("const static int a;")
    p.assertNoDiagnostics()
    val expected = Declaration(listOf(Keywords.CONST, Keywords.STATIC, Keywords.INT),
        listOf(InitDeclarator(IdentifierNode("a"))))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationMultipleDeclarators() {
    val p = prepareCode("int a, b, c;")
    p.assertNoDiagnostics()
    val expected = Declaration(listOf(Keywords.INT),
        listOf("a", "b", "c").map { InitDeclarator(IdentifierNode(it)) })
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationWithSimpleInitializer() {
    val p = prepareCode("int a = 1;")
    p.assertNoDiagnostics()
    val expected = Declaration(listOf(Keywords.INT),
        listOf(InitDeclarator(IdentifierNode("a"), int(1))))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationWithArithmeticInitializer() {
    val p = prepareCode("int a = 1 + 2 * 3 - 4 / 5;")
    p.assertNoDiagnostics()
    val expr = Operators.SUB.with {
      lhs = Operators.ADD.with {
        lhs = int(1)
        rhs = 2 to 3 with Operators.MUL
      }
      rhs = 4 to 5 with Operators.DIV
    }
    val expected =
        Declaration(listOf(Keywords.INT), listOf(InitDeclarator(IdentifierNode("a"), expr)))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationWithParenInitializer() {
    val p = prepareCode("int a = (1);")
    p.assertNoDiagnostics()
    val expected = Declaration(listOf(Keywords.INT),
        listOf(InitDeclarator(IdentifierNode("a"), int(1))))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }
}
