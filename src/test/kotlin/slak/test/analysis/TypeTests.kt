package slak.test.analysis

import org.junit.jupiter.api.Test
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

  @Test
  fun `Binary Add Bad Types`() {
    val p = prepareCode("int main() {1 + main;}", source)
    p.assertDiags(DiagnosticId.INVALID_ARGS_BINARY)
    int func ("main" withParams emptyList()) body compoundOf(
        1 add nameRef("main", FunctionType(SignedIntType, emptyList()))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Assignment To Cast Not Allowed`() {
    val p = prepareCode("int main() {int x = 1; (long) x = 5;}", source)
    p.assertDiags(DiagnosticId.ILLEGAL_CAST_ASSIGNMENT)
    int func ("main" withParams emptyList()) body compoundOf(
        int declare ("x" assign 1),
        SignedLongType.cast(nameRef("x", SignedIntType)) assign 5
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Assignment To Binary Expression Not Allowed`() {
    val p = prepareCode("int main() {int x; (x + 2) = 5;}", source)
    p.assertDiags(DiagnosticId.EXPRESSION_NOT_ASSIGNABLE)
    int func ("main" withParams emptyList()) body compoundOf(
        int declare "x",
        (nameRef("x", SignedIntType) add 2) assign 5
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Assignment To Constant Not Allowed`() {
    val p = prepareCode("int main() {2 = 5;}", source)
    p.assertDiags(DiagnosticId.CONSTANT_NOT_ASSIGNABLE)
    int func ("main" withParams emptyList()) body compoundOf(
        2 assign 5
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Array Of Functions Not Allowed`() {
    val p = prepareCode("int a[123]();", source)
    p.assertDiags(DiagnosticId.INVALID_ARR_TYPE)
    int declare (name("a")[123] withParams emptyList()) assertEquals p.root.decls[0]
  }

  @Test
  fun `Array Of Functions Not Allowed As Declaration Statement`() {
    val p = prepareCode("int main() {int a[123]();}", source)
    p.assertDiags(DiagnosticId.INVALID_ARR_TYPE)
    val badDecl = int declare (name("a")[123] withParams emptyList())
    int func ("main" withParams emptyList()) body compoundOf(badDecl) assertEquals p.root.decls[0]
  }
}
