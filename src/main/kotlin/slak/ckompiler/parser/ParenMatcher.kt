package slak.ckompiler.parser

import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.*

interface IParenMatcher {
  /**
   * Find matching parenthesis in token list. Handles nested parens. Prints errors about unmatched
   * parens.
   * @param lparen the left paren: eg '(' or '[' or '{'
   * @param rparen the right paren: eg ')' or ']' or '}'
   * @param stopAtSemi whether or not to return -1 when hitting a semicolon
   * @return -1 if the parens are unbalanced or a [Punctuators.SEMICOLON] was found before they can
   * get balanced (and [stopAtSemi] is true), the size of the token stack if there were no parens,
   * or the (real) idx of the rightmost paren otherwise
   */
  fun findParenMatch(lparen: Punctuators, rparen: Punctuators, stopAtSemi: Boolean = true): Int

  /**
   * [findParenMatch] for [Keywords].
   * @see findParenMatch
   */
  fun findKeywordMatch(begin: Keywords, end: Keywords, stopAtSemi: Boolean = true): Int
}

class ParenMatcher(debugHandler: DebugHandler, tokenHandler: TokenHandler) :
    IParenMatcher, IDebugHandler by debugHandler, ITokenHandler by tokenHandler {

  /**
   * Generalization of [findParenMatch]. Even though that function calls through to this
   * one, the more general behaviour of this one is just the fact that it can accept both
   * [Punctuators] and [Keywords]. Otherwise, it works identically to [findParenMatch].
   * @see findParenMatch
   * @see findKeywordMatch
   */
  private inline fun <reified T, E> findMatch(start: E, final: E, stopAtSemi: Boolean): Int
      where T : StaticToken, E : StaticTokenEnum {
    var hasParens = false
    var stack = 0
    val end = indexOfFirst {
      if (it::class != T::class) return@indexOfFirst false
      it as StaticToken
      when (it.enum) {
        start -> {
          hasParens = true
          stack++
          return@indexOfFirst false
        }
        final -> {
          // FIXME: error here if stack is 0
          stack--
          return@indexOfFirst stack == 0
        }
        Punctuators.SEMICOLON -> return@indexOfFirst stopAtSemi
        else -> return@indexOfFirst false
      }
    }
    if (end == -1 && !hasParens) {
      // This is the case where there aren't any lparens until the end
      return tokenCount
    }
    if (!hasParens) {
      // This is the case where there aren't any lparens until a semicolon
      return end
    }
    if (end == -1 || (tokenAt(end) as StaticToken).enum != final) {
      diagnostic {
        id = DiagnosticId.UNMATCHED_PAREN
        formatArgs(final.realName)
        if (end == -1) {
          column(colPastTheEnd(tokenCount))
        } else {
          errorOn(tokenAt(end))
        }
      }
      diagnostic {
        id = DiagnosticId.MATCH_PAREN_TARGET
        formatArgs(start.realName)
        errorOn(safeToken(0))
      }
      return -1
    }
    return end
  }

  override fun findParenMatch(lparen: Punctuators, rparen: Punctuators, stopAtSemi: Boolean) =
      findMatch<Punctuator, Punctuators>(lparen, rparen, stopAtSemi)

  override fun findKeywordMatch(begin: Keywords, end: Keywords, stopAtSemi: Boolean) =
      findMatch<Keyword, Keywords>(begin, end, stopAtSemi)
}
