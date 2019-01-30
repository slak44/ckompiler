package slak.ckompiler.lexer

import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler

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

fun keyword(s: String) = Keywords.values()
    .find { s.slice(0 until nextWhitespaceOrPunct(s)) == it.keyword }
    ?.let { Keyword(it) }

fun punct(s: String) =
    Punctuators.values().find { s.startsWith(it.s) }?.let { Punctuator(it) }

enum class IntegralSuffix(val length: Int) {
  UNSIGNED(1), LONG(1), LONG_LONG(2),
  UNSIGNED_LONG(2), UNSIGNED_LONG_LONG(3),
  NONE(0);

  override fun toString() = when (this) {
    IntegralSuffix.UNSIGNED -> "u"
    IntegralSuffix.LONG -> "l"
    IntegralSuffix.LONG_LONG -> "ll"
    IntegralSuffix.UNSIGNED_LONG -> "ul"
    IntegralSuffix.UNSIGNED_LONG_LONG -> "ull"
    IntegralSuffix.NONE -> "i"
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

enum class StringEncoding(val prefixLength: Int) {
  CHAR(0), UTF8(2), WCHAR_T(1), CHAR16_T(1), CHAR32_T(1)
}

enum class CharEncoding(val prefixLength: Int) {
  UNSIGNED_CHAR(0), WCHAR_T(1), CHAR16_T(1), CHAR32_T(1)
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
