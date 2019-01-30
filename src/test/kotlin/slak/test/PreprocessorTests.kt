package slak.test

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.Preprocessor
import kotlin.test.assertEquals

class PreprocessorTests {
  @Test
  fun `Empty Char Literal`() {
    val pp = Preprocessor("char a = '';", source)
    assertEquals(DiagnosticId.EMPTY_CHAR_CONSTANT, pp.diags[0].id)
  }
}
