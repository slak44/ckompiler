import mu.KotlinLogging

private val logger = KotlinLogging.logger("Lexer")

sealed class Token(val consumedChars: Int) {
  init {
    if (consumedChars == 0) {
      logger.throwICE("Zero-length token created") { "token: $this" }
    }
  }
}

data class Keyword(val value: Keywords) : Token(value.keyword.length)
enum class Keywords(val keyword: String) {
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
  THREAD_LOCAL("_Thread_local")
}

fun keyword(s: String) =
    Keywords.values().find { s.startsWith(it.keyword) }?.let { Keyword(it) }.opt()

data class Punctuator(val punctuator: Punctuators) : Token(punctuator.punct.length)
enum class Punctuators(val punct: String) {
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
  PERCENT_COLON_PERCENT_COLON("%:%:"), PERCENT_COLON("%:"), PERCENT("%")
}

fun punct(s: String) =
    Punctuators.values().find { s.startsWith(it.punct) }?.let { Punctuator(it) }.opt()

/** C standard: A.1.3 */
fun isNonDigit(c: Char) = c == '_' || c in 'A'..'Z' || c in 'a'..'z'

/** C standard: A.1.3 */
fun isDigit(c: Char) = c in '0'..'9'

/** C standard: A.1.5 */
fun isHexDigit(c: Char) = isDigit(c) || c in 'A'..'F' || c in 'a'..'f'

/** C standard: A.1.5 */
fun isOctalDigit(c: Char) = c in '0'..'7'

/**
 * @returns the index of the first whitespace or [Punctuators] in the string, or the string length
 * if there isn't any.
 */
fun nextWhitespaceOrPunct(s: String, vararg excludeChars: Char): Int {
  val idx = s.withIndex().indexOfFirst {
    it.value !in excludeChars && (it.value.isWhitespace() || (punct(s.drop(it.index)) !is Empty))
  }
  return if (idx == -1) s.length else idx
}

/**
 * These names look like '\u1234' or '\U12341234', where the numbers are hex digits.
 *
 * C standard: A.1.4
 */
fun universalCharacterName(s: String): Char {
  if (s[0] == '\\') {
    if (s[1] == 'u') {
      val areDigits = s.slice(2 until 6).all { isHexDigit(it) }
      if (!areDigits) TODO("show error here")
      TODO("convert the hex-quad to a char")
    }
    if (s[1] == 'U') {
      val areDigits = s.slice(2 until 10).all { isHexDigit(it) }
      if (!areDigits) TODO("show error here")
      TODO("convert the 2 hex-quads to a char")
    }
    TODO("show error here, only u or U can come after \\ in an identifier")
  }
  TODO("not ucn")
}

data class Identifier(val name: String) : Token(name.length)

enum class IntegralSuffix(val suffixLength: Int) {
  UNSIGNED(1), LONG(1), LONG_LONG(2),
  UNSIGNED_LONG(2), UNSIGNED_LONG_LONG(3),
  NONE(0)
}

sealed class Constant(consumedChars: Int) : Token(consumedChars)

sealed class IntegralConstant(
    val string: String,
    val suffix: IntegralSuffix,
    prefixLength: Int = 0) : Constant(prefixLength + string.length + suffix.suffixLength) {
  class Decimal(s: String, suffix: IntegralSuffix) : IntegralConstant(s, suffix)
  class Hex(s: String, suffix: IntegralSuffix) : IntegralConstant(s, suffix, 2)
  class Octal(s: String, suffix: IntegralSuffix) : IntegralConstant(s, suffix)

  override fun hashCode() = 31 * string.hashCode() + suffix.hashCode()

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as IntegralConstant
    if (string != other.string || suffix != other.suffix) return false
    return true
  }

  override fun toString(): String = "${javaClass.simpleName}[$string $suffix]"
}

enum class FloatingSuffix(val suffixLength: Int) {
  FLOAT(1), LONG_DOUBLE(1), NONE(0)
}

sealed class FloatingConstant(
    val string: String,
    val suffix: FloatingSuffix) : Constant(string.length + suffix.suffixLength) {
  class Decimal(s: String, suffix: FloatingSuffix) : FloatingConstant(s, suffix)
  class Hex(s: String, suffix: FloatingSuffix) : FloatingConstant(s, suffix)

  override fun hashCode() = 31 * string.hashCode() + suffix.hashCode()

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as FloatingConstant
    if (string != other.string || suffix != other.suffix) return false
    return true
  }

  override fun toString(): String = "${javaClass.simpleName}[$string $suffix]"

}

sealed class CharSequence(dataLength: Int,
                          prefixLength: Int) : Constant(prefixLength + dataLength + 2)

enum class StringEncoding(val prefixLength: Int) {
  CHAR(0), UTF8(2), WCHAR_T(1), CHAR16_T(1), CHAR32_T(1)
}

data class StringLiteral(
    val data: String,
    val encoding: StringEncoding) : CharSequence(data.length, encoding.prefixLength)

enum class CharEncoding(val prefixLength: Int) {
  UNSIGNED_CHAR(0), WCHAR_T(1), CHAR16_T(1), CHAR32_T(1)
}

data class CharLiteral(
    val data: String,
    val encoding: CharEncoding) : CharSequence(data.length, encoding.prefixLength)

class Lexer(source: String, private val srcFile: SourceFile) {
  val tokens = mutableListOf<Token>()
  val inspections = mutableListOf<Diagnostic>()
  private var src: String = source
  private var currentOffset: Int = 0

  init {
    tokenize()
  }

  private fun lexerDiagnostic(build: DiagnosticBuilder.() -> Unit) {
    inspections.add(newDiagnostic {
      sourceFile = srcFile
      origin = "Lexer"
      this.build()
    })
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
      if (stopIdx != -1) column(currentOffset + stopIdx - 1)
    }
    return noPrefix.slice(0 until stopIdx)
  }

  /** C standard: A.1.6, 6.4.5 */
  private fun stringLiteral(s: String): Optional<StringLiteral> {
    val encoding = when {
      s.startsWith("\"") -> StringEncoding.CHAR
      s.startsWith("u8\"") -> StringEncoding.UTF8
      s.startsWith("L\"") -> StringEncoding.WCHAR_T
      s.startsWith("u\"") -> StringEncoding.CHAR16_T
      s.startsWith("U\"") -> StringEncoding.CHAR32_T
      else -> return Empty()
    }
    val data = charSequence(s, '"', encoding.prefixLength)
    return StringLiteral(data, encoding).opt()
  }

  /** C standard: A.1.5 */
  private fun characterConstant(s: String): Optional<CharLiteral> {
    val encoding = when {
      s.startsWith("'") -> CharEncoding.UNSIGNED_CHAR
      s.startsWith("L'") -> CharEncoding.WCHAR_T
      s.startsWith("u'") -> CharEncoding.CHAR16_T
      s.startsWith("U'") -> CharEncoding.CHAR32_T
      else -> return Empty()
    }
    val data = charSequence(s, '\'', encoding.prefixLength)
    return CharLiteral(data, encoding).opt()
  }

  /**
   * C standard: A.1.5, 6.4.4.4
   * @param s a possible float suffix string
   * @param nrLength the length of the float constant before the suffix
   * @see FloatingSuffix
   */
  private fun floatingSuffix(s: String, nrLength: Int): FloatingSuffix = when {
    s.isEmpty() -> FloatingSuffix.NONE
    // Float looks like 123. or 123.23
    s[0].isWhitespace() || s[0] == '.' || isDigit(s[0]) -> FloatingSuffix.NONE
    // Float looks like 123.245E-10F
    s[0].toUpperCase() == 'F' -> FloatingSuffix.FLOAT
    // Float looks like 123.245E-10L
    s[0].toUpperCase() == 'L' -> FloatingSuffix.LONG_DOUBLE
    else -> {
      lexerDiagnostic {
        id = DiagnosticId.INVALID_SUFFIX
        formatArgs(s[0], "floating")
        columns(currentOffset + nrLength until currentOffset + nextWhitespaceOrPunct(s))
      }
      FloatingSuffix.NONE
    }
  }

  /** C standard: A.1.5 */
  private fun floatingConstant(s: String): Optional<FloatingConstant> {
    // FIXME: missing hex floating constants
    val whitespaceOrDot = nextWhitespaceOrPunct(s)
    // Not a float: reached end of string and no dot fount
    if (whitespaceOrDot == s.length) return Empty()
    // Not a float: found something else before finding a dot
    if (s[whitespaceOrDot] != '.') return Empty()
    val integerPartEnd = s.slice(0..whitespaceOrDot).indexOfFirst { !isDigit(it) }
    if (integerPartEnd < whitespaceOrDot) lexerDiagnostic {
      id = DiagnosticId.INVALID_DIGIT
      messageFormatArgs = listOf(s[integerPartEnd + 1])
      column(currentOffset + integerPartEnd + 1)
    } else if (integerPartEnd > whitespaceOrDot) {
      logger.throwICE("Integer part of float contains whitespace or dot") {
        "integerPartEnd: $integerPartEnd, whitespaceOrDot: $whitespaceOrDot, lexer: $this"
      }
    }
    val floatLen = whitespaceOrDot + 1 +
        nextWhitespaceOrPunct(s.drop(whitespaceOrDot + 1), '+', '-')
    // Float has exponent
    if (s[floatLen - 1] == 'e' || s[floatLen - 1] == 'E') {
      val hasSign = s[floatLen] == '+' || s[floatLen] == '-'
      val expEndStartIdx = floatLen + if (hasSign) 1 else 0
      val expEnd = s.drop(expEndStartIdx).indexOfFirst { !isDigit(it) }
      val expEndIdx = expEndStartIdx + expEnd
      val suffix =
          if (expEnd == -1) FloatingSuffix.NONE else floatingSuffix(s.drop(expEndIdx), expEndIdx)
      val endOfFloat = (if (expEnd == -1) s.length else expEnd) - suffix.suffixLength
      return FloatingConstant.Decimal(s.slice(0 until endOfFloat), suffix).opt()
    }
    val suffix = floatingSuffix(s.drop(floatLen - 1), floatLen - 1)
    val float = s.slice(0 until (floatLen - suffix.suffixLength))
    // If the float is just a dot, it's not actually a float
    if (float == ".") return Empty()
    return FloatingConstant.Decimal(float, suffix).opt()
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
    if (s.drop(suffix.suffixLength).isNotEmpty()) lexerDiagnostic {
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
  private fun integerConstant(s: String): Optional<IntegralConstant> {
    // Decimal numbers
    if (isDigit(s[0]) && s[0] != '0') {
      val c = digitSequence(s) { isDigit(it) }
      return IntegralConstant.Decimal(c.first, c.second).opt()
    }
    // Hex numbers
    if ((s.startsWith("0x") || s.startsWith("0X") && isHexDigit(s[2]))) {
      val c = digitSequence(s, 2) { isHexDigit(it) }
      return IntegralConstant.Hex(c.first, c.second).opt()
    }
    // Octal numbers
    if (s[0] == '0') {
      val c = digitSequence(s) { isOctalDigit(it) }
      return IntegralConstant.Octal(c.first, c.second).opt()
    }
    return Empty()
  }

  private fun <T : Token> Optional<T>.consumeIfPresent(): Boolean {
    ifPresent {
      tokens.add(it)
      currentOffset += it.consumedChars
      src = src.drop(it.consumedChars)
      return true
    }
    return false
  }

  /**
   * C standard: A.1.3, A.1.4
   * @return an empty optional if the string is not an identifier, or the identifier otherwise
   */
  private fun identifier(s: String): Optional<Identifier> {
    // An identifier must start with a non-digit if it isn't a universal character name
    if (!isNonDigit(s[0])) return Empty()
    // FIXME check for universal character names
    val idx = s.indexOfFirst { !isDigit(it) && !isNonDigit(it) }
    val ident = s.slice(0 until (if (idx == -1) s.length else idx))
    return Identifier(ident).opt()
  }

  private tailrec fun tokenize() {
    src = src.trimStart()
    if (src.isEmpty()) return
    // Only consume one of them in each iteration (order of calls matters!)
    keyword(src).consumeIfPresent() ||
        floatingConstant(src).consumeIfPresent() ||
        integerConstant(src).consumeIfPresent() ||
        characterConstant(src).consumeIfPresent() ||
        stringLiteral(src).consumeIfPresent() ||
        identifier(src).consumeIfPresent() ||
        punct(src).consumeIfPresent()
    return tokenize()
  }
}
