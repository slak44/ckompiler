package slak.test.parser

import org.junit.Test
import slak.ckompiler.*
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
}
