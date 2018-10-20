import org.junit.Test
import kotlin.test.assertEquals

class LexerTests {
  private val source = "<test/${javaClass.simpleName}>"
  private fun Lexer.assertNoInspections() = assertEquals(0, inspections.size)

  @Test fun identifiers() {
    val idents = listOf("abc", "_abc", "a", "a123b", "a1_bc", "a1_", "b2")
    val l = Lexer(idents.joinToString(" "), source)
    l.assertNoInspections()
    assertEquals(idents.size, l.tokens.size)
    for ((ident, token) in idents.zip(l.tokens)) assertEquals(Identifier(ident), token)
  }
  @Test fun keywords() {
    val l = Lexer(Keywords.values().joinToString(" ") { it.keyword }, source)
    l.assertNoInspections()
    assertEquals(Keywords.values().size, l.tokens.size)
    for ((enum, token) in Keywords.values().zip(l.tokens)) assertEquals(Keyword(enum), token)
  }
  @Test fun punctuators() {
    val l = Lexer(Punctuators.values().joinToString(" ") { it.punct }, source)
    l.assertNoInspections()
    val res: List<Token> = Punctuators.values().map { Punctuator(it) }
    assertEquals(res, l.tokens)
  }
  @Test fun integers() {
    val l = Lexer("1234 07 0xF 0", source)
    val ints = l.tokens
    l.assertNoInspections()
    assertEquals(4, ints.size)
    assertEquals(IntegralConstant.Decimal("1234", IntegralSuffix.NONE), ints[0])
    assertEquals(IntegralConstant.Octal("07", IntegralSuffix.NONE), ints[1])
    assertEquals(IntegralConstant.Hex("F", IntegralSuffix.NONE), ints[2])
    assertEquals(IntegralConstant.Octal("0", IntegralSuffix.NONE), ints[3])
  }
  @Test fun integerSuffixes() {
    val l = Lexer("1U 1L 1UL 1LU 1ULL 1LLU 1LL 1lLu", source)
    val ints = l.tokens
    l.assertNoInspections()
    assertEquals(8, ints.size)
    assertEquals(IntegralConstant.Decimal("1", IntegralSuffix.UNSIGNED), ints[0])
    assertEquals(IntegralConstant.Decimal("1", IntegralSuffix.LONG), ints[1])
    assertEquals(IntegralConstant.Decimal("1", IntegralSuffix.UNSIGNED_LONG), ints[2])
    assertEquals(IntegralConstant.Decimal("1", IntegralSuffix.UNSIGNED_LONG), ints[3])
    assertEquals(IntegralConstant.Decimal("1", IntegralSuffix.UNSIGNED_LONG_LONG), ints[4])
    assertEquals(IntegralConstant.Decimal("1", IntegralSuffix.UNSIGNED_LONG_LONG), ints[5])
    assertEquals(IntegralConstant.Decimal("1", IntegralSuffix.LONG_LONG), ints[6])
    assertEquals(IntegralConstant.Decimal("1", IntegralSuffix.UNSIGNED_LONG_LONG), ints[7])
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
    val text = chars.joinToString(" ") { "'$it'" }
    val l = Lexer(text, source)
    l.assertNoInspections()
    assertEquals(chars.size, l.tokens.size)
    for ((char, token) in chars.zip(l.tokens)) {
      assertEquals(CharConstant(char, CharEncoding.UNSIGNED_CHAR), token)
    }
  }
  @Test fun charPrefixes() {
    val l = Lexer("L'a' u'a' U'a'", source)
    l.assertNoInspections()
    val res = listOf<Token>(
        CharConstant("a", CharEncoding.WCHAR_T),
        CharConstant("a", CharEncoding.CHAR16_T),
        CharConstant("a", CharEncoding.CHAR32_T))
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
