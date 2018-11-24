package slak.test.parser

import org.junit.Test
import slak.ckompiler.*
import slak.ckompiler.parser.*
import slak.test.*
import kotlin.test.assertEquals

/** Similarly to [LexerPseudoUnitTests], these are not strictly unit tests. */
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
    val spec = RealDeclarationSpecifier(typeSpecifier = TypeSpecifier.SIGNED_INT,
        hasConst = true, storageSpecifier = Keywords.STATIC)
    assertEquals(listOf(spec declare "a"), p.root.decls)
  }

  @Test
  fun declarationMultipleDeclarators() {
    val p = prepareCode("int a, b, c;", source)
    p.assertNoDiagnostics()
    val expected = Declaration(int,
        listOf("a", "b", "c").map { InitDeclarator(name(it)) })
    assertEquals(listOf(expected.wrap()), p.root.decls)
  }

  @Test
  fun declarationMultipleDeclarations() {
    val p = prepareCode("int a; int b; int c;", source)
    p.assertNoDiagnostics()
    val expected = listOf("a", "b", "c").map {
      int declare listOf(InitDeclarator(name(it)))
    }
    assertEquals(expected, p.root.decls)
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
    p.assertNoDiagnostics()
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
    assertEquals(listOf(int declare ("a" assign ErrorNode())), p.root.decls)
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
  fun declSpecsThreadLocalAlone() {
    val p = prepareCode("_Thread_local int a;", source)
    p.assertNoDiagnostics()
    val spec =
        RealDeclarationSpecifier(typeSpecifier = TypeSpecifier.SIGNED_INT, hasThreadLocal = true)
    assertEquals(listOf(spec declare "a"), p.root.decls)
  }

  @Test
  fun declSpecsThreadLocalCorrectStorage() {
    val p = prepareCode("_Thread_local static int a;", source)
    p.assertNoDiagnostics()
    val spec = RealDeclarationSpecifier(typeSpecifier = TypeSpecifier.SIGNED_INT,
        hasThreadLocal = true, storageSpecifier = Keywords.STATIC)
    assertEquals(listOf(spec declare "a"), p.root.decls)
  }

  private fun testDeclSpecErrors(s: String, id: DiagnosticId) {
    val p = prepareCode(s, source)
    assert(p.diags.size >= 1)
    assertEquals(id, p.diags[0].id)
    val d = ((p.root.decls[0] as EitherNode.Value<*>).value as Declaration)
    assert(d.declSpecs is ErrorDeclarationSpecifier)
  }

  @Test
  fun declSpecsThreadLocalIncorrectStorage() {
    testDeclSpecErrors("_Thread_local register int a;", DiagnosticId.INCOMPATIBLE_DECL_SPEC)
  }

  @Test
  fun declSpecsMultipleStorage() {
    testDeclSpecErrors("static register int a;", DiagnosticId.INCOMPATIBLE_DECL_SPEC)
  }

  @Test
  fun declSpecsDuplicates() {
    val p = prepareCode("static static int a;", source)
    assert(p.diags.size >= 1)
    assertEquals(DiagnosticId.DUPLICATE_DECL_SPEC, p.diags[0].id)
  }

  @Test
  fun declSpecsUnsupportedComplex() {
    testDeclSpecErrors("float _Complex a;", DiagnosticId.UNSUPPORTED_COMPLEX)
  }
}
