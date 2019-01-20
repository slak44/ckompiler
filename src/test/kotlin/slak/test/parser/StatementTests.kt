package slak.test.parser

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.*
import slak.test.*

class StatementTests {
  @Test
  fun `If Basic`() {
    val p = prepareCode("""
      int main() {
        if (123) 1 + 1;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        ifSt(int(123)) {
          1 add 1
        }
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `If With Else`() {
    val p = prepareCode("""
      int main() {
        if (123) 1 + 1; else 2 + 2;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        ifSt(int(123)) {
          1 add 1
        } elseSt {
          2 add 2
        }
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `If With Block`() {
    val p = prepareCode("""
      int main() {
        if (123) {
          3 - 3;
        }
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        ifSt(int(123), compoundOf(
            3 sub 3
        ))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `If With Blocks And Else`() {
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
    int func ("main" withParams emptyList()) body compoundOf(
        ifSt(int(123), compoundOf(
            3 sub 3
        )) elseSt compoundOf(
            2 sub 2
        )
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `If Error'd Condition`() {
    val p = prepareCode("""
      int main() {
        if (int) 1 + 1;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_PRIMARY)
    int func ("main" withParams emptyList()) body compoundOf(
        ifSt(ErrorExpression()) {
          1 add 1
        }
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `If With Missing Condition`() {
    val p = prepareCode("""
      int main() {
        if () 1 + 1;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_EXPR)
    int func ("main" withParams emptyList()) body compoundOf(
        ifSt(ErrorExpression()) {
          1 add 1
        }
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `If With Bad Block`() {
    val p = prepareCode("""
      int main() {
        if (123)
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_STATEMENT)
    int func ("main" withParams emptyList()) body compoundOf(
        IfStatement(int(123), ErrorStatement(), null)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `If With Bad Block And Else After`() {
    val p = prepareCode("""
      int main() {
        if (123) else 1 + 1;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_STATEMENT)
    int func ("main" withParams emptyList()) body compoundOf(
        IfStatement(int(123), ErrorStatement(), (1 add 1))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `If No Op`() {
    val p = prepareCode("""
      int main() {
        if (1); else;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        ifSt(int(1)) {
          Noop()
        } elseSt {
          Noop()
        }
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Return Basic`() {
    val p = prepareCode("""
      int main() {
        return 0;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        returnSt(int(0))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Return Expression`() {
    val p = prepareCode("""
      int main() {
        return (1 + 1) / 2;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        returnSt((1 add 1) div 2)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Return Missing Semi`() {
    val p = prepareCode("""
      int main() {
        return 0
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_SEMI_AFTER)
    int func ("main" withParams emptyList()) body compoundOf(
        returnSt(int(0))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Break Statement`() {
    val p = prepareCode("""
      int main() {
        while (1) break;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        whileSt(int(1)) {
          BreakStatement()
        }
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Break No Semi`() {
    val p = prepareCode("""
      int main() {
        break
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_SEMI_AFTER)
    int func ("main" withParams emptyList()) body compoundOf(
        BreakStatement()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Continue Statement`() {
    val p = prepareCode("""
      int main() {
        while (1) continue;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        whileSt(int(1)) {
          ContinueStatement()
        }
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Continue No Semi`() {
    val p = prepareCode("""
      int main() {
        continue
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_SEMI_AFTER)
    int func ("main" withParams emptyList()) body compoundOf(
        ContinueStatement()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Label Basic`() {
    val p = prepareCode("""
      int main() {
        label: ;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        "label" labeled Noop()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Label No Semi`() {
    val p = prepareCode("""
      int main() {
        label:
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_STATEMENT)
    // This hacky thing is needed because the parser is smart enough to add the label to the scope
    // even if the final LabeledStatement is an error, while the test DSL is *not*
    val scope = LexicalScope()
    scope.labels += name("label")
    val block = CompoundStatement(listOf(StatementItem(ErrorStatement())), scope)
    int func FunctionDeclarator(nameDecl("main"), emptyList(), scope = scope) body
        block assertEquals p.root.decls[0]
  }

  @Test
  fun `Labeled Expression`() {
    val p = prepareCode("""
      int main() {
        label: 1 + 1;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        "label" labeled (1 add 1)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Goto Basic`() {
    val p = prepareCode("""
      int main() {
        goto fakeLabel;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func ("main" withParams emptyList()) body compoundOf(
        goto("fakeLabel")
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Goto No Semi`() {
    val p = prepareCode("""
      int main() {
        goto fakeLabel
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_SEMI_AFTER)
    int func ("main" withParams emptyList()) body compoundOf(
        goto("fakeLabel")
    ) assertEquals p.root.decls[0]
  }
}
