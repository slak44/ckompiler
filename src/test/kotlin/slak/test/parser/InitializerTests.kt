package slak.test.parser

import org.junit.jupiter.api.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.*
import slak.test.*
import kotlin.test.assertEquals

class InitializerTests {
  @Test
  fun `Simple Initializer`() {
    val p = prepareCode("int a = 1;", source)
    p.assertNoDiagnostics()
    assertEquals(listOf(int declare ("a" assign 1)), p.root.decls)
  }

  @Test
  fun `Initializer And Multiple Declarators Don't Confuse Comma Expression`() {
    val p = prepareCode("int x = 5, y;", source)
    p.assertNoDiagnostics()
    int declare listOf("x" assign 5, "y") assertEquals p.root.decls[0]
  }

  @Test
  fun `Initializer And Multiple Declarators Don't Confuse Comma Expression With Parens`() {
    val p = prepareCode("int x = (5), y;", source)
    p.assertNoDiagnostics()
    int declare listOf("x" assign 5, "y") assertEquals p.root.decls[0]
  }

  @Test
  fun `Initializer Comma Expression In Parens Does Not Separate Declarators`() {
    val p = prepareCode("int x = (5, 6);", source)
    p.assertNoDiagnostics()
    int declare ("x" assign (5 comma 6)) assertEquals p.root.decls[0]
  }

  @Test
  fun `Identifier Initializer`() {
    val p = prepareCode("int a = someVariable;", source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED)
    assertEquals(listOf(int declare ("a" assign nameRef("someVariable", ErrorType))), p.root.decls)
  }

  @Test
  fun `Arithmetic Initializer`() {
    val p = prepareCode("int a = 1 + 2 * 3 - 4 / 5;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign (1 add (2 mul 3) sub (4 div 5))) assertEquals p.root.decls[0]
  }

  @Test
  fun `Complex Arithmetic Initializer`() {
    val p = prepareCode("int a = 1 + 2 * 2 * (3 - 4) / 5 / 6;", source)
    p.assertNoDiagnostics()
    val expr = 1 add (2 mul 2 mul (3 sub 4) div 5 div 6)
    assertEquals(listOf(int declare ("a" assign expr)), p.root.decls)
  }

  @Test
  fun `Simple Paren Initializer`() {
    val p = prepareCode("int a = (1);", source)
    p.assertNoDiagnostics()
    assertEquals(listOf(int declare ("a" assign 1)), p.root.decls)
  }

  @Test
  fun `Expression In Paren Initializer`() {
    val p = prepareCode("int a = (1 + 1);", source)
    p.assertNoDiagnostics()
    assertEquals(listOf(int declare ("a" assign (1 add 1))), p.root.decls)
  }

  @Test
  fun `Bad Initializer`() {
    val p = prepareCode("int a = 1 + ;", source)
    assertEquals(DiagnosticId.EXPECTED_PRIMARY, p.diags[0].id)
    assertEquals(listOf(int declare ("a" assign ErrorExpression())), p.root.decls)
  }

  @Test
  fun `Initializer List For Scalar`() {
    val p = prepareCode("int a = { 2 };", source)
    p.assertNoDiagnostics()
    int declare ("a" assign initializerList(2)) assertEquals p.root.decls[0]
  }

  @Test
  fun `Initializer List For Scalar Of Wrong Type`() {
    val p = prepareCode("struct test { int a, b; } x; int fail = { x };", source)
    p.assertDiags(DiagnosticId.INITIALIZER_TYPE_MISMATCH)
  }

  @Test
  fun `Initializer List For Scalar Error Expression Produces Correct Diagnostics`() {
    val p = prepareCode("int fail = { 1 + };", source)
    p.assertDiags(DiagnosticId.EXPECTED_EXPR)
  }

  @Test
  fun `Excess Initializer List For Scalar`() {
    val p = prepareCode("int a = { 2, 43 };", source)
    int declare ("a" assign initializerList(2, 43)) assertEquals p.root.decls[0]
    p.assertDiags(DiagnosticId.EXCESS_INITIALIZERS_SCALAR)
  }

  @Test
  fun `Designator In Initializer List For Scalar Not Allowed`() {
    val p = prepareCode("int a = { .a = 2 };", source)
    int declare ("a" assign initializerList(("a" ofType int at 0) designates 2)) assertEquals p.root.decls[0]
    p.assertDiags(DiagnosticId.DESIGNATOR_FOR_SCALAR)
  }

  @Test
  fun `Incomplete Type Initializer`() {
    val p = prepareCode("struct point; struct point x = { .x = 1, 2 };", source)
    p.assertDiags(DiagnosticId.VARIABLE_TYPE_INCOMPLETE)
  }

  @Test
  fun `Incomplete Type Initializer For Local Variable`() {
    val p = prepareCode("struct point; int main() { struct point x = { .x = 1, 2 }; }", source)
    p.assertDiags(DiagnosticId.VARIABLE_TYPE_INCOMPLETE)
  }

  @Test
  fun `Simple Struct Initializer`() {
    val p = prepareCode("struct vec2 { int x; int y; } thing = { 56, 3 };", source)
    val vec2 = struct("vec2",
        int declare "x",
        int declare "y"
    ).toSpec()
    vec2 declare ("thing" assign initializerList(56, 3)) assertEquals p.root.decls[0]
    p.assertNoDiagnostics()
  }

  @Test
  fun `Dot Designator Without Identifier Is Error`() {
    val p = prepareCode("struct vec2 { int x; int y; } thing = { . };", source)
    p.assertDiags(DiagnosticId.EXPECTED_DOT_DESIGNATOR)
  }

  @Test
  fun `Dot Designator And Non-Identifier Is Error`() {
    val p = prepareCode("struct vec2 { int x; int y; } thing = { .return };", source)
    p.assertDiags(DiagnosticId.EXPECTED_DOT_DESIGNATOR)
  }

  @Test
  fun `Dot Designator That Is Actually Float Is Allowed`() {
    val p = prepareCode("struct vec2 { float x; float y; } thing = { .123 };", source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `Designated Initializer Should Have Initializer, Not Just Designators`() {
    val p = prepareCode("struct vec2 { float x; float y; } thing = { .x };", source)
    p.assertDiags(DiagnosticId.EXPECTED_NEXT_DESIGNATOR)
  }

  @Test
  fun `Simple Struct Initializer With Designators`() {
    val p = prepareCode("struct vec2 { int x; int y; } thing = { .y = 3, .x = 56 };", source)
    val vec2 = struct("vec2",
        int declare "x",
        int declare "y"
    ).toSpec()
    vec2 declare ("thing" assign initializerList(
        ("y" ofType int at 1) designates 3,
        ("x" ofType int at 0) designates 56
    )) assertEquals p.root.decls[0]
    p.assertNoDiagnostics()
  }

  @Test
  fun `Simple Struct Mixed`() {
    val p = prepareCode("struct vec2 { int x; int y; } thing = { .x = 56, 3 };", source)
    val vec2 = struct("vec2",
        int declare "x",
        int declare "y"
    ).toSpec()
    vec2 declare ("thing" assign initializerList(("x" ofType int at 0) designates 56, 3)) assertEquals p.root.decls[0]
    p.assertNoDiagnostics()
  }

  @Test
  fun `Array Initializer`() {
    val p = prepareCode("int a[2] = { 2, 3 };", source)
    int declare (nameDecl("a")[2] assign initializerList(2, 3)) assertEquals p.root.decls[0]
    p.assertNoDiagnostics()
  }

  @Test
  fun `Array Initializer With No Size`() {
    val p = prepareCode("int a[] = { 2, 3 };", source)
    int declare (nameDecl("a")[NoSize] assign initializerList(2, 3)) assertEquals p.root.decls[0]
    p.assertNoDiagnostics()
  }

  @Test
  fun `Array Initializer On Struct`() {
    val p = prepareCode("struct vec2 { int x; int y; } thing = { [0] = 56, 3 };", source)
    p.assertDiags(DiagnosticId.ARRAY_DESIGNATOR_NON_ARRAY)
  }

  @Test
  fun `Tag Initializer On Array`() {
    val p = prepareCode("int a[2] = { .x = 56, 3 };", source)
    p.assertDiags(DiagnosticId.DOT_DESIGNATOR_NON_TAG)
  }

  @Test
  fun `Initializer List On Incomplete Type`() {
    val p = prepareCode("struct a; struct a asdasdasd = { 23 };", source)
    p.assertDiags(DiagnosticId.VARIABLE_TYPE_INCOMPLETE)
  }

  @Test
  fun `Initializer List On Incomplete Type With Designator`() {
    val p = prepareCode("struct a; struct a asdasdasd = { .sad = 23 };", source)
    p.assertDiags(DiagnosticId.VARIABLE_TYPE_INCOMPLETE)
  }

  @Test
  fun `Excess Initializer List For Struct`() {
    val p = prepareCode("struct { int x, y; } a = { 2, 3, 4 };", source)
    p.assertDiags(DiagnosticId.EXCESS_INITIALIZERS)
  }
}
