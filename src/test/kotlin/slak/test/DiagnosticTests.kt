package slak.test

import org.junit.Test
import slak.ckompiler.length
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

  private fun Parser.assertDiagCaret(diagNr: Int,
                                     line: Int? = null,
                                     col: Int? = null,
                                     colCount: Int? = null) {
    assert(diagNr >= 0 && diagNr < diags.size)
    val (errLine, errCol, _) = diags[diagNr].errorOf(diags[diagNr].caret)
    line?.let { assertEquals(line, errLine) }
    col?.let { assertEquals(col, errCol) }
    colCount?.let { assertEquals(it, diags[diagNr].caret.length()) }
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
    p.assertDiagCaret(diagNr = 0, line = 1, col = 0, colCount = 3)
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
    p.assertDiagCaret(diagNr = 0, line = 1, colCount = 7)
  }

  @Test
  fun `Parser Diagnostic Correct Range Length For Abstract Declarator Parameters`() {
    val p = prepareCode("int f(double, int) {return 1;}", source)
    // Error is on "double" and "int"
    p.assertDiagCaret(diagNr = 0, line = 1, colCount = "double".length)
    p.assertDiagCaret(diagNr = 1, line = 1, colCount = "int".length)
  }

  @Test
  fun `Parser Diagnostic Multi-line Range`() {
    val p = prepareCode("""
      typedef unsigned int X = 1 + 1 +
      1 + 1 + 1;
    """.trimIndent(), source)
    p.assertDiagCaret(diagNr = 0, line = 1)
  }

  @Test
  fun `Parser Correct Columns For Stuff After Variadic Dots`() {
    val p = prepareCode("""
      int f(..., int a);
    """.trimIndent(), source)
    p.assertDiagCaret(diagNr = 0, line = 1, col = 9)
    p.assertDiagCaret(diagNr = 1, line = 1, col = 9)
  }

  @Test
  fun `Empty Translation Unit Has Correct Diagnostic`() {
    val p = prepareCode("\n", source)
    p.assertDiagCaret(diagNr = 0, line = 1, col = 0)
  }
}
