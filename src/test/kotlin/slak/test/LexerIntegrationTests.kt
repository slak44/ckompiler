package slak.test

import org.junit.Test
import slak.ckompiler.lexer.*
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
    assertEquals(listOf(
        FloatingConstant("123.", FloatingSuffix.NONE, Radix.DECIMAL),
        IntegralConstant("456", IntegralSuffix.NONE, Radix.DECIMAL),
        FloatingConstant(".123", FloatingSuffix.NONE, Radix.DECIMAL),

        FloatingConstant("123.", FloatingSuffix.FLOAT, Radix.DECIMAL),
        IntegralConstant("456", IntegralSuffix.UNSIGNED_LONG_LONG, Radix.DECIMAL),
        FloatingConstant(".123", FloatingSuffix.NONE, Radix.DECIMAL),

        FloatingConstant("123.", FloatingSuffix.NONE, Radix.DECIMAL, '-', "12"),
        IntegralConstant("456", IntegralSuffix.NONE, Radix.DECIMAL)
    ), l.tokens)
  }

  @Test
  fun dotPunctuatorVsDotInFloat() {
    val l = Lexer("""
      ident.someOther
      ident. someOther
      ident .someOther
      ident . someOther
      123. other
      123 . other
      123.E1F.other
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    assertEquals(listOf(
        Identifier("ident"),
        Punctuator(Punctuators.DOT),
        Identifier("someOther"),

        Identifier("ident"),
        Punctuator(Punctuators.DOT),
        Identifier("someOther"),

        Identifier("ident"),
        Punctuator(Punctuators.DOT),
        Identifier("someOther"),

        Identifier("ident"),
        Punctuator(Punctuators.DOT),
        Identifier("someOther"),

        FloatingConstant("123.", FloatingSuffix.NONE, Radix.DECIMAL),
        Identifier("other"),

        IntegralConstant("123", IntegralSuffix.NONE, Radix.DECIMAL),
        Punctuator(Punctuators.DOT),
        Identifier("other"),

        FloatingConstant("123.", FloatingSuffix.FLOAT, Radix.DECIMAL, exponent = "1"),
        Punctuator(Punctuators.DOT),
        Identifier("other")
    ), l.tokens)
  }

  @Test
  fun declaration() {
    val l = Lexer("int a = 1;\n", source)
    l.assertNoDiagnostics()
    assertEquals(listOf(
        Keyword(Keywords.INT), Identifier("a"), Punctuator(Punctuators.ASSIGN),
        IntegralConstant("1", IntegralSuffix.NONE, Radix.DECIMAL),
        Punctuator(Punctuators.SEMICOLON)
    ), l.tokens)
  }

  @Test
  fun functionCall() {
    val l = Lexer("int a = f(123, 5.5);", source)
    l.assertNoDiagnostics()
    assertEquals(listOf(
        Keyword(Keywords.INT), Identifier("a"), Punctuator(Punctuators.ASSIGN),
        Identifier("f"), Punctuator(Punctuators.LPAREN),
        IntegralConstant("123", IntegralSuffix.NONE, Radix.DECIMAL), Punctuator(Punctuators.COMMA),
        FloatingConstant("5.5", FloatingSuffix.NONE, Radix.DECIMAL), Punctuator(Punctuators.RPAREN),
        Punctuator(Punctuators.SEMICOLON)
    ), l.tokens)
  }
}
