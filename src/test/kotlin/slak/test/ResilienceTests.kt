package slak.test

import org.junit.jupiter.api.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.ErrorToken
import slak.ckompiler.lexer.Identifier
import slak.ckompiler.parser.ErrorDeclarator
import slak.ckompiler.parser.ErrorExpression
import slak.ckompiler.parser.NamedDeclarator
import slak.ckompiler.parser.TypedIdentifier
import kotlin.test.assertEquals

/**
 * Tests for correct error reporting. Components should be able to report multiple errors correctly,
 * even with other errors around, without being influenced by whitespace or semi-ambiguous
 * constructs, and then recover from the error and keep working correctly. When that is not
 * possible, a reasonable attempt should be made to keep going.
 */
class ResilienceTests {
  @Test
  fun `PP Keeps Going After Bad Suffix`() {
    val pp = preparePP("123.23A ident", source)
    assert(pp.tokens[0] is ErrorToken)
    assertEquals(Identifier("ident"), pp.tokens[1])
  }

  @Test
  fun `PP Keeps Going After Bad Exponent`() {
    val pp = preparePP("1.EF ident", source)
    assert(pp.tokens[0] is ErrorToken)
    assertEquals(Identifier("ident"), pp.tokens[1])
  }

  @Test
  fun `Parser Keeps Going After Unmatched Paren`() {
    val p = prepareCode("int a = 1 * (2 + 3; int b = 32;", source)
    p.assertDiags(DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET)
    int declare ("b" assign 32) assertEquals p.root.decls[1]
  }

  @Test
  fun `Parser Keeps Going After Unmatched Bracket`() {
    val p = prepareCode("int main() { 123 + 23; \n int b = 32;", source)
    p.assertDiags(DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET)
    int declare ("b" assign 32) assertEquals p.root.decls[1]
  }

  @Test
  fun `Parser Keeps Going After Bad Function Declarator`() {
    val p = prepareCode("int default(); double dbl = 1.1;", source)
    p.assertDiags(DiagnosticId.EXPECTED_IDENT_OR_PAREN)
    double declare ("dbl" assign 1.1) assertEquals p.root.decls[1]
  }

  @Test
  fun `Parser Keeps Going After Bad Declaration`() {
    val p = prepareCode("int default; double dbl = 1.1;", source)
    p.assertDiags(DiagnosticId.EXPECTED_IDENT_OR_PAREN)
    double declare ("dbl" assign 1.1) assertEquals p.root.decls[1]
  }

  @Test
  fun `Parser Keeps Going In Declaration After Bad Declarator `() {
    val p = prepareCode("int default, x = 1;", source)
    p.assertDiags(DiagnosticId.EXPECTED_IDENT_OR_PAREN)
    int declare listOf(ErrorDeclarator() to null, "x" assign 1) assertEquals p.root.decls[0]
  }

  @Test
  fun `Parser Statement After Missing Semicolon`() {
    val p = prepareCode("""
      int main() {
        return 0
        1 + 1;
      }
    """.trimIndent(), source)
    int func "main" body compoundOf(
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
    int proto ("f" withParams listOf(double param "x")) assertEquals p.root.decls[0]
    double func "g" body compoundOf(
        returnSt(double(0.1))
    ) assertEquals p.root.decls[1]
  }

  @Test
  fun `Parser Direct Declarator Suffix Must Recover After Unmatched Parens`() {
    val p = prepareCode("""
      int a(int ;
      int x;
      int b(
    """.trimIndent(), source)
    int declare "x" assertEquals p.root.decls[1]
    p.assertDiags(
        DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET,
        DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET,
        DiagnosticId.EXPECTED_SEMI_AFTER
    )
  }

  @Test
  fun `Parser Initializer Bad Parens Are Contained Until A Comma`() {
    val p = prepareCode("int x = (5 + 1, y;", source)
    int declare listOf("x" assign ErrorExpression(), "y") assertEquals p.root.decls[0]
    p.assertDiags(DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET)
  }

  @Test
  fun `Parser Initializer Bad Parens In Function Call Don't Break Declarators Completely`() {
    val p = prepareCode("""
      int f(int, int);
      int x = f(1, (2 + 2 ), y;
    """.trimIndent(), source)
    val funProto = int proto ("f" withParams listOf(int.toParam(), int.toParam()))
    funProto assertEquals p.root.decls[0]
    val f = TypedIdentifier(funProto.declSpecs, funProto.declaratorList[0].first as NamedDeclarator)
    int declare listOf("x" assign f()) assertEquals p.root.decls[1]
    // The parser gets incredibly confused by stuff like this
    // It is impossible to know exactly what is valid and what isn't when parens aren't matched, so
    // we only want to see if the root cause (unmatched paren) is reported
    assert(DiagnosticId.UNMATCHED_PAREN in p.diags.ids)
  }
}
