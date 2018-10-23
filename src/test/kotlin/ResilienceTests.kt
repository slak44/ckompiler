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
}
