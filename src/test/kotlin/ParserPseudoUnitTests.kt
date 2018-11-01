import org.junit.Test

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
  fun basicDeclaration() {
    val p = prepareCode("int a;")
  }
}
