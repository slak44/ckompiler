package slak.test.parser

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.ErrorNode
import slak.test.*
import kotlin.test.assertEquals

class ExpressionTests {
  @Test
  fun exprArithmPrecedence() {
    val p = prepareCode("int a = 1 + 2 * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign (1 add (2 mul 3))) assertEquals p.root.getDeclarations()[0]
  }

  @Test
  fun exprArithmParensLevel1() {
    val p = prepareCode("int a = (1 + 2) * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign ((1 add 2) mul 3)) assertEquals p.root.getDeclarations()[0]
  }

  @Test
  fun exprArithmParensLevel2() {
    val p = prepareCode("int a = (1 + (2 + 3)) * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign ((1 add (2 add 3)) mul 3)) assertEquals p.root.getDeclarations()[0]
  }

  @Test
  fun exprArithmParensLevelLots() {
    val p = prepareCode("int a = (1 + (2 + (3 + (4 + (5 + (6)))))) * 3;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign ((1 add (2 add (3 add (4 add (5 add 6))))) mul 3)) assertEquals
        p.root.getDeclarations()[0]
  }

  @Test
  fun exprArithmUnmatchedParens() {
    val p = prepareCode("int a = (1 * (2 + 3);", source)
    assertEquals(listOf(
        DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET), p.diags.map { it.id })
    int declare ("a" assign ErrorNode()) assertEquals p.root.getDeclarations()[0]
  }

  @Test
  fun exprArithmUnmatchedRightParenAtEndOfExpr() {
    val p = prepareCode("int a = 1 + 1);", source)
    assertEquals(listOf(
        DiagnosticId.EXPECTED_SEMI_AFTER,
        DiagnosticId.EXPECTED_EXTERNAL_DECL), p.diags.map { it.id })
    int declare ("a" assign (1 add 1)) assertEquals p.root.getDeclarations()[0]
  }
}
