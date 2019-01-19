package slak.test

import org.junit.Test
import slak.ckompiler.lexer.ErrorToken
import slak.ckompiler.lexer.Lexer
import slak.ckompiler.parser.Parser
import kotlin.test.assertEquals

/**
 * Tests that diagnostics report the correct position of the error in the source code.
 */
class DiagnosticTests {
  @Test
  fun `Lexer Correct Diagnostic Column`() {
    val text = "ident     123.23A"
    val l = Lexer(text, source)
    assert(l.tokens[1] is ErrorToken)
    // Test if error is on the last column
    assertEquals(text.length - 1, l.diags[0].sourceColumns[0].start)
  }

  private fun Parser.assertDiagCaret(diagNr: Int, line: Int? = null, col: Int? = null) {
    val (errLine, errCol, _) = diags[diagNr].errorOf(diags[diagNr].caret)
    line?.let { assertEquals(line, errLine) }
    col?.let { assertEquals(col, errCol) }
  }

  @Test
  fun `Parser Diagnostic Correct Line`() {
    val p = prepareCode("""
      int a = 123;
      int b = ;
      int c = 23;
      int d = 23 + ;
    """.trimIndent(), source)
    p.assertDiagCaret(0, 2, 10)
    p.assertDiagCaret(0, 4, 15)
  }

  @Test
  fun `Parser Diagnostic Correct Column In Line`() {
    val code = "int;"
    val p = prepareCode(code, source)
    p.assertDiagCaret(0, 1, code.indexOf(';'))
  }

  @Test
  fun `Parser Diagnostic Correct Column 0 In Line`() {
    val p = prepareCode("register int x;", source)
    p.assertDiagCaret(0, 1, 0)
  }
}
