package slak.test.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.*
import slak.test.*
import slak.test.source

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

  @Suppress("unused")
  enum class ReturnMismatchTests(
      val type: String,
      val expectedType: DeclarationSpecifier,
      val str: String,
      val expected: Expression
  ) {
    INT_FLOAT("int", int, "2.0F", float(2.0)),
    INT_VOID("int", int, "(void) 2", VoidType.cast(2)),
    STRUCT_FLOAT("struct { float x, y; }",
        struct(null, listOf(float declare listOf("x", "y"))).toSpec(), "5.7F", float(5.7))
  }

  @ParameterizedTest
  @EnumSource(ReturnMismatchTests::class)
  fun `Return Type Mismatch`(ret: ReturnMismatchTests) {
    val p = prepareCode("""
      ${ret.type} f() {
        return ${ret.str};
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.RET_TYPE_MISMATCH)
    ret.expectedType func "f" body compoundOf(
        returnSt(ret.expected)
    ) assertEquals p.root.decls[0]
  }
}
