package slak.test.analysis

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.*
import slak.test.*

class TypeTests {
  @Test
  fun `Unary Plus And Minus`() {
    val p = prepareCode("int a = -1; int b = +a;", source)
    p.assertNoDiagnostics()
    val a = nameRef("a", SignedIntType)
    int declare ("a" assign UnaryOperators.MINUS[1]) assertEquals p.root.decls[0]
    int declare ("b" assign UnaryOperators.PLUS[a]) assertEquals p.root.decls[1]
  }

  @Test
  fun `Unary Plus And Minus On Bad Type`() {
    val p = prepareCode("int main() {-main;}", source)
    p.assertDiags(DiagnosticId.INVALID_ARGUMENT_UNARY)
    int func ("main" withParams emptyList()) body compoundOf(
        UnaryOperators.MINUS[ErrorExpression()]
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Unary Bitwise Not On Bad Type`() {
    val p = prepareCode("int main() {~main;}", source)
    p.assertDiags(DiagnosticId.INVALID_ARGUMENT_UNARY)
    int func ("main" withParams emptyList()) body compoundOf(
        UnaryOperators.BIT_NOT[ErrorExpression()]
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Call An Expr`() {
    val p = prepareCode("""
      int f();
      int a = (&f)();
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val f = nameRef("f", FunctionType(SignedIntType, emptyList()))
    int declare ("a" assign UnaryOperators.REF[f]()) assertEquals p.root.decls[1]
  }

  @Test
  fun `Prefix Increment Bad Type`() {
    val p = prepareCode("int main() {++main;}", source)
    p.assertDiags(DiagnosticId.INVALID_INC_DEC_ARGUMENT)
    int func ("main" withParams emptyList()) body compoundOf(
        PrefixIncrement(ErrorExpression())
    ) assertEquals p.root.decls[0]
  }
}
