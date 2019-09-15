package slak.test.parser

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.AbstractDeclarator
import slak.ckompiler.parser.ErrorStatement
import slak.ckompiler.parser.PointerType
import slak.ckompiler.parser.SignedIntType
import slak.test.*
import kotlin.test.assertEquals

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
    int proto ("f" withParams listOf(double param "dbl", int param "x")) assertEquals
        p.root.decls[0]
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
  fun `Function Declaration Dangling Comma`() {
    val p = prepareCode("int f(double dbl, );", source)
    p.assertDiags(DiagnosticId.EXPECTED_PARAM_DECL)
  }

  @Test
  fun `Function Declaration Just A Comma`() {
    val p = prepareCode("int f(,);", source)
    p.assertDiags(DiagnosticId.EXPECTED_PARAM_DECL)
  }

  @Test
  fun `Function Declaration Comma First`() {
    val p = prepareCode("int f(, double);", source)
    p.assertDiags(DiagnosticId.EXPECTED_PARAM_DECL)
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
  fun `Function Return Pointer Type`() {
    val p = prepareCode("int* f(int a) {return (int*)0;}", source)
    p.assertNoDiagnostics()
    int func (ptr("f") withParams listOf(int param "a")) body compoundOf(
        returnSt(PointerType(SignedIntType, emptyList(), false).cast(0))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Function Can't Return Function Type`() {
    val p = prepareCode("int f(int)(int);", source)
    p.assertDiags(DiagnosticId.INVALID_RET_TYPE)
    int proto ("f" withParams listOf(int.toParam()) withParams listOf(int.toParam())) assertEquals
        p.root.decls[0]
  }

  @Test
  fun `Function Can't Return Array Type`() {
    val p = prepareCode("int f(int)[123];", source)
    p.assertDiags(DiagnosticId.INVALID_RET_TYPE)
    int proto ("f" withParams listOf(int.toParam()))[123] assertEquals p.root.decls[0]
  }

  @Test
  fun `Function Can't Return Typedef To Function Type`() {
    val p = prepareCode("""
      typedef int ft(int);
      ft real();
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.INVALID_RET_TYPE)
    val ftDecl = "ft" withParams listOf(int.toParam())
    val (typedef, ft) = typedef(int, ftDecl)
    typedef proto ftDecl assertEquals p.root.decls[0]
    ft proto ("real" withParams emptyList()) assertEquals p.root.decls[1]
  }

  @Test
  fun `Function Prototype Take Function As Parameter`() {
    val p = prepareCode("int f(int ());", source)
    p.assertNoDiagnostics()
    val f = int declare ("f" withParams listOf(int withParams emptyList()))
    f assertEquals p.root.decls[0]
  }

  @Disabled("declarator parsing temporarily broken")
  @Test
  fun `Function Prototype Return Pointer To Function`() {
    val p = prepareCode("int (*f(int x))(double y);", source)
    p.assertNoDiagnostics()
    val f =
        int declare ptr("f" withParams listOf(int param "x") withParams listOf(double param "y"))
    f assertEquals p.root.decls[0]
  }

  @Disabled("declarator parsing temporarily broken")
  @Test
  fun `Function Prototype Return Nested Pointer To Function`() {
    val p = prepareCode("int (*(*f(int x))(int y))(int z);", source)
    p.assertNoDiagnostics()
    val f = int declare ptr("f" withParams listOf(int param "x")
        withParams listOf(int param "y") withParams listOf(int param "z"))
    f assertEquals p.root.decls[0]
  }

  @Disabled("declarator parsing temporarily broken")
  @Test
  fun `Function Prototype Return Pointer To Function Complex`() {
    val p = prepareCode("int (*fpfi(int (*)(long), int))(int, int);", source)
    p.assertNoDiagnostics()
    val innerFun = int param (AbstractDeclarator(listOf(listOf()), emptyList())
        withParams listOf(long.toParam()))
    val f =
        int declare (ptr("fpfi" withParams listOf(innerFun, int.toParam()))
            withParams listOf(int.toParam(), int.toParam()))
    f assertEquals p.root.decls[0]
  }

  @Test
  fun `Function Proto Abstract Declarators As Parameters`() {
    val p = prepareCode("int f(double, int);", source)
    p.assertNoDiagnostics()
    int proto ("f" withParams listOf(double.toParam(), int.toParam())) assertEquals p.root.decls[0]
  }

  @Test
  fun `Function Definition Only Named Declarators`() {
    val p = prepareCode("int f(double) {return 1;}", source)
    p.assertDiags(DiagnosticId.PARAM_NAME_OMITTED)
    int func ("f" withParams listOf(double.toParam())) body compoundOf(
        returnSt(1)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Prototype From Header`() {
    val p = prepareCode("""
      #include <printf.h>
      int main() {
        printf("Hello World!\n");
        return 0;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    assertEquals(2, p.root.decls.size)
  }
}
