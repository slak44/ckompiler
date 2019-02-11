package slak.test.parser

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.Keywords
import slak.ckompiler.parser.*
import slak.test.*
import kotlin.test.assertEquals

/** Similarly to [LexerTests], these are not strictly unit tests. */
class DeclarationTests {
  @Test
  fun `Basic Declaration`() {
    val p = prepareCode("int a;", source)
    p.assertNoDiagnostics()
    assertEquals(listOf(int declare "a"), p.root.decls)
  }

  @Test
  fun `Multiple Declarators`() {
    val p = prepareCode("int a, b, c;", source)
    p.assertNoDiagnostics()
    assertEquals(int declare listOf("a", "b", "c"), p.root.decls[0])
  }

  @Test
  fun `Multiple Declarations`() {
    val p = prepareCode("int a; int b; int c;", source)
    p.assertNoDiagnostics()
    assertEquals(listOf("a", "b", "c").map { int declare it }, p.root.decls)
  }

  @Test
  fun `Simple Initializer`() {
    val p = prepareCode("int a = 1;", source)
    p.assertNoDiagnostics()
    assertEquals(listOf(int declare ("a" assign 1)), p.root.decls)
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
  fun `Declaration With Bad Name`() {
    val p = prepareCode("int default = 1;", source)
    assertEquals(DiagnosticId.EXPECTED_IDENT_OR_PAREN, p.diags[0].id)
  }

  @Test
  fun `Declaration Missing Semicolon`() {
    val p = prepareCode("int a", source)
    assertEquals(DiagnosticId.EXPECTED_SEMI_AFTER, p.diags[0].id)
    assertEquals(listOf(int declare "a"), p.root.decls)
  }

  @Test
  fun `Declaration Doesn't Declare Anything`() {
    val p = prepareCode("int;", source)
    p.assertDiags(DiagnosticId.MISSING_DECLARATIONS)
  }

  @Test
  fun `Declarator With Pointer`() {
    val p = prepareCode("int* a;", source)
    p.assertNoDiagnostics()
    assertEquals(listOf(int declare ptr("a")), p.root.decls)
  }

  @Test
  fun `Declarator With Pointers`() {
    val p = prepareCode("int**** a;", source)
    p.assertNoDiagnostics()
    val d = int declare "a".withPtrs(listOf(), listOf(), listOf(), listOf())
    assertEquals(listOf(d), p.root.decls)
  }

  @Test
  fun `Declarator With Type Qualifiers On Pointers`() {
    val p = prepareCode("int *const volatile _Atomic *volatile *_Atomic * a;", source)
    p.assertNoDiagnostics()
    val d = int declare "a".withPtrs(
        listOf(Keywords.CONST.kw, Keywords.VOLATILE.kw, Keywords.ATOMIC.kw),
        listOf(Keywords.VOLATILE.kw),
        listOf(Keywords.ATOMIC.kw),
        listOf()
    )
    assertEquals(listOf(d), p.root.decls)
  }

  @Test
  fun `Declarator With Identifier In Pointer`() {
    val p = prepareCode("int * lalala * a;", source)
    p.assertDiags(DiagnosticId.EXPECTED_SEMI_AFTER, DiagnosticId.EXPECTED_EXTERNAL_DECL)
  }

  @Test
  fun `Declarator With Keyword In Pointer`() {
    val p = prepareCode("int * int * a;", source)
    p.assertDiags(DiagnosticId.EXPECTED_IDENT_OR_PAREN)
  }

  @Test
  fun `Declarator With Pointer But Ends Too Early`() {
    val p = prepareCode("int *", source)
    p.assertDiags(DiagnosticId.EXPECTED_IDENT_OR_PAREN, DiagnosticId.EXPECTED_SEMI_AFTER)
  }

  @Test
  fun `Typedef Multiple Declarators`() {
    val p = prepareCode("typedef int new_int, * new_int2, * const new_int3;", source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `Typedef Requires Name`() {
    val p = prepareCode("typedef int;", source)
    p.assertDiags(DiagnosticId.TYPEDEF_REQUIRES_NAME)
  }

  @Test
  fun `Array Simple Declaration`() {
    val p = prepareCode("int array_of_stuff[734];", source)
    p.assertNoDiagnostics()
    int declare nameDecl("array_of_stuff")[734] assertEquals p.root.decls[0]
  }

  @Test
  fun `Array 2D Declaration`() {
    val p = prepareCode("int array2d[73][23];", source)
    p.assertNoDiagnostics()
    int declare nameDecl("array2d")[73][23] assertEquals p.root.decls[0]
  }

  @Test
  fun `Array 6D Declaration`() {
    val p = prepareCode("int array6d[73][23][2][78 + 1][3 / 1][1];", source)
    p.assertNoDiagnostics()
    int declare nameDecl("array6d")[73][23][2][78 add 1][3 div 1][1] assertEquals p.root.decls[0]
  }

  @Test
  fun `Array Static Without Size`() {
    val p = prepareCode("""
      int f(int a[static]);
      int g(int a[static const]);
      int h(int a[const static]);
    """.trimIndent(), source)
    p.assertDiags(*Array(3) { DiagnosticId.ARRAY_STATIC_NO_SIZE })
  }
}
