package slak.ckompiler.lexer

import slak.ckompiler.SourceFileName

/**
 * Useful for delegation.
 * @see TextSourceHandler
 */
interface ITextSourceHandler {
  /** The file name (or some other descriptor) that the [originalSource] came from. */
  val srcFileName: SourceFileName

  /** Initial text to handle. */
  val originalSource: String

  /** The text from the [originalSource] left unhandled. */
  val currentSrc: String

  /** The offset inside the [originalSource] that the [currentSrc] starts at. */
  val currentOffset: Int

  /** Advances the [currentOffset] and updates the [currentSrc]. */
  fun dropChars(count: Int)

  /**
   * For each character that satisfies the [dropIfTrue] predicate, advance the [currentOffset] by 1.
   * The [currentOffset] is updated between [dropIfTrue] calls.
   * The [currentSrc] is _unchanged_ until this function returns, at which point it is updated to
   * reflect the new offset.
   * The return value represents the dropped chars.
   */
  fun dropCharsWhile(dropIfTrue: (Char) -> Boolean): String
}

/**
 * Handles a string that is being currently parsed. Remembers how much was parsed, how much is left,
 * and allows advancing through the text.
 */
class TextSourceHandler(
    override val originalSource: String,
    override val srcFileName: SourceFileName,
) : ITextSourceHandler {
  override var currentSrc: String = originalSource
  override var currentOffset: Int = 0

  override fun dropChars(count: Int) {
    currentOffset += count
    currentSrc = currentSrc.drop(count)
  }

  override fun dropCharsWhile(dropIfTrue: (Char) -> Boolean): String {
    val sb = StringBuilder()
    currentSrc = currentSrc.dropWhile {
      val wasCharDropped = dropIfTrue(it)
      if (wasCharDropped) {
        currentOffset++
        sb.append(it)
      }
      return@dropWhile wasCharDropped
    }
    return sb.toString()
  }
}
