package slak.test.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.*
import slak.test.*
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Various normal cases of scoping are indirectly tested by all the other test cases. Here are only
 * tests for special, pathological, or error cases.
 */
class ScopeTests {
  @Test
  fun `File Scope Redefinition Via Different Declaration`() {
    val p = prepareCode("int a; int a;", source)
    p.assertDiags(DiagnosticId.REDEFINITION, DiagnosticId.REDEFINITION_PREVIOUS)
  }

  @Test
  fun `File Scope Redefinition Via Same Declaration`() {
    val p = prepareCode("int a, a;", source)
    p.assertDiags(DiagnosticId.REDEFINITION, DiagnosticId.REDEFINITION_PREVIOUS)
  }

  @Test
  fun `Block Scope Redefinition`() {
    val p = prepareCode("int main() { int x, x; }", source)
    p.assertDiags(DiagnosticId.REDEFINITION, DiagnosticId.REDEFINITION_PREVIOUS)
  }

  @Test
  fun `Adjacent Scopes Don't Overlap`() {
    val p = prepareCode("""
      int main() {
        { int x = 1; }
        { int x = 2; }
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `Adjacent For-Loop Scopes Don't Overlap`() {
    val p = prepareCode("""
      int main() {
        for (int x = 1; ; );
        for (int x = 2; ; );
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `For-Loop Declaration Scope Is Separate`() {
    val p = prepareCode("""
      int main() {
        int x = 1;
        for (int x = 2; ; ) {
          int x = 3;
        }
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `Loop-Local Scope Doesn't Collide With Outer Function Scope`() {
    val p = prepareCode("""
      int main() {
        int x;
        while (1) {
          int x;
        }
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `Shadowing Of File Scope Variable With Block Scope Variable`() {
    val p = prepareCode("int x; int main() { int x; }", source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `Shadowing Shadowed Name`() {
    val p = prepareCode("int x; int main() { int x; { int x; } }", source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `Shadowing Doesn't Break TypedIdentifier IDs`() {
    val p = prepareCode("int main() { int x; { int x; } }", source)
    p.assertNoDiagnostics()
    val funBlock = p.root.decls.firstFun().block
    val innerBlock = funBlock.items[1].st as CompoundStatement
    val innerIdents = innerBlock.items[0].decl.idents(innerBlock.scope)
    val outerIdents = funBlock.items[0].decl.idents(funBlock.scope)
    assertNotEquals(innerIdents.map { it.id }, outerIdents.map { it.id })
  }

  @Test
  fun `Reusing TypedIdentifier Maintains ID`() {
    val p = prepareCode("int main() { int x; +x; }", source)
    p.assertNoDiagnostics()
    val funBlock = p.root.decls.firstFun().block
    val decl = funBlock.items[0].decl
    val expr = funBlock.items[1].st as UnaryExpression
    assertEquals(decl.idents(funBlock.scope).first().id, (expr.operand as TypedIdentifier).id)
  }

  @Test
  fun `Label Redefinition`() {
    val p = prepareCode("int main() { label:; label:; }", source)
    assertEquals(p.diags.ids,
        listOf(DiagnosticId.REDEFINITION_LABEL, DiagnosticId.REDEFINITION_PREVIOUS))
  }

  @Test
  fun `Label And Variable Together`() {
    val p = prepareCode("int main() { label:; int label; }", source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `Undeclared Usage`() {
    val p = prepareCode("int main() { int x = y; }", source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED)
  }

  @Test
  fun `Typedef Valid Redefinition`() {
    val p = prepareCode("typedef int myint; typedef int myint;", source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `Typedef Redefinition With Different Type Specifiers`() {
    val p = prepareCode("typedef int myint; typedef unsigned int myint;", source)
    p.assertDiags(DiagnosticId.REDEFINITION_TYPEDEF, DiagnosticId.REDEFINITION_PREVIOUS)
  }

  @Test
  fun `Typedef Redefinition With Different Type Qualifiers`() {
    val p = prepareCode("typedef int myint; typedef const int myint;", source)
    p.assertDiags(DiagnosticId.REDEFINITION_TYPEDEF, DiagnosticId.REDEFINITION_PREVIOUS)
  }

  @Test
  fun `Typedef Redefinition With Different Indirection Level`() {
    val p = prepareCode("typedef int myint; typedef int* myint;", source)
    p.assertDiags(DiagnosticId.REDEFINITION_TYPEDEF, DiagnosticId.REDEFINITION_PREVIOUS)
  }

  @Test
  fun `Typedef Redefinition With Different Indirection Qualifier`() {
    val p = prepareCode("typedef int * myint; typedef unsigned int * const myint;", source)
    p.assertDiags(DiagnosticId.REDEFINITION_TYPEDEF, DiagnosticId.REDEFINITION_PREVIOUS)
  }

  @Test
  fun `Typedef Shares Namespace With Other Identifiers`() {
    val p = prepareCode("""
      typedef unsigned int blabla;
      char blabla = 'x';
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.REDEFINITION_OTHER_SYM, DiagnosticId.REDEFINITION_PREVIOUS)
  }

  @Test
  fun `Typedef Unexpected In Expression`() {
    val p = prepareCode("""
      typedef unsigned int blabla;
      int thing = 1 + blabla * 2;
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.UNEXPECTED_TYPEDEF_USE)
  }

  @Test
  fun `Tag Mismatch With Incomplete Types`() {
    val p = prepareCode("""
      struct x;
      union x;
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.TAG_MISMATCH, DiagnosticId.TAG_MISMATCH_PREVIOUS)
  }

  @Test
  fun `Tag Mismatch With Complete Types`() {
    val p = prepareCode("""
      struct vec2 {int x,y;};
      union vec2 {int a,b;};
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.TAG_MISMATCH, DiagnosticId.TAG_MISMATCH_PREVIOUS)
  }

  @Test
  fun `Tag And Typedef With Same Name Are Allowed`() {
    val p = prepareCode("""
      struct my_type {int x,y;};
      typedef const int my_type;
    """.trimIndent(), source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `Tag Forward Declared After Definition Is Allowed`() {
    val p = prepareCode("""
      struct my_type {int x,y;};
      struct my_type;
    """.trimIndent(), source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `Tag Replace Forward Definition`() {
    val p = prepareCode("""
      union my_type;
      union my_type {int x,y;};
    """.trimIndent(), source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `Tag Is Complete After Definition`() {
    val p = prepareCode("""
      struct vec2 {double x, y;};
      struct vec2 u;
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val vec2 = struct("vec2", listOf(double declare listOf("x", "y"))).toSpec()
    vec2 declare "u" assertEquals p.root.decls[0]
  }

  @Test
  fun `Complete Tag Used In Inner Scope`() {
    val p = prepareCode("""
      struct vec2 {double x, y;};
      int main() {
        struct vec2 u;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val vec2 = struct("vec2", listOf(double declare listOf("x", "y"))).toSpec()
    int func ("main" withParams emptyList()) body compoundOf(
        vec2 declare "u"
    ) assertEquals p.root.decls[0]
  }

  @ParameterizedTest
  @ValueSource(strings = [
    "union my_type {int x,y;};",
    "struct str {float a;};",
    "enum test { A, B, C };"
  ])
  fun `Tag Redefinition`(tagStr: String) {
    val p = prepareCode("""
      $tagStr
      $tagStr
    """.trimIndent(), source)
    // For the enum one, diags are printed for the enum AND the constants
    assertEquals(listOf(DiagnosticId.REDEFINITION, DiagnosticId.REDEFINITION_PREVIOUS),
        p.diags.ids.distinct())
  }

  @Test
  fun `Enum Used Undefined`() {
    val p = prepareCode("""
      enum testing testValue;
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.USE_ENUM_UNDEFINED, DiagnosticId.VARIABLE_TYPE_INCOMPLETE)
  }

  @Test
  fun `Enum Values Used Are Correct In Scope`() {
    val p = prepareCode("""
      enum color { RED, GREEN, BLUE };
      enum color b = RED;
      int c = BLUE;
    """.trimIndent(), source)
    p.assertNoDiagnostics()
  }

  @Test
  fun `Block Scope Inner`() {
    val p = prepareCode("""
      int main() {
        {
          int x = 1;
          { x = 3; int x = 2; }
          return x;
        }
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
  }
}
