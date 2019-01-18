package slak.test.parser

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.ErrorStatement
import slak.test.*

class FunctionsTests {
  @Test
  fun `Function Declaration No Params`() {
    val p = prepareCode("int f();", source)
    p.assertNoDiagnostics()
    int func ("f" withParams emptyList()) assertEquals p.root.decls[0]
  }

  @Test
  fun `Function Declaration One Param`() {
    val p = prepareCode("int f(double dbl);", source)
    p.assertNoDiagnostics()
    int func ("f" withParams listOf(double param "dbl")) assertEquals p.root.decls[0]
  }

  @Test
  fun `Function Declaration Two Params`() {
    val p = prepareCode("int f(double dbl, int x);", source)
    p.assertNoDiagnostics()
    int func ("f" withParams listOf(double param "dbl", int param "x")) assertEquals p.root.decls[0]
  }

  @Test
  fun `Function Declaration Three Params`() {
    val p = prepareCode("int f(double dbl, int x, int y);", source)
    p.assertNoDiagnostics()
    int func ("f" withParams listOf(double param "dbl", int param "x", int param "y")) assertEquals
        p.root.decls[0]
  }

  @Test
  fun `Function Declaration Four Params`() {
    val p = prepareCode("int f(double dbl, int x, int y, double asd);", source)
    p.assertNoDiagnostics()
    int func ("f" withParams
        listOf(double param "dbl", int param "x", int param "y", double param "asd")) assertEquals
        p.root.decls[0]
  }

  @Test
  fun `Function Proto Expected Ident Or Paren`() {
    val p = prepareCode("int default();", source)
    p.assertDiags(DiagnosticId.EXPECTED_IDENT_OR_PAREN)
  }

  @Test
  fun `Function Definition Basic Empty`() {
    val p = prepareCode("int main() {}", source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body emptyList() assertEquals p.root.decls[0]
  }

  @Test
  fun `Function Definition Unmatched Brackets`() {
    val p = prepareCode("int main() {", source)
    p.assertDiags(DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET)
    int func ("main" withParams emptyList()) body ErrorStatement() assertEquals p.root.decls[0]
  }

  @Test
  fun `Function Definition One Arg Empty`() {
    val p = prepareCode("int main(int argc) {}", source)
    p.assertNoDiagnostics()
    int func ("main" withParams listOf(int param "argc")) body emptyCompound() assertEquals
        p.root.decls[0]
  }

  @Test
  fun `Function Definition Basic With Expression`() {
    val p = prepareCode("int main() { 1 + 1; }", source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(1 add 1) assertEquals
        p.root.decls[0]
  }

  @Test
  fun `Variadic Basic`() {
    val p = prepareCode("int f(int a, ...);", source)
    p.assertNoDiagnostics()
    int func ("f" withParamsV listOf(int param "a")) assertEquals p.root.decls[0]
  }

  @Test
  fun variadic2Args() {
    val p = prepareCode("int f(int a, double b, ...);", source)
    p.assertNoDiagnostics()
    int func ("f" withParamsV listOf(int param "a", double param "b")) assertEquals p.root.decls[0]
  }

  @Test
  fun `Arg After Variadic`() {
    val p = prepareCode("int f(int x, ..., int a);", source)
    p.assertDiags(DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET)
    int func ("f" withParamsV listOf(int param "x")) assertEquals p.root.decls[0]
  }

  @Test
  fun `Variadic Alone`() {
    val p = prepareCode("int f(...);", source)
    p.assertDiags(DiagnosticId.PARAM_BEFORE_VARIADIC)
    int func ("f" withParamsV emptyList()) assertEquals p.root.decls[0]
  }

  @Test
  fun `Variadic Nothing Before And Something After`() {
    val p = prepareCode("int f(..., int a);", source)
    p.assertDiags(DiagnosticId.PARAM_BEFORE_VARIADIC,
        DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET)
    int func ("f" withParamsV emptyList()) assertEquals p.root.decls[0]
  }
}
