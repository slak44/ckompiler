package slak.test.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    int func "main" body compoundOf(
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
    int func "main" body compoundOf(
        ifSt(int(123)) {
          1 add 1
        } elseSt {
          2 add 2
        }
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `If With Else And Function Calls`() {
    val p = prepareCode("""
      void f() {}
      void g() {}
      int main() {
        if (123) f();
        else g();
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    val f = void func "f" body emptyCompound()
    val g = void func "g" body emptyCompound()
    f assertEquals p.root.decls[0]
    g assertEquals p.root.decls[1]
    int func "main" body compoundOf(
        ifSt(int(123)) {
          f()
        } elseSt {
          g()
        }
    ) assertEquals p.root.decls[2]
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
    int func "main" body compoundOf(
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
    int func "main" body compoundOf(
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
    int func "main" body compoundOf(
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
    int func "main" body compoundOf(
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
    int func "main" body compoundOf(
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
    int func "main" body compoundOf(
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
    int func "main" body compoundOf(
        ifSt(int(1)) {
          Noop()
        } elseSt {
          Noop()
        }
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `If Missing Paren`() {
    val p = prepareCode("""
      int main() {
        if 1) 1 + 1;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_LPAREN_AFTER)
    int func "main" body compoundOf(
        ErrorStatement()
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
    int func "main" body compoundOf(
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
    int func "main" body compoundOf(
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
    int func "main" body compoundOf(
        returnSt(int(0))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Return Nothing`() {
    val p = prepareCode("""
      void f() {
        return;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    void func "f" body compoundOf(
        returnSt()
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
    int func "main" body compoundOf(
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
    int func "main" body compoundOf(
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
    int func "main" body compoundOf(
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
    int func "main" body compoundOf(
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
    int func "main" body compoundOf(
        "label" labeled Noop()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Label With No Colon Is Degenerate Expression`() {
    val p = prepareCode("""
      int main() {
        label
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.USE_UNDECLARED, DiagnosticId.EXPECTED_SEMI_AFTER)
    int func "main" body compoundOf(
        nameRef("label", ErrorType)
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
    int func "main" body compoundOf(
        "label" labeled ErrorStatement()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Labeled Expression`() {
    val p = prepareCode("""
      int main() {
        label: 1 + 1;
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func "main" body compoundOf(
        "label" labeled (1 add 1)
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Default Label Outside Switch Is Error`() {
    val p = prepareCode("""
      int main() {
        default: ;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.UNEXPECTED_SWITCH_LABEL)
    int func "main" body compoundOf(
        defaultLabeled(Noop())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Default Label Without Colon Is Error`() {
    val p = prepareCode("""
      int main() {
        default
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.UNEXPECTED_SWITCH_LABEL, DiagnosticId.EXPECTED_COLON_AFTER)
    int func "main" body compoundOf(
        ErrorStatement()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Case Label Outside Switch Is Error`() {
    val p = prepareCode("""
      int main() {
        case 123: ;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.UNEXPECTED_SWITCH_LABEL)
    int func "main" body compoundOf(
        123.caseLabeled(Noop())
    ) assertEquals p.root.decls[0]
  }

  @ParameterizedTest
  @ValueSource(strings = ["case 123", "case", "case 1 + 1", "case (123)"])
  fun `Case Label Without Colon Is Error`(caseStr: String) {
    val p = prepareCode("""
      int main() {
        $caseStr;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.UNEXPECTED_SWITCH_LABEL, DiagnosticId.EXPECTED_COLON_AFTER)
    int func "main" body compoundOf(
        ErrorStatement()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Case Label With Extra Tokens In Expr`() {
    val p = prepareCode("""
      int main() {
        case 123 123: ;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.UNEXPECTED_SWITCH_LABEL, DiagnosticId.EXPECTED_COLON_AFTER)
    int func "main" body compoundOf(
        123.caseLabeled(Noop())
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
    int func "main" body compoundOf(
        goto("fakeLabel")
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Goto Missing Label`() {
    val p = prepareCode("""
      int main() {
        goto;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_IDENT)
    int func "main" body compoundOf(ErrorStatement()) assertEquals p.root.decls[0]
  }

  @Test
  fun `Goto No Semi`() {
    val p = prepareCode("""
      int main() {
        goto fakeLabel
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_SEMI_AFTER)
    int func "main" body compoundOf(
        goto("fakeLabel")
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Struct Definition As Statement`() {
    val p = prepareCode("""
      int main() {
        struct vec {int x, y;};
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func "main" body compoundOf(
        struct("vec", listOf(int declare listOf("x", "y")))
    ) assertEquals p.root.decls[0]
  }
}
