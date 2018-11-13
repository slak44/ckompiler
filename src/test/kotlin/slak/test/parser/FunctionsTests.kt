package slak.test.parser

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.test.*
import kotlin.test.assertEquals

class FunctionsTests {
  @Test
  fun functionDeclarationNoParams() {
    val p = prepareCode("int f();", source)
    p.assertNoDiagnostics()
    assertEquals(int func ("f" withParams emptyList()), p.root.getDeclarations()[0])
  }

  @Test
  fun functionDeclarationOneParam() {
    val p = prepareCode("int f(double dbl);", source)
    p.assertNoDiagnostics()
    assertEquals(int func ("f" withParams listOf(double param "dbl")), p.root.getDeclarations()[0])
  }

  @Test
  fun functionDeclarationTwoParams() {
    val p = prepareCode("int f(double dbl, int x);", source)
    p.assertNoDiagnostics()
    assertEquals(int func ("f" withParams listOf(double param "dbl", int param "x")),
        p.root.getDeclarations()[0])
  }

  @Test
  fun functionDeclarationThreeParams() {
    val p = prepareCode("int f(double dbl, int x, int y);", source)
    p.assertNoDiagnostics()
    assertEquals(int func ("f" withParams listOf(double param "dbl", int param "x", int param "y")),
        p.root.getDeclarations()[0])
  }

  @Test
  fun functionDeclarationFourParams() {
    val p = prepareCode("int f(double dbl, int x, int y, double asd);", source)
    p.assertNoDiagnostics()
    assertEquals(int func ("f" withParams listOf(
        double param "dbl", int param "x", int param "y", double param "asd")),
        p.root.getDeclarations()[0])
  }

  @Test
  fun functionProtoExpectedIdentOrParen() {
    val p = prepareCode("int default();", source)
    assert(p.diags.size > 0)
    assertEquals(DiagnosticId.EXPECTED_IDENT_OR_PAREN, p.diags[0].id)
  }
}
