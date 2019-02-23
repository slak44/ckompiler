package slak.ckompiler

import slak.ckompiler.lexer.TokenObject
import java.util.*

interface ITokenHandler<Token : TokenObject> {
  /**
   * Gets a token, or if all were eaten, the last one. Tries really hard to not fail.
   * Useful for diagnostics.
   */
  fun safeToken(offset: Int): Token

  /** Get the column just after the end of the token at [offset]. Useful for diagnostics. */
  fun colPastTheEnd(offset: Int): Int {
    val tok = safeToken(offset)
    return tok.startIdx + tok.consumedChars
  }

  /** Get a range of the current token. Useful for [slak.ckompiler.parser.ErrorNode]s or
   * [slak.ckompiler.parser.Terminal]s.
   */
  fun rangeOne() = safeToken(0).range

  fun parentContext(): List<Token>

  /**
   * Creates a "sub-parser" context for a given list of tokens. However many elements are eaten in
   * the sub context will be eaten in the parent context too. Useful for parsing parenthesis and the
   * like.
   *
   * The list of tokens starts at the current index (inclusive), and ends at the
   * given [endIdx] (exclusive).
   */
  fun <T> tokenContext(endIdx: Int, block: (List<Token>) -> T): T

  /** @return the first (real) index matching the condition, or -1 if there is none */
  fun indexOfFirst(block: (Token) -> Boolean): Int

  val tokenCount: Int
  fun current(): Token
  fun relative(offset: Int): Token
  fun tokenAt(contextIdx: Int): Token

  fun isEaten(): Boolean
  fun isNotEaten() = !isEaten()
  fun eat()
  fun eatUntil(contextIdx: Int)
}

class TokenHandler<Token : TokenObject>(tokens: List<Token>, debugHandler: DebugHandler) :
    ITokenHandler<Token>,
    IDebugHandler by debugHandler {
  private val tokStack = Stack<List<Token>>()
  private val idxStack = Stack<Int>()

  init {
    tokStack.push(tokens)
    idxStack.push(0)
  }

  private fun withOffset(offset: Int) = idxStack.peek() + offset

  private fun isValidOffset(offset: Int): Boolean {
    return withOffset(offset) in 0 until tokenCount
  }

  override fun safeToken(offset: Int) = when {
    !isValidOffset(offset) && tokStack.peek().isEmpty() -> parentContext().last()
    !isValidOffset(offset) -> tokStack.peek().last()
    else -> tokStack.peek()[withOffset(offset).coerceIn(0 until tokenCount)]
  }

  override fun <T> tokenContext(endIdx: Int, block: (List<Token>) -> T): T {
    val tokens = tokStack.peek().subList(idxStack.peek(), endIdx)
    tokStack.push(tokens)
    idxStack.push(0)
    val result = block(tokens)
    tokStack.pop()
    val eatenInContext = idxStack.pop()
    idxStack.push(idxStack.pop() + eatenInContext)
    return result
  }

  override fun parentContext(): List<Token> = tokStack[tokStack.size - 2]

  override fun indexOfFirst(block: (Token) -> Boolean): Int {
    val idx = tokStack.peek().drop(idxStack.peek()).indexOfFirst(block)
    return if (idx == -1) -1 else idx + idxStack.peek()
  }

  override fun isEaten(): Boolean = idxStack.peek() >= tokenCount

  override val tokenCount: Int get() = tokStack.peek().size

  override fun current(): Token = tokStack.peek()[idxStack.peek()]

  override fun relative(offset: Int): Token = tokStack.peek()[withOffset(offset)]

  override fun tokenAt(contextIdx: Int) = tokStack.peek()[contextIdx]

  override fun eat() {
    idxStack.push(idxStack.pop() + 1)
  }

  override fun eatUntil(contextIdx: Int) {
    val old = idxStack.pop()
    if (contextIdx < old) {
      logger.throwICE("Trying to eat tokens backwards") { "old=$old, contextIdx=$contextIdx" }
    }
    idxStack.push(contextIdx)
  }
}
