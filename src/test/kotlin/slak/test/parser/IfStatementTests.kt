package slak.test.parser

import org.junit.jupiter.api.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.ErrorExpression
import slak.ckompiler.parser.ErrorStatement
import slak.ckompiler.parser.IfStatement
import slak.ckompiler.parser.Noop
import slak.test.*

class IfStatementTests {
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
}
