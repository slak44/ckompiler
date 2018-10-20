import org.junit.Test
import kotlin.test.assertEquals

class LexerTests {
  private val source = "<test/${javaClass.simpleName}>"
  private fun Lexer.assertNoInspections() = assertEquals(0, inspections.size)

  @Test fun identifiers() {
    val idents = listOf("abc", "_abc", "a", "a123b", "a1_bc", "a1_", "b2")
    val l = Lexer(idents.joinToString(" "), source)
    l.assertNoInspections()
    val res: List<Token> = idents.map { Identifier(it) }
    assertEquals(res, l.tokens)
  }
  @Test fun keywords() {
    val l = Lexer(Keywords.values().joinToString(" ") { it.keyword }, source)
    l.assertNoInspections()
    val res: List<Token> = Keywords.values().map { Keyword(it) }
    assertEquals(res, l.tokens)
  }
  @Test fun punctuators() {
    val l = Lexer(Punctuators.values().joinToString(" ") { it.punct }, source)
    l.assertNoInspections()
    val res: List<Token> = Punctuators.values().map { Punctuator(it) }
    assertEquals(res, l.tokens)
  }
  @Test fun integers() {
    val l = Lexer("1234 07 0xF 0", source)
    l.assertNoInspections()
    val res: List<Token> = listOf(
        IntegralConstant.Decimal("1234", IntegralSuffix.NONE),
        IntegralConstant.Octal("07", IntegralSuffix.NONE),
        IntegralConstant.Hex("F", IntegralSuffix.NONE),
        IntegralConstant.Octal("0", IntegralSuffix.NONE)
    )
    assertEquals(res, l.tokens)
  }
  @Test fun integerSuffixes() {
    val l = Lexer("1U 1L 1UL 1LU 1ULL 1LLU 1LL 1lLu", source)
    l.assertNoInspections()
    val res: List<Token> = listOf(
        IntegralConstant.Decimal("1", IntegralSuffix.UNSIGNED),
        IntegralConstant.Decimal("1", IntegralSuffix.LONG),
        IntegralConstant.Decimal("1", IntegralSuffix.UNSIGNED_LONG),
        IntegralConstant.Decimal("1", IntegralSuffix.UNSIGNED_LONG),
        IntegralConstant.Decimal("1", IntegralSuffix.UNSIGNED_LONG_LONG),
        IntegralConstant.Decimal("1", IntegralSuffix.UNSIGNED_LONG_LONG),
        IntegralConstant.Decimal("1", IntegralSuffix.LONG_LONG),
        IntegralConstant.Decimal("1", IntegralSuffix.UNSIGNED_LONG_LONG)
    )
    assertEquals(res, l.tokens)
  }
  @Test fun invalidSuffixError() {
    val inspections1 = Lexer("123A", source).inspections
    assert(inspections1.size >= 1)
    assertEquals(InspectionId.INVALID_SUFFIX, inspections1[0].id)
    val inspections2 = Lexer("123UA", source).inspections
    assert(inspections2.size >= 1)
    assertEquals(InspectionId.INVALID_SUFFIX, inspections2[0].id)
    val inspections3 = Lexer("123U2", source).inspections
    assert(inspections3.size >= 1)
    assertEquals(InspectionId.INVALID_SUFFIX, inspections3[0].id)
  }
  @Test fun charConstants() {
    val chars = listOf("a", "*", "asdf", "\"")
    val l = Lexer(chars.joinToString(" ") { "'$it'" }, source)
    l.assertNoInspections()
    val res: List<Token> = chars.map { CharConstant(it, CharEncoding.UNSIGNED_CHAR) }
    assertEquals(res, l.tokens)
  }
  @Test fun charPrefixes() {
    val l = Lexer("L'a' u'a' U'a'", source)
    l.assertNoInspections()
    val res = listOf<Token>(
        CharConstant("a", CharEncoding.WCHAR_T),
        CharConstant("a", CharEncoding.CHAR16_T),
        CharConstant("a", CharEncoding.CHAR32_T)
    )
    assertEquals(res, l.tokens)
  }
  @Test fun unmatchedQuoteError() {
    val inspections1 = Lexer("'asfadgs", source).inspections
    assert(inspections1.size >= 1)
    assertEquals(InspectionId.MISSING_QUOTE, inspections1[0].id)
    val inspections2 = Lexer("'123\nasd'", source).inspections
    assert(inspections2.size >= 1)
    assertEquals(InspectionId.MISSING_QUOTE, inspections2[0].id)
  }
}
