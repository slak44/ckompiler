import org.junit.Test
import kotlin.test.assertEquals

class ResilienceTests {
  @Test
  fun badFloats() {
    val l = Lexer("""
      123.23A ident
    """.trimIndent(), source)
    assert(l.tokens[0] is ErrorToken)
    assertEquals(Identifier("ident"), l.tokens[1])
  }

  // FIXME more tests like this
  @Test
  fun lexerDiagnosticColumn() {
    val text = "ident     123.23A"
    val l = Lexer(text, source)
    assert(l.tokens[1] is ErrorToken)
    // Test if error is on the last column
    assertEquals(text.length - 1, l.inspections[0].sourceColumns[0].start)
  }
}
