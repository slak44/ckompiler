package slak.ckompiler

import slak.ckompiler.lexer.*

class Preprocessor(sourceText: String, srcFileName: SourceFileName) {
  private val debugHandler = DebugHandler("Preprocessor", srcFileName, sourceText)
  val diags = debugHandler.diags
  val alteredSourceText: String

  init {
    val l = PPLexer(debugHandler, sourceText, srcFileName)
//    val p = PPParser(TokenHandler(l.ppTokens, debugHandler))
    alteredSourceText = sourceText // FIXME
    diags.forEach { it.print() }
  }
}

private class PPParser(tokenHandler: TokenHandler<PPToken>) :
    IDebugHandler by tokenHandler,
    ITokenHandler<PPToken> by tokenHandler {
  // FIXME
}

private class PPLexer(debugHandler: DebugHandler, sourceText: String, srcFileName: SourceFileName) :
    IDebugHandler by debugHandler,
    ITextSourceHandler by TextSourceHandler(sourceText, srcFileName) {

  private val ppTokens = mutableListOf<PPToken>()

  val tokenSequence = generateSequence {
    if (!tokenize()) return@generateSequence null
    else return@generateSequence ppTokens.last()
  }

  /** C standard: A.1.8, 6.4.0.4 */
  private fun headerName(s: String): PPToken? {
    // We are required by 6.4.0.4 to only recognize the `header-name` pp-token within #include or
    // #pragma directives
    val canBeHeaderName = ppTokens.size > 1 &&
        ppTokens[ppTokens.size - 2] == PPPunctuator(Punctuators.HASH) &&
        (ppTokens[ppTokens.size - 1] == PPIdentifier("include") ||
            ppTokens[ppTokens.size - 1] == PPIdentifier("pragma"))
    if (!canBeHeaderName) return null
    val quote = s[0]
    if (quote != '<' && quote != '"') return null
    val otherQuote = if (s[0] == '<') '>' else '"'
    val endIdx = s.drop(1).indexOfFirst { it == '\n' || it == otherQuote }
    if (endIdx == -1 || s[1 + endIdx] == '\n') {
      diagnostic {
        id = DiagnosticId.EXPECTED_H_Q_CHAR_SEQUENCE
        formatArgs(quote, otherQuote)
        column(if (endIdx == -1) currentOffset + s.length else currentOffset + 1 + endIdx)
      }
      return ErrorPPToken(if (endIdx == -1) s.length else 1 + endIdx)
    }
    return HeaderName(s.slice(1 until endIdx), quote)
  }

  /** C standard: A.1.9 */
  private fun ppNumber(s: String): PPNumber? {
    if (s[0] != '.' && !isDigit(s[0])) return null
    if (s[0] == '.' && (s.length < 2 || !isDigit(s[1]))) return null
    // FIXME: this is technically non-conforming (a massive corner is cut here)
    val endIdx = s.indexOfFirst {
      it != '.' && !isDigit(it) && !isNonDigit(it) && it != '+' && it != '-'
    }
    val realEndIdx = if (endIdx == -1) s.length else endIdx
    return PPNumber(s.slice(0 until realEndIdx))
  }

  /**
   * Reads and adds a single [PPToken] to the [ppTokens] list.
   * [PPToken] search order lifted from the standard.
   * Unmatched ' or " are undefined behaviour.
   *
   * C standard: A.1.1, 6.4.0.3
   * @return false if there are no more tokens, true otherwise
   */
  private fun tokenize(): Boolean {
    dropCharsWhile(Char::isWhitespace)
    if (currentSrc.isEmpty()) return false
    val token = headerName(currentSrc) ?:
        identifier(currentSrc)?.toPPToken() ?:
        ppNumber(currentSrc) ?:
        characterConstant(currentSrc, currentOffset)?.toPPToken() ?:
        stringLiteral(currentSrc, currentOffset)?.toPPToken() ?:
        punct(currentSrc)?.toPPToken() ?: PPOther(currentSrc[0])

    if (token is PPCharLiteral && token.data.isEmpty()) diagnostic {
      id = DiagnosticId.EMPTY_CHAR_CONSTANT
      columns(currentOffset..(currentOffset + 1))
    }
    token.startIdx = currentOffset
    ppTokens += token
    dropChars(token.consumedChars)
    dropCharsWhile(Char::isWhitespace)
    return true
  }
}
