package slak.test.parser

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.Operators
import slak.ckompiler.parser.ErrorExpression
import slak.ckompiler.parser.PostfixIncrement
import slak.ckompiler.parser.PrefixIncrement
import slak.ckompiler.parser.SizeofExpression
import slak.test.*
import kotlin.test.assertEquals

class ExpressionTests {
  @Test
  fun `Arithmetic Precedence`() {
    val p = prepareCode("int a = 1 + 2 * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign (1 add (2 mul 3))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Arithmetic Expression Parens Level 1`() {
    val p = prepareCode("int a = (1 + 2) * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign ((1 add 2) mul 3)) assertEquals p.root.decls[0]
  }

  @Test
  fun `Arithmetic Expression Parens Level 2`() {
    val p = prepareCode("int a = (1 + (2 + 3)) * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign ((1 add (2 add 3)) mul 3)) assertEquals p.root.decls[0]
  }

  @Test
  fun `Arithmetic Expression Parens Level Lots`() {
    val p = prepareCode("int a = (1 + (2 + (3 + (4 + (5 + (6)))))) * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign ((1 add (2 add (3 add (4 add (5 add 6))))) mul 3)) assertEquals
        p.root.decls[0]
  }

  @Test
  fun `Arithmetic Expression Unmatched Parens`() {
    val p = prepareCode("int a = (1 * (2 + 3);", source)
    assertEquals(listOf(
        DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET), p.diags.ids)
    int declare ("a" assign ErrorExpression()) assertEquals p.root.decls[0]
  }

  @Test
  fun `Arithmetic Expression Unmatched Right Paren At End Of Expr`() {
    val p = prepareCode("int a = 1 + 1);", source)
    assertEquals(listOf(
        DiagnosticId.EXPECTED_SEMI_AFTER,
        DiagnosticId.EXPECTED_EXTERNAL_DECL), p.diags.ids)
    int declare ("a" assign (1 add 1)) assertEquals p.root.decls[0]
  }

  @Test
  fun `Unexpected Bracket In Expression`() {
    val p = prepareCode("""
      int main() {
        1 + 1 +
      }
    """.trimIndent(), source)
    assert(p.diags.ids.contains(DiagnosticId.EXPECTED_SEMI_AFTER))
    int func ("main" withParams emptyList()) body compoundOf(
        1 add 1 add ErrorExpression()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Size Of Primary Expression`() {
    val p = prepareCode("int a = sizeof 1;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign SizeofExpression(int(1))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Simple Prefix Increment`() {
    val p = prepareCode("int a = ++b;", source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED)
    int declare ("a" assign PrefixIncrement(name("b"))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Paren Prefix Increment`() {
    // This is invalid code, but valid grammar
    val p = prepareCode("int a = ++(1);", source)
    p.assertNoDiagnostics()
    int declare ("a" assign PrefixIncrement(int(1))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Simple Postfix Increment`() {
    val p = prepareCode("int a = b++;", source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED)
    int declare ("a" assign PostfixIncrement(name("b"))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Paren Postfix Increment`() {
    // This is invalid code, but valid grammar
    val p = prepareCode("int a = (1)++;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign PostfixIncrement(int(1))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Unary Reference`() {
    val p = prepareCode("int a = &b;", source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED)
    int declare ("a" assign (Operators.REF apply name("b"))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Unary Lots Of Operators`() {
    val p = prepareCode("int a = *&+-~!b;", source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED)
    int declare ("a" assign (
        Operators.DEREF apply (Operators.REF apply (Operators.PLUS apply
            (Operators.MINUS apply (Operators.BIT_NOT apply (Operators.NOT apply name("b"))))))
        )) assertEquals p.root.decls[0]
  }
}
