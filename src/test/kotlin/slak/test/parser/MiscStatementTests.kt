package slak.test.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.*
import slak.test.*

class MiscStatementTests {
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
    p.assertDiags(DiagnosticId.EXPECTED_SEMI_AFTER, DiagnosticId.BREAK_OUTSIDE_LOOP_SWITCH)
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
    p.assertDiags(DiagnosticId.EXPECTED_SEMI_AFTER, DiagnosticId.CONTINUE_OUTSIDE_LOOP)
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
