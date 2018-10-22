import mu.KotlinLogging

private val logger = KotlinLogging.logger("Lexer")

sealed class Token(val consumedChars: Int) {
  init {
    if (consumedChars == 0) {
      logger.throwICE("Zero-length token created") { "token: $this" }
    }
  }
}

class ErrorToken(consumedChars: Int) : Token(consumedChars)

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

data class Identifier(val name: String) : Token(name.length)

enum class IntegralSuffix(val length: Int) {
  UNSIGNED(1), LONG(1), LONG_LONG(2),
  UNSIGNED_LONG(2), UNSIGNED_LONG_LONG(3),
  NONE(0)
}

enum class Radix(val prefixLength: Int) {
  DECIMAL(0), OCTAL(0), HEXADECIMAL(2)
}

data class IntegralConstant(val n: String, val suffix: IntegralSuffix, val radix: Radix) :
    Token(radix.prefixLength + n.length + suffix.length) {
  override fun toString(): String = "${javaClass.simpleName}[$radix $n $suffix]"
}

enum class FloatingSuffix(val length: Int) {
  FLOAT(1), LONG_DOUBLE(1), NONE(0)
}

data class FloatingConstant(val f: String, val suffix: FloatingSuffix, val radix: Radix) :
    Token(radix.prefixLength + f.length + suffix.length) {
  init {
    if (radix == Radix.OCTAL) logger.throwICE("Octal floating constants are not supported") {}
  }

  override fun toString(): String = "${javaClass.simpleName}[$radix $f $suffix]"
}

sealed class CharSequence(dataLength: Int,
                          prefixLength: Int) : Token(prefixLength + dataLength + 2)

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
    s[0].toUpperCase() == 'F' && s.length == 1 -> FloatingSuffix.FLOAT
    // Float looks like 123.245E-10L
    s[0].toUpperCase() == 'L' && s.length == 1 -> FloatingSuffix.LONG_DOUBLE
    else -> {
      lexerDiagnostic {
        id = DiagnosticId.INVALID_SUFFIX
        formatArgs(s[0], "floating")
        columns(currentOffset + nrLength until currentOffset + nextWhitespaceOrPunct(s))
      }
      FloatingSuffix.NONE
    }
  }

  // FIXME: missing hex floating constants
  /** C standard: A.1.5 */
  private fun floatingConstant(s: String): Optional<Token> {
    // Not a float: must start with either digit or dot
    if (!isDigit(s[0]) && s[0] != '.') return Empty()
    // Not a float: just a dot
    if (s[0] == '.' && s.length == 1) return Empty()
    // Not a float: character after dot must be either suffix or exponent
    if (s[0] == '.' && !isDigit(s[1]) && s[1].toUpperCase() !in listOf('E', 'F', 'L')) {
      return Empty()
    }
    val whitespaceOrPunct = nextWhitespaceOrPunct(s)
    // Not a float: reached end of string and no dot fount
    if (whitespaceOrPunct == s.length) return Empty()
    // Not a float: found something else before finding a dot
    if (s[whitespaceOrPunct] != '.') return Empty()
    // Not a float: found non-digit(s) before dot
    if (s.slice(0 until whitespaceOrPunct).any { !isDigit(it) }) return Empty()
    val integerPartEnd = s.slice(0..whitespaceOrPunct).indexOfFirst { !isDigit(it) }
    if (integerPartEnd < whitespaceOrPunct) lexerDiagnostic {
      id = DiagnosticId.INVALID_DIGIT
      messageFormatArgs = listOf(s[integerPartEnd + 1])
      column(currentOffset + integerPartEnd + 1)
    } else if (integerPartEnd > whitespaceOrPunct) {
      logger.throwICE("Integer part of float contains whitespace or dot") {
        "integerPartEnd: $integerPartEnd, whitespaceOrDot: $whitespaceOrPunct, lexer: $this"
      }
    }
    val floatLen = whitespaceOrPunct + 1 +
        nextWhitespaceOrPunct(s.drop(whitespaceOrPunct + 1), '+', '-')
    // Float has exponent
    if (s[floatLen - 1] == 'e' || s[floatLen - 1] == 'E') {
      val fracPart = s.slice(whitespaceOrPunct + 1 until floatLen - 1)
      val firstNonDigit = fracPart.indexOfFirst { !isDigit(it) }
      if (firstNonDigit != -1) {
        lexerDiagnostic {
          id = DiagnosticId.INVALID_DIGIT
          formatArgs(fracPart)
          column(currentOffset + whitespaceOrPunct + 1 + firstNonDigit)
        }
        return ErrorToken(floatLen).opt()
      }
      val hasSign = s[floatLen] == '+' || s[floatLen] == '-'
      val expEndStartIdx = floatLen + if (hasSign) 1 else 0
      val expEnd = s.drop(expEndStartIdx).indexOfFirst { !isDigit(it) }
      val expEndIdx = expEndStartIdx + expEnd
      val suffix =
          if (expEnd == -1) FloatingSuffix.NONE else floatingSuffix(s.drop(expEndIdx), expEndIdx)
      val endOfFloat = (if (expEnd == -1) s.length else expEnd) - suffix.length
      return FloatingConstant(s.slice(0 until endOfFloat), suffix, Radix.DECIMAL).opt()
    }
    val idxBeforeSuffix = s.slice(0 until floatLen).indexOfLast { isDigit(it) || it == '.' }
    val suffix = floatingSuffix(s.slice(idxBeforeSuffix + 1 until floatLen), idxBeforeSuffix + 1)
    val float = s.slice(0 until (floatLen - suffix.length))
    // If the float is just a dot, it's not actually a float
    if (float == ".") return Empty()
    return FloatingConstant(float, suffix, Radix.DECIMAL).opt()
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
  private fun integerConstant(s: String): Optional<IntegralConstant> {
    // Decimal numbers
    if (isDigit(s[0]) && s[0] != '0') {
      val c = digitSequence(s) { isDigit(it) }
      return IntegralConstant(c.first, c.second, Radix.DECIMAL).opt()
    }
    // Hex numbers
    if ((s.startsWith("0x") || s.startsWith("0X") && isHexDigit(s[2]))) {
      val c = digitSequence(s, 2) { isHexDigit(it) }
      return IntegralConstant(c.first, c.second, Radix.HEXADECIMAL).opt()
    }
    // Octal numbers
    if (s[0] == '0') {
      val c = digitSequence(s) { isOctalDigit(it) }
      return IntegralConstant(c.first, c.second, Radix.OCTAL).opt()
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
    // Ordering:
    // keyword before identifier
    // floatingConstant before integerConstant
    // characterConstant before identifier
    // stringLiteral before identifier
    // floatingConstant before punct

    // Only consume one in each iteration (short-circuits with || if consumed)
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
