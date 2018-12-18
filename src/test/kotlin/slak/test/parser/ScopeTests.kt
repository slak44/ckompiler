package slak.test.parser

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.test.*
import kotlin.test.assertEquals

/**
 * Various normal cases of scoping are indirectly tested by all the other test cases. Here are only
 * tests for special, pathological, or error cases.
 */
class ScopeTests {
  @Test
  fun fileScopeRedefinitionViaDifferentDeclaration() {
    val p = prepareCode("int a; int a;", source)
    assertEquals(p.diags.ids, listOf(DiagnosticId.REDEFINITION, DiagnosticId.REDEFINITION_PREVIOUS))
  }

  @Test
  fun fileScopeRedefinitionViaSameDeclaration() {
    val p = prepareCode("int a, a;", source)
    assertEquals(p.diags.ids, listOf(DiagnosticId.REDEFINITION, DiagnosticId.REDEFINITION_PREVIOUS))
  }

  @Test
  fun fileScopeRedefinitionOf2FunctionDeclarators() {
    val p = prepareCode("int a; int a;", source)
    assertEquals(p.diags.ids, listOf(DiagnosticId.REDEFINITION, DiagnosticId.REDEFINITION_PREVIOUS))
  }

  @Test
  fun blockScopeRedefinition() {
    val p = prepareCode("int main() { int x, x; }", source)
    assertEquals(p.diags.ids, listOf(DiagnosticId.REDEFINITION, DiagnosticId.REDEFINITION_PREVIOUS))
  }

  @Test
  fun shadowing() {
    val p = prepareCode("int x; int main() { int x; }", source)
    p.assertNoDiagnostics()
  }
}
