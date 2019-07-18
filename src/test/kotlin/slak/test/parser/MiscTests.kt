package slak.test.parser

import org.junit.jupiter.api.Test
import slak.ckompiler.DiagnosticId
import slak.test.assertDiags
import slak.test.prepareCode
import slak.test.source
import kotlin.test.assertEquals

class MiscTests {
  @Test
  fun `Not A Translation Unit`() {
    val p = prepareCode("1 + 2;", source)
    assertEquals(DiagnosticId.EXPECTED_EXTERNAL_DECL, p.diags[0].id)
  }

  @Test
  fun `At Least One Declaration Per Translation Unit`() {
    val p = prepareCode("", source)
    p.assertDiags(DiagnosticId.TRANSLATION_UNIT_NEEDS_DECL)
  }
}
