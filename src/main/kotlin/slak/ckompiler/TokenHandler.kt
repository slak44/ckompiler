package slak.ckompiler

import org.apache.logging.log4j.LogManager
import slak.ckompiler.lexer.LexicalToken

interface ITokenHandler {
  /**
   * Gets a token, or if all were eaten, the last one. Tries really hard to not fail.
   * Useful for diagnostics.
   */
  fun safeToken(offset: Int): LexicalToken

  /** Get the column just after the end of the token at [offset]. Useful for diagnostics. */
  fun colPastTheEnd(offset: Int): Int {
    val tok = safeToken(offset)
    return tok.startIdx + tok.consumedChars
  }

  /** Get a range of the current token. Useful for [slak.ckompiler.parser.ErrorNode]s or
   * [slak.ckompiler.parser.Terminal]s.
   */
  fun rangeOne(): SourcedRange = safeToken(0)

  /**
   * Creates a "sub-parser" context for a given list of tokens. However many elements are eaten in
   * the sub context will be eaten in the parent context too. Useful for parsing parenthesis and the
   * like.
   *
   * The list of tokens starts at the current index (inclusive), and ends at the
   * given [endIdx] (exclusive).
   */
  fun <T> tokenContext(endIdx: Int, block: (List<LexicalToken>) -> T): T

  /**
   * @param startIdx the context idx to start from (or -1 for the current idx)
   * @return the first context index matching the condition, or -1 if there is none
   */
  fun indexOfFirst(startIdx: Int, block: (LexicalToken) -> Boolean): Int

  /** @see indexOfFirst */
  fun indexOfFirst(block: (LexicalToken) -> Boolean): Int = indexOfFirst(-1, block)

  val tokenCount: Int
  val currentIdx: Int
  fun current(): LexicalToken = tokenAt(currentIdx)
  fun relative(offset: Int): LexicalToken
  fun tokenAt(contextIdx: Int): LexicalToken

  val tokensLeft: Int
  fun isEaten(): Boolean
  fun isNotEaten() = !isEaten()
  fun eat()
  fun eatUntil(contextIdx: Int)
}

class TokenHandler(tokens: List<LexicalToken>) : ITokenHandler {
  private val tokStack = mutableListOf<List<LexicalToken>>()
  private val idxStack = mutableListOf<Int>()

  init {
    tokStack += tokens
    idxStack += 0
  }

  private fun withOffset(offset: Int) = idxStack.last() + offset

  private fun isValidOffset(offset: Int): Boolean {
    return withOffset(offset) in 0 until tokenCount
  }

  override fun safeToken(offset: Int) = when {
    !isValidOffset(offset) && tokStack.last().isEmpty() -> tokStack[tokStack.size - 2].last()
    !isValidOffset(offset) -> tokStack.last().last()
    else -> tokStack.last()[withOffset(offset).coerceIn(0 until tokenCount)]
  }

  override fun <T> tokenContext(endIdx: Int, block: (List<LexicalToken>) -> T): T {
    val tokens = tokStack.last().subList(idxStack.last(), endIdx)
    tokStack += tokens
    idxStack += 0
    val result = block(tokens)
    tokStack.removeLast()
    val eatenInContext = idxStack.removeLast()
    idxStack[idxStack.size - 1] += eatenInContext
    return result
  }

  override fun indexOfFirst(startIdx: Int, block: (LexicalToken) -> Boolean): Int {
    val toDrop = if (startIdx == -1) idxStack.last() else startIdx
    val idx = tokStack.last().drop(toDrop).indexOfFirst(block)
    return if (idx == -1) -1 else idx + toDrop
  }

  override fun isEaten(): Boolean = idxStack.last() >= tokenCount

  override val currentIdx: Int get() = idxStack.last()

  override val tokenCount: Int get() = tokStack.last().size

  override val tokensLeft: Int get() = (tokStack.last().size - idxStack.last()).coerceAtLeast(0)

  override fun relative(offset: Int): LexicalToken = tokStack.last()[withOffset(offset)]

  override fun tokenAt(contextIdx: Int) = tokStack.last()[contextIdx]

  override fun eat() {
    idxStack[idxStack.size - 1] += 1
  }

  override fun eatUntil(contextIdx: Int) {
    val old = idxStack.removeLast()
    if (contextIdx < old) {
      logger.throwICE("Trying to eat tokens backwards") { "old=$old, contextIdx=$contextIdx" }
    }
    idxStack += if (contextIdx > tokenCount) tokenCount else contextIdx
  }

  companion object {
    private val logger = LogManager.getLogger()
  }
}
