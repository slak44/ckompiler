package slak.test.parser

import org.junit.Ignore
import org.junit.Test
import slak.ckompiler.*
import slak.test.*
import kotlin.test.assertEquals

/** Similarly to [LexerPseudoUnitTests], these are not strictly unit tests. */
class DeclarationTests {
  @Test
  fun declarationBasic() {
    val p = prepareCode("int a;", source)
    p.assertNoDiagnostics()
    val expected = Declaration(intDecl,
        listOf(InitDeclarator(IdentifierNode("a"))))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationWithMultipleDeclSpecs() {
    val p = prepareCode("const static int a;", source)
    p.assertNoDiagnostics()
    val expected = Declaration(RealDeclarationSpecifier(typeSpecifier = TypeSpecifier.SIGNED_INT,
        hasConst = true, storageSpecifier = Keywords.STATIC),
        listOf(InitDeclarator(IdentifierNode("a"))))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationMultipleDeclarators() {
    val p = prepareCode("int a, b, c;", source)
    p.assertNoDiagnostics()
    val expected = Declaration(intDecl,
        listOf("a", "b", "c").map { InitDeclarator(IdentifierNode(it)) })
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationMultipleDeclarations() {
    val p = prepareCode("int a; int b; int c;", source)
    p.assertNoDiagnostics()
    val expected = listOf("a", "b", "c").map {
      Declaration(intDecl, listOf(InitDeclarator(IdentifierNode(it))))
    }
    assertEquals(expected, p.root.getDeclarations())
  }

  @Test
  fun declarationWithSimpleInitializer() {
    val p = prepareCode("int a = 1;", source)
    p.assertNoDiagnostics()
    val expected = Declaration(intDecl,
        listOf(InitDeclarator(IdentifierNode("a"), int(1))))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationWithIdentifierInitializer() {
    val p = prepareCode("int a = someVariable;", source)
    p.assertNoDiagnostics()
    val expected = Declaration(intDecl,
        listOf(InitDeclarator(IdentifierNode("a"), IdentifierNode("someVariable"))))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationWithArithmeticInitializer() {
    val p = prepareCode("int a = 1 + 2 * 3 - 4 / 5;", source)
    p.assertNoDiagnostics()
    val expr = Operators.SUB.with {
      lhs = Operators.ADD.with {
        lhs = int(1)
        rhs = 2 to 3 with Operators.MUL
      }
      rhs = 4 to 5 with Operators.DIV
    }
    val expected =
        Declaration(intDecl, listOf(InitDeclarator(IdentifierNode("a"), expr)))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationWithComplexArithmeticInitializer() {
    val p = prepareCode("int a = 1 + 2 * 2 * (3 - 4) / 5 / 6;", source)
    p.assertNoDiagnostics()
    val expr = Operators.ADD.with {
      lhs = int(1)
      rhs = Operators.DIV.with {
        lhs = Operators.DIV.with {
          lhs = Operators.MUL.with {
            lhs = 2 to 2 with Operators.MUL
            rhs = 3 to 4 with Operators.SUB
          }
          rhs = int(5)
        }
        rhs = int(6)
      }
    }
    val expected =
        Declaration(intDecl, listOf(InitDeclarator(IdentifierNode("a"), expr)))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationWithSimpleParenInitializer() {
    val p = prepareCode("int a = (1);", source)
    p.assertNoDiagnostics()
    val expected = Declaration(intDecl,
        listOf(InitDeclarator(IdentifierNode("a"), int(1))))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationWithExprParenInitializer() {
    val p = prepareCode("int a = (1 + 1);", source)
    p.assertNoDiagnostics()
    val expected = Declaration(intDecl,
        listOf(InitDeclarator(IdentifierNode("a"), 1 to 1 with Operators.ADD)))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationWithBadInitializer() {
    val p = prepareCode("int a = 1 + ;", source)
    assertEquals(DiagnosticId.EXPECTED_PRIMARY, p.diags[0].id)
    val expected = Declaration(intDecl,
        listOf(InitDeclarator(IdentifierNode("a"), ErrorNode())))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declarationMissingSemicolon() {
    val p = prepareCode("int a", source)
    assertEquals(DiagnosticId.EXPECTED_SEMI_AFTER_DECL, p.diags[0].id)
    val expected = Declaration(intDecl, listOf(InitDeclarator(IdentifierNode("a"))))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declSpecsThreadLocalAlone() {
    val p = prepareCode("_Thread_local int a;", source)
    p.assertNoDiagnostics()
    val expected = Declaration(RealDeclarationSpecifier(typeSpecifier = TypeSpecifier.SIGNED_INT,
        hasThreadLocal = true),
        listOf(InitDeclarator(IdentifierNode("a"))))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  @Test
  fun declSpecsThreadLocalCorrectStorage() {
    val p = prepareCode("_Thread_local static int a;", source)
    p.assertNoDiagnostics()
    val expected = Declaration(RealDeclarationSpecifier(typeSpecifier = TypeSpecifier.SIGNED_INT,
        hasThreadLocal = true, storageSpecifier = Keywords.STATIC),
        listOf(InitDeclarator(IdentifierNode("a"))))
    assertEquals(listOf(expected), p.root.getDeclarations())
  }

  private fun testDeclSpecErrors(s: String, id: DiagnosticId) {
    val p = prepareCode(s, source)
    assert(p.diags.size >= 1)
    assertEquals(id, p.diags[0].id)
    assert((p.root.getDeclarations()[0] as Declaration).declSpecs is ErrorDeclarationSpecifier)
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

  @Test
  @Ignore("unimplemented")
  fun declSpecsNoVoidOnVariables() {
    val p = prepareCode("void a;", source)
  }
}
