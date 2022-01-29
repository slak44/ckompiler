package slak.ckompiler.lexer

import mu.KotlinLogging
import slak.ckompiler.SourceFileName
import slak.ckompiler.SourcedRange
import slak.ckompiler.throwICE
import kotlin.js.JsExport

private val logger = KotlinLogging.logger {}

/**
 * Represents a token from the lexical grammar.
 * @param consumedChars how many characters long was the token in the SOURCE (ie the data in the
 * token might appear shorter or longer than it was in the source)
 */
@JsExport
sealed class LexicalToken(val consumedChars: Int) : SourcedRange {
  override var sourceFileName: SourceFileName? = null
  override var sourceText: String? = null
  override var expandedName: String? = null
  override var expandedFrom: SourcedRange? = null
  override var range: IntRange = 0..0

  /**
   * At what index within the source the token can be found.
   */
  var startIdx: Int = INVALID_INDEX
    private set(value) {
      if (value < 0) logger.throwICE("Bad starting idx") { "value: $value" }
      field = value
      range = startIdx until startIdx + consumedChars
    }
    get() {
      if (field == INVALID_INDEX) {
        logger.throwICE("Trying to access invalid start index") { "token: $this" }
      }
      return field
    }

  fun withDebugData(
      srcFileName: SourceFileName,
      sourceText: String,
      startIdx: Int,
      expandedName: String? = null,
      expandedFrom: SourcedRange? = null,
  ): LexicalToken {
    this.startIdx = startIdx
    this.sourceFileName = srcFileName
    this.sourceText = sourceText
    this.expandedName = expandedName
    this.expandedFrom = expandedFrom
    return this
  }

  fun copyDebugFrom(other: LexicalToken) = withDebugData(
      other.sourceFileName!!,
      other.sourceText!!,
      other.startIdx,
      other.expandedName,
      other.expandedFrom
  )

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
  override fun toString(): String = "${this::class.simpleName}[$radix $n $suffix]"

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

data class FloatingConstant(
    val f: String,
    val suffix: FloatingSuffix,
    val radix: Radix,
    val exponent: Exponent? = null,
) :
    LexicalToken(radix.prefixLength + f.length + suffix.length + (exponent?.length ?: 0)) {
  init {
    if (radix == Radix.OCTAL) logger.throwICE("Octal floating constants are not supported")
    if (!f.any { isDigit(it) || it == '.' }) {
      logger.throwICE("Float is not just digits") { "token: $this" }
    }
  }

  override fun toString() = "${this::class.simpleName}[$radix $f ${exponent ?: '_'} $suffix]"
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
    val realLength: Int = data.length,
) : CharSequence(realLength, encoding.prefix.length) {
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
    val realLength: Int = data.length,
) : CharSequence(realLength, encoding.prefix.length) {
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
