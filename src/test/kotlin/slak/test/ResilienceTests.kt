package slak.test

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.ErrorToken
import slak.ckompiler.lexer.Identifier
import slak.ckompiler.lexer.Lexer
import slak.ckompiler.parser.ErrorDeclarator
import slak.ckompiler.parser.Declaration
import kotlin.test.assertEquals

/**
 * Tests for correct error reporting. Components should be able to report multiple errors correctly,
 * even with other errors around, without being influenced by whitespace or semi-ambiguous
 * constructs, and then recover from the error and keep working correctly. When that is not
 * possible, a reasonable attempt should be made to keep going.
 */
class ResilienceTests {
  @Test
  fun `Lexer Keeps Going After Bad Suffix`() {
    val l = Lexer("123.23A ident", source)
    assert(l.tokens[0] is ErrorToken)
    assertEquals(Identifier("ident"), l.tokens[1])
  }

  @Test
  fun `Lexer Keeps Going After Bad Exponent`() {
    val l = Lexer("1.EF ident", source)
    assert(l.tokens[0] is ErrorToken)
    assertEquals(Identifier("ident"), l.tokens[1])
  }

  @Test
  fun `Parser Keeps Going After Unmatched Paren`() {
    val p = prepareCode("int a = 1 * (2 + 3; int b = 32;", source)
    p.assertDiags(DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET)
    int declare ("b" assign int(32)) assertEquals p.root.decls[1]
  }

  @Test
  fun `Parser Keeps Going After Unmatched Bracket`() {
    val p = prepareCode("int main() { 123 + 23; \n int b = 32;", source)
    p.assertDiags(DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET)
    int declare ("b" assign int(32)) assertEquals p.root.decls[1]
  }

  @Test
  fun `Parser Keeps Going After Bad Function Declarator`() {
    val p = prepareCode("int default(); double dbl = 1.1;", source)
    assert(p.diags.isNotEmpty())
    assertEquals(DiagnosticId.EXPECTED_IDENT_OR_PAREN, p.diags[0].id)
    double declare ("dbl" assign double(1.1)) assertEquals p.root.decls[1]
  }

  @Test
  fun `Parser Keeps Going After Bad Declaration`() {
    val p = prepareCode("int default; double dbl = 1.1;", source)
    assert(p.diags.isNotEmpty())
    assertEquals(DiagnosticId.EXPECTED_IDENT_OR_PAREN, p.diags[0].id)
    double declare ("dbl" assign double(1.1)) assertEquals p.root.decls[1]
  }

  @Test
  fun `Parser Keeps Going In Declaration After Bad Declarator `() {
    val p = prepareCode("int default, x = 1;", source)
    assert(p.diags.isNotEmpty())
    assertEquals(DiagnosticId.EXPECTED_IDENT_OR_PAREN, p.diags[0].id)
    val decl = Declaration(int, listOf(ErrorDeclarator(), "x" assign int(1)))
    decl assertEquals p.root.decls[0]
  }

  @Test
  fun `Parser Statement After Missing Semicolon`() {
    val p = prepareCode("""
      int main() {
        return 0
        1 + 1;
      }
    """.trimIndent(), source)
    int func ("main" withParams emptyList()) body compoundOf(
        returnSt(int(0)),
        1 add 1
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Parser No Confusion With Brackets And Functions`() {
    val p = prepareCode("""
      int f(double x);
      double g() {
        return 0.1;
      }
    """.trimIndent(), source)
    int func ("f" withParams listOf(double param "x")) assertEquals p.root.decls[0]
    double func ("g" withParams emptyList()) body compoundOf(
        returnSt(double(0.1))
    ) assertEquals p.root.decls[1]
  }

  @Test
  fun `Lexer Correct Diagnostic Column`() {
    val text = "ident     123.23A"
    val l = Lexer(text, source)
    assert(l.tokens[1] is ErrorToken)
    // Test if error is on the last column
    assertEquals(text.length - 1, l.diags[0].sourceColumns[0].start)
  }

  @Test
  fun `Parser Diagnostic Correct Column In Line`() {
    val code = "int;"
    val p = prepareCode(code, source)
    val (line, col, _) = p.diags[0].errorOf(p.diags[0].caret)
    assertEquals(1, line)
    assertEquals(code.indexOf(';'), col)
  }

  @Test
  fun `Parser Diagnostic Correct Column 0 In Line`() {
    val code = "register int x;"
    val p = prepareCode(code, source)
    val (line, col, _) = p.diags[0].errorOf(p.diags[0].caret)
    assertEquals(1, line)
    assertEquals(0, col)
  }
}
