import org.junit.Test
import kotlin.test.assertEquals

/**
 * Similarly to [LexerPseudoUnitTests], these are not strictly unit tests.
 * @see LexerPseudoUnitTests
 */
class ParserPseudoUnitTests {
  private fun prepareCode(s: String): Parser {
    val lexer = Lexer(s, source)
    lexer.assertNoDiagnostics()
    return Parser(lexer.tokens, source, s, lexer.tokStartIdxes)
  }

  @Test
  fun declarationBasic() {
    val p = prepareCode("int a;")
    p.assertNoDiagnostics()
    val expected = Declaration(intDecl,
        listOf(InitDeclarator(IdentifierNode("a"))))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationWithMultipleDeclSpecs() {
    val p = prepareCode("const static int a;")
    p.assertNoDiagnostics()
    val expected = Declaration(DeclarationSpecifier(typeSpecifier = TypeSpecifier.SIGNED_INT,
        hasConst = true, storageSpecifier = Keywords.STATIC),
        listOf(InitDeclarator(IdentifierNode("a"))))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationMultipleDeclarators() {
    val p = prepareCode("int a, b, c;")
    p.assertNoDiagnostics()
    val expected = Declaration(intDecl,
        listOf("a", "b", "c").map { InitDeclarator(IdentifierNode(it)) })
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationMultipleDeclarations() {
    val p = prepareCode("int a; int b; int c;")
    p.assertNoDiagnostics()
    val expected = listOf("a", "b", "c").map {
      Declaration(intDecl, listOf(InitDeclarator(IdentifierNode(it))))
    }
    assertEquals(expected, p.root.getDeclarations())
  }

  @Test
  fun declarationWithSimpleInitializer() {
    val p = prepareCode("int a = 1;")
    p.assertNoDiagnostics()
    val expected = Declaration(intDecl,
        listOf(InitDeclarator(IdentifierNode("a"), int(1))))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationWithIdentifierInitializer() {
    val p = prepareCode("int a = someVariable;")
    p.assertNoDiagnostics()
    val expected = Declaration(intDecl,
        listOf(InitDeclarator(IdentifierNode("a"), IdentifierNode("someVariable"))))
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
        Declaration(intDecl, listOf(InitDeclarator(IdentifierNode("a"), expr)))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationWithComplexArithmeticInitializer() {
    val p = prepareCode("int a = 1 + 2 * 2 * (3 - 4) / 5 / 6;")
    p.assertNoDiagnostics()
    val expr = Operators.ADD.with {
      lhs = int(1)
      rhs = Operators.DIV.with {
        lhs = Operators.DIV.with {
          lhs = Operators.MUL.with {
            lhs = 2 to 2 with Operators.MUL
            rhs = 3 to 4 with Operators.SUB
          }
          rhs = int(5)
        }
        rhs = int(6)
      }
    }
    val expected =
        Declaration(intDecl, listOf(InitDeclarator(IdentifierNode("a"), expr)))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationWithParenInitializer() {
    val p = prepareCode("int a = (1);")
    p.assertNoDiagnostics()
    val expected = Declaration(intDecl,
        listOf(InitDeclarator(IdentifierNode("a"), int(1))))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationWithBadInitializer() {
    val p = prepareCode("int a = 1 + ;")
    assertEquals(DiagnosticId.EXPECTED_PRIMARY, p.diags[0].id)
    val expected = Declaration(intDecl,
        listOf(InitDeclarator(IdentifierNode("a"), ErrorNode())))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationMissingSemicolon() {
    val p = prepareCode("int a")
    assertEquals(DiagnosticId.EXPECTED_SEMI_AFTER_DECL, p.diags[0].id)
    val expected = Declaration(intDecl, listOf(InitDeclarator(IdentifierNode("a"))))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }
}
