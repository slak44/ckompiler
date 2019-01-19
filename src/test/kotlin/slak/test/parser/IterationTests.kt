package slak.test.parser

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.*
import slak.test.*
import kotlin.test.assertEquals

/**
 * Tests for "while", "for" and "do-while" loops.
 */
class IterationTests {
  @Test
  fun `While Missing Paren`() {
    val p = prepareCode("""
      int main() {
        while 0) 1 + 1;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_LPAREN_AFTER)
    int func ("main" withParams emptyList()) body compoundOf(ErrorStatement()) assertEquals
        p.root.decls[0]
  }

  @Test
  fun `While Basic`() {
    val p = prepareCode("""
      int main() {
        while (1 + 1) 1 + 1;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        whileSt(1 add 1) {
          1 add 1
        }
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `While With Compound`() {
    val p = prepareCode("""
      int main() {
        while (1 + 1) {
          1 + 1;
          2 + 2;
        }
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        whileSt(1 add 1, compoundOf(
            1 add 1,
            2 add 2
        ))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `While Missing Cond`() {
    val p = prepareCode("""
      int main() {
        while () 1 + 1;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_EXPR)
    int func ("main" withParams emptyList()) body compoundOf(
        whileSt(ErrorExpression()) {
          1 add 1
        }
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `While Empty Body`() {
    val p = prepareCode("""
      int main() {
        while (1);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        whileSt(int(1)) {
          Noop()
        }
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `While Missing Body`() {
    val p = prepareCode("""
      int main() {
        while (1)
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_STATEMENT)
    int func ("main" withParams emptyList()) body compoundOf(
        WhileStatement(int(1), ErrorStatement())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `While Missing All`() {
    val p = prepareCode("""
      int main() {
        while ()
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_EXPR, DiagnosticId.EXPECTED_STATEMENT)
    int func ("main" withParams emptyList()) body compoundOf(
        WhileStatement(ErrorExpression(), ErrorStatement())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Do While Empty Body`() {
    val p = prepareCode("""
      int main() {
        do while (1);
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_STATEMENT)
    int func ("main" withParams emptyList()) body compoundOf(
        ErrorStatement() asDoWhile int(1)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Do While Basic`() {
    val p = prepareCode("""
      int main() {
        do {} while (1);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        emptyCompound() asDoWhile int(1)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Do While Missing Cond`() {
    val p = prepareCode("""
      int main() {
        do {} while ();
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_EXPR)
    int func ("main" withParams emptyList()) body compoundOf(
        emptyCompound() asDoWhile ErrorExpression()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Do While Missing Cond And Semi`() {
    val p = prepareCode("""
      int main() {
        do {} while ()
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_EXPR, DiagnosticId.EXPECTED_SEMI_AFTER)
    int func ("main" withParams emptyList()) body compoundOf(
        emptyCompound() asDoWhile ErrorExpression()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Do While Empty Body And No Semi`() {
    val p = prepareCode("""
      int main() {
        do while (1)
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_STATEMENT,
        DiagnosticId.EXPECTED_SEMI_AFTER), p.diags.ids)
    int func ("main" withParams emptyList()) body compoundOf(
        ErrorStatement() asDoWhile int(1)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Do While Missing All`() {
    val p = prepareCode("""
      int main() {
        do while ()
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_STATEMENT, DiagnosticId.EXPECTED_EXPR,
        DiagnosticId.EXPECTED_SEMI_AFTER), p.diags.ids)
    int func ("main" withParams emptyList()) body compoundOf(
        ErrorStatement() asDoWhile ErrorExpression()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `For Basic No Body`() {
    val p = prepareCode("""
      int main() {
        for (1 + 1; 1 + 1; 1 + 1);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        forSt(1 add 1, 1 add 1, 1 add 1, Noop())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `For Decl No Body`() {
    val p = prepareCode("""
      int main() {
        for (int i = 65; 1 + 1; 1 + 1);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        forSt(int declare ("i" assign int(65)), 1 add 1, 1 add 1, Noop())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `For No Paren No Body`() {
    val p = prepareCode("""
      int main() {
        for int i = 65; 1 + 1; 1 + 1);
      }
    """.trimIndent(), source)
    // This kind of syntax error can cause lots of random diagnostics
    // As long as we report "EXPECTED_LPAREN_AFTER" it's good enough
    assert(p.diags.isNotEmpty())
    assert(p.diags.ids.contains(DiagnosticId.EXPECTED_LPAREN_AFTER))
  }

  @Test
  fun `For No Clause1 No Body`() {
    val p = prepareCode("""
      int main() {
        for (; 1 + 1; 1 + 1);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        forSt(EmptyInitializer(), 1 add 1, 1 add 1, Noop())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `For No Expr2 No Body`() {
    val p = prepareCode("""
      int main() {
        for (1 + 1; ; 1 + 1);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        forSt(1 add 1, null, 1 add 1, Noop())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `For No Expr3 No Body`() {
    val p = prepareCode("""
      int main() {
        for (1 + 1; 1 + 1;);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        forSt(1 add 1, 1 add 1, null, Noop())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `For Empty Specifiers No Body`() {
    val p = prepareCode("""
      int main() {
        for (;;);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        forSt(EmptyInitializer(), null, null, Noop())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `For Missing Semi2 No Body`() {
    val p = prepareCode("""
      int main() {
        for (1 + 1; 1 + 1);
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_SEMI_IN_FOR)
    int func ("main" withParams emptyList()) body compoundOf(
        forSt(1 add 1, ErrorExpression(), ErrorExpression(), Noop())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `For Missing Semi1 No Body`() {
    val p = prepareCode("""
      int main() {
        for (1 + 1);
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_SEMI_IN_FOR)
    int func ("main" withParams emptyList()) body compoundOf(
        forSt(ErrorInitializer(), ErrorExpression(), ErrorExpression(), Noop())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `For Missing All Specs No Body`() {
    val p = prepareCode("""
      int main() {
        for ();
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_SEMI_IN_FOR)
    int func ("main" withParams emptyList()) body compoundOf(
        forSt(ErrorInitializer(), ErrorExpression(), ErrorExpression(), Noop())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `For Basic Missing Body`() {
    val p = prepareCode("""
      int main() {
        for (1 + 1; 1 + 1; 1 + 1)
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_STATEMENT)
    int func ("main" withParams emptyList()) body compoundOf(
        forSt(1 add 1, 1 add 1, 1 add 1, ErrorStatement())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `For Missing All`() {
    val p = prepareCode("""
      int main() {
        for ()
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_SEMI_IN_FOR,
        DiagnosticId.EXPECTED_STATEMENT), p.diags.ids)
    int func ("main" withParams emptyList()) body compoundOf(
        forSt(ErrorInitializer(), ErrorExpression(), ErrorExpression(), ErrorStatement())
    ) assertEquals p.root.decls[0]
  }
}
