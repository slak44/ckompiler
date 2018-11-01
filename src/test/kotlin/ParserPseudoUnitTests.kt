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
    return Parser(lexer.tokens, source)
  }

  @Test
  fun declarationBasic() {
    val p = prepareCode("int a;")
    p.assertNoDiagnostics()
    val expected = Declaration(listOf(Keywords.INT),
        listOf(InitDeclarator(IdentifierNode("a"), Empty())))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationWithMultipleDeclSpecs() {
    val p = prepareCode("const static int a;")
    p.assertNoDiagnostics()
    val expected = Declaration(listOf(Keywords.CONST, Keywords.STATIC, Keywords.INT),
        listOf(InitDeclarator(IdentifierNode("a"), Empty())))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }
}
