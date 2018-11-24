package slak.test.parser

import org.junit.Test
import slak.ckompiler.*
import slak.ckompiler.parser.*
import slak.test.*
import kotlin.test.assertEquals

class IterationStatementTests {
  @Test
  fun whileMissingCond() {
    val p = prepareCode("""
      int main() {
        while () 1 + 1;
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_EXPR), p.diags.ids)
    int func ("main" withParams emptyList()) body listOf(
        whileSt(ErrorNode()) {
          1 add 1
        }
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun whileEmptyBody() {
    val p = prepareCode("""
      int main() {
        while (1);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        whileSt(int(1)) {
          Noop
        }
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun whileMissingBody() {
    val p = prepareCode("""
      int main() {
        while (1)
      }
    """.trimIndent(), source)
    kotlin.test.assertEquals(listOf(DiagnosticId.EXPECTED_STATEMENT), p.diags.ids)
    int func ("main" withParams emptyList()) body listOf(
        WhileStatement(int(1).wrap(), ErrorNode())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun whileMissingAll() {
    val p = prepareCode("""
      int main() {
        while ()
      }
    """.trimIndent(), source)
    kotlin.test.assertEquals(listOf(DiagnosticId.EXPECTED_EXPR, DiagnosticId.EXPECTED_STATEMENT), p.diags.ids)
    int func ("main" withParams emptyList()) body listOf(
        WhileStatement(ErrorNode(), ErrorNode())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun doWhileEmptyBody() {
    val p = prepareCode("""
      int main() {
        do while (1);
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_STATEMENT), p.diags.ids)
    int func ("main" withParams emptyList()) body listOf(
        ErrorNode() asDoWhile int(1).wrap()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun doWhileBasic() {
    val p = prepareCode("""
      int main() {
        do {} while (1);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        listOf<BlockItem>().compound() asDoWhile int(1).wrap()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun doWhileMissingCond() {
    val p = prepareCode("""
      int main() {
        do {} while ();
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_EXPR), p.diags.ids)
    int func ("main" withParams emptyList()) body listOf(
        listOf<BlockItem>().compound() asDoWhile ErrorNode()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun doWhileMissingCondAndSemi() {
    val p = prepareCode("""
      int main() {
        do {} while ()
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_EXPR, DiagnosticId.EXPECTED_SEMI_AFTER), p.diags.ids)
    int func ("main" withParams emptyList()) body listOf(
        listOf<BlockItem>().compound() asDoWhile ErrorNode()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun doWhileEmptyBodyAndNoSemi() {
    val p = prepareCode("""
      int main() {
        do while (1)
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_STATEMENT,
        DiagnosticId.EXPECTED_SEMI_AFTER), p.diags.ids)
    int func ("main" withParams emptyList()) body listOf(
        ErrorNode() asDoWhile int(1).wrap()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun doWhileMissingAll() {
    val p = prepareCode("""
      int main() {
        do while ()
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_STATEMENT, DiagnosticId.EXPECTED_EXPR,
        DiagnosticId.EXPECTED_SEMI_AFTER), p.diags.ids)
    int func ("main" withParams emptyList()) body listOf(
        ErrorNode() asDoWhile ErrorNode()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun forBasicNoBody() {
    val p = prepareCode("""
      int main() {
        for (1 + 1; 1 + 1; 1 + 1);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        forSt(Triple(1 add 1, 1 add 1, 1 add 1), Noop.wrap())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun forDeclNoBody() {
    val p = prepareCode("""
      int main() {
        for (int i = 65; 1 + 1; 1 + 1);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        ForStatement(int declare ("i" assign int(65)), (1 add 1).wrap(), (1 add 1).wrap(),
            slak.ckompiler.parser.Noop.wrap())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun forNoParenNoBody() {
    val p = prepareCode("""
      int main() {
        for int i = 65; 1 + 1; 1 + 1);
      }
    """.trimIndent(), source)
    // This kind of syntax error can cause lots of random diagnostics, are random parsing
    // As long as we report "EXPECTED_LPAREN_AFTER" it's good enough
    assert(p.diags.size > 0)
    assert(p.diags.ids.contains(DiagnosticId.EXPECTED_LPAREN_AFTER))
  }

  @Test
  fun forNoClause1NoBody() {
    val p = prepareCode("""
      int main() {
        for (; 1 + 1; 1 + 1);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        forSt(Triple(null, 1 add 1, 1 add 1), Noop.wrap())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun forNoExpr2NoBody() {
    val p = prepareCode("""
      int main() {
        for (1 + 1; ; 1 + 1);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        forSt(Triple(1 add 1, null, 1 add 1), Noop.wrap())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun forNoExpr3NoBody() {
    val p = prepareCode("""
      int main() {
        for (1 + 1; 1 + 1;);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        forSt(Triple(1 add 1, 1 add 1, null), Noop.wrap())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun forEmptySpecifiersNoBody() {
    val p = prepareCode("""
      int main() {
        for (;;);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        forSt(Triple(null, null, null), Noop.wrap())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun forMissingSemi2NoBody() {
    val p = prepareCode("""
      int main() {
        for (1 + 1; 1 + 1);
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_SEMI_IN_FOR), p.diags.ids)
    int func ("main" withParams emptyList()) body listOf(
        ForStatement((1 add 1).wrap(), ErrorNode(), ErrorNode(), slak.ckompiler.parser.Noop.wrap())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun forMissingSemi1NoBody() {
    val p = prepareCode("""
      int main() {
        for (1 + 1);
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_SEMI_IN_FOR), p.diags.ids)
    int func ("main" withParams emptyList()) body listOf(
        ForStatement(ErrorNode(), ErrorNode(), ErrorNode(), slak.ckompiler.parser.Noop.wrap())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun forMissingAllSpecsNoBody() {
    val p = prepareCode("""
      int main() {
        for ();
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_SEMI_IN_FOR), p.diags.ids)
    int func ("main" withParams emptyList()) body listOf(
        ForStatement(ErrorNode(), ErrorNode(), ErrorNode(), slak.ckompiler.parser.Noop.wrap())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun forBasicMissingBody() {
    val p = prepareCode("""
      int main() {
        for (1 + 1; 1 + 1; 1 + 1)
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_STATEMENT), p.diags.ids)
    int func ("main" withParams emptyList()) body listOf(
        forSt(Triple(1 add 1, 1 add 1, 1 add 1), ErrorNode())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun forMissingAll() {
    val p = prepareCode("""
      int main() {
        for ()
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_SEMI_IN_FOR,
        DiagnosticId.EXPECTED_STATEMENT), p.diags.ids)
    int func ("main" withParams emptyList()) body listOf(
        ForStatement(ErrorNode(), ErrorNode(), ErrorNode(), ErrorNode())
    ) assertEquals p.root.decls[0]
  }
}
