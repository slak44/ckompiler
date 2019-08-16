package slak.ckompiler.lexer

import org.apache.logging.log4j.LogManager
import slak.ckompiler.SourceFileName
import slak.ckompiler.throwICE

private val logger = LogManager.getLogger("Tokens")

/**
 * Represents a token from the lexical grammar.
 * @param consumedChars how many characters long was the token in the SOURCE (ie the data in the
 * token might it appear shorter or longer than it was in the source)
 */
sealed class LexicalToken(val consumedChars: Int) {
  /**
   * Where the token came from.
   */
  var srcFileName: SourceFileName? = null

  /**
   * At what index within the source the token can be found.
   */
  var startIdx: Int = INVALID_INDEX
    private set(value) {
      if (field != INVALID_INDEX) logger.throwICE("Trying to overwrite token startIdx") {
        "field: $field, value: $value"
      }
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

  fun withStartIdx(idx: Int): LexicalToken {
    startIdx = idx
    return this
  }

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
}

fun LexicalToken.asPunct(): Punctuators? = (this as? Punctuator)?.pct

data class Identifier(val name: String) : LexicalToken(name.length)

data class IntegralConstant(val n: String, val suffix: IntegralSuffix, val radix: Radix) :
    LexicalToken(radix.prefixLength + n.length + suffix.length) {
  override fun toString(): String = "${javaClass.simpleName}[$radix $n $suffix]"

  companion object {
    fun zero() = IntegralConstant("0", IntegralSuffix.NONE, Radix.DECIMAL)
    fun one() = IntegralConstant("1", IntegralSuffix.NONE, Radix.DECIMAL)
  }
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
    if (radix == Radix.OCTAL) logger.throwICE("Octal floating constants are not supported")
    if (!f.any { isDigit(it) || it == '.' }) {
      logger.throwICE("Float is not just digits") { "token: $this" }
    }
  }

  override fun toString() = "${javaClass.simpleName}[$radix $f ${exponent ?: '_'} $suffix]"
}

/**
 * @param realLength used for [LexicalToken.consumedChars], may be higher than data length
 */
sealed class CharSequence(realLength: Int, prefixLength: Int) :
    LexicalToken(prefixLength + realLength + 2)

/**
 * [realLength] is not included in [equals] because it is only relevant to the lexer, and it would
 * break tests that create literals synthetically.
 */
data class StringLiteral(
    val data: String,
    val encoding: StringEncoding,
    val realLength: Int = data.length
) : CharSequence(realLength, encoding.prefixLength) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is StringLiteral) return false

    if (data != other.data) return false
    if (encoding != other.encoding) return false

    return true
  }

  override fun hashCode(): Int {
    var result = data.hashCode()
    result = 31 * result + encoding.hashCode()
    return result
  }
}

/** @see StringLiteral */
data class CharLiteral(
    val data: String,
    val encoding: CharEncoding,
    val realLength: Int = data.length
) : CharSequence(realLength, encoding.prefixLength) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is CharLiteral) return false

    if (data != other.data) return false
    if (encoding != other.encoding) return false

    return true
  }

  override fun hashCode(): Int {
    var result = data.hashCode()
    result = 31 * result + encoding.hashCode()
    return result
  }
}

/** C standard: A.1.8 */
data class HeaderName(val data: String, val kind: Char) : LexicalToken(data.length + 2)

/** This exists because newlines are significant for preprocessing. */
object NewLine : LexicalToken(1)
