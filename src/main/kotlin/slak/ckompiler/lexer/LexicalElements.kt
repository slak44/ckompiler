package slak.ckompiler.lexer

import io.github.oshai.kotlinlogging.KotlinLogging
import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler
import slak.ckompiler.throwICE

private val logger = KotlinLogging.logger {}

interface StaticTokenEnum {
  val realName: String
}

enum class Keywords(val keyword: String) : StaticTokenEnum {
  AUTO("auto"),
  BREAK("break"),
  CASE("case"),
  CHAR("char"),
  CONST("const"),
  CONTINUE("continue"),
  DEFAULT("default"),
  DOUBLE("double"),
  DO("do"),
  ELSE("else"),
  ENUM("enum"),
  EXTERN("extern"),
  FLOAT("float"),
  FOR("for"),
  GOTO("goto"),
  IF("if"),
  INLINE("inline"),
  INT("int"),
  LONG("long"),
  REGISTER("register"),
  RESTRICT("restrict"),
  RETURN("return"),
  SHORT("short"),
  SIGNED("signed"),
  SIZEOF("sizeof"),
  STATIC("static"),
  STRUCT("struct"),
  SWITCH("switch"),
  TYPEDEF("typedef"),
  UNION("union"),
  UNSIGNED("unsigned"),
  VOID("void"),
  VOLATILE("volatile"),
  WHILE("while"),
  ALIGNAS("_Alignas"),
  ALIGNOF("_Alignof"),
  ATOMIC("_Atomic"),
  BOOL("_Bool"),
  COMPLEX("_Complex"),
  GENERIC("_Generic"),
  IMAGINARY("_Imaginary"),
  NORETURN("_Noreturn"),
  STATIC_ASSERT("_Static_assert"),
  THREAD_LOCAL("_Thread_local");

  override val realName get() = keyword
}

enum class Punctuators(val s: String) : StaticTokenEnum {
  LSQPAREN("["), RSQPAREN("]"), LPAREN("("), RPAREN(")"),
  LBRACKET("{"), RBRACKET("}"), DOTS("..."), DOT("."), ARROW("->"),
  MUL_ASSIGN("*="), DIV_ASSIGN("/="), MOD_ASSIGN("%="), PLUS_ASSIGN("+="),
  SUB_ASSIGN("-="), LSH_ASSIGN("<<="), RSH_ASSIGN(">>="),
  AND_ASSIGN("&="), XOR_ASSIGN("^="), OR_ASSIGN("|="),
  AND("&&"), OR("||"),
  INC("++"), DEC("--"), AMP("&"), STAR("*"), PLUS("+"), MINUS("-"),
  TILDE("~"),
  LESS_COLON("<:"), LESS_PERCENT("<%"),
  SLASH("/"), LSH("<<"), RSH(">>"),
  LEQ("<="), GEQ(">="), LT("<"), GT(">"),
  EQUALS("=="), NEQUALS("!="), CARET("^"), PIPE("|"),
  QMARK("?"), SEMICOLON(";"),
  NOT("!"),
  ASSIGN("="),
  COMMA(","), DOUBLE_HASH("##"), HASH("#"),
  COLON_MORE(":>"), COLON(":"), PERCENT_MORE("%>"),
  PERCENT_COLON_PERCENT_COLON("%:%:"), PERCENT_COLON("%:"), PERCENT("%");

  override val realName get() = s
}

fun punct(s: String) =
    Punctuators.entries.find { s.startsWith(it.s) }?.let { Punctuator(it) }

enum class IntegralSuffix(val length: Int) {
  UNSIGNED(1), LONG(1), LONG_LONG(2),
  UNSIGNED_LONG(2), UNSIGNED_LONG_LONG(3),
  NONE(0);

  override fun toString() = when (this) {
    UNSIGNED -> "u"
    LONG -> "l"
    LONG_LONG -> "ll"
    UNSIGNED_LONG -> "ul"
    UNSIGNED_LONG_LONG -> "ull"
    NONE -> "i"
  }
}

enum class FloatingSuffix(val length: Int) {
  FLOAT(1), LONG_DOUBLE(1), NONE(0);

  override fun toString() = when (this) {
    FLOAT -> "f"
    LONG_DOUBLE -> "ld"
    NONE -> "d"
  }
}

enum class Radix(val prefixLength: Int) {
  DECIMAL(0), OCTAL(0), HEXADECIMAL(2);

  fun toInt(): Int = when (this) {
    DECIMAL -> 10
    OCTAL -> 8
    HEXADECIMAL -> 16
  }
}

/** C standard: A.1.3 */
fun isNonDigit(c: Char) = c == '_' || c in 'A'..'Z' || c in 'a'..'z'

/** C standard: A.1.3, 6.4.2.1.0.3 */
fun isIdentifierNonDigit(c: Char) =
    isNonDigit(c) || c == '$' || c.category in arrayOf(CharCategory.LOWERCASE_LETTER, CharCategory.UPPERCASE_LETTER)

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
  if (!isIdentifierNonDigit(s[0])) return null
  // FIXME check for universal character names
  val idx = s.indexOfFirst { !isDigit(it) && !isIdentifierNonDigit(it) }
  val ident = s.slice(0 until if (idx == -1) s.length else idx)
  return Identifier(ident)
}

/**
 * Maps the character after an escape sequence (eg the a in \a) to the actual char value it should
 * have.
 *
 * C standard: A.1.5, 6.4.4.4, 5.2.2.0.2, note 77
 */
private val simpleEscapeSequences = mapOf(
    '\'' to '\'',
    '"' to '"',
    '?' to '?',
    '\\' to '\\',
    'a' to 0x7.toChar(),
    'b' to 0x8.toChar(),
    'f' to 0xC.toChar(),
    'n' to 0xA.toChar(),
    'r' to 0xD.toChar(),
    't' to 0x9.toChar(),
    'v' to 0xB.toChar(),
    '0' to 0x0.toChar()
)

/**
 * Parses `c-char-sequence` or `s-char-sequence`, depending on [quoteChar]. Returns string contents,
 * and how many characters in the given string [s] were consumed to create the sequence; this
 * number can be greater than the returned string's length due to escape sequences.
 *
 * C standard: A.1.5, A.1.6, 6.4.4.4, 6.4.5
 */
fun IDebugHandler.charSequence(
    s: String,
    currentOffset: Int,
    quoteChar: Char,
    prefixLength: Int,
): Pair<String, Int> {
  val noPrefix = s.drop(1 + prefixLength)
  var idx = 0
  var charSeq = ""
  while (idx < noPrefix.length) {
    val c = noPrefix[idx]
    if (c == quoteChar) break
    if (c == '\n') break
    // Regular characters
    if (c != '\\') {
      charSeq += c
      idx++
      continue
    }
    // Last char of string is backslash, will print missing quote diagnostic below
    if (idx == noPrefix.length - 1) {
      idx = -1
      break
    }
    // Deal with escape sequences here
    val nextChar = noPrefix[idx + 1]
    if (simpleEscapeSequences.containsKey(nextChar)) {
      idx += 2
      charSeq += simpleEscapeSequences[nextChar]
    } else {
      TODO("other kinds of escape sequences")
    }
  }
  // No quote was found to break out of the loop, so missing quote it is
  if (idx >= noPrefix.length) idx = -1
  if (idx == -1 || noPrefix[idx] == '\n') diagnostic {
    id = DiagnosticId.MISSING_QUOTE
    formatArgs(quoteChar)
    column(currentOffset)
    if (idx != -1) column(currentOffset - 1 + idx - 1)
  }
  // If we printed MISSING_QUOTE, but the sequence is empty, we need to make sure we don't also
  // print EMPTY_CHAR_CONSTANT later
  val contents = if (idx == -1 && charSeq.isEmpty()) byteArrayOf(0).toString() else charSeq
  val originalLength = if (idx == -1) noPrefix.length else idx
  return contents to originalLength
}

enum class StringEncoding(val prefix: String) {
  CHAR(""), UTF8("u8"), WCHAR_T("L"), CHAR16_T("u"), CHAR32_T("U")
}

enum class CharEncoding(val prefix: String) {
  UNSIGNED_CHAR(""), WCHAR_T("L"), CHAR16_T("u"), CHAR32_T("U")
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
  val data = charSequence(s, currentOffset, '"', encoding.prefix.length)
  return StringLiteral(data.first, encoding, data.second)
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
  val data = charSequence(s, currentOffset, '\'', encoding.prefix.length)
  return CharLiteral(data.first, encoding, data.second)
}

/**
 * C standard: A.1.5, 6.4.4.4
 * @param s a possible float suffix string
 * @param nrLength the length of the float constant before the suffix
 * @see FloatingSuffix
 */
private fun IDebugHandler.floatingSuffix(
    s: String,
    currentOffset: Int,
    nrLength: Int,
): FloatingSuffix? = when {
  s.isEmpty() -> FloatingSuffix.NONE
  // Float looks like 123. or 123.23
  s[0].isWhitespace() || s[0] == '.' || isDigit(s[0]) -> FloatingSuffix.NONE
  // Float looks like 123.245E-10F
  s[0].uppercaseChar() == 'F' && s.length == 1 -> FloatingSuffix.FLOAT
  // Float looks like 123.245E-10L
  s[0].uppercaseChar() == 'L' && s.length == 1 -> FloatingSuffix.LONG_DOUBLE
  else -> {
    diagnostic {
      id = DiagnosticId.INVALID_SUFFIX
      formatArgs(s, "floating")
      columns(currentOffset + nrLength until currentOffset + nextWhitespaceOrPunct(s))
    }
    null
  }
}

// FIXME: missing hex floating constants
/** C standard: A.1.5 */
fun IDebugHandler.floatingConstant(s: String, currentOffset: Int): LexicalToken? {
  // Not a float: must start with either digit or dot
  if (!isDigit(s[0]) && s[0] != '.') return null
  // Not a float: just a dot
  if (s[0] == '.' && s.length == 1) return null
  // Not a float: character after dot must be either suffix or exponent
  if (s[0] == '.' && !isDigit(s[1]) && s[1].uppercaseChar() !in listOf('E', 'F', 'L')) {
    return null
  }
  val dotIdx = nextWhitespaceOrPunct(s)
  // Not a float: reached end of string and no dot fount
  if (dotIdx == s.length) return null
  // Not a float: found something else before finding a dot
  if (s[dotIdx] != '.') return null
  // Not a float: found non-digit(s) before dot
  if (s.slice(0 until dotIdx).any { !isDigit(it) }) return null
  val integerPartEnd = s.slice(0..dotIdx).indexOfFirst { !isDigit(it) }
  if (integerPartEnd != dotIdx) {
    logger.throwICE("Integer part of float contains non-digits") {
      "integerPartEnd: $integerPartEnd, whitespaceOrDot: $dotIdx, lexer: $this"
    }
  }
  val fractionalPartEnd = s.drop(dotIdx + 1).indexOfFirst { !isDigit(it) }
  // The rest of the string is the float
  if (fractionalPartEnd == -1 || dotIdx + 1 + fractionalPartEnd == s.length) {
    return FloatingConstant(s, FloatingSuffix.NONE, Radix.DECIMAL)
  }
  val bonusIdx = dotIdx + 1 + fractionalPartEnd
  val float = s.slice(0 until bonusIdx)
  // If the float is just a dot, it's not actually a float
  if (float == ".") return null
  // Has exponent part
  if (s[bonusIdx].uppercaseChar() == 'E') {
    val sign = when (s[bonusIdx + 1]) {
      '+' -> '+'
      '-' -> '-'
      else -> null
    }
    val signLen = if (sign == null) 0 else 1
    val exponentStartIdx = bonusIdx + 1 + signLen
    val exponentEndIdx = s.drop(exponentStartIdx).indexOfFirst { !isDigit(it) }
    // The rest of the string is the exponent
    if (exponentEndIdx == -1 || exponentStartIdx + exponentEndIdx == s.length) {
      return FloatingConstant(
          float, FloatingSuffix.NONE, Radix.DECIMAL,
          Exponent(s.substring(exponentStartIdx), sign)
      )
    }
    val exponent = s.slice(exponentStartIdx until exponentStartIdx + exponentEndIdx)
    val totalLengthWithoutSuffix = float.length + 1 + signLen + exponent.length
    val tokenEnd = exponentStartIdx + exponentEndIdx +
        nextWhitespaceOrPunct(s.drop(exponentStartIdx + exponentEndIdx))
    if (exponent.isEmpty()) {
      diagnostic {
        id = DiagnosticId.NO_EXP_DIGITS
        column(currentOffset + exponentStartIdx)
      }
      return ErrorToken(tokenEnd)
    }
    val suffixStr = s.slice(exponentStartIdx + exponentEndIdx until tokenEnd)
    val suffix = floatingSuffix(suffixStr, currentOffset, totalLengthWithoutSuffix)
        ?: return ErrorToken(tokenEnd)
    return FloatingConstant(float, suffix, Radix.DECIMAL, Exponent(exponent, sign))
  }
  val tokenEnd = bonusIdx + nextWhitespaceOrPunct(s.drop(bonusIdx))
  val suffix = floatingSuffix(s.slice(bonusIdx until tokenEnd), currentOffset, float.length)
      ?: return ErrorToken(tokenEnd)
  return FloatingConstant(float, suffix, Radix.DECIMAL)
}

/**
 * C standard: A.1.5, 6.4.4.1
 * @param s a possible integer suffix string
 * @param nrLength the length of the int constant before the suffix
 * @see IntegralSuffix
 */
private fun IDebugHandler.integerSuffix(
    s: String,
    currentOffset: Int,
    nrLength: Int,
): IntegralSuffix {
  if (s.isEmpty()) return IntegralSuffix.NONE
  val t = s.uppercase()
  val suffix = when {
    t.startsWith("ULL") || t.startsWith("LLU") -> IntegralSuffix.UNSIGNED_LONG_LONG
    t.startsWith("UL") || t.startsWith("LU") -> IntegralSuffix.UNSIGNED_LONG
    t.startsWith("LL") -> IntegralSuffix.LONG_LONG
    t.startsWith("L") -> IntegralSuffix.LONG
    t.startsWith("U") -> IntegralSuffix.UNSIGNED
    else -> IntegralSuffix.NONE
  }
  if (s.drop(suffix.length).isNotEmpty()) diagnostic {
    id = DiagnosticId.INVALID_SUFFIX
    formatArgs(s, "integer")
    columns(currentOffset + nrLength until currentOffset + nextWhitespaceOrPunct(s))
  }
  return suffix
}

private inline fun IDebugHandler.digitSequence(
    s: String,
    currentOffset: Int,
    prefixLength: Int = 0,
    isValidDigit: (c: Char) -> Boolean,
): Pair<String, IntegralSuffix> {
  val nrWithSuffix = s.slice(prefixLength until nextWhitespaceOrPunct(s))
  val nrEndIdx = nrWithSuffix.indexOfFirst { !isValidDigit(it) }
  val nrText = nrWithSuffix.slice(0 until if (nrEndIdx == -1) nrWithSuffix.length else nrEndIdx)
  return Pair(nrText, integerSuffix(nrWithSuffix.drop(nrText.length), currentOffset, nrText.length))
}

/** C standard: A.1.5 */
fun IDebugHandler.integerConstant(s: String, currentOffset: Int): IntegralConstant? {
  // Decimal numbers
  if (isDigit(s[0]) && s[0] != '0') {
    val c = digitSequence(s, currentOffset) { isDigit(it) }
    return IntegralConstant(c.first, c.second, Radix.DECIMAL)
  }
  // Hex numbers
  if ((s.startsWith("0x") || s.startsWith("0X") && isHexDigit(s[2]))) {
    val c = digitSequence(s, currentOffset, 2) { isHexDigit(it) }
    return IntegralConstant(c.first, c.second, Radix.HEXADECIMAL)
  }
  // Octal numbers
  if (s[0] == '0') {
    val c = digitSequence(s, currentOffset) { isOctalDigit(it) }
    return IntegralConstant(c.first, c.second, Radix.OCTAL)
  }
  return null
}

/** C standard: A.1.8 */
fun headerName(s: String): LexicalToken? {
  val quote = s[0]
  if (quote != '<' && quote != '"') return null
  val otherQuote = if (s[0] == '<') '>' else '"'
  val endIdx = s.drop(1).indexOfFirst { it == '\n' || it == otherQuote }
  if (endIdx == -1 || s[1 + endIdx] == '\n') {
    return ErrorToken(if (endIdx == -1) s.length else 1 + endIdx)
  }
  return HeaderName(s.slice(1..endIdx), quote)
}
