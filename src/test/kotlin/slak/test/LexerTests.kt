package slak.test

import org.junit.Test
import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.*
import kotlin.test.assertEquals

/**
 * These are something like "pseudo" unit tests, because technically, while most of them test a
 * single code path within the lexer, an entire [Lexer] class is instantiated for each test; from
 * that point of view, they more closely resemble integration tests.
 */
class LexerTests {
  @Test
  fun `Identifiers`() {
    val idents = listOf("abc", "_abc", "a", "a123b", "a1_bc", "a1_", "b2", "struct1")
    val l = Lexer(idents.joinToString(" "), source)
    l.assertNoDiagnostics()
    val res: List<Token> = idents.map { Identifier(it) }
    assertEquals(res, l.tokens)
  }

  @Test
  fun `Keywords`() {
    val l = Lexer(Keywords.values().joinToString(" ") { it.keyword }, source)
    l.assertNoDiagnostics()
    val res: List<Token> = Keywords.values().map { Keyword(it) }
    assertEquals(res, l.tokens)
  }

  @Test
  fun `Punctuators`() {
    val l = Lexer(Punctuators.values().joinToString(" ") { it.s }, source)
    l.assertNoDiagnostics()
    val res: List<Token> = Punctuators.values().map { Punctuator(it) }
    assertEquals(res, l.tokens)
  }

  @Test
  fun `Integers`() {
    val l = Lexer("1234 07 0xF 0", source)
    l.assertNoDiagnostics()
    val res: List<Token> = listOf(
        IntegralConstant("1234", IntegralSuffix.NONE, Radix.DECIMAL),
        IntegralConstant("07", IntegralSuffix.NONE, Radix.OCTAL),
        IntegralConstant("F", IntegralSuffix.NONE, Radix.HEXADECIMAL),
        IntegralConstant("0", IntegralSuffix.NONE, Radix.OCTAL)
    )
    assertEquals(res, l.tokens)
  }

  @Test
  fun `Integer Suffixes`() {
    val l = Lexer("1U 1L 1UL 1LU 1ULL 1LLU 1LL 1lLu", source)
    l.assertNoDiagnostics()
    val res: List<Token> = listOf(
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

  private fun assertDiagnostic(s: String, id: DiagnosticId) {
    val inspections = Lexer(s, source).diags
    assert(inspections.size >= 1)
    assertEquals(id, inspections[0].id)
  }

  @Test
  fun `Invalid Int Suffix Error`() {
    assertDiagnostic("123A", DiagnosticId.INVALID_SUFFIX)
    assertDiagnostic("123UA", DiagnosticId.INVALID_SUFFIX)
    assertDiagnostic("123U2", DiagnosticId.INVALID_SUFFIX)
  }

  @Test
  fun `Floats`() {
    val l = Lexer("""
      123.123 123. .123
      1.1E2 1.E2 .1E2
      1.1e2 1.e2 .1e2
      12.1E-10 12.E-2 .12E-2
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    val res: List<Token> = listOf(
        FloatingConstant("123.123", FloatingSuffix.NONE, Radix.DECIMAL),
        FloatingConstant("123.", FloatingSuffix.NONE, Radix.DECIMAL),
        FloatingConstant(".123", FloatingSuffix.NONE, Radix.DECIMAL),

        FloatingConstant("1.1", FloatingSuffix.NONE, Radix.DECIMAL, exponent = "2"),
        FloatingConstant("1.", FloatingSuffix.NONE, Radix.DECIMAL, exponent = "2"),
        FloatingConstant(".1", FloatingSuffix.NONE, Radix.DECIMAL, exponent = "2"),

        FloatingConstant("1.1", FloatingSuffix.NONE, Radix.DECIMAL, exponent = "2"),
        FloatingConstant("1.", FloatingSuffix.NONE, Radix.DECIMAL, exponent = "2"),
        FloatingConstant(".1", FloatingSuffix.NONE, Radix.DECIMAL, exponent = "2"),

        FloatingConstant("12.1", FloatingSuffix.NONE, Radix.DECIMAL,
            exponent = "10", exponentSign = '-'),
        FloatingConstant("12.", FloatingSuffix.NONE, Radix.DECIMAL,
            exponent = "2", exponentSign = '-'),
        FloatingConstant(".12", FloatingSuffix.NONE, Radix.DECIMAL,
            exponent = "2", exponentSign = '-')
    )
    assertEquals(res, l.tokens)
  }

  @Test
  fun `Float Suffixes`() {
    val l = Lexer("""
      12.1L 12.L .12L
      12.1E+10F 12.E+10F .12E+10F
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    val res: List<Token> = listOf(
        FloatingConstant("12.1", FloatingSuffix.LONG_DOUBLE, Radix.DECIMAL),
        FloatingConstant("12.", FloatingSuffix.LONG_DOUBLE, Radix.DECIMAL),
        FloatingConstant(".12", FloatingSuffix.LONG_DOUBLE, Radix.DECIMAL),

        FloatingConstant("12.1", FloatingSuffix.FLOAT, Radix.DECIMAL,
            exponentSign = '+', exponent = "10"),
        FloatingConstant("12.", FloatingSuffix.FLOAT, Radix.DECIMAL,
            exponentSign = '+', exponent = "10"),
        FloatingConstant(".12", FloatingSuffix.FLOAT, Radix.DECIMAL,
            exponentSign = '+', exponent = "10")
    )
    assertEquals(res, l.tokens)
  }

  @Test
  fun `Invalid Float Suffix Error`() {
    assertDiagnostic("123.12A", DiagnosticId.INVALID_SUFFIX)
    assertDiagnostic("123.12FA", DiagnosticId.INVALID_SUFFIX)
    assertDiagnostic("123.12AF", DiagnosticId.INVALID_SUFFIX)
    assertDiagnostic("123.A", DiagnosticId.INVALID_SUFFIX)
    assertDiagnostic("123.12A1", DiagnosticId.INVALID_SUFFIX)
    assertDiagnostic(".1A", DiagnosticId.INVALID_SUFFIX)
    assertDiagnostic("1.1E1A", DiagnosticId.INVALID_SUFFIX)
    assertDiagnostic("1.FE", DiagnosticId.INVALID_SUFFIX)
  }

  @Test
  fun `No Exp Digits Error`() {
    assertDiagnostic("1.1EA", DiagnosticId.NO_EXP_DIGITS)
    assertDiagnostic("1.EA", DiagnosticId.NO_EXP_DIGITS)
    assertDiagnostic("1.EF", DiagnosticId.NO_EXP_DIGITS)
    assertDiagnostic("1.E+F", DiagnosticId.NO_EXP_DIGITS)
  }

  @Test
  fun `Char Constants`() {
    val chars = listOf("a", "*", "asdf", "\"")
    val l = Lexer(chars.joinToString(" ") { "'$it'" }, source)
    l.assertNoDiagnostics()
    val res: List<Token> = chars.map { CharLiteral(it, CharEncoding.UNSIGNED_CHAR) }
    assertEquals(res, l.tokens)
  }

  @Test
  fun `Char Prefixes`() {
    val l = Lexer("L'a' u'a' U'a'", source)
    l.assertNoDiagnostics()
    val res = listOf<Token>(
        CharLiteral("a", CharEncoding.WCHAR_T),
        CharLiteral("a", CharEncoding.CHAR16_T),
        CharLiteral("a", CharEncoding.CHAR32_T)
    )
    assertEquals(res, l.tokens)
  }

  @Test
  fun `Unmatched Quote Error`() {
    val inspections1 = Lexer("'asfadgs", source).diags
    assert(inspections1.size >= 1)
    assertEquals(DiagnosticId.MISSING_QUOTE, inspections1[0].id)
    val inspections2 = Lexer("'123\nasd'", source).diags
    assert(inspections2.size >= 1)
    assertEquals(DiagnosticId.MISSING_QUOTE, inspections2[0].id)
  }

  @Test
  fun `String Literals`() {
    val strings = listOf("a", "*", "asdf", "'")
    val l = Lexer(strings.joinToString(" ") { "\"$it\"" }, source)
    l.assertNoDiagnostics()
    val res: List<Token> = strings.map { StringLiteral(it, StringEncoding.CHAR) }
    assertEquals(res, l.tokens)
  }

  @Test
  fun `String Prefixes`() {
    val l = Lexer("""
      u8"string"
      L"string"
      u"string"
      U"string"
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    val res = listOf<Token>(
        StringLiteral("string", StringEncoding.UTF8),
        StringLiteral("string", StringEncoding.WCHAR_T),
        StringLiteral("string", StringEncoding.CHAR16_T),
        StringLiteral("string", StringEncoding.CHAR32_T)
    )
    assertEquals(res, l.tokens)
  }

  @Test
  fun `Unmatched Double Quote Error`() {
    val inspections1 = Lexer("\"asfadgs", source).diags
    assert(inspections1.size >= 1)
    assertEquals(DiagnosticId.MISSING_QUOTE, inspections1[0].id)
    val inspections2 = Lexer("\"123\nasd\"", source).diags
    assert(inspections2.size >= 1)
    assertEquals(DiagnosticId.MISSING_QUOTE, inspections2[0].id)
  }

  @Test
  fun `Numbers One After Another`() {
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
  fun `Dot Punctuator Vs Dot In Float`() {
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
  fun `Declaration`() {
    val l = Lexer("int a = 1;\n", source)
    l.assertNoDiagnostics()
    assertEquals(listOf(
        Keyword(Keywords.INT), Identifier("a"), Punctuator(Punctuators.ASSIGN),
        IntegralConstant("1", IntegralSuffix.NONE, Radix.DECIMAL),
        Punctuator(Punctuators.SEMICOLON)
    ), l.tokens)
  }

  @Test
  fun `Function Call`() {
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

  @Test
  fun `Comment`() {
    val l = Lexer("""
      // lalalalla int = dgdgd 1 .34/ // /////
      int a = 1;
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    assertEquals(listOf(
        Keyword(Keywords.INT), Identifier("a"), Punctuator(Punctuators.ASSIGN),
        IntegralConstant("1", IntegralSuffix.NONE, Radix.DECIMAL), Punctuator(Punctuators.SEMICOLON)
    ), l.tokens)
  }

  @Test
  fun `Comment Multiline`() {
    val l = Lexer("""
      /* lalalalla int = dgdgd 1 .34/ // ////* /*
      asf
      fg` ȀȁȂȃȄȅȆȇȈȉ ȊȋȌȍȎȏ02 10ȐȑȒȓȔȕȖȗ ȘșȚțȜȝȞ
      32ng
      g */
      int a = 1;
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    assertEquals(listOf(
        Keyword(Keywords.INT), Identifier("a"), Punctuator(Punctuators.ASSIGN),
        IntegralConstant("1", IntegralSuffix.NONE, Radix.DECIMAL), Punctuator(Punctuators.SEMICOLON)
    ), l.tokens)
  }

  @Test
  fun `Comment Multiline Unfinished`() {
    assertDiagnostic("""
      /* lalalalla int = dgdgd 1 .34/ // ////* /*
      asf
      fg` ȀȁȂȃȄȅȆȇȈȉ ȊȋȌȍȎȏ02 10ȐȑȒȓȔȕȖȗ ȘșȚțȜȝȞ
      32ng
      g
      int a = 1;
    """.trimIndent(), DiagnosticId.UNFINISHED_COMMENT)
  }

  @Test
  fun `Comment End Of File`() {
    val l = Lexer("//", source)
    l.assertNoDiagnostics()
  }
}
