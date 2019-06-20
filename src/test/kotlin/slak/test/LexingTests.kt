package slak.test

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.*
import kotlin.test.assertEquals

/**
 * These are something like "pseudo" unit tests, because technically, while most of them test a
 * single code path within the lexer, an entire class is instantiated for each test; from
 * that point of view, they more closely resemble integration tests.
 */
class LexingTests {
  @Test
  fun `Identifiers Various`() {
    val idents = listOf("abc", "_abc", "a", "a123b", "a1_bc", "a1_", "b2", "struct1")
    val l = Preprocessor(idents.joinToString(" "), source)
    l.assertNoDiagnostics()
    val res: List<LexicalToken> = idents.map { Identifier(it) }
    assertEquals(res, l.tokens)
  }

  @Test
  fun `Keywords All`() {
    val l = Preprocessor(Keywords.values().joinToString(" ") { it.keyword }, source)
    l.assertNoDiagnostics()
    val res: List<LexicalToken> = Keywords.values().map { Keyword(it) }
    assertEquals(res, l.tokens)
  }

  @Test
  fun `Punctuators All`() {
    val l = Preprocessor(Punctuators.values().joinToString(" ") { it.s }, source)
    l.assertNoDiagnostics()
    val res: List<LexicalToken> = Punctuators.values().map { Punctuator(it) }
    assertEquals(res, l.tokens)
  }

  @Test
  fun `Integers Basic`() {
    val l = Preprocessor("1234 07 0xF 0", source)
    l.assertNoDiagnostics()
    val res: List<LexicalToken> = listOf(
        IntegralConstant("1234", IntegralSuffix.NONE, Radix.DECIMAL),
        IntegralConstant("07", IntegralSuffix.NONE, Radix.OCTAL),
        IntegralConstant("F", IntegralSuffix.NONE, Radix.HEXADECIMAL),
        IntegralConstant("0", IntegralSuffix.NONE, Radix.OCTAL)
    )
    assertEquals(res, l.tokens)
  }

  @Test
  fun `Integer Suffixes`() {
    val l = Preprocessor("1U 1L 1UL 1LU 1ULL 1LLU 1LL 1lLu", source)
    l.assertNoDiagnostics()
    val res: List<LexicalToken> = listOf(
        IntegralConstant("1", IntegralSuffix.UNSIGNED, Radix.DECIMAL),
        IntegralConstant("1", IntegralSuffix.LONG, Radix.DECIMAL),
        IntegralConstant("1", IntegralSuffix.UNSIGNED_LONG, Radix.DECIMAL),
        IntegralConstant("1", IntegralSuffix.UNSIGNED_LONG, Radix.DECIMAL),
        IntegralConstant("1", IntegralSuffix.UNSIGNED_LONG_LONG, Radix.DECIMAL),
        IntegralConstant("1", IntegralSuffix.UNSIGNED_LONG_LONG, Radix.DECIMAL),
        IntegralConstant("1", IntegralSuffix.LONG_LONG, Radix.DECIMAL),
        IntegralConstant("1", IntegralSuffix.UNSIGNED_LONG_LONG, Radix.DECIMAL)
    )
    assertEquals(res, l.tokens)
  }

  @Test
  fun `Invalid Int Suffix Error`() {
    assertPPDiagnostic("123A", source, DiagnosticId.INVALID_SUFFIX)
    assertPPDiagnostic("123UA", source, DiagnosticId.INVALID_SUFFIX)
    assertPPDiagnostic("123U2", source, DiagnosticId.INVALID_SUFFIX)
  }

  @Test
  fun `Floats Various`() {
    val l = Preprocessor("""
      123.123 123. .123
      1.1E2 1.E2 .1E2
      1.1e2 1.e2 .1e2
      12.1E-10 12.E-2 .12E-2
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    val res: List<LexicalToken> = listOf(
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
    assertEquals(res, l.tokens)
  }

  @Test
  fun `Float Suffixes`() {
    val l = Preprocessor("""
      12.1L 12.L .12L
      12.1E+10F 12.E+10F .12E+10F
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    val res: List<LexicalToken> = listOf(
        FloatingConstant("12.1", FloatingSuffix.LONG_DOUBLE, Radix.DECIMAL),
        FloatingConstant("12.", FloatingSuffix.LONG_DOUBLE, Radix.DECIMAL),
        FloatingConstant(".12", FloatingSuffix.LONG_DOUBLE, Radix.DECIMAL),

        FloatingConstant("12.1", FloatingSuffix.FLOAT, Radix.DECIMAL, Exponent("10", '+')),
        FloatingConstant("12.", FloatingSuffix.FLOAT, Radix.DECIMAL, Exponent("10", '+')),
        FloatingConstant(".12", FloatingSuffix.FLOAT, Radix.DECIMAL, Exponent("10", '+'))
    )
    assertEquals(res, l.tokens)
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

  @Test
  fun `Char Constants`() {
    val chars = listOf("a", "*", "asdf", "\"")
    val l = Preprocessor(chars.joinToString(" ") { "'$it'" }, source)
    l.assertNoDiagnostics()
    val res: List<LexicalToken> = chars.map { CharLiteral(it, CharEncoding.UNSIGNED_CHAR) }
    assertEquals(res, l.tokens)
  }

  @Test
  fun `Char Prefixes`() {
    val l = Preprocessor("L'a' u'a' U'a'", source)
    l.assertNoDiagnostics()
    val res = listOf<LexicalToken>(
        CharLiteral("a", CharEncoding.WCHAR_T),
        CharLiteral("a", CharEncoding.CHAR16_T),
        CharLiteral("a", CharEncoding.CHAR32_T)
    )
    assertEquals(res, l.tokens)
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

  @Test
  fun `String Literals`() {
    val strings = listOf("a", "*", "asdf", "'")
    val l = Preprocessor(strings.joinToString(" ") { "\"$it\"" }, source)
    l.assertNoDiagnostics()
    val res: List<LexicalToken> = strings.map { StringLiteral(it, StringEncoding.CHAR) }
    assertEquals(res, l.tokens)
  }

  @Test
  fun `String Prefixes`() {
    val l = Preprocessor("""
      u8"string"
      L"string"
      u"string"
      U"string"
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    val res = listOf<LexicalToken>(
        StringLiteral("string", StringEncoding.UTF8),
        StringLiteral("string", StringEncoding.WCHAR_T),
        StringLiteral("string", StringEncoding.CHAR16_T),
        StringLiteral("string", StringEncoding.CHAR32_T)
    )
    assertEquals(res, l.tokens)
  }

  @Test
  fun `Unmatched Double Quote Error`() {
    assertPPDiagnostic("\"asfadgs", source, DiagnosticId.MISSING_QUOTE)
    assertPPDiagnostic("\"123\nasd\"", source, DiagnosticId.MISSING_QUOTE, DiagnosticId.MISSING_QUOTE)
  }

  @Test
  fun `Numbers One After Another`() {
    val l = Preprocessor("""
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

        FloatingConstant("123.", FloatingSuffix.NONE, Radix.DECIMAL, Exponent("12", '-')),
        IntegralConstant("456", IntegralSuffix.NONE, Radix.DECIMAL)
    ), l.tokens)
  }

  @Test
  fun `Dot Punctuator Vs Dot In Float`() {
    val l = Preprocessor("""
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

        FloatingConstant("123.", FloatingSuffix.FLOAT, Radix.DECIMAL, Exponent("1")),
        Punctuator(Punctuators.DOT),
        Identifier("other")
    ), l.tokens)
  }

  @Test
  fun `Declaration Basic`() {
    val l = Preprocessor("int a = 1;\n", source)
    l.assertNoDiagnostics()
    assertEquals(listOf(
        Keywords.INT.kw, Identifier("a"), Punctuator(Punctuators.ASSIGN),
        IntegralConstant("1", IntegralSuffix.NONE, Radix.DECIMAL),
        Punctuator(Punctuators.SEMICOLON)
    ), l.tokens)
  }

  @Test
  fun `Function Call`() {
    val l = Preprocessor("int a = f(123, 5.5);", source)
    l.assertNoDiagnostics()
    assertEquals(listOf(
        Keywords.INT.kw, Identifier("a"), Punctuator(Punctuators.ASSIGN),
        Identifier("f"), Punctuator(Punctuators.LPAREN),
        IntegralConstant("123", IntegralSuffix.NONE, Radix.DECIMAL), Punctuator(Punctuators.COMMA),
        FloatingConstant("5.5", FloatingSuffix.NONE, Radix.DECIMAL), Punctuator(Punctuators.RPAREN),
        Punctuator(Punctuators.SEMICOLON)
    ), l.tokens)
  }
}
