package slak.test.parser

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.Keyword
import slak.ckompiler.lexer.Keywords
import slak.ckompiler.parser.*
import slak.test.*
import kotlin.test.assertEquals

/** Similarly to [LexerTests], these are not strictly unit tests. */
class DeclarationTests {
  @Test
  fun declarationBasic() {
    val p = prepareCode("int a;", source)
    p.assertNoDiagnostics()
    assertEquals(listOf(int declare "a"), p.root.decls)
  }

  @Test
  fun declarationWithMultipleDeclSpecs() {
    val p = prepareCode("const static int a;", source)
    p.assertNoDiagnostics()
    val spec = DeclarationSpecifier(
        typeQualifiers = listOf(Keyword(Keywords.CONST)),
        storageClass = Keyword(Keywords.STATIC),
        typeSpec = IntType(Keyword(Keywords.INT)))
    assertEquals(listOf(spec declare "a"), p.root.decls)
  }

  @Test
  fun declarationMultipleDeclarators() {
    val p = prepareCode("int a, b, c;", source)
    p.assertNoDiagnostics()
    assertEquals(int declare listOf("a", "b", "c").map { nameDecl(it) }, p.root.decls[0])
  }

  @Test
  fun declarationMultipleDeclarations() {
    val p = prepareCode("int a; int b; int c;", source)
    p.assertNoDiagnostics()
    assertEquals(listOf("a", "b", "c").map { int declare it }, p.root.decls)
  }

  @Test
  fun declarationWithSimpleInitializer() {
    val p = prepareCode("int a = 1;", source)
    p.assertNoDiagnostics()
    assertEquals(listOf(int declare ("a" assign int(1))), p.root.decls)
  }

  @Test
  fun declarationWithIdentifierInitializer() {
    val p = prepareCode("int a = someVariable;", source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED)
    assertEquals(listOf(int declare ("a" assign IdentifierNode("someVariable"))), p.root.decls)
  }

  @Test
  fun declarationWithArithmeticInitializer() {
    val p = prepareCode("int a = 1 + 2 * 3 - 4 / 5;", source)
    p.assertNoDiagnostics()
    int declare ("a" assign (1 add (2 mul 3) sub (4 div 5))) assertEquals p.root.decls[0]
  }

  @Test
  fun declarationWithComplexArithmeticInitializer() {
    val p = prepareCode("int a = 1 + 2 * 2 * (3 - 4) / 5 / 6;", source)
    p.assertNoDiagnostics()
    val expr = 1 add (2 mul 2 mul (3 sub 4) div 5 div 6)
    assertEquals(listOf(int declare ("a" assign expr)), p.root.decls)
  }

  @Test
  fun declarationWithSimpleParenInitializer() {
    val p = prepareCode("int a = (1);", source)
    p.assertNoDiagnostics()
    assertEquals(listOf(int declare ("a" assign int(1))), p.root.decls)
  }

  @Test
  fun declarationWithExprParenInitializer() {
    val p = prepareCode("int a = (1 + 1);", source)
    p.assertNoDiagnostics()
    assertEquals(listOf(int declare ("a" assign (1 add 1))), p.root.decls)
  }

  @Test
  fun declarationWithBadInitializer() {
    val p = prepareCode("int a = 1 + ;", source)
    assertEquals(DiagnosticId.EXPECTED_PRIMARY, p.diags[0].id)
    assertEquals(listOf(int declare ("a" assign ErrorExpression())), p.root.decls)
  }

  @Test
  fun declarationWithBadName() {
    val p = prepareCode("int default = 1;", source)
    assertEquals(DiagnosticId.EXPECTED_IDENT_OR_PAREN, p.diags[0].id)
  }

  @Test
  fun declarationMissingSemicolon() {
    val p = prepareCode("int a", source)
    assertEquals(DiagnosticId.EXPECTED_SEMI_AFTER, p.diags[0].id)
    assertEquals(listOf(int declare "a"), p.root.decls)
  }

  @Test
  fun declarationDoesntDeclareAnything() {
    val p = prepareCode("int;", source)
    p.assertDiags(DiagnosticId.MISSING_DECLARATIONS)
  }

  @Test
  fun declaratorWithPointer() {
    val p = prepareCode("int* a;", source)
    p.assertNoDiagnostics()
    assertEquals(listOf(int declare ptr("a")), p.root.decls)
  }

  @Test
  fun declaratorWithPointers() {
    val p = prepareCode("int**** a;", source)
    p.assertNoDiagnostics()
    val d = int declare "a".withPtrs(listOf(), listOf(), listOf(), listOf())
    assertEquals(listOf(d), p.root.decls)
  }

  @Test
  fun declaratorWithTypeQualsOnPointers() {
    val p = prepareCode("int * const volatile _Atomic* volatile* _Atomic* a;", source)
    p.assertNoDiagnostics()
    val d = int declare "a".withPtrs(
        listOf(),
        listOf(Keyword(Keywords.CONST), Keyword(Keywords.VOLATILE), Keyword(Keywords.ATOMIC)),
        listOf(Keyword(Keywords.VOLATILE)),
        listOf(Keyword(Keywords.ATOMIC))
    )
    assertEquals(listOf(d), p.root.decls)
  }

  @Test
  fun declaratorWithIdentInPointer() {
    val p = prepareCode("int * lalala * a;", source)
    p.assertDiags(DiagnosticId.EXPECTED_SEMI_AFTER, DiagnosticId.EXPECTED_EXTERNAL_DECL)
  }

  @Test
  fun declaratorWithKeywordInPointer() {
    val p = prepareCode("int * int * a;", source)
    p.assertDiags(DiagnosticId.EXPECTED_IDENT_OR_PAREN)
  }

  @Test
  fun declaratorWithPointerButEndsTooEarly() {
    val p = prepareCode("int *", source)
    p.assertDiags(DiagnosticId.EXPECTED_IDENT_OR_PAREN, DiagnosticId.EXPECTED_SEMI_AFTER)
  }
}
