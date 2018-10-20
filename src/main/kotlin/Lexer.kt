import mu.KotlinLogging
import java.lang.IllegalStateException

private val logger = KotlinLogging.logger("Lexer")

sealed class Token(val consumedChars: Int) {
  init {
    if (consumedChars == 0) throw IllegalStateException("Lexer bug, zero-length token created")
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
  LBRACKET("{"), RBRACKET("}"), DOT("."), ARROW("->"),
  INC("++"), DEC("--"), AMP("&"), STAR("*"), PLUS("+"), MINUS("-"),
  TILDE("~"), NOT("!"),
  SLASH("/"), PERCENT("%"), LSH("<<"), RSH(">>"),
  LT("<"), GT(">"), LEQ("<="), GEQ(">="),
  EQUALS("=="), NEQUALS("!="), CARET("^"), PIPE("|"), AND("&&"), OR("||"),
  QMARK("?"), COLON(":"), SEMICOLON(";"), DOTS("..."),
  ASSIGN("="), MUL_ASSIGN("*="), DIV_ASSIGN("/="), MOD_ASSIGN("%="), PLUS_ASSIGN("+="),
  SUB_ASSIGN("-="), LSH_ASSIGN("<<="), RSH_ASSIGN(">>="),
  AND_ASSIGN("&="), XOR_ASSIGN("^="), OR_ASSIGN("|="),
  COMMA(","), HASH("#"), DOUBLE_HASH("##"),
  LESS_COLON("<:"), COLON_MORE(":>"), LESS_PERCENT("<%"), PERCENT_MORE("%>"),
  PERCENT_COLON("%:"), PERCENT_COLON_PERCENT_COLON("%:%:"),
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
fun nextWhitespaceOrPunct(s: String): Int {
  val idx = s.withIndex().indexOfFirst {
    it.value.isWhitespace() || (punct(s.drop(it.index)) !is Empty)
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

/**
 * C standard: A.1.3, A.1.4
 * @return an empty optional if the string is not an identifier, or the identifier otherwise
 */
fun identifier(s: String): Optional<Identifier> {
  // An identifier must start with a non-digit if it isn't a universal character name
  if (!isNonDigit(s[0])) return Empty()
  // FIXME check for universal character names
  val idx = s.indexOfFirst { !isDigit(it) && !isNonDigit(it) }
  val ident = s.slice(0 until (if (idx == -1) s.length else idx))
  return Identifier(ident).opt()
}

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

  override fun toString(): String {
    return "${javaClass.name}[$string $suffix]"
  }
}

enum class FloatingSuffix(val suffixLength: Int) {
  FLOAT(1), LONG_DOUBLE(1), NONE(0)
}

sealed class FloatingConstant(
    val string: String,
    val suffix: FloatingSuffix) : Constant(string.length + suffix.suffixLength) {
  class Decimal(s: String, suffix: FloatingSuffix) : FloatingConstant(s, suffix)
  class Hex(s: String, suffix: FloatingSuffix) : FloatingConstant(s, suffix)
}

/**
 * C standard: A.1.5
 */
fun floatingConstant(s: String): Optional<FloatingConstant> {
  val dotIdx = s.indexOfFirst { it == '.' }
  if (!s.slice(0 until dotIdx).all { isDigit(it) }) {
    TODO("error: non digits in floating number")
  }
  TODO("implement")
}

enum class StringEncoding(val prefixLength: Int) {
  CHAR(0), UTF8(2), WCHAR_T(1), CHAR16_T(1), CHAR32_T(1)
}

data class StringLiteral(val data: String,
                         val encoding: StringEncoding) : Token(encoding.prefixLength + data.length)

enum class CharEncoding(val prefixLength: Int) {
  UNSIGNED_CHAR(0), WCHAR_T(1), CHAR16_T(1), CHAR32_T(1)
}

data class CharConstant(
    val data: String,
    val encoding: CharEncoding) : Constant(data.length + 2 + encoding.prefixLength)

class Lexer(source: String, private val srcFile: SourceFile) {
  val tokens = mutableListOf<Token>()
  val inspections = mutableListOf<Inspection>()
  private var src: String = source
  private var currentOffset: Int = 0

  init {
    tokenizeRec()
  }

  private fun lexerInspection(build: InspectionBuilder.() -> Unit) {
    inspections.add(newInspection {
      sourceFile = srcFile
      origin = "Lexer"
      this.build()
    })
  }

  /** C standard: A.1.5, A.1.6, 6.4.4.4, 6.4.5 */
  private fun charSequence(s: String,
                           quoteChar: Char,
                           quoteInspectionId: InspectionId,
                           prefixLength: Int): String {
    val noPrefix = s.drop(1 + prefixLength)
    // FIXME implement escape sequences
    val stopIdx = noPrefix.indexOfFirst { it == '\n' || it == quoteChar }
    if (stopIdx == -1 || noPrefix[stopIdx] == '\n') lexerInspection {
      id = quoteInspectionId
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
    val data = charSequence(s, '"', InspectionId.MISSING_DOUBLE_QUOTE, encoding.prefixLength)
    return StringLiteral(data, encoding).opt()
  }

  /** C standard: A.1.5, 6.4.4.4 */
  private fun characterConstant(s: String): Optional<CharConstant> {
    val encoding = when {
      s.startsWith("'") -> CharEncoding.UNSIGNED_CHAR
      s.startsWith("L'") -> CharEncoding.WCHAR_T
      s.startsWith("u'") -> CharEncoding.CHAR16_T
      s.startsWith("U'") -> CharEncoding.CHAR32_T
      else -> return Empty()
    }
    val data = charSequence(s, '\'', InspectionId.MISSING_QUOTE, encoding.prefixLength)
    return CharConstant(data, encoding).opt()
  }

  /**
   * C standard: A.1.5
   * @param s a possible integer suffix string
   * @see IntegralSuffix
   */
  private fun integerSuffix(s: String): IntegralSuffix {
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
    if (s.drop(suffix.suffixLength).isNotEmpty()) lexerInspection {
      id = InspectionId.INVALID_SUFFIX
      messageFormatArgs = listOf(s)
      // FIXME: missing the char count of the nr itself here on both sides
      columns(currentOffset + suffix.suffixLength until currentOffset + s.length)
    }
    return suffix
  }

  private inline fun integerConstantImpl(s: String,
                                         prefixLength: Int = 0,
                                         isValidDigit: (c: Char) -> Boolean): Pair<String, IntegralSuffix> {
    val nrWithSuffix = s.slice(prefixLength until nextWhitespaceOrPunct(s))
    val nrEndIdx = nrWithSuffix.indexOfFirst { !isValidDigit(it) }
    val nrText = nrWithSuffix.slice(0 until if (nrEndIdx == -1) nrWithSuffix.length else nrEndIdx)
    return Pair(nrText, integerSuffix(nrWithSuffix.drop(nrText.length)))
  }

  /** C standard: A.1.5 */
  private fun integerConstant(s: String): Optional<IntegralConstant> {
    // Decimal numbers
    if (isDigit(s[0]) && s[0] != '0') {
      val c = integerConstantImpl(s) { isDigit(it) }
      return IntegralConstant.Decimal(c.first, c.second).opt()
    }
    // Hex numbers
    if ((s.startsWith("0x") || s.startsWith("0X") && isHexDigit(s[2]))) {
      val c = integerConstantImpl(s, 2) { isHexDigit(it) }
      return IntegralConstant.Hex(c.first, c.second).opt()
    }
    // Octal numbers
    if (s[0] == '0') {
      val c = integerConstantImpl(s) { isOctalDigit(it) }
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

  private tailrec fun tokenizeRec() {
    src = src.trimStart()
    if (src.isEmpty()) return
    // Only consume one of them in each iteration
    keyword(src).consumeIfPresent() ||
        integerConstant(src).consumeIfPresent() ||
        characterConstant(src).consumeIfPresent() ||
        stringLiteral(src).consumeIfPresent() ||
        identifier(src).consumeIfPresent() ||
        punct(src).consumeIfPresent()
    return tokenizeRec()
  }
}
