package slak.ckompiler.lexer

import slak.ckompiler.DebugHandler
import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler
import slak.ckompiler.SourceFileName

class Preprocessor(sourceText: String, srcFileName: SourceFileName) :
    IDebugHandler by DebugHandler("Preprocessor", srcFileName, sourceText),
    ITextSourceHandler by TextSourceHandler(sourceText, srcFileName) {

  private val ppTokens = mutableListOf<PPToken>()
  val alteredSourceText: String

  init {
    getPPTokens()
    alteredSourceText = sourceText // FIXME
  }

  /** C standard: A.1.8, 6.4.0.4 */
  private fun headerName(s: String): PPToken? {
    // We are required by 6.4.0.4 to only recognize the `header-name` pp-token within #include or
    // #pragma directives
    val canBeHeaderName = ppTokens.size > 1 &&
        ppTokens[ppTokens.size - 2] == Punctuator(Punctuators.HASH) &&
        (ppTokens[ppTokens.size - 1] == Identifier("include") ||
            ppTokens[ppTokens.size - 1] == Identifier("pragma"))
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
      return ErrorToken(if (endIdx == -1) s.length else 1 + endIdx)
    }
    return HeaderName(s.slice(1 until endIdx), quote)
  }

  /** C standard: A.1.9 */
  private fun ppNumber(s: String): PPNumber? {
    if (s[0] != '.' && !isDigit(s[0])) return null
    if (s[0] == '.' && (s.length < 2 || !isDigit(s[1]))) return null
    // FIXME: this is technically non-conforming
    val endIdx = s.indexOfFirst {
      it != '.' && !isDigit(it) && !isNonDigit(it) && it != '+' && it != '-'
    }
    val realEndIdx = if (endIdx == -1) s.length else endIdx
    return PPNumber(s.slice(0 until realEndIdx))
  }

  /**
   * [PPToken] search order lifted from the standard.
   * Unmatched ' or " are undefined behaviour.
   *
   * C standard: A.1.1, 6.4.0.3
   */
  private tailrec fun getPPTokens() {
    dropCharsWhile(Char::isWhitespace)
    if (currentSrc.isEmpty()) return
    val token = headerName(currentSrc) ?:
        identifier(currentSrc) ?:
        ppNumber(currentSrc) ?:
        characterConstant(currentSrc, currentOffset) ?:
        stringLiteral(currentSrc, currentOffset) ?:
        punct(currentSrc) ?:
        PPOther(currentSrc[0])
    ppTokens += token
    dropChars(token.consumedChars)
    return getPPTokens()
  }
}
