package slak.test.parser

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.ErrorNode
import slak.ckompiler.Operators
import slak.test.*
import kotlin.test.assertEquals

class FunctionsTests {
  @Test
  fun functionDeclarationNoParams() {
    val p = prepareCode("int f();", source)
    p.assertNoDiagnostics()
    int func ("f" withParams emptyList()) assertEquals p.root.getDeclarations()[0]
  }

  @Test
  fun functionDeclarationOneParam() {
    val p = prepareCode("int f(double dbl);", source)
    p.assertNoDiagnostics()
    int func ("f" withParams listOf(double param "dbl")) assertEquals p.root.getDeclarations()[0]
  }

  @Test
  fun functionDeclarationTwoParams() {
    val p = prepareCode("int f(double dbl, int x);", source)
    p.assertNoDiagnostics()
    int func ("f" withParams listOf(double param "dbl", int param "x")) assertEquals
        p.root.getDeclarations()[0]
  }

  @Test
  fun functionDeclarationThreeParams() {
    val p = prepareCode("int f(double dbl, int x, int y);", source)
    p.assertNoDiagnostics()
    int func ("f" withParams listOf(double param "dbl", int param "x", int param "y")) assertEquals
        p.root.getDeclarations()[0]
  }

  @Test
  fun functionDeclarationFourParams() {
    val p = prepareCode("int f(double dbl, int x, int y, double asd);", source)
    p.assertNoDiagnostics()
    int func ("f" withParams
        listOf( double param "dbl", int param "x", int param "y", double param "asd")) assertEquals
        p.root.getDeclarations()[0]
  }

  @Test
  fun functionProtoExpectedIdentOrParen() {
    val p = prepareCode("int default();", source)
    assert(p.diags.size > 0)
    assertEquals(DiagnosticId.EXPECTED_IDENT_OR_PAREN, p.diags[0].id)
  }

  @Test
  fun functionDefinitionBasicEmpty() {
    val p = prepareCode("int main() {}", source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body emptyList() assertEquals
        p.root.getDeclarations()[0]
  }

  @Test
  fun functionDefinitionUnmatchedBrackets() {
    val p = prepareCode("int main() {", source)
    assertEquals(listOf(DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET),
        p.diags.map { it.id })
    int func ("main" withParams emptyList()) body ErrorNode() assertEquals
        p.root.getDeclarations()[0]
  }

  @Test
  fun functionDefinitionOneArgEmpty() {
    val p = prepareCode("int main(int argc) {}", source)
    p.assertNoDiagnostics()
    int func ("main" withParams listOf(int param "argc")) body emptyList() assertEquals
        p.root.getDeclarations()[0]
  }

  @Test
  fun functionDefinitionBasicWithExpression() {
    val p = prepareCode("int main() { 1 + 1; }", source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(1 add 1) assertEquals
        p.root.getDeclarations()[0]
  }
}
