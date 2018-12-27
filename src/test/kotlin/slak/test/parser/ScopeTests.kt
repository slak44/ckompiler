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
    p.assertDiags(DiagnosticId.REDEFINITION, DiagnosticId.REDEFINITION_PREVIOUS)
  }

  @Test
  fun fileScopeRedefinitionViaSameDeclaration() {
    val p = prepareCode("int a, a;", source)
    p.assertDiags(DiagnosticId.REDEFINITION, DiagnosticId.REDEFINITION_PREVIOUS)
  }

  @Test
  fun fileScopeRedefinitionOf2FunctionDeclarators() {
    val p = prepareCode("int a; int a;", source)
    p.assertDiags(DiagnosticId.REDEFINITION, DiagnosticId.REDEFINITION_PREVIOUS)
  }

  @Test
  fun blockScopeRedefinition() {
    val p = prepareCode("int main() { int x, x; }", source)
    p.assertDiags(DiagnosticId.REDEFINITION, DiagnosticId.REDEFINITION_PREVIOUS)
  }

  @Test
  fun shadowing() {
    val p = prepareCode("int x; int main() { int x; }", source)
    p.assertNoDiagnostics()
  }

  @Test
  fun shadowingFurther() {
    val p = prepareCode("int x; int main() { int x; { int x; } }", source)
    p.assertNoDiagnostics()
  }

  @Test
  fun labelRedefinition() {
    val p = prepareCode("int main() { label:; label:; }", source)
    assertEquals(p.diags.ids,
        listOf(DiagnosticId.REDEFINITION_LABEL, DiagnosticId.REDEFINITION_PREVIOUS))
  }

  @Test
  fun labelAndVariableTogether() {
    val p = prepareCode("int main() { label:; int label; }", source)
    p.assertNoDiagnostics()
  }

  @Test
  fun undeclaredUsage() {
    val p = prepareCode("int main() { int x = y; }", source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED)
  }
}
