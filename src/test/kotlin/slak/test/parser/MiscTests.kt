package slak.test.parser

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.test.source
import kotlin.test.assertEquals

class MiscTests {
  @Test
  fun emptyCharLiteral() {
    val p = prepareCode("char a = '';", source)
    assertEquals(DiagnosticId.EMPTY_CHAR_CONSTANT, p.diags[0].id)
  }
}
