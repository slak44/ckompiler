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
    int declare ("a" assign initializerList(("a" ofType ErrorType at 0) designates 2)) assertEquals p.root.decls[0]
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
    vec2 declare ("thing" assign initializerList("x" ofType int at 0 designates 56, 3)) assertEquals p.root.decls[0]
    p.assertNoDiagnostics()
  }

  @Test
  fun `Struct With Duplicate Initializers`() {
    val p = prepareCode("struct vec2 { int x; int y; } thing = { .x = 56, .x = 3 };", source)
    val vec2 = struct("vec2",
        int declare "x",
        int declare "y"
    ).toSpec()
    vec2 declare ("thing" assign initializerList(
        "x" ofType int at 0 designates 56,
        "x" ofType int at 0 designates 3
    )) assertEquals p.root.decls[0]
    p.assertDiags(DiagnosticId.INITIALIZER_OVERRIDES_PRIOR, DiagnosticId.PRIOR_INITIALIZER)
  }

  @Test
  fun `Struct With Duplicate Initializers Indirectly`() {
    val p = prepareCode("struct vec2 { int x; int y; } thing = { .y = 56, .x = 3, 785 };", source)
    val vec2 = struct("vec2",
        int declare "x",
        int declare "y"
    ).toSpec()
    vec2 declare ("thing" assign initializerList(
        "y" ofType int at 1 designates 56,
        "x" ofType int at 0 designates 3,
        785
    )) assertEquals p.root.decls[0]
    p.assertDiags(DiagnosticId.INITIALIZER_OVERRIDES_PRIOR, DiagnosticId.PRIOR_INITIALIZER)
  }

  @Test
  fun `Union With Initializers`() {
    val p = prepareCode("union stuff { int x; float y; } thing = { .x = 56 };", source)
    val stuff = union("stuff",
        int declare "x",
        float declare "y"
    ).toSpec()
    stuff declare ("thing" assign initializerList("x" ofType int at 0 designates 56)) assertEquals p.root.decls[0]
    p.assertNoDiagnostics()
  }

  @Test
  fun `Union With Multiple Initializers`() {
    val p = prepareCode("union stuff { int x; float y; } thing = { .x = 56, .y = 3.5f };", source)
    val stuff = union("stuff",
        int declare "x",
        float declare "y"
    ).toSpec()
    stuff declare ("thing" assign initializerList(
        "x" ofType int at 0 designates 56,
        "y" ofType float at 1 designates 3.5f
    )) assertEquals p.root.decls[0]
    p.assertDiags(DiagnosticId.INITIALIZER_OVERRIDES_PRIOR, DiagnosticId.PRIOR_INITIALIZER)
  }

  @Test
  fun `Struct Wrong Name`() {
    val p = prepareCode("struct vec2 { int x; int y; } thing = { .lalalal = 56, 3 };", source)
    val vec2 = struct("vec2",
        int declare "x",
        int declare "y"
    ).toSpec()
    vec2 declare ("thing" assign initializerList(("lalalal" ofType ErrorType at 0) designates 56, 3)) assertEquals p.root.decls[0]
    p.assertDiags(DiagnosticId.DOT_DESIGNATOR_NO_FIELD)
  }

  @Test
  fun `Array Initializer`() {
    val p = prepareCode("int a[2] = { 2, 3 };", source)
    int declare (nameDecl("a")[2] assign initializerList(2, 3)) assertEquals p.root.decls[0]
    p.assertNoDiagnostics()
  }

  @Test
  fun `Array Initializer With Designators`() {
    val p = prepareCode("int a[2] = { [0] = 2, [1] = 3 };", source)
    val type = ArrayType(SignedIntType, ConstantSize(int(2)))
    int declare (nameDecl("a")[2] assign initializerList(type[0] designates 2, type[1] designates 3)) assertEquals p.root.decls[0]
    p.assertNoDiagnostics()
  }

  @Test
  fun `Array Initializer With Designators Not In Order`() {
    val p = prepareCode("int a[2] = { [1] = 3, [0] = 2 };", source)
    val type = ArrayType(SignedIntType, ConstantSize(int(2)))
    int declare (nameDecl("a")[2] assign initializerList(type[1] designates 3, type[0] designates 2)) assertEquals p.root.decls[0]
    p.assertNoDiagnostics()
  }

  @Test
  fun `Array Initializer With Mixed Designators`() {
    val p = prepareCode("int a[100] = { [1] = 3, 2, [10] = 5, 11 };", source)
    val type = ArrayType(SignedIntType, ConstantSize(int(100)))
    int declare (nameDecl("a")[100] assign initializerList(
        type[1] designates 3,
        2,
        type[10] designates 5,
        11
    )) assertEquals p.root.decls[0]
    p.assertNoDiagnostics()
  }

  @Test
  fun `Array Negative Index`() {
    val p = prepareCode("int x[123] = { [-1] = 6 };", source)
    p.assertDiags(DiagnosticId.ARRAY_DESIGNATOR_NEGATIVE)
  }

  @Test
  fun `Array Out Of Bounds Index`() {
    val p = prepareCode("int x[123] = { [124] = 6 };", source)
    p.assertDiags(DiagnosticId.ARRAY_DESIGNATOR_BOUNDS)
  }

  @Test
  fun `Array Out Of Bounds Excess Initializer`() {
    val p = prepareCode("int x[123] = { [122] = 6, 56 };", source)
    p.assertDiags(DiagnosticId.EXCESS_INITIALIZERS_ARRAY)
  }

  /**
   * C standard: 6.7.9.0.26
   */
  @Test
  fun `Array 2D Initializer Fully Bracketed`() {
    val p = prepareCode("""
      int y[4][3] = {
        { 1, 3, 5 },
        { 2, 4, 6 },
        { 3, 5, 7 },
      };
    """.trimIndent(), source)
    int declare (nameDecl("y")[4][3] assign initializerList(
        initializerList(1, 3, 5),
        initializerList(2, 4, 6),
        initializerList(3, 5, 7),
    )) assertEquals p.root.decls[0]
    p.assertNoDiagnostics()
  }

  /**
   * C standard: 6.7.9.0.26
   */
  @Test
  fun `Array 2D Initializer Not Bracketed`() {
    val p = prepareCode("""
      int y[4][3] = {
        1, 3, 5, 2, 4, 6, 3, 5, 7
      };
    """.trimIndent(), source)
    int declare (nameDecl("y")[4][3] assign initializerList(
        1, 3, 5, 2, 4, 6, 3, 5, 7
    )) assertEquals p.root.decls[0]
    p.assertNoDiagnostics()
  }

  @Test
  fun `Array Initializer With No Size`() {
    val p = prepareCode("int a[] = { 2, 3 };", source)
    int declare (nameDecl("a")[NoSize] assign initializerList(2, 3)) assertEquals p.root.decls[0]
    p.assertNoDiagnostics()
  }

  @Test
  fun `Char Array Initializer From String`() {
    val p = prepareCode("char a[] = \"Hello\";", source)
    char declare (nameDecl("a")[NoSize] assign strLit("Hello")) assertEquals p.root.decls[0]
    p.assertNoDiagnostics()
  }

  @Test
  fun `Char Array Initializer From Too Long String`() {
    val p = prepareCode("char a[2] = \"Hello\";", source)
    char declare (nameDecl("a")[2] assign strLit("Hello")) assertEquals p.root.decls[0]
    p.assertDiags(DiagnosticId.EXCESS_INITIALIZER_SIZE)
  }

  @Test
  fun `Array Initializer On Struct`() {
    val p = prepareCode("struct vec2 { int x; int y; } thing = { [0] = 56, 3 };", source)
    p.assertDiags(DiagnosticId.ARRAY_DESIGNATOR_NON_ARRAY)
  }

  @Test
  fun `Array Initializer Non-Constant`() {
    val p = prepareCode("int a = 1; int x[123] = { [a] = 6 };", source)
    p.assertDiags(DiagnosticId.EXPR_NOT_CONSTANT_INT)
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
