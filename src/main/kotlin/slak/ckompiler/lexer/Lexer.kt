package slak.ckompiler.lexer

import org.apache.logging.log4j.LogManager
import slak.ckompiler.*

/**
 * Translation phase 3.
 *
 * C standard: 5.1.1.2.0.1.3
 */
class Lexer(debugHandler: DebugHandler, sourceText: String, srcFileName: SourceFileName) :
    IDebugHandler by debugHandler,
    ITextSourceHandler by TextSourceHandler(sourceText, srcFileName) {

  val ppTokens = mutableListOf<LexicalToken>()
  /**
   * Comments can also be found here, besides the whitespace.
   */
  val whitespaceBefore = mutableListOf<String>()
  private val currentWhitespace = StringBuilder()

  init {
    tokenize()
    if (whitespaceBefore.size != ppTokens.size) logger.throwICE("Size mismatch in Lexer")
  }

  /**
   * Finds regular header name tokens (ie not macro-replaced).
   * C standard: A.1.8, 6.4.0.4
   */
  private fun directHeaderName(s: String): LexicalToken? {
    // We are required by 6.4.0.4 to only recognize the `header-name` pp-token within #include or
    // #pragma directives
    val canBeHeaderName = ppTokens.size >= 2 &&
        ppTokens[ppTokens.size - 2] == Punctuator(Punctuators.HASH) &&
        (ppTokens[ppTokens.size - 1] == Identifier("include") ||
            ppTokens[ppTokens.size - 1] == Identifier("pragma"))
    if (!canBeHeaderName) return null
    return headerName(s)
  }

  /**
   * Reads and adds a single token to the [ppTokens] list (and the corresponding data to
   * [whitespaceBefore]).
   *
   * Unmatched ' or " are undefined behaviour, but [characterConstant] and [stringLiteral] try to
   * deal with them nicely.
   *
   * 6.4.8 and A.1.9 syntax suggest that numbers have a more general lexical grammar and don't
   * have a type or value until translation phase 7. We don't relax the grammar, and the numbers are
   * concrete from the beginning.
   *
   * We shouldn't (in theory) print all the diagnostics encountered before translation phase 4.
   * However, `if-section`s make sure this doesn't happen, and error directives eat the entire line
   * using a little hack in here. As a result, we get to ignore this requirement.
   *
   * C standard: A.1.1, 6.4.0.3, 6.4.8, A.1.9
   */
  private tailrec fun tokenize() {
    dropCharsWhile {
      if (it == '\n') {
        whitespaceBefore += currentWhitespace.toString()
        currentWhitespace.clear()
        ppTokens += NewLine
        return@dropCharsWhile true
      }
      currentWhitespace.append(it)
      return@dropCharsWhile it.isWhitespace()
    }
    if (currentSrc.isEmpty()) {
      currentWhitespace.clear()
      return
    }

    // Comments
    if (currentSrc.startsWith("//")) {
      currentWhitespace.append(dropCharsWhile { it != '\n' })
      return tokenize()
    } else if (currentSrc.startsWith("/*")) {
      currentWhitespace.append(dropCharsWhile {
        it != '*' || (currentOffset < currentSrc.length - 1 && currentSrc[currentOffset + 1] != '/')
      })
      // Unterminated comment
      if (currentSrc.isEmpty()) diagnostic {
        id = DiagnosticId.UNFINISHED_COMMENT
        column(currentOffset)
      } else {
        currentWhitespace.append("*/")
        // Get rid of the '*/'
        dropChars(2)
      }
      return tokenize()
    }

    // Ordering to avoid conflicts between token prefixes:
    // headerName before punct
    // headerName before stringLiteral
    // characterConstant before identifier
    // stringLiteral before identifier
    // floatingConstant before integerConstant
    // floatingConstant before punct

    val token = directHeaderName(currentSrc)
        ?: characterConstant(currentSrc, currentOffset)
        ?: stringLiteral(currentSrc, currentOffset)
        ?: floatingConstant(currentSrc, currentOffset)
        ?: integerConstant(currentSrc, currentOffset)
        ?: identifier(currentSrc)
        ?: punct(currentSrc)
        ?: logger.throwICE("Extraneous character")

    if (token is CharLiteral && token.data.isEmpty()) diagnostic {
      id = DiagnosticId.EMPTY_CHAR_CONSTANT
      columns(currentOffset..(currentOffset + 1))
    }

    ppTokens += token.withDebugData(srcFileName, originalSource, currentOffset)
    whitespaceBefore += currentWhitespace.toString()
    currentWhitespace.clear()
    dropChars(token.consumedChars)

    // Deal with error/pragma directives to avoid spurious diagnostics
    if (ppTokens.size >= 2 &&
        ppTokens[ppTokens.size - 1] in arrayOf(Identifier("error"), Identifier("pragma")) &&
        ppTokens[ppTokens.size - 2] == Punctuator(Punctuators.HASH)) {
      val errorMessage = dropCharsWhile { it != '\n' }
      if (errorMessage.isNotEmpty()) {
        ppTokens += Identifier(errorMessage)
            .withDebugData(srcFileName, originalSource, currentOffset - errorMessage.length)
        whitespaceBefore += ""
      }
    }

    return tokenize()
  }

  companion object {
    private val logger = LogManager.getLogger()
  }
}
