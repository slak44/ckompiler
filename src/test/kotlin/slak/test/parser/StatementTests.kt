package slak.test.parser

import org.junit.Test
import slak.ckompiler.*
import slak.test.*
import kotlin.test.assertEquals

class StatementTests {
  @Test
  fun statementIfSimple() {
    val p = prepareCode("""
      int main() {
        if (123) 1 + 1;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        ifSt(int(123)) {
          1 add 1
        }
    ) assertEquals p.root.getDeclarations()[0]
  }

  @Test
  fun statementIfWithElse() {
    val p = prepareCode("""
      int main() {
        if (123) 1 + 1; else 2 + 2;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        ifSt(int(123)) {
          1 add 1
        } elseSt {
          2 add 2
        }
    ) assertEquals p.root.getDeclarations()[0]
  }

  @Test
  fun statementIfWithBlock() {
    val p = prepareCode("""
      int main() {
        if (123) {
          3 - 3;
        }
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        ifSt(int(123)) {
          listOf(3 sub 3).compound()
        }
    ) assertEquals p.root.getDeclarations()[0]
  }

  @Test
  fun statementIfWithBlocksAndElse() {
    val p = prepareCode("""
      int main() {
        if (123) {
          3 - 3;
        } else {
          2 - 2;
        }
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        ifSt(int(123)) {
          listOf(3 sub 3).compound()
        } elseSt {
          listOf(2 sub 2).compound()
        }
    ) assertEquals p.root.getDeclarations()[0]
  }

  @Test
  fun statementIfErroredCondition() {
    val p = prepareCode("""
      int main() {
        if (int) 1 + 1;
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_PRIMARY), p.diags.map { it.id })
    int func ("main" withParams emptyList()) body listOf(
        ifSt(ErrorNode()) {
          1 add 1
        }
    ) assertEquals p.root.getDeclarations()[0]
  }

  @Test
  fun statementIfMissingCondition() {
    val p = prepareCode("""
      int main() {
        if () 1 + 1;
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_EXPR), p.diags.map { it.id })
    int func ("main" withParams emptyList()) body listOf(
        ifSt(ErrorNode()) {
          1 add 1
        }
    ) assertEquals p.root.getDeclarations()[0]
  }

  @Test
  fun statementIfBadBlock() {
    val p = prepareCode("""
      int main() {
        if (123)
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_STATEMENT), p.diags.map { it.id })
    int func ("main" withParams emptyList()) body listOf(
        IfStatement(int(123).asEither(), ErrorNode(), null)
    ) assertEquals p.root.getDeclarations()[0]
  }

  @Test
  fun statementIfBadBlockWithElseAfter() {
    val p = prepareCode("""
      int main() {
        if (123) else 1 + 1;
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_STATEMENT), p.diags.map { it.id })
    int func ("main" withParams emptyList()) body listOf(
        IfStatement(int(123).asEither(), ErrorNode(), (1 add 1).asEither())
    ) assertEquals p.root.getDeclarations()[0]
  }

  @Test
  fun statementIfNoOp() {
    val p = prepareCode("""
      int main() {
        if (1); else;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        ifSt(int(1)) {
          Noop
        } elseSt {
          Noop
        }
    ) assertEquals p.root.getDeclarations()[0]
  }

  @Test
  fun returnSimple() {
    val p = prepareCode("""
      int main() {
        return 0;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        returnSt(int(0))
    ) assertEquals p.root.getDeclarations()[0]
  }

  @Test
  fun returnExpr() {
    val p = prepareCode("""
      int main() {
        return (1 + 1) / 2;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        returnSt((1 add 1) div 2)
    ) assertEquals p.root.getDeclarations()[0]
  }

  @Test
  fun returnMissingSemi() {
    val p = prepareCode("""
      int main() {
        return 0
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_SEMI_AFTER), p.diags.map { it.id })
    int func ("main" withParams emptyList()) body listOf(
        returnSt(int(0))
    ) assertEquals p.root.getDeclarations()[0]
  }
}
