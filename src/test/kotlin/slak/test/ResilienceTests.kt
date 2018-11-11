package slak.test

import org.junit.Test
import slak.ckompiler.ErrorToken
import slak.ckompiler.Identifier
import slak.ckompiler.Lexer
import kotlin.test.assertEquals

/**
 * Tests for correct error reporting. Components should be able to report multiple errors correctly,
 * even with other errors around, without being influenced by whitespace or semi-ambiguous
 * constructs.
 */
class ResilienceTests {
  @Test
  fun lexerKeepsGoingAfterBadSuffix() {
    val l = Lexer("123.23A ident", source)
    assert(l.tokens[0] is ErrorToken)
    assertEquals(Identifier("ident"), l.tokens[1])
  }

  @Test
  fun lexerKeepsGoingAfterBadExponent() {
    val l = Lexer("1.EF ident", source)
    assert(l.tokens[0] is ErrorToken)
    assertEquals(Identifier("ident"), l.tokens[1])
  }

  // FIXME more tests like this
  @Test
  fun lexerCorrectDiagnosticColumn() {
    val text = "ident     123.23A"
    val l = Lexer(text, source)
    assert(l.tokens[1] is ErrorToken)
    // Test if error is on the last column
    assertEquals(text.length - 1, l.inspections[0].sourceColumns[0].start)
  }
}
