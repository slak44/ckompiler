import org.junit.Test
import kotlin.test.assertEquals

class LexerIntegrationTests {
  @Test
  fun numbersOneAfterAnother() {
    val l = Lexer("""
      123. 456 .123
      123.f 456ULL .123
      123.E-12 456
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    assertEquals(listOf<Token>(
        FloatingConstant.Decimal("123.", FloatingSuffix.NONE),
        IntegralConstant.Decimal("456", IntegralSuffix.NONE),
        FloatingConstant.Decimal(".123", FloatingSuffix.NONE),

        FloatingConstant.Decimal("123.", FloatingSuffix.FLOAT),
        IntegralConstant.Decimal("456", IntegralSuffix.UNSIGNED_LONG_LONG),
        FloatingConstant.Decimal(".123", FloatingSuffix.NONE),

        FloatingConstant.Decimal("123.E-12", FloatingSuffix.NONE),
        IntegralConstant.Decimal("456", IntegralSuffix.NONE)
    ), l.tokens)
  }

  @Test
  fun dotPunctuatorVsDotInFloat() {
    val l = Lexer("""
      ident.someOther
      ident. someOther
      123. other
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    assertEquals(listOf<Token>(
        Identifier("ident"),
        Punctuator(Punctuators.DOT),
        Identifier("someOther"),

        Identifier("ident"),
        Punctuator(Punctuators.DOT),
        Identifier("someOther"),

        FloatingConstant.Decimal("123.", FloatingSuffix.NONE),
        Identifier("other")
    ), l.tokens)
  }
}
