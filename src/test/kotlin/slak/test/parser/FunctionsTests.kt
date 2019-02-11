package slak.test.parser

import org.junit.Ignore
import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.ErrorStatement
import slak.test.*

class FunctionsTests {
  @Test
  fun `Function Declaration Explicitly No Parameters`() {
    val p = prepareCode("int f(void);", source)
    p.assertNoDiagnostics()
    int proto "f" assertEquals p.root.decls[0]
  }

  @Test
  fun `Function Declaration No Parameters`() {
    val p = prepareCode("int f();", source)
    p.assertNoDiagnostics()
    int proto "f" assertEquals p.root.decls[0]
  }

  @Test
  fun `Function Declaration One Parameter`() {
    val p = prepareCode("int f(double dbl);", source)
    p.assertNoDiagnostics()
    int proto ("f" withParams listOf(double param "dbl")) assertEquals p.root.decls[0]
  }

  @Test
  fun `Function Declaration Two Parameters`() {
    val p = prepareCode("int f(double dbl, int x);", source)
    p.assertNoDiagnostics()
    int proto ("f" withParams listOf(double param "dbl", int param "x")) assertEquals p.root.decls[0]
  }

  @Test
  fun `Function Declaration Three Parameters`() {
    val p = prepareCode("int f(double dbl, int x, int y);", source)
    p.assertNoDiagnostics()
    int proto ("f" withParams listOf(double param "dbl", int param "x", int param "y")) assertEquals
        p.root.decls[0]
  }

  @Test
  fun `Function Declaration Four Parameters`() {
    val p = prepareCode("int f(double dbl, int x, int y, double asd);", source)
    p.assertNoDiagnostics()
    int proto ("f" withParams
        listOf(double param "dbl", int param "x", int param "y", double param "asd")) assertEquals
        p.root.decls[0]
  }

  @Test
  fun `Function Prototype Expected Identifier Or Paren`() {
    val p = prepareCode("int default();", source)
    p.assertDiags(DiagnosticId.EXPECTED_IDENT_OR_PAREN)
  }

  @Test
  fun `Empty Function Definition`() {
    val p = prepareCode("int main() {}", source)
    p.assertNoDiagnostics()
    int func "main" body emptyCompound() assertEquals p.root.decls[0]
  }

  @Test
  fun `Function Definition Unmatched Brackets`() {
    val p = prepareCode("int main() {", source)
    p.assertDiags(DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET)
    int func "main" body ErrorStatement() assertEquals p.root.decls[0]
  }

  @Test
  fun `One Arg Empty Function Definition`() {
    val p = prepareCode("int main(int argc) {}", source)
    p.assertNoDiagnostics()
    int func ("main" withParams listOf(int param "argc")) body emptyCompound() assertEquals
        p.root.decls[0]
  }

  @Test
  fun `Basic Function Definition With Expression`() {
    val p = prepareCode("int main() { 1 + 1; }", source)
    p.assertNoDiagnostics()
    int func "main" body compoundOf(1 add 1) assertEquals
        p.root.decls[0]
  }

  @Test
  fun `Variadic Basic`() {
    val p = prepareCode("int f(int a, ...);", source)
    p.assertNoDiagnostics()
    int proto ("f" withParamsV listOf(int param "a")) assertEquals p.root.decls[0]
  }

  @Test
  fun `Variadic 2 Args`() {
    val p = prepareCode("int f(int a, double b, ...);", source)
    p.assertNoDiagnostics()
    int proto ("f" withParamsV listOf(int param "a", double param "b")) assertEquals p.root.decls[0]
  }

  @Test
  fun `Arg After Variadic`() {
    val p = prepareCode("int f(int x, ..., int a);", source)
    p.assertDiags(DiagnosticId.EXPECTED_RPAREN_AFTER_VARIADIC)
    int proto ("f" withParamsV listOf(int param "x")) assertEquals p.root.decls[0]
  }

  @Test
  fun `Variadic Alone`() {
    val p = prepareCode("int f(...);", source)
    p.assertDiags(DiagnosticId.PARAM_BEFORE_VARIADIC)
    int proto ("f" withParamsV emptyList()) assertEquals p.root.decls[0]
  }

  @Test
  fun `Variadic Nothing Before And Something After`() {
    val p = prepareCode("int f(..., int a);", source)
    p.assertDiags(DiagnosticId.PARAM_BEFORE_VARIADIC, DiagnosticId.EXPECTED_RPAREN_AFTER_VARIADIC)
    int proto ("f" withParamsV emptyList()) assertEquals p.root.decls[0]
  }

  @Test
  fun `Function Parameters No Initializers`() {
    val p = prepareCode("""
      int f(int y = 1, int x = 54 + 3 / 34 >> 3);
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.NO_DEFAULT_ARGS, DiagnosticId.NO_DEFAULT_ARGS)
  }

  @Test
  @Ignore("We can't parse that yet")
  fun `Function Prototype Declaration Can't Return Function Type`() {
    val p = prepareCode("int f(int)(int);", source)
    // FIXME: function cannot return function type 'int (int)'
  }

  @Test
  fun `Function Prototype Return Pointer To Function`() {
    val p = prepareCode("int (*f(int x))(int y);", source)
    p.assertNoDiagnostics()
    val f = int declare ptr("f" withParams listOf(int param "x") withParams listOf(int param "y"))
    f assertEquals p.root.decls[0]
  }
}
