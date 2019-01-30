package slak.ckompiler.lexer

import slak.ckompiler.*

class Lexer(textSource: String, srcFileName: SourceFileName) :
    IDebugHandler by DebugHandler("Lexer", srcFileName, textSource),
    ITextSourceHandler by TextSourceHandler(textSource, srcFileName) {
  val tokens = mutableListOf<Token>()

  init {
    tokenize()
    diags.forEach { it.print() }
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
    if (integerPartEnd < dotIdx) diagnostic {
      id = DiagnosticId.INVALID_DIGIT
      formatArgs(s[integerPartEnd + 1])
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
            Exponent(s.substring(exponentStartIdx), sign))
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
      val suffix = floatingSuffix(suffixStr, totalLengthWithoutSuffix)
          ?: return ErrorToken(tokenEnd)
      return FloatingConstant(float, suffix, Radix.DECIMAL, Exponent(exponent, sign))
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
    if (s.drop(suffix.length).isNotEmpty()) diagnostic {
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

  private tailrec fun tokenize() {
    // Ignore whitespace
    dropCharsWhile(Char::isWhitespace)
    if (currentSrc.isEmpty()) return

    // Comments
    if (currentSrc.startsWith("//")) {
      dropCharsWhile { it != '\n' }
      return tokenize()
    } else if (currentSrc.startsWith("/*")) {
      dropCharsWhile { it != '*' || currentSrc[currentOffset + 1] != '/' }
      if (currentSrc.isEmpty()) {
        // Unterminated comment
        diagnostic {
          id = DiagnosticId.UNFINISHED_COMMENT
          column(currentOffset)
        }
      } else {
        // Get rid of the '*/'
        dropChars(2)
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
        keyword(currentSrc)
            ?: floatingConstant(currentSrc)
            ?: integerConstant(currentSrc)
            ?: characterConstant(currentSrc, currentOffset)
            ?: stringLiteral(currentSrc, currentOffset)
            ?: identifier(currentSrc)
            ?: punct(currentSrc)
            ?: TODO("extraneous/unhandled thing")
    tok.startIdx = currentOffset
    tokens += tok
    dropChars(tok.consumedChars)
    return tokenize()
  }
}
