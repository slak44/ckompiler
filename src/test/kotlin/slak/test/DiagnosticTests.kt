package slak.test

import org.junit.Test
import slak.ckompiler.DiagnosticId
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
    assert(diagNr >= 0 && diagNr < diags.size)
    val (errLine, errCol, _) = diags[diagNr].errorOf(diags[diagNr].caret)
    line?.let { assertEquals(line, errLine) }
    col?.let { assertEquals(col, errCol) }
  }

  @Test
  fun `Parser Diagnostic Correct Line`() {
    val p = prepareCode("""
      int a = ;
      int b = ;
      int c = ;
      int d = ;
    """.trimIndent(), source)
    p.assertDiagCaret(diagNr = 0, line = 1, col = 8)
    p.assertDiagCaret(diagNr = 1, line = 2, col = 8)
    p.assertDiagCaret(diagNr = 2, line = 3, col = 8)
    p.assertDiagCaret(diagNr = 3, line = 4, col = 8)
  }

  @Test
  fun `Parser Diagnostic Correct Column In Line`() {
    val code = "int;"
    val p = prepareCode(code, source)
    p.assertDiagCaret(diagNr = 0, line = 1, col = code.indexOf(';'))
  }

  @Test
  fun `Parser Diagnostic Correct Column 0 In Line`() {
    val p = prepareCode("register int x;", source)
    p.assertDiagCaret(diagNr = 0, line = 1, col = 0)
  }

  @Test
  fun `Parser Diagnostic Correct Range Length`() {
    val p = prepareCode("""
      typedef unsigned int X = 1 + 1;
    """.trimIndent(), source)
    //                       ^~~~~~~
    //                       7 chars
    p.assertDiagCaret(diagNr = 0, line = 1)
    assertEquals(7, p.diags[0].caret.endInclusive + 1 - p.diags[0].caret.start)
  }

  @Test
  fun `Parser Diagnostic Multi-line Range`() {
    val p = prepareCode("""
      typedef unsigned int X = 1 + 1 +
      1 + 1 + 1;
    """.trimIndent(), source)
    p.assertDiagCaret(diagNr = 0, line = 1)
  }
}
