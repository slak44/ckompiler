package slak.test.parser

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.FunctionDeclarator
import slak.ckompiler.InitDeclarator
import slak.test.*
import kotlin.test.assertEquals
import kotlin.test.expect

class FunctionsTests {
  @Test
  fun functionDeclaration() {
    val p = prepareCode("int f();", source)
    p.assertNoDiagnostics()
    expect(int declare InitDeclarator(FunctionDeclarator(name("f"), emptyList()))) {
      p.root.getDeclarations()[0]
    }
  }

  @Test
  fun functionProtoExpectedIdentOrParen() {
    val p = prepareCode("int default();", source)
    assert(p.diags.size > 0)
    assertEquals(DiagnosticId.EXPECTED_IDENT_OR_PAREN, p.diags[0].id)
  }
}
