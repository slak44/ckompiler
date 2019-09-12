package slak.test.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.ErrorStatement
import slak.ckompiler.parser.Noop
import slak.test.*
import slak.test.source

class SwitchTests {
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
}
