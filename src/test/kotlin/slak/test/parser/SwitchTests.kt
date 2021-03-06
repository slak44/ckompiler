package slak.test.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.DiagnosticId
import slak.ckompiler.parser.*
import slak.test.*

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

  @Test
  fun `Degenerate No-op Switch Statement`() {
    val p = prepareCode("""
      int main() {
        switch (123);
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func "main" body compoundOf(
        switch(123, Noop())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Empty Compound Switch Statement`() {
    val p = prepareCode("""
      int main() {
        switch (123) {}
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func "main" body compoundOf(
        switch(123, emptyCompound())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Switch Statement Missing Start Paren`() {
    val p = prepareCode("""
      int main() {
        switch 123);
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_LPAREN_AFTER)
    int func "main" body compoundOf(
        ErrorStatement()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Switch Statement Missing End Paren`() {
    val p = prepareCode("""
      int main() {
        switch (123;
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.UNMATCHED_PAREN, DiagnosticId.MATCH_PAREN_TARGET)
    int func "main" body compoundOf(
        ErrorStatement()
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Switch Statement Bad Condition`() {
    val p = prepareCode("""
      int main() {
        switch (1 +);
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_EXPR)
    int func "main" body compoundOf(
        switch(1 add ErrorExpression(), Noop())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Switch Statement Empty Condition`() {
    val p = prepareCode("""
      int main() {
        switch ();
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.EXPECTED_EXPR)
    int func "main" body compoundOf(
        switch(ErrorExpression(), Noop())
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Switch Statement With Random Code Inside`() {
    val p = prepareCode("""
      int main() {
        switch (123) {
          1 + 1;
          int a = 5;
          a += 4;
        }
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func "main" body compoundOf(
        switch(123, compoundOf(
            1 add 1,
            int declare ("a" assign 5),
            intVar("a") plusAssign 4
        ))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Switch Statement With Random Code Before Case Label`() {
    val p = prepareCode("""
      int main() {
        switch (123) {
          1 + 1;
          int a = 5;
          a += 4;
          case 123:;
        }
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func "main" body compoundOf(
        switch(123, compoundOf(
            1 add 1,
            int declare ("a" assign 5),
            intVar("a") plusAssign 4,
            123.caseLabeled(Noop())
        ))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Switch Statement With Default Case`() {
    val p = prepareCode("""
      int main() {
        switch (123) {
          default:;
        }
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func "main" body compoundOf(
        switch(123, compoundOf(
            defaultLabeled(Noop())
        ))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Switch Statement With Random Case`() {
    val p = prepareCode("""
      int main() {
        switch (123) {
          case 2323 : ;
        }
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func "main" body compoundOf(
        switch(123, compoundOf(
            2323.caseLabeled(Noop())
        ))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Switch Statement Multiple Labels`() {
    val p = prepareCode("""
      int main() {
        switch (123) {
          case 2323:;
          case 123:;
          case 56:;
          default:;
        }
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func "main" body compoundOf(
        switch(123, compoundOf(
            2323.caseLabeled(Noop()),
            123.caseLabeled(Noop()),
            56.caseLabeled(Noop()),
            defaultLabeled(Noop())
        ))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Switch Statement Nested Compound`() {
    val p = prepareCode("""
      int main() {
        switch (123) {
          case 2323: {
            2 + 2;
          }
        }
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func "main" body compoundOf(
        switch(123, compoundOf(
            2323.caseLabeled(compoundOf(
                2 add 2
            ))
        ))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Switch Statement Case Label Nested`() {
    val p = prepareCode("""
      int main() {
        switch (123) {
          case 2323: {
            case 55: 2;
          }
        }
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func "main" body compoundOf(
        switch(123, compoundOf(
            2323.caseLabeled(compoundOf(
                55.caseLabeled(int(2))
            ))
        ))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Switch Statement Default Label Nested`() {
    val p = prepareCode("""
      int main() {
        switch (123) {
          case 2323: {
            default: 2;
          }
        }
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func "main" body compoundOf(
        switch(123, compoundOf(
            2323.caseLabeled(compoundOf(
                defaultLabeled(int(2))
            ))
        ))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Switch Statement Break Inside Is Ok`() {
    val p = prepareCode("""
      int main() {
        switch (123) {
          break;
        }
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func "main" body compoundOf(
        switch(123, compoundOf(
            BreakStatement()
        ))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Break Inside Case Is Ok`() {
    val p = prepareCode("""
      int main() {
        switch (123) {
          case 23: break;
        }
      }
    """.trimIndent(), source)
    p.assertNoDiagnostics()
    int func "main" body compoundOf(
        switch(123, compoundOf(
            23.caseLabeled(BreakStatement())
        ))
    ) assertEquals p.root.decls[0]
  }

  @Test
  fun `Switch Statement Condition Has Boolean Type`() {
    val p = prepareCode("""
      int main() {
        _Bool b = 0;
        switch (b) { }
      }
    """.trimIndent(), source)
    p.assertDiags(DiagnosticId.SWITCH_COND_IS_BOOL)
    int func "main" body compoundOf(
        bool declare ("b" assign BooleanType.cast(0)),
        switch(nameRef("b", BooleanType), emptyCompound())
    ) assertEquals p.root.decls[0]
  }
}
