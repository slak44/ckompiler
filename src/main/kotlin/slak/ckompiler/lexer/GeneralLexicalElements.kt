package slak.ckompiler.lexer

import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler

fun keyword(s: String) = Keywords.values()
    .find { s.slice(0 until nextWhitespaceOrPunct(s)) == it.keyword }
    ?.let { Keyword(it) }

fun punct(s: String) =
    Punctuators.values().find { s.startsWith(it.s) }?.let { Punctuator(it) }

/** C standard: A.1.3 */
fun isNonDigit(c: Char) = c == '_' || c in 'A'..'Z' || c in 'a'..'z'

/** C standard: A.1.3 */
fun isDigit(c: Char) = c in '0'..'9'

/** C standard: A.1.5 */
fun isHexDigit(c: Char) = isDigit(c) || c in 'A'..'F' || c in 'a'..'f'

/** C standard: A.1.5 */
fun isOctalDigit(c: Char) = c in '0'..'7'

/**
 * @return the index of the first whitespace or [Punctuators] in the string, or the string
 * length if there isn't any.
 */
fun nextWhitespaceOrPunct(s: String, vararg excludeChars: Char): Int {
  val idx = s.withIndex().indexOfFirst {
    it.value !in excludeChars && (it.value.isWhitespace() || (punct(s.drop(it.index)) != null))
  }
  return if (idx == -1) s.length else idx
}

/**
 * C standard: A.1.3, A.1.4
 * @return an null if the string is not an identifier, or the [Identifier] otherwise
 */
fun identifier(s: String): Identifier? {
  // An identifier must start with a non-digit if it isn't a universal character name
  if (!isNonDigit(s[0])) return null
  // FIXME check for universal character names
  val idx = s.indexOfFirst { !isDigit(it) && !isNonDigit(it) }
  val ident = s.slice(0 until (if (idx == -1) s.length else idx))
  return Identifier(ident)
}

/** C standard: A.1.5, A.1.6, 6.4.4.4, 6.4.5 */
fun IDebugHandler.charSequence(s: String,
                               currentOffset: Int,
                               quoteChar: Char,
                               prefixLength: Int): String {
  val noPrefix = s.drop(1 + prefixLength)
  // FIXME implement escape sequences
  val stopIdx = noPrefix.indexOfFirst { it == '\n' || it == quoteChar }
  if (stopIdx == -1 || noPrefix[stopIdx] == '\n') diagnostic {
    id = DiagnosticId.MISSING_QUOTE
    formatArgs(quoteChar)
    column(currentOffset)
    if (stopIdx != -1) column(currentOffset - 1 + stopIdx - 1)
  }
  return noPrefix.slice(0 until stopIdx)
}

/** C standard: A.1.6, 6.4.5 */
fun IDebugHandler.stringLiteral(s: String, currentOffset: Int): StringLiteral? {
  val encoding = when {
    s.startsWith("\"") -> StringEncoding.CHAR
    s.startsWith("u8\"") -> StringEncoding.UTF8
    s.startsWith("L\"") -> StringEncoding.WCHAR_T
    s.startsWith("u\"") -> StringEncoding.CHAR16_T
    s.startsWith("U\"") -> StringEncoding.CHAR32_T
    else -> return null
  }
  val data = charSequence(s, currentOffset, '"', encoding.prefixLength)
  return StringLiteral(data, encoding)
}

/** C standard: A.1.5 */
fun IDebugHandler.characterConstant(s: String, currentOffset: Int): CharLiteral? {
  val encoding = when {
    s.startsWith("'") -> CharEncoding.UNSIGNED_CHAR
    s.startsWith("L'") -> CharEncoding.WCHAR_T
    s.startsWith("u'") -> CharEncoding.CHAR16_T
    s.startsWith("U'") -> CharEncoding.CHAR32_T
    else -> return null
  }
  val data = charSequence(s, currentOffset, '\'', encoding.prefixLength)
  return CharLiteral(data, encoding)
}
