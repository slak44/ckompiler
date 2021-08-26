package slak.test

import org.junit.jupiter.api.Test
import slak.ckompiler.DebugHandler
import slak.ckompiler.Diagnostic
import slak.ckompiler.DiagnosticId
import slak.ckompiler.length
import slak.ckompiler.lexer.ErrorToken
import slak.ckompiler.parser.Declaration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests that diagnostics report the correct position of the error in the source code.
 */
class DiagnosticTests {
  private fun List<Diagnostic>.assertDiagCaret(
      diagNr: Int,
      line: Int? = null,
      col: Int? = null,
      colCount: Int? = null
  ) {
    assertDiagSourceCol(diagNr, 0, line, col, colCount)
  }

  private fun Diagnostic.assertDiagCaret(
      line: Int? = null,
      col: Int? = null,
      colCount: Int? = null
  ) {
    assertDiagSourceCol(0, line, col, colCount)
  }

  private fun List<Diagnostic>.assertDiagSourceCol(
      diagNr: Int,
      sourceColIdx: Int,
      line: Int? = null,
      col: Int? = null,
      colCount: Int? = null
  ) {
    assert(diagNr in 0 until size)
    this[diagNr].assertDiagSourceCol(sourceColIdx, line, col, colCount)
  }

  private fun Diagnostic.assertDiagSourceCol(
      sourceColIdx: Int,
      line: Int? = null,
      col: Int? = null,
      colCount: Int? = null
  ) {
    val (errLine, errCol, _) = dataFor(sourceColumns[sourceColIdx])
    line?.let { assertEquals(line, errLine) }
    col?.let { assertEquals(col, errCol) }
    colCount?.let { assertEquals(it, sourceColumns[sourceColIdx].length()) }
  }

  @Test
  fun `PP Correct Diagnostic Column`() {
    val text = "ident     123.23A"
    val pp = preparePP(text, source)
    assert(pp.tokens[1] is ErrorToken)
    pp.diags.assertDiagCaret(diagNr = 0, col = text.length - 1)
  }

  @Test
  fun `PP Define Past-The-End Column`() {
    val pp = preparePP("#define\n", source)
    pp.diags.assertDiagCaret(diagNr = 0, col = 7, colCount = 1)
  }

  @Test
  fun `PP IfSection Defined Paren Past-The-End Column`() {
    val pp = preparePP("#if defined ( TEST\n#endif", source)
    pp.diags.assertDiagCaret(diagNr = 0, line = 1, col = 18, colCount = 1)
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
    val callRange = assertNotNull(declarator.second)
    val diag = DebugHandler(source, source, src).createDiagnostic {
      errorOn(callRange)
    }
    diag.assertDiagCaret(line = 2, col = 13, colCount = 6)
    diag.print()
  }

  @Test
  fun `Function Call's Missing Arg Diagnostic Points To The End Paren Of The Call`() {
    val p = prepareCode("""
      int f(int a, int b);
      int a = f(1, );
    """.trimIndent(), source)
    p.diags.assertDiagCaret(diagNr = 0, line = 2, col = 13, colCount = 1)
  }

  @Test
  fun `Assignment To Cast Covers The Entire Cast Expression`() {
    val p = prepareCode("int main() {int x = 1; (long) x = 5;}", source)
    p.assertDiags(DiagnosticId.ILLEGAL_CAST_ASSIGNMENT)
    p.diags.assertDiagCaret(diagNr = 0, col = 32, colCount = 1)
    p.diags.assertDiagSourceCol(diagNr = 0, sourceColIdx = 1, col = 23, colCount = 8)
  }

  @Test
  fun `Assignment To Constant Has Correct Length`() {
    val p = prepareCode("int main() {2   = 5;}", source)
    p.assertDiags(DiagnosticId.CONSTANT_NOT_ASSIGNABLE)
    p.diags.assertDiagCaret(diagNr = 0, col = 16, colCount = 1)
    p.diags.assertDiagSourceCol(diagNr = 0, sourceColIdx = 1, col = 12, colCount = 1)
  }

  @Test
  fun `Regular Diagnostic Does Not Produce 'Macro Expanded' Diagnostic Text`() {
    val p = prepareCode("const const int a = 1;", source)
    p.assertDiags(DiagnosticId.DUPLICATE_DECL_SPEC)
    assert("|EXPANDED_FROM]" !in p.diags[0].toString())
  }
}
