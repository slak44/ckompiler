package slak.test.parser

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.test.assertNoDiagnostics
import slak.test.source
import kotlin.test.assertEquals

class FunctionsTests {
  @Test
  fun functionMain() {
    val p = prepareCode("int main() {}", source)
    p.assertNoDiagnostics()
  }

  @Test
  fun functionProtoExpectedIdentOrParen() {
    val p = prepareCode("int default();", source)
    assert(p.diags.size > 0)
    assertEquals(DiagnosticId.EXPECTED_IDENT_OR_PAREN, p.diags[0].id)
  }
}
