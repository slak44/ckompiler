package slak.ckompiler.lexer

import mu.KotlinLogging
import slak.ckompiler.throwICE

private val logger = KotlinLogging.logger("Tokens")

/** Implementors are some kind of token from some grammar. */
sealed class TokenObject(val consumedChars: Int) {
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

  companion object {
    const val INVALID_INDEX = -0x35 // Arbitrary negative value
  }
}

/** Represents a token from the lexical grammar. Is the output of the [Lexer]. */
sealed class LexicalToken(consumedChars: Int) : TokenObject(consumedChars)

class ErrorToken(consumedChars: Int) : LexicalToken(consumedChars)

sealed class StaticToken(consumedChars: Int) : LexicalToken(consumedChars) {
  abstract val enum: StaticTokenEnum
}

data class Keyword(val value: Keywords) : StaticToken(value.keyword.length) {
  override val enum: StaticTokenEnum get() = value
}

fun LexicalToken.asKeyword(): Keywords? = (this as? Keyword)?.value

data class Punctuator(val pct: Punctuators) : StaticToken(pct.s.length) {
  override val enum: StaticTokenEnum get() = pct

  fun toPPToken() = PPPunctuator(pct)
}

fun LexicalToken.asPunct(): Punctuators? = (this as? Punctuator)?.pct

data class Identifier(val name: String) : LexicalToken(name.length) {
  fun toPPToken() = PPIdentifier(name)
}

data class IntegralConstant(val n: String, val suffix: IntegralSuffix, val radix: Radix) :
    LexicalToken(radix.prefixLength + n.length + suffix.length) {
  override fun toString(): String = "${javaClass.simpleName}[$radix $n $suffix]"
}

data class Exponent(val exponent: String, val exponentSign: Char? = null) {
  init {
    if (exponent.any { !isDigit(it) }) {
      logger.throwICE("Exponent is not just digits") { "token: $this" }
    }
  }

  val length = (if (exponentSign == null) 0 else 1) + 1 + exponent.length

  override fun toString() = "E${exponentSign ?: "_"}$exponent"
}

data class FloatingConstant(val f: String,
                            val suffix: FloatingSuffix,
                            val radix: Radix,
                            val exponent: Exponent? = null) :
    LexicalToken(radix.prefixLength + f.length + suffix.length + (exponent?.length ?: 0)) {
  init {
    if (radix == Radix.OCTAL) logger.throwICE("Octal floating constants are not supported") {}
    if (!f.any { isDigit(it) || it == '.' }) {
      logger.throwICE("Float is not just digits") { "token: $this" }
    }
  }

  override fun toString() = "${javaClass.simpleName}[$radix $f ${exponent ?: '_'} $suffix]"
}

sealed class CharSequence(dataLength: Int, prefixLength: Int) :
    LexicalToken(prefixLength + dataLength + 2)

data class StringLiteral(val data: String, val encoding: StringEncoding) :
    CharSequence(data.length, encoding.prefixLength) {
  fun toPPToken() = PPStringLiteral(data, encoding)
}

data class CharLiteral(val data: String, val encoding: CharEncoding) :
    CharSequence(data.length, encoding.prefixLength) {
  fun toPPToken() = PPCharLiteral(data, encoding)
}

/**
 * Represents a preprocessing token.
 *
 * C standard: A.1.1
 */
sealed class PPToken(consumedChars: Int) : TokenObject(consumedChars)

data class PPOther(val extraneous: Char) : PPToken(1) {
  init {
    if (extraneous.isWhitespace()) {
      logger.throwICE("PPOther is not allowed to be whitespace") { this }
    }
  }
}

/** C standard: A.1.8 */
data class HeaderName(val data: String, val kind: Char) : PPToken(data.length + 1)

/** C standard: A.1.9 */
data class PPNumber(val data: String) : PPToken(data.length)

/** @see Punctuator */
data class PPPunctuator(val pct: Punctuators) : PPToken(pct.s.length)

/** @see Identifier */
data class PPIdentifier(val name: String) : PPToken(name.length)

/** @see ErrorToken */
class ErrorPPToken(consumedChars: Int) : PPToken(consumedChars)

/** @see CharSequence */
sealed class PPCharSequence(dataLength: Int, prefixLength: Int) :
    PPToken(prefixLength + dataLength + 2)

/** @see StringLiteral */
data class PPStringLiteral(val data: String, val encoding: StringEncoding) :
    PPCharSequence(data.length, encoding.prefixLength)

/** @see CharLiteral */
data class PPCharLiteral(val data: String, val encoding: CharEncoding) :
    PPCharSequence(data.length, encoding.prefixLength)
