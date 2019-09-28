package slak.test.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.Expression
import slak.ckompiler.parser.FunctionType
import slak.ckompiler.parser.PointerType
import slak.ckompiler.parser.VoidType
import slak.test.*

class ReturnStatementTests {
  @Test
  fun `Return Basic`() {
    val p = prepareCode("""
      int main() {
        return 0;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func "main" body compoundOf(
        returnSt(int(0))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Return Expression`() {
    val p = prepareCode("""
      int main() {
        return (1 + 1) / 2;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func "main" body compoundOf(
        returnSt((1 add 1) div 2)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Return Missing Semi`() {
    val p = prepareCode("""
      int main() {
        return 0
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_SEMI_AFTER)
    int func "main" body compoundOf(
        returnSt(int(0))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Return Nothing`() {
    val p = prepareCode("""
      void f() {
        return;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    void func "f" body compoundOf(
        returnSt()
    ) assertEquals p.root.decls[0]
  }

  @Suppress("unused")
  enum class ReturnSomethingInVoidTests(val str: String, val expected: Expression) {
    RET_INT("1", int(1)),
    RET_FLOAT("1.0", double(1.0)),
    RET_FUNC("f", nameRef("f",
        FunctionType(VoidType, emptyList()).let { PointerType(it, emptyList(), it) }))
  }

  @ParameterizedTest
  @EnumSource(ReturnSomethingInVoidTests::class)
  fun `Return Something In Void Function`(ret: ReturnSomethingInVoidTests) {
    val p = prepareCode("""
      void f() {
        return ${ret.str};
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.VOID_RETURNS_VALUE)
    void func "f" body compoundOf(
        returnSt(ret.expected)
    ) assertEquals p.root.decls[0]
  }

  @ParameterizedTest
  @ValueSource(strings = ["int", "float", "struct {float x, y;}"])
  fun `Return Nothing In Non-Void Function`(type: String) {
    val p = prepareCode("""
      $type f() {
        return;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.NON_VOID_RETURNS_NOTHING)
  }

  @ParameterizedTest
  @ValueSource(strings = ["(void) 1", "(void) 1.23", "f()"])
  fun `Don't Return Void Expressions`(expr: String) {
    val p = prepareCode("""
      void f() {
        return $expr;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.DONT_RETURN_VOID_EXPR)
  }

  @Test
  fun `Return Type Mismatch Void`() {
    val p = prepareCode("""
      int f() {
        return (void) 2;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.RET_TYPE_MISMATCH)
    int func "f" body compoundOf(
        returnSt(VoidType.cast(2))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Return Type Mismatch With Struct`() {
    val p = prepareCode("""
      struct { float x, y; } f() {
        return 5.7F;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.RET_TYPE_MISMATCH)
    val s = struct(null, float declare listOf("x", "y")).toSpec()
    s func "f" body compoundOf(
        returnSt(float(5.7))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Return Type Mismatch With Pointers`() {
    val p = prepareCode("""
      int* f() {
        return 5.7F;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.RET_TYPE_MISMATCH)
    int func (ptr("f") withParams emptyList()) body compoundOf(
        returnSt(float(5.7))
    ) assertEquals p.root.decls[0]
  }
}
