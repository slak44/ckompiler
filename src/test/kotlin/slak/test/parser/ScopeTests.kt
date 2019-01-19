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
  fun `File Scope Redefinition Via Different Declaration`() {
    val p = prepareCode("int a; int a;", source)
    p.assertDiags(DiagnosticId.REDEFINITION, DiagnosticId.REDEFINITION_PREVIOUS)
  }

  @Test
  fun `File Scope Redefinition Via Same Declaration`() {
    val p = prepareCode("int a, a;", source)
    p.assertDiags(DiagnosticId.REDEFINITION, DiagnosticId.REDEFINITION_PREVIOUS)
  }

  @Test
  fun `Block Scope Redefinition`() {
    val p = prepareCode("int main() { int x, x; }", source)
    p.assertDiags(DiagnosticId.REDEFINITION, DiagnosticId.REDEFINITION_PREVIOUS)
  }

  @Test
  fun `Shadowing Of File Scope Variable With Block Scope Variable`() {
    val p = prepareCode("int x; int main() { int x; }", source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `Shadowing Shadowed Name`() {
    val p = prepareCode("int x; int main() { int x; { int x; } }", source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `Label Redefinition`() {
    val p = prepareCode("int main() { label:; label:; }", source)
    assertEquals(p.diags.ids,
        listOf(DiagnosticId.REDEFINITION_LABEL, DiagnosticId.REDEFINITION_PREVIOUS))
  }

  @Test
  fun `Label And Variable Together`() {
    val p = prepareCode("int main() { label:; int label; }", source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `Undeclared Usage`() {
    val p = prepareCode("int main() { int x = y; }", source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED)
  }
}
