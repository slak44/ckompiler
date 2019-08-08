package slak.test.lexer

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.*
import slak.test.*
import slak.test.assertNoDiagnostics
import slak.test.assertPPDiagnostic
import slak.test.preparePP
import slak.test.source

/**
 * These are something like "pseudo" unit tests, because technically, while most of them test a
 * single code path within the lexer, an entire class is instantiated for each test; from
 * that point of view, they more closely resemble integration tests.
 */
class LexingTests {
  @ParameterizedTest
  @ValueSource(strings = ["abc", "_abc", "a", "a123b", "a1_bc", "a1_", "b2", "struct1"])
  fun `Identifiers Various`(ident: String) {
    val l = preparePP(ident, source)
    l.assertNoDiagnostics()
    l.assertTokens(Identifier(ident))
  }

  @Test
  fun `Keywords All`() {
    val l = preparePP(Keywords.values().joinToString(" ") { it.keyword }, source)
    l.assertNoDiagnostics()
    l.assertTokens(*Keywords.values())
  }

  @Test
  fun `Punctuators All`() {
    val l = preparePP(Punctuators.values().joinToString(" ") { it.s }, source)
    l.assertNoDiagnostics()
    l.assertTokens(*Punctuators.values())
  }

  @Test
  fun `Integers Basic`() {
    val l = preparePP("1234 07 0xF 0", source)
    l.assertNoDiagnostics()
    l.assertTokens(
        IntegralConstant("1234", IntegralSuffix.NONE, Radix.DECIMAL),
        IntegralConstant("07", IntegralSuffix.NONE, Radix.OCTAL),
        IntegralConstant("F", IntegralSuffix.NONE, Radix.HEXADECIMAL),
        IntegralConstant("0", IntegralSuffix.NONE, Radix.OCTAL)
    )
  }

  @Test
  fun `Integer Suffixes`() {
    val l = preparePP("1U 1L 1UL 1LU 1ULL 1LLU 1LL 1lLu", source)
    l.assertNoDiagnostics()
    l.assertTokens(
        IntegralConstant("1", IntegralSuffix.UNSIGNED, Radix.DECIMAL),
        IntegralConstant("1", IntegralSuffix.LONG, Radix.DECIMAL),
        IntegralConstant("1", IntegralSuffix.UNSIGNED_LONG, Radix.DECIMAL),
        IntegralConstant("1", IntegralSuffix.UNSIGNED_LONG, Radix.DECIMAL),
        IntegralConstant("1", IntegralSuffix.UNSIGNED_LONG_LONG, Radix.DECIMAL),
        IntegralConstant("1", IntegralSuffix.UNSIGNED_LONG_LONG, Radix.DECIMAL),
        IntegralConstant("1", IntegralSuffix.LONG_LONG, Radix.DECIMAL),
        IntegralConstant("1", IntegralSuffix.UNSIGNED_LONG_LONG, Radix.DECIMAL)
    )
  }

  @Test
  fun `Invalid Int Suffix Error`() {
    assertPPDiagnostic("123A", source, DiagnosticId.INVALID_SUFFIX)
    assertPPDiagnostic("123UA", source, DiagnosticId.INVALID_SUFFIX)
    assertPPDiagnostic("123U2", source, DiagnosticId.INVALID_SUFFIX)
  }

  @Test
  fun `Floats Various`() {
    val l = preparePP("""
      123.123 123. .123
      1.1E2 1.E2 .1E2
      1.1e2 1.e2 .1e2
      12.1E-10 12.E-2 .12E-2
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    l.assertTokens(
        FloatingConstant("123.123", FloatingSuffix.NONE, Radix.DECIMAL),
        FloatingConstant("123.", FloatingSuffix.NONE, Radix.DECIMAL),
        FloatingConstant(".123", FloatingSuffix.NONE, Radix.DECIMAL),

        FloatingConstant("1.1", FloatingSuffix.NONE, Radix.DECIMAL, Exponent("2")),
        FloatingConstant("1.", FloatingSuffix.NONE, Radix.DECIMAL, Exponent("2")),
        FloatingConstant(".1", FloatingSuffix.NONE, Radix.DECIMAL, Exponent("2")),

        FloatingConstant("1.1", FloatingSuffix.NONE, Radix.DECIMAL, Exponent("2")),
        FloatingConstant("1.", FloatingSuffix.NONE, Radix.DECIMAL, Exponent("2")),
        FloatingConstant(".1", FloatingSuffix.NONE, Radix.DECIMAL, Exponent("2")),

        FloatingConstant("12.1", FloatingSuffix.NONE, Radix.DECIMAL, Exponent("10", '-')),
        FloatingConstant("12.", FloatingSuffix.NONE, Radix.DECIMAL, Exponent("2", '-')),
        FloatingConstant(".12", FloatingSuffix.NONE, Radix.DECIMAL, Exponent("2", '-'))
    )
  }

  @Test
  fun `Float Suffixes`() {
    val l = preparePP("""
      12.1L 12.L .12L
      12.1E+10F 12.E+10F .12E+10F
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    l.assertTokens(
        FloatingConstant("12.1", FloatingSuffix.LONG_DOUBLE, Radix.DECIMAL),
        FloatingConstant("12.", FloatingSuffix.LONG_DOUBLE, Radix.DECIMAL),
        FloatingConstant(".12", FloatingSuffix.LONG_DOUBLE, Radix.DECIMAL),

        FloatingConstant("12.1", FloatingSuffix.FLOAT, Radix.DECIMAL, Exponent("10", '+')),
        FloatingConstant("12.", FloatingSuffix.FLOAT, Radix.DECIMAL, Exponent("10", '+')),
        FloatingConstant(".12", FloatingSuffix.FLOAT, Radix.DECIMAL, Exponent("10", '+'))
    )
  }

  @Test
  fun `Invalid Float Suffix Error`() {
    assertPPDiagnostic("123.12A", source, DiagnosticId.INVALID_SUFFIX)
    assertPPDiagnostic("123.12FA", source, DiagnosticId.INVALID_SUFFIX)
    assertPPDiagnostic("123.12AF", source, DiagnosticId.INVALID_SUFFIX)
    assertPPDiagnostic("123.A", source, DiagnosticId.INVALID_SUFFIX)
    assertPPDiagnostic("123.12A1", source, DiagnosticId.INVALID_SUFFIX)
    assertPPDiagnostic(".1A", source, DiagnosticId.INVALID_SUFFIX)
    assertPPDiagnostic("1.1E1A", source, DiagnosticId.INVALID_SUFFIX)
    assertPPDiagnostic("1.FE", source, DiagnosticId.INVALID_SUFFIX)
  }

  @Test
  fun `No Exp Digits Error`() {
    assertPPDiagnostic("1.1EA", source, DiagnosticId.NO_EXP_DIGITS)
    assertPPDiagnostic("1.EA", source, DiagnosticId.NO_EXP_DIGITS)
    assertPPDiagnostic("1.EF", source, DiagnosticId.NO_EXP_DIGITS)
    assertPPDiagnostic("1.E+F", source, DiagnosticId.NO_EXP_DIGITS)
  }

  @ParameterizedTest
  @ValueSource(strings = ["a", "*", "asdf", "\""])
  fun `Char Constants`(charContent: String) {
    val l = preparePP("'$charContent'", source)
    l.assertNoDiagnostics()
    l.assertTokens(CharLiteral(charContent, CharEncoding.UNSIGNED_CHAR))
  }

  @Test
  fun `Char Prefixes`() {
    val l = preparePP("L'a' u'a' U'a'", source)
    l.assertNoDiagnostics()
    l.assertTokens(
        CharLiteral("a", CharEncoding.WCHAR_T),
        CharLiteral("a", CharEncoding.CHAR16_T),
        CharLiteral("a", CharEncoding.CHAR32_T)
    )
  }

  @Test
  fun `Unmatched Quote Error`() {
    assertPPDiagnostic("'asfadgs", source, DiagnosticId.MISSING_QUOTE)
    assertPPDiagnostic("'123\nasd'", source, DiagnosticId.MISSING_QUOTE, DiagnosticId.MISSING_QUOTE)
  }

  @Test
  fun `Empty Char Literal`() {
    assertPPDiagnostic("char a = '';", source, DiagnosticId.EMPTY_CHAR_CONSTANT)
  }

  @ParameterizedTest
  @ValueSource(strings = ["a", "*", "asdf", "'"])
  fun `String Literals`(strContent: String) {
    val l = preparePP("\"$strContent\"", source)
    l.assertNoDiagnostics()
    l.assertTokens(StringLiteral(strContent, StringEncoding.CHAR))
  }

  @Test
  fun `String Prefixes`() {
    val l = preparePP("""
      u8"string"
      L"string"
      u"string"
      U"string"
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    l.assertTokens(
        StringLiteral("string", StringEncoding.UTF8),
        StringLiteral("string", StringEncoding.WCHAR_T),
        StringLiteral("string", StringEncoding.CHAR16_T),
        StringLiteral("string", StringEncoding.CHAR32_T)
    )
  }

  @Test
  fun `Unmatched Double Quote Error`() {
    assertPPDiagnostic("\"asfadgs", source, DiagnosticId.MISSING_QUOTE)
    assertPPDiagnostic("\"123\nasd\"", source,
        DiagnosticId.MISSING_QUOTE, DiagnosticId.MISSING_QUOTE)
  }

  @Test
  fun `Numbers One After Another`() {
    val l = preparePP("""
      123. 456 .123
      123.f 456ULL .123
      123.E-12 456
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    l.assertTokens(
        FloatingConstant("123.", FloatingSuffix.NONE, Radix.DECIMAL),
        IntegralConstant("456", IntegralSuffix.NONE, Radix.DECIMAL),
        FloatingConstant(".123", FloatingSuffix.NONE, Radix.DECIMAL),

        FloatingConstant("123.", FloatingSuffix.FLOAT, Radix.DECIMAL),
        IntegralConstant("456", IntegralSuffix.UNSIGNED_LONG_LONG, Radix.DECIMAL),
        FloatingConstant(".123", FloatingSuffix.NONE, Radix.DECIMAL),

        FloatingConstant("123.", FloatingSuffix.NONE, Radix.DECIMAL, Exponent("12", '-')),
        IntegralConstant("456", IntegralSuffix.NONE, Radix.DECIMAL)
    )
  }

  @Test
  fun `Dot Punctuator Vs Dot In Float`() {
    val l = preparePP("""
      ident.someOther
      ident. someOther
      ident .someOther
      ident . someOther
      123. other
      123 . other
      123.E1F.other
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    l.assertTokens(
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

        FloatingConstant("123.", FloatingSuffix.FLOAT, Radix.DECIMAL, Exponent("1")),
        Punctuator(Punctuators.DOT),
        Identifier("other")
    )
  }

  @Test
  fun `Declaration Basic`() {
    val l = preparePP("int a = 1;\n", source)
    l.assertNoDiagnostics()
    l.assertTokens(Keywords.INT, Identifier("a"), Punctuators.ASSIGN, 1, Punctuators.SEMICOLON)
  }

  @Test
  fun `Function Call`() {
    val l = preparePP("int a = f(123, 5.5);", source)
    l.assertNoDiagnostics()
    l.assertTokens(Keywords.INT, Identifier("a"), Punctuators.ASSIGN, Identifier("f"),
        Punctuators.LPAREN, 123, Punctuators.COMMA, 5.5, Punctuators.RPAREN, Punctuators.SEMICOLON)
  }
}
