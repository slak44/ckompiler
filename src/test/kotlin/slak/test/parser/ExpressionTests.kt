package slak.test.parser

import org.junit.Test
import slak.ckompiler.*
import slak.test.*
import kotlin.test.assertEquals

class ExpressionTests {
  @Test
  fun exprArithmPrecedence() {
    val p = prepareCode("int a = 1 + 2 * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign (1 add (2 mul 3))) assertEquals p.root.decls[0]
  }

  @Test
  fun exprArithmParensLevel1() {
    val p = prepareCode("int a = (1 + 2) * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign ((1 add 2) mul 3)) assertEquals p.root.decls[0]
  }

  @Test
  fun exprArithmParensLevel2() {
    val p = prepareCode("int a = (1 + (2 + 3)) * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign ((1 add (2 add 3)) mul 3)) assertEquals p.root.decls[0]
  }

  @Test
  fun exprArithmParensLevelLots() {
    val p = prepareCode("int a = (1 + (2 + (3 + (4 + (5 + (6)))))) * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign ((1 add (2 add (3 add (4 add (5 add 6))))) mul 3)) assertEquals
        p.root.decls[0]
  }

  @Test
  fun exprArithmUnmatchedParens() {
    val p = prepareCode("int a = (1 * (2 + 3);", source)
    assertEquals(listOf(
        DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET), p.diags.ids)
    int declare ("a" assign ErrorNode()) assertEquals p.root.decls[0]
  }

  @Test
  fun exprArithmUnmatchedRightParenAtEndOfExpr() {
    val p = prepareCode("int a = 1 + 1);", source)
    assertEquals(listOf(
        DiagnosticId.EXPECTED_SEMI_AFTER,
        DiagnosticId.EXPECTED_EXTERNAL_DECL), p.diags.ids)
    int declare ("a" assign (1 add 1)) assertEquals p.root.decls[0]
  }

  @Test
  fun exprUnexpectedBracket() {
    val p = prepareCode("""
      int main() {
        1 + 1 +
      }
    """.trimIndent(), source)
    assert(p.diags.ids.contains(DiagnosticId.EXPECTED_SEMI_AFTER))
    int func ("main" withParams emptyList()) body listOf(
        1 add 1 add ErrorNode()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun exprSizeOfPrimary() {
    val p = prepareCode("int a = sizeof 1;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign SizeofExpression(int(1).wrap())) assertEquals p.root.decls[0]
  }

  @Test
  fun exprPrefixIncSimple() {
    val p = prepareCode("int a = ++b;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign PrefixIncrement(name("b"))) assertEquals p.root.decls[0]
  }

  @Test
  fun exprPrefixIncParen() {
    // This is invalid code, but valid grammar
    val p = prepareCode("int a = ++(1);", source)
    p.assertNoDiagnostics()
    int declare ("a" assign PrefixIncrement(int(1).wrap())) assertEquals p.root.decls[0]
  }

  @Test
  fun exprPostfixIncSimple() {
    val p = prepareCode("int a = b++;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign PostfixIncrement(name("b"))) assertEquals p.root.decls[0]
  }

  @Test
  fun exprPostfixIncParen() {
    // This is invalid code, but valid grammar
    val p = prepareCode("int a = (1)++;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign PostfixIncrement(int(1).wrap())) assertEquals p.root.decls[0]
  }

  @Test
  fun exprUnaryRef() {
    val p = prepareCode("int a = &b;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign (Operators.REF apply name("b"))) assertEquals p.root.decls[0]
  }
  @Test
  fun exprUnaryLots() {
    val p = prepareCode("int a = *&+-~!b;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign (
        Operators.DEREF apply (Operators.REF apply (Operators.PLUS apply
            (Operators.MINUS apply (Operators.BIT_NOT apply (Operators.NOT apply name("b"))))))
        )) assertEquals p.root.decls[0]
  }
}
