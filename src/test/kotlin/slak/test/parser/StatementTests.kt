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
    ) assertEquals p.root.decls[0]
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
    ) assertEquals p.root.decls[0]
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
    ) assertEquals p.root.decls[0]
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
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun statementIfErroredCondition() {
    val p = prepareCode("""
      int main() {
        if (int) 1 + 1;
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_PRIMARY), p.diags.ids)
    int func ("main" withParams emptyList()) body listOf(
        ifSt(ErrorNode()) {
          1 add 1
        }
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun statementIfMissingCondition() {
    val p = prepareCode("""
      int main() {
        if () 1 + 1;
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_EXPR), p.diags.ids)
    int func ("main" withParams emptyList()) body listOf(
        ifSt(ErrorNode()) {
          1 add 1
        }
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun statementIfBadBlock() {
    val p = prepareCode("""
      int main() {
        if (123)
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_STATEMENT), p.diags.ids)
    int func ("main" withParams emptyList()) body listOf(
        IfStatement(int(123).wrap(), ErrorNode(), null)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun statementIfBadBlockWithElseAfter() {
    val p = prepareCode("""
      int main() {
        if (123) else 1 + 1;
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_STATEMENT), p.diags.ids)
    int func ("main" withParams emptyList()) body listOf(
        IfStatement(int(123).wrap(), ErrorNode(), (1 add 1).wrap())
    ) assertEquals p.root.decls[0]
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
    ) assertEquals p.root.decls[0]
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
    ) assertEquals p.root.decls[0]
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
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun returnMissingSemi() {
    val p = prepareCode("""
      int main() {
        return 0
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_SEMI_AFTER), p.diags.ids)
    int func ("main" withParams emptyList()) body listOf(
        returnSt(int(0))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun whileMissingParen() {
    val p = prepareCode("""
      int main() {
        while 0) 1 + 1;
      }
    """.trimIndent(), source)
    assertEquals(listOf(DiagnosticId.EXPECTED_LPAREN_AFTER), p.diags.ids)
    int func ("main" withParams emptyList()) body
        CompoundStatement(listOf(ErrorNode())) assertEquals p.root.decls[0]
  }

  @Test
  fun whileBasic() {
    val p = prepareCode("""
      int main() {
        while (1 + 1) 1 + 1;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        whileSt(1 add 1) {
          1 add 1
        }
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun whileWithCompound() {
    val p = prepareCode("""
      int main() {
        while (1 + 1) {
          1 + 1;
          2 + 2;
        }
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        whileSt(1 add 1) {
          listOf(1 add 1,
              2 add 2).compound()
        }
    ) assertEquals p.root.decls[0]
  }

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
    assertEquals(listOf(DiagnosticId.EXPECTED_STATEMENT), p.diags.ids)
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
    assertEquals(listOf(DiagnosticId.EXPECTED_EXPR, DiagnosticId.EXPECTED_STATEMENT), p.diags.ids)
    int func ("main" withParams emptyList()) body listOf(
        WhileStatement(ErrorNode(), ErrorNode())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun breakStatement() {
    val p = prepareCode("""
      int main() {
        while (1) break;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        whileSt(int(1)) {
          BreakStatement
        }
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun continueStatement() {
    val p = prepareCode("""
      int main() {
        while (1) continue;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        whileSt(int(1)) {
          ContinueStatement
        }
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun labelBasic() {
    val p = prepareCode("""
      int main() {
        label: ;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        "label" labeled Noop
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun labeledExpr() {
    val p = prepareCode("""
      int main() {
        label: 1 + 1;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body listOf(
        "label" labeled (1 add 1)
    ) assertEquals p.root.decls[0]
  }
}
