package slak.test

import org.junit.Test
import slak.ckompiler.*
import slak.test.parser.prepareCode
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

  @Test
  fun parserKeepsGoingAfterBadFunctionDeclarator() {
    val p = prepareCode("int default(); double dbl = 1.1;", source)
    assert(p.diags.size > 0)
    assertEquals(DiagnosticId.EXPECTED_IDENT_OR_PAREN, p.diags[0].id)
    assertEquals(double declare ("dbl" assign double(1.1)), p.root.getDeclarations()[1])
  }

  @Test
  fun parserKeepsGoingAfterBadDeclaration() {
    val p = prepareCode("int default; double dbl = 1.1;", source)
    assert(p.diags.size > 0)
    assertEquals(DiagnosticId.EXPECTED_IDENT_OR_PAREN, p.diags[0].id)
    assertEquals(double declare ("dbl" assign double(1.1)), p.root.getDeclarations()[1])
  }

  @Test
  fun parserKeepsGoingInDeclarationAfterBadDeclarator() {
    val p = prepareCode("int default, x;", source)
    assert(p.diags.size > 0)
    assertEquals(DiagnosticId.EXPECTED_IDENT_OR_PAREN, p.diags[0].id)
    val decl = int declare listOf(InitDeclarator(ErrorNode()), InitDeclarator(name("x")))
    assertEquals(decl, p.root.getDeclarations()[0])
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
