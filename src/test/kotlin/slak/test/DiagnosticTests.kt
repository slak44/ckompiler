package slak.test

import org.junit.Test
import slak.ckompiler.Diagnostic
import slak.ckompiler.createDiagnostic
import slak.ckompiler.length
import slak.ckompiler.lexer.ErrorToken
import slak.ckompiler.parser.Declaration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests that diagnostics report the correct position of the error in the source code.
 */
class DiagnosticTests {
  private fun List<Diagnostic>.assertDiagCaret(diagNr: Int,
                                               line: Int? = null,
                                               col: Int? = null,
                                               colCount: Int? = null) {
    assert(diagNr in 0 until size)
    this[diagNr].assertDiagCaret(line, col, colCount)
  }

  private fun Diagnostic.assertDiagCaret(line: Int? = null,
                                         col: Int? = null,
                                         colCount: Int? = null) {
    val (errLine, errCol, _) = errorOf(caret)
    line?.let { assertEquals(line, errLine) }
    col?.let { assertEquals(col, errCol) }
    colCount?.let { assertEquals(it, caret.length()) }
  }

  @Test
  fun `PP Correct Diagnostic Column`() {
    val text = "ident     123.23A"
    val pp = preparePP(text, source)
    assert(pp.tokens[1] is ErrorToken)
    // Test if error is on the last column
    assertEquals(text.length - 1, pp.diags[0].sourceColumns[0].first)
  }

  @Test
  fun `PP Define Past-The-End Column`() {
    val pp = preparePP("#define\n", source)
    pp.diags.assertDiagCaret(diagNr = 0, col = 7, colCount = 1)
  }

  @Test
  fun `Parser Diagnostic Correct Line`() {
    val p = prepareCode("""
      int a = ;
      int b = ;
      int c = ;
      int d = ;
    """.trimIndent(), source)
    p.diags.assertDiagCaret(diagNr = 0, line = 1, col = 8)
    p.diags.assertDiagCaret(diagNr = 1, line = 2, col = 8)
    p.diags.assertDiagCaret(diagNr = 2, line = 3, col = 8)
    p.diags.assertDiagCaret(diagNr = 3, line = 4, col = 8)
  }

  @Test
  fun `Parser Diagnostic Correct Column In Line`() {
    val code = "int;"
    val p = prepareCode(code, source)
    p.diags.assertDiagCaret(diagNr = 0, line = 1, col = 0, colCount = 3)
  }

  @Test
  fun `Parser Diagnostic Correct Column 0 In Line`() {
    val p = prepareCode("register int x;", source)
    p.diags.assertDiagCaret(diagNr = 0, line = 1, col = 0)
  }

  @Test
  fun `Parser Diagnostic Correct Range Length`() {
    val p = prepareCode("""
      typedef unsigned int X = 1 + 1;
    """.trimIndent(), source)
    //                       ^~~~~~~
    //                       7 chars
    p.diags.assertDiagCaret(diagNr = 0, line = 1, colCount = 7)
  }

  @Test
  fun `Parser Diagnostic Correct Range Length For Abstract Declarator Parameters`() {
    val p = prepareCode("int f(double, int) {return 1;}", source)
    // Error is on "double" and "int"
    p.diags.assertDiagCaret(diagNr = 0, line = 1, colCount = "double".length)
    p.diags.assertDiagCaret(diagNr = 1, line = 1, colCount = "int".length)
  }

  @Test
  fun `Parser Diagnostic Multi-line Range`() {
    val p = prepareCode("""
      typedef unsigned int X = 1 + 1 +
      1 + 1 + 1;
    """.trimIndent(), source)
    p.diags.assertDiagCaret(diagNr = 0, line = 1)
  }

  @Test
  fun `Parser Correct Columns For Stuff After Variadic Dots`() {
    val p = prepareCode("""
      int f(..., int a);
    """.trimIndent(), source)
    p.diags.assertDiagCaret(diagNr = 0, line = 1, col = 9)
    p.diags.assertDiagCaret(diagNr = 1, line = 1, col = 9)
  }

  @Test
  fun `Empty Translation Unit Has Correct Diagnostic`() {
    val p = prepareCode("\n", source)
    p.diags.assertDiagCaret(diagNr = 0, line = 1, col = 0)
  }

  @Test
  fun `Parser Correct Columns For Function Calls`() {
    val src = """
      int f(int);
      int result = f(123);
    """.trimIndent()
    val p = prepareCode(src, source)
    val declarator = assertNotNull(p.root.decls[1] as? Declaration).declaratorList[0]
    val callRange = assertNotNull(declarator.second).tokenRange
    val diag = createDiagnostic {
      sourceText = src
      columns(callRange)
    }
    diag.assertDiagCaret(line = 2, col = 13, colCount = 6)
    diag.print()
  }
}
