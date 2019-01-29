package slak.ckompiler.lexer

import mu.KotlinLogging
import slak.ckompiler.throwICE

private val logger = KotlinLogging.logger("Tokens")

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

  fun asBinaryOperator(): Operators? = Operators.binaryExprOps.find { it.op == this }
  fun asUnaryOperator(): Operators? = Operators.unaryOperators.find { it.op == this }
}

enum class Associativity { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

enum class Arity { UNARY, BINARY, TERNARY }

/**
 * This class is used in the expression parser. The list is not complete, and the standard does not
 * define these properties; they are derived from the grammar.
 * C standard: A.2.1
 */
enum class Operators(val op: Punctuators,
                     val precedence: Int,
                     val arity: Arity,
                     val assoc: Associativity) {
  // Unary
  REF(Punctuators.AMP, 100, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
  DEREF(Punctuators.STAR, 100, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
  PLUS(Punctuators.PLUS, 100, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
  MINUS(Punctuators.MINUS, 100, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
  BIT_NOT(Punctuators.TILDE, 100, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
  NOT(Punctuators.NOT, 100, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
  // Arithmetic
  MUL(Punctuators.STAR, 95, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  DIV(Punctuators.SLASH, 95, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  MOD(Punctuators.PERCENT, 95, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  ADD(Punctuators.PLUS, 90, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  SUB(Punctuators.MINUS, 90, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  // Bit-shift
  LSH(Punctuators.LSH, 80, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  RSH(Punctuators.RSH, 80, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  // Relational
  LT(Punctuators.LT, 70, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  GT(Punctuators.GT, 70, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  LEQ(Punctuators.LEQ, 70, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  GEQ(Punctuators.GEQ, 70, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  // Equality
  EQ(Punctuators.EQUALS, 60, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  NEQ(Punctuators.NEQUALS, 60, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  // Bitwise
  BIT_AND(Punctuators.AMP, 58, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  BIT_XOR(Punctuators.CARET, 54, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  BIT_OR(Punctuators.PIPE, 50, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  // Logical
  AND(Punctuators.AND, 45, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  OR(Punctuators.OR, 40, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  // Assignment
  ASSIGN(Punctuators.ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  MUL_ASSIGN(Punctuators.MUL_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  DIV_ASSIGN(Punctuators.DIV_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  MOD_ASSIGN(Punctuators.MOD_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  PLUS_ASSIGN(Punctuators.PLUS_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  SUB_ASSIGN(Punctuators.SUB_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  LSH_ASSIGN(Punctuators.LSH_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  RSH_ASSIGN(Punctuators.RSH_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  AND_ASSIGN(Punctuators.AND_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  XOR_ASSIGN(Punctuators.XOR_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  OR_ASSIGN(Punctuators.OR_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT);

  companion object {
    val binaryExprOps = Operators.values().filter { it.arity == Arity.BINARY }
    /** C standard: 6.5.3, A.2.1 */
    val unaryOperators = Operators.values().filter { it.arity == Arity.UNARY }
  }
}

fun Token.asBinaryOperator(): Operators? = asPunct()?.asBinaryOperator()
fun Token.asUnaryOperator(): Operators? = asPunct()?.asUnaryOperator()

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

enum class StringEncoding(val prefixLength: Int) {
  CHAR(0), UTF8(2), WCHAR_T(1), CHAR16_T(1), CHAR32_T(1)
}

enum class CharEncoding(val prefixLength: Int) {
  UNSIGNED_CHAR(0), WCHAR_T(1), CHAR16_T(1), CHAR32_T(1)
}

/** Represents a token from the lexical grammar. Is the output of the [Lexer]. */
sealed class Token(val consumedChars: Int) {
  companion object {
    const val INVALID_INDEX = -0x35
  }

  var startIdx: Int = INVALID_INDEX
    set(value) {
      if (value < 0) logger.throwICE("Bad starting idx") { "value: $value" }
      field = value
    }
    get() {
      if (field == INVALID_INDEX) {
        logger.throwICE("Trying to access invalid start index") { "token: $this" }
      }
      return field
    }

  val range: IntRange by lazy { startIdx until startIdx + consumedChars }

  init {
    if (consumedChars == 0) {
      logger.throwICE("Zero-length token created") { "token: $this" }
    } else if (consumedChars < 0) {
      logger.throwICE("Negative-length token created") { "token: $this" }
    }
  }
}

/**
 * Implementors represent preprocessing tokens.
 *
 * C standard: A.1.1
 */
interface PPToken {
  val consumedChars: Int
}

data class PPOther(val extraneous: Char): PPToken {
  init {
    if (extraneous.isWhitespace()) {
      logger.throwICE("PPOther is not allowed to be whitespace") { this }
    }
  }
  override val consumedChars = 1
}

/** C standard: A.1.8 */
data class HeaderName(val data: String, val kind: Char) : PPToken {
  override val consumedChars = data.length + 1
}

/** C standard: A.1.9 */
data class PPNumber(val data: String) : PPToken {
  override val consumedChars = data.length
}

class ErrorToken(consumedChars: Int) : Token(consumedChars), PPToken

sealed class StaticToken(consumedChars: Int) : Token(consumedChars) {
  abstract val enum: StaticTokenEnum
}

data class Keyword(val value: Keywords) : StaticToken(value.keyword.length) {
  override val enum: StaticTokenEnum get() = value
}

fun Token.asKeyword(): Keywords? = (this as? Keyword)?.value

data class Punctuator(val pct: Punctuators) : StaticToken(pct.s.length), PPToken {
  override val enum: StaticTokenEnum get() = pct
}

fun Token.asPunct(): Punctuators? = (this as? Punctuator)?.pct

data class Identifier(val name: String) : Token(name.length), PPToken

data class IntegralConstant(val n: String, val suffix: IntegralSuffix, val radix: Radix) :
    Token(radix.prefixLength + n.length + suffix.length) {
  override fun toString(): String = "${javaClass.simpleName}[$radix $n $suffix]"
}

data class FloatingConstant(val f: String,
                            val suffix: FloatingSuffix,
                            val radix: Radix,
                            val exponentSign: Char? = null,
                            val exponent: String = "") : Token(
    radix.prefixLength +
        f.length +
        (if (exponentSign == null) 0 else 1) +
        (if (exponent.isEmpty()) 0 else 1) +
        exponent.length +
        suffix.length) {
  init {
    if (radix == Radix.OCTAL) logger.throwICE("Octal floating constants are not supported") {}
    if (!f.any { isDigit(it) || it == '.' }) {
      logger.throwICE("Float is not just digits") { "token: $this" }
    }
    if (exponent.any { !isDigit(it) }) {
      logger.throwICE("Exp is not just digits") { "token: $this" }
    }
  }

  override fun toString(): String =
      "${javaClass.simpleName}[$radix $f ${exponentSign ?: "_"}" +
          "${if (exponent.isEmpty()) "_" else exponent} $suffix]"
}

sealed class CharSequence(dataLength: Int,
                          prefixLength: Int) : Token(prefixLength + dataLength + 2), PPToken

data class StringLiteral(
    val data: String,
    val encoding: StringEncoding) : CharSequence(data.length, encoding.prefixLength)

data class CharLiteral(
    val data: String,
    val encoding: CharEncoding) : CharSequence(data.length, encoding.prefixLength)
