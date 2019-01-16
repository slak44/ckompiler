package slak.ckompiler.lexer

import mu.KotlinLogging
import slak.ckompiler.*

class Lexer(private val textSource: String, private val srcFileName: SourceFileName) {
  val tokens = mutableListOf<Token>()
  val diags = mutableListOf<Diagnostic>()
  private var src: String = textSource
  private var currentOffset: Int = 0

  init {
    tokenize()
    diags.forEach { it.print() }
  }

  private fun lexerDiagnostic(build: DiagnosticBuilder.() -> Unit) {
    diags += createDiagnostic {
      sourceFileName = srcFileName
      sourceText = textSource
      origin = "Lexer"
      this.build()
    }
  }

  companion object {
    private val logger = KotlinLogging.logger("Lexer")

    private fun keyword(s: String) = Keywords.values()
        .find { s.slice(0 until nextWhitespaceOrPunct(s)) == it.keyword }
        ?.let { Keyword(it) }

    private fun punct(s: String) =
        Punctuators.values().find { s.startsWith(it.s) }?.let { Punctuator(it) }

    /** C standard: A.1.3 */
    private fun isNonDigit(c: Char) = c == '_' || c in 'A'..'Z' || c in 'a'..'z'

    /** C standard: A.1.3 */
    fun isDigit(c: Char) = c in '0'..'9'

    /** C standard: A.1.5 */
    private fun isHexDigit(c: Char) = isDigit(c) || c in 'A'..'F' || c in 'a'..'f'

    /** C standard: A.1.5 */
    private fun isOctalDigit(c: Char) = c in '0'..'7'

    /**
     * @return the index of the first whitespace or [Punctuators] in the string, or the string
     * length if there isn't any.
     */
    private fun nextWhitespaceOrPunct(s: String, vararg excludeChars: Char): Int {
      val idx = s.withIndex().indexOfFirst {
        it.value !in excludeChars && (it.value.isWhitespace() || (punct(s.drop(it.index)) != null))
      }
      return if (idx == -1) s.length else idx
    }
  }

  /** C standard: A.1.5, A.1.6, 6.4.4.4, 6.4.5 */
  private fun charSequence(s: String, quoteChar: Char, prefixLength: Int): String {
    val noPrefix = s.drop(1 + prefixLength)
    // FIXME implement escape sequences
    val stopIdx = noPrefix.indexOfFirst { it == '\n' || it == quoteChar }
    if (stopIdx == -1 || noPrefix[stopIdx] == '\n') lexerDiagnostic {
      id = DiagnosticId.MISSING_QUOTE
      messageFormatArgs = listOf(quoteChar)
      column(currentOffset)
      if (stopIdx != -1) column(currentOffset - 1 + stopIdx - 1)
    }
    return noPrefix.slice(0 until stopIdx)
  }

  /** C standard: A.1.6, 6.4.5 */
  private fun stringLiteral(s: String): StringLiteral? {
    val encoding = when {
      s.startsWith("\"") -> StringEncoding.CHAR
      s.startsWith("u8\"") -> StringEncoding.UTF8
      s.startsWith("L\"") -> StringEncoding.WCHAR_T
      s.startsWith("u\"") -> StringEncoding.CHAR16_T
      s.startsWith("U\"") -> StringEncoding.CHAR32_T
      else -> return null
    }
    val data = charSequence(s, '"', encoding.prefixLength)
    return StringLiteral(data, encoding)
  }

  /** C standard: A.1.5 */
  private fun characterConstant(s: String): CharLiteral? {
    val encoding = when {
      s.startsWith("'") -> CharEncoding.UNSIGNED_CHAR
      s.startsWith("L'") -> CharEncoding.WCHAR_T
      s.startsWith("u'") -> CharEncoding.CHAR16_T
      s.startsWith("U'") -> CharEncoding.CHAR32_T
      else -> return null
    }
    val data = charSequence(s, '\'', encoding.prefixLength)
    return CharLiteral(data, encoding)
  }

  /**
   * C standard: A.1.5, 6.4.4.4
   * @param s a possible float suffix string
   * @param nrLength the length of the float constant before the suffix
   * @see FloatingSuffix
   */
  private fun floatingSuffix(s: String, nrLength: Int): FloatingSuffix? = when {
    s.isEmpty() -> FloatingSuffix.NONE
    // Float looks like 123. or 123.23
    s[0].isWhitespace() || s[0] == '.' || isDigit(s[0]) -> FloatingSuffix.NONE
    // Float looks like 123.245E-10F
    s[0].toUpperCase() == 'F' && s.length == 1 -> FloatingSuffix.FLOAT
    // Float looks like 123.245E-10L
    s[0].toUpperCase() == 'L' && s.length == 1 -> FloatingSuffix.LONG_DOUBLE
    else -> {
      lexerDiagnostic {
        id = DiagnosticId.INVALID_SUFFIX
        formatArgs(s, "floating")
        columns(currentOffset + nrLength until currentOffset + nextWhitespaceOrPunct(s))
      }
      null
    }
  }

  // FIXME: missing hex floating constants
  /** C standard: A.1.5 */
  private fun floatingConstant(s: String): Token? {
    // Not a float: must start with either digit or dot
    if (!isDigit(s[0]) && s[0] != '.') return null
    // Not a float: just a dot
    if (s[0] == '.' && s.length == 1) return null
    // Not a float: character after dot must be either suffix or exponent
    if (s[0] == '.' && !isDigit(s[1]) && s[1].toUpperCase() !in listOf('E', 'F', 'L')) {
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
    if (integerPartEnd < dotIdx) lexerDiagnostic {
      id = DiagnosticId.INVALID_DIGIT
      messageFormatArgs = listOf(s[integerPartEnd + 1])
      column(currentOffset + integerPartEnd + 1)
    } else if (integerPartEnd > dotIdx) {
      logger.throwICE("Integer part of float contains whitespace or dot") {
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
    if (s[bonusIdx].toUpperCase() == 'E') {
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
        return FloatingConstant(float, FloatingSuffix.NONE, Radix.DECIMAL,
            exponentSign = sign, exponent = s.substring(exponentStartIdx))
      }
      val exponent = s.slice(exponentStartIdx until exponentStartIdx + exponentEndIdx)
      val totalLengthWithoutSuffix = float.length + 1 + signLen + exponent.length
      val tokenEnd = exponentStartIdx + exponentEndIdx +
          nextWhitespaceOrPunct(s.drop(exponentStartIdx + exponentEndIdx))
      if (exponent.isEmpty()) {
        lexerDiagnostic {
          id = DiagnosticId.NO_EXP_DIGITS
          column(currentOffset + exponentStartIdx)
        }
        return ErrorToken(tokenEnd)
      }
      val suffixStr = s.slice(exponentStartIdx + exponentEndIdx until tokenEnd)
      val suffix = floatingSuffix(suffixStr, totalLengthWithoutSuffix)
          ?: return ErrorToken(tokenEnd)
      return FloatingConstant(float, suffix, Radix.DECIMAL, sign, exponent)
    }
    val tokenEnd = bonusIdx + nextWhitespaceOrPunct(s.drop(bonusIdx))
    val suffix = floatingSuffix(s.slice(bonusIdx until tokenEnd), float.length)
        ?: return ErrorToken(tokenEnd)
    return FloatingConstant(float, suffix, Radix.DECIMAL)
  }

  /**
   * C standard: A.1.5, 6.4.4.1
   * @param s a possible integer suffix string
   * @param nrLength the length of the int constant before the suffix
   * @see IntegralSuffix
   */
  private fun integerSuffix(s: String, nrLength: Int): IntegralSuffix {
    if (s.isEmpty()) return IntegralSuffix.NONE
    val t = s.toUpperCase()
    val suffix = when {
      t.startsWith("ULL") || t.startsWith("LLU") -> IntegralSuffix.UNSIGNED_LONG_LONG
      t.startsWith("UL") || t.startsWith("LU") -> IntegralSuffix.UNSIGNED_LONG
      t.startsWith("LL") -> IntegralSuffix.LONG_LONG
      t.startsWith("L") -> IntegralSuffix.LONG
      t.startsWith("U") -> IntegralSuffix.UNSIGNED
      else -> IntegralSuffix.NONE
    }
    if (s.drop(suffix.length).isNotEmpty()) lexerDiagnostic {
      id = DiagnosticId.INVALID_SUFFIX
      formatArgs(s, "integer")
      columns(currentOffset + nrLength until currentOffset + nextWhitespaceOrPunct(s))
    }
    return suffix
  }

  private inline fun digitSequence(
      s: String,
      prefixLength: Int = 0,
      isValidDigit: (c: Char) -> Boolean): Pair<String, IntegralSuffix> {
    val nrWithSuffix = s.slice(prefixLength until nextWhitespaceOrPunct(s))
    val nrEndIdx = nrWithSuffix.indexOfFirst { !isValidDigit(it) }
    val nrText = nrWithSuffix.slice(0 until if (nrEndIdx == -1) nrWithSuffix.length else nrEndIdx)
    return Pair(nrText, integerSuffix(nrWithSuffix.drop(nrText.length), nrText.length))
  }

  /** C standard: A.1.5 */
  private fun integerConstant(s: String): IntegralConstant? {
    // Decimal numbers
    if (isDigit(s[0]) && s[0] != '0') {
      val c = digitSequence(s) { isDigit(it) }
      return IntegralConstant(c.first, c.second, Radix.DECIMAL)
    }
    // Hex numbers
    if ((s.startsWith("0x") || s.startsWith("0X") && isHexDigit(s[2]))) {
      val c = digitSequence(s, 2) { isHexDigit(it) }
      return IntegralConstant(c.first, c.second, Radix.HEXADECIMAL)
    }
    // Octal numbers
    if (s[0] == '0') {
      val c = digitSequence(s) { isOctalDigit(it) }
      return IntegralConstant(c.first, c.second, Radix.OCTAL)
    }
    return null
  }

  /**
   * C standard: A.1.3, A.1.4
   * @return an empty optional if the string is not an identifier, or the identifier otherwise
   */
  private fun identifier(s: String): Identifier? {
    // An identifier must start with a non-digit if it isn't a universal character name
    if (!isNonDigit(s[0])) return null
    // FIXME check for universal character names
    val idx = s.indexOfFirst { !isDigit(it) && !isNonDigit(it) }
    val ident = s.slice(0 until (if (idx == -1) s.length else idx))
    return Identifier(ident)
  }

  private tailrec fun tokenize() {
    src = src.trimStart {
      if (it.isWhitespace()) {
        currentOffset++
        return@trimStart true
      }
      return@trimStart false
    }
    if (src.isEmpty()) return

    // Comments
    if (src.startsWith("//")) {
      currentOffset += 2
      if (src.isEmpty()) return
      src = src.dropWhile {
        if (it == '\n') return@dropWhile false
        currentOffset++
        return@dropWhile true
      }
      return tokenize()
    } else if (src.startsWith("/*")) {
      currentOffset += 2
      if (src.isEmpty()) return
      var dropped = 0
      src = src.dropWhile {
        if (it == '*' && src[dropped + 1] == '/') return@dropWhile false
        dropped++
        return@dropWhile true
      }
      currentOffset += dropped
      if (src.isEmpty()) {
        // Unterminated comment
        lexerDiagnostic {
          id = DiagnosticId.UNFINISHED_COMMENT
          column(currentOffset)
        }
      } else {
        // Get rid of the */
        src = src.drop(2)
        currentOffset += 2
      }
      return tokenize()
    }

    // Ordering to avoid conflicts (ie avoid taking a keyword as an identifier):
    // keyword before identifier
    // characterConstant before identifier
    // stringLiteral before identifier
    // floatingConstant before integerConstant
    // floatingConstant before punct

    // Only consume one token in each iteration
    val tok =
        keyword(src)
            ?: floatingConstant(src)
            ?: integerConstant(src)
            ?: characterConstant(src)
            ?: stringLiteral(src)
            ?: identifier(src)
            ?: punct(src)
            ?: TODO("extraneous/unhandled thing")
    tok.startIdx = currentOffset
    tokens += tok
    currentOffset += tok.consumedChars
    src = src.drop(tok.consumedChars)
    return tokenize()
  }
}
