import org.junit.Test
import kotlin.test.assertEquals

class LexerPseudoUnitTests {
  @Test
  fun identifiers() {
    val idents = listOf("abc", "_abc", "a", "a123b", "a1_bc", "a1_", "b2")
    val l = Lexer(idents.joinToString(" "), source)
    l.assertNoDiagnostics()
    val res: List<Token> = idents.map { Identifier(it) }
    assertEquals(res, l.tokens)
  }

  @Test
  fun keywords() {
    val l = Lexer(Keywords.values().joinToString(" ") { it.keyword }, source)
    l.assertNoDiagnostics()
    val res: List<Token> = Keywords.values().map { Keyword(it) }
    assertEquals(res, l.tokens)
  }

  @Test
  fun punctuators() {
    val l = Lexer(Punctuators.values().joinToString(" ") { it.punct }, source)
    l.assertNoDiagnostics()
    val res: List<Token> = Punctuators.values().map { Punctuator(it) }
    assertEquals(res, l.tokens)
  }

  @Test
  fun integers() {
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
  fun integerSuffixes() {
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

  @Test
  fun invalidIntSuffixError() {
    val inspections1 = Lexer("123A", source).inspections
    assert(inspections1.size >= 1)
    assertEquals(DiagnosticId.INVALID_SUFFIX, inspections1[0].id)
    val inspections2 = Lexer("123UA", source).inspections
    assert(inspections2.size >= 1)
    assertEquals(DiagnosticId.INVALID_SUFFIX, inspections2[0].id)
    val inspections3 = Lexer("123U2", source).inspections
    assert(inspections3.size >= 1)
    assertEquals(DiagnosticId.INVALID_SUFFIX, inspections3[0].id)
  }

  @Test
  fun floats() {
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

        FloatingConstant("1.1E2", FloatingSuffix.NONE, Radix.DECIMAL),
        FloatingConstant("1.E2", FloatingSuffix.NONE, Radix.DECIMAL),
        FloatingConstant(".1E2", FloatingSuffix.NONE, Radix.DECIMAL),

        FloatingConstant("1.1e2", FloatingSuffix.NONE, Radix.DECIMAL),
        FloatingConstant("1.e2", FloatingSuffix.NONE, Radix.DECIMAL),
        FloatingConstant(".1e2", FloatingSuffix.NONE, Radix.DECIMAL),

        FloatingConstant("12.1E-10", FloatingSuffix.NONE, Radix.DECIMAL),
        FloatingConstant("12.E-2", FloatingSuffix.NONE, Radix.DECIMAL),
        FloatingConstant(".12E-2", FloatingSuffix.NONE, Radix.DECIMAL)
    )
    assertEquals(res, l.tokens)
  }

  @Test
  fun floatSuffixes() {
    val l = Lexer("""
      12.1L 12.L .12L
      12.1E+10F 12.E+10F .12E+10F
    """.trimIndent(), source)
    l.assertNoDiagnostics()
    val res: List<Token> = listOf(
        FloatingConstant("12.1", FloatingSuffix.LONG_DOUBLE, Radix.DECIMAL),
        FloatingConstant("12.", FloatingSuffix.LONG_DOUBLE, Radix.DECIMAL),
        FloatingConstant(".12", FloatingSuffix.LONG_DOUBLE, Radix.DECIMAL),

        FloatingConstant("12.1E+10", FloatingSuffix.FLOAT, Radix.DECIMAL),
        FloatingConstant("12.E+10", FloatingSuffix.FLOAT, Radix.DECIMAL),
        FloatingConstant(".12E+10", FloatingSuffix.FLOAT, Radix.DECIMAL)
    )
    assertEquals(res, l.tokens)
  }

  @Test
  fun invalidFloatSuffixError() {
    val inspections1 = Lexer("123.12A", source).inspections
    assert(inspections1.size >= 1)
    assertEquals(DiagnosticId.INVALID_SUFFIX, inspections1[0].id)
    val inspections2 = Lexer("123.12FA", source).inspections
    assert(inspections2.size >= 1)
    assertEquals(DiagnosticId.INVALID_SUFFIX, inspections2[0].id)
    val inspections3 = Lexer("123.12AF", source).inspections
    assert(inspections3.size >= 1)
    assertEquals(DiagnosticId.INVALID_SUFFIX, inspections3[0].id)
    val inspections4 = Lexer("123.A", source).inspections
    assert(inspections4.size >= 1)
    assertEquals(DiagnosticId.INVALID_SUFFIX, inspections4[0].id)
    val inspections5 = Lexer(".1A", source).inspections
    assert(inspections5.size >= 1)
    assertEquals(DiagnosticId.INVALID_SUFFIX, inspections5[0].id)
    val inspections6 = Lexer("1.1E1A", source).inspections
    assert(inspections6.size >= 1)
    assertEquals(DiagnosticId.INVALID_SUFFIX, inspections6[0].id)
    val inspections7 = Lexer("1.1EA", source).inspections
    assert(inspections7.size >= 1)
    assertEquals(DiagnosticId.INVALID_SUFFIX, inspections7[0].id)
    val inspections8 = Lexer("1.EA", source).inspections
    assert(inspections8.size >= 1)
    assertEquals(DiagnosticId.INVALID_SUFFIX, inspections8[0].id)
    val inspections9 = Lexer("1.FE", source).inspections
    assert(inspections9.size >= 1)
    assertEquals(DiagnosticId.INVALID_DIGIT, inspections9[0].id)
    val inspections10 = Lexer("1.EF", source).inspections
    assert(inspections10.size >= 1)
    assertEquals(DiagnosticId.INVALID_SUFFIX, inspections10[0].id)
    val inspections11 = Lexer("1.E+F", source).inspections
    assert(inspections11.size >= 1)
    assertEquals(DiagnosticId.INVALID_SUFFIX, inspections11[0].id)
  }

  @Test
  fun charConstants() {
    val chars = listOf("a", "*", "asdf", "\"")
    val l = Lexer(chars.joinToString(" ") { "'$it'" }, source)
    l.assertNoDiagnostics()
    val res: List<Token> = chars.map { CharLiteral(it, CharEncoding.UNSIGNED_CHAR) }
    assertEquals(res, l.tokens)
  }

  @Test
  fun charPrefixes() {
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
  fun unmatchedQuoteError() {
    val inspections1 = Lexer("'asfadgs", source).inspections
    assert(inspections1.size >= 1)
    assertEquals(DiagnosticId.MISSING_QUOTE, inspections1[0].id)
    val inspections2 = Lexer("'123\nasd'", source).inspections
    assert(inspections2.size >= 1)
    assertEquals(DiagnosticId.MISSING_QUOTE, inspections2[0].id)
  }

  @Test
  fun stringLiterals() {
    val strings = listOf("a", "*", "asdf", "'")
    val l = Lexer(strings.joinToString(" ") { "\"$it\"" }, source)
    l.assertNoDiagnostics()
    val res: List<Token> = strings.map { StringLiteral(it, StringEncoding.CHAR) }
    assertEquals(res, l.tokens)
  }

  @Test
  fun stringPrefixes() {
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
  fun unmatchedDoubleQuoteError() {
    val inspections1 = Lexer("\"asfadgs", source).inspections
    assert(inspections1.size >= 1)
    assertEquals(DiagnosticId.MISSING_QUOTE, inspections1[0].id)
    val inspections2 = Lexer("\"123\nasd\"", source).inspections
    assert(inspections2.size >= 1)
    assertEquals(DiagnosticId.MISSING_QUOTE, inspections2[0].id)
  }
}
