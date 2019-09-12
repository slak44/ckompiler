package slak.ckompiler.parser

import org.apache.logging.log4j.LogManager
import slak.ckompiler.*
import slak.ckompiler.lexer.*

private val logger = LogManager.getLogger()

interface IParenMatcher {
  /**
   * Find matching parenthesis in token list. Handles nested parens. Prints errors about unmatched
   * parens.
   * @param lparen the left paren: eg '(' or '[' or '{'
   * @param rparen the right paren: eg ')' or ']' or '}'
   * @param startIdx see [slak.ckompiler.ITokenHandler.indexOfFirst]'s startIdx
   * @param disableDiags don't generate diagnostics if true
   * @param stopAtSemi whether or not to return -1 when hitting a semicolon
   * @return -1 if the parens are unbalanced or a [Punctuators.SEMICOLON] was found before they can
   * get balanced (and [stopAtSemi] is true), the size of the token stack if there were no parens,
   * or the context idx of the rightmost paren otherwise
   */
  fun findParenMatch(lparen: Punctuators,
                     rparen: Punctuators,
                     startIdx: Int = -1,
                     disableDiags: Boolean = false,
                     stopAtSemi: Boolean = true): Int

  /**
   * [findParenMatch] for [Keywords].
   * @see findParenMatch
   */
  fun findKeywordMatch(begin: Keywords,
                       end: Keywords,
                       startIdx: Int = -1,
                       disableDiags: Boolean = false,
                       stopAtSemi: Boolean = true): Int

  /**
   * Gets the index of [target]'s first appearance, but ignore the ones found between parens.
   * For example, in `int x = f(1,2), y = 2;`, we might want the first comma, as it separates
   * declarators. However, commas also separate function arguments, and function calls are allowed
   * in initializers. So, we want to ignore the commas found in parens, and get the first one after.
   * @return the index of [target], or [TokenHandler.tokenCount] if none was found
   */
  fun firstOutsideParens(target: Punctuators,
                         lparen: Punctuators,
                         rparen: Punctuators,
                         stopAtSemi: Boolean): Int
}

class ParenMatcher(debugHandler: IDebugHandler, tokenHandler: ITokenHandler) :
    IParenMatcher, IDebugHandler by debugHandler, ITokenHandler by tokenHandler {

  /**
   * Generalization of [findParenMatch]. Even though that function calls through to this
   * one, the more general behaviour of this one is just the fact that it can accept both
   * [Punctuators] and [Keywords]. Otherwise, it works identically to [findParenMatch].
   * @see findParenMatch
   * @see findKeywordMatch
   */
  private inline fun <reified T, E> findMatch(start: E,
                                              final: E,
                                              startIdx: Int,
                                              disableDiags: Boolean,
                                              stopAtSemi: Boolean): Int
      where T : StaticToken, E : StaticTokenEnum {
    var hasParens = false
    var stack = 0
    val end = indexOfFirst(startIdx) {
      if (it::class != T::class) return@indexOfFirst false
      it as StaticToken
      when (it.enum) {
        start -> {
          hasParens = true
          stack++
          return@indexOfFirst false
        }
        final -> {
          stack--
          return@indexOfFirst stack <= 0
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
      if (!disableDiags) diagnostic {
        id = DiagnosticId.UNMATCHED_PAREN
        formatArgs(final.realName)
        if (end == -1) {
          column(colPastTheEnd(tokenCount))
        } else {
          errorOn(tokenAt(end))
        }
      }
      if (!disableDiags) diagnostic {
        id = DiagnosticId.MATCH_PAREN_TARGET
        formatArgs(start.realName)
        val unmatched = tokenAt(if (startIdx == -1) currentIdx else startIdx)
        if ((unmatched as? StaticToken)?.enum != start) {
          logger.throwICE("Token reported by MATCH_PAREN_TARGET is not the right one") {
            "expected: $start\nactual: $unmatched"
          }
        }
        errorOn(unmatched)
      }
      return -1
    }
    return end
  }

  override fun findParenMatch(lparen: Punctuators,
                              rparen: Punctuators,
                              startIdx: Int,
                              disableDiags: Boolean,
                              stopAtSemi: Boolean) =
      findMatch<Punctuator, Punctuators>(lparen, rparen, startIdx, disableDiags, stopAtSemi)

  override fun findKeywordMatch(begin: Keywords,
                                end: Keywords,
                                startIdx: Int,
                                disableDiags: Boolean,
                                stopAtSemi: Boolean) =
      findMatch<Keyword, Keywords>(begin, end, startIdx, disableDiags, stopAtSemi)

  override fun firstOutsideParens(target: Punctuators,
                                  lparen: Punctuators,
                                  rparen: Punctuators,
                                  stopAtSemi: Boolean): Int {
    val firstThingIdx = indexOfFirst(target, lparen)
    val parenEndIdx = findParenMatch(
        lparen = lparen,
        rparen = rparen,
        startIdx = firstThingIdx,
        disableDiags = true,
        stopAtSemi = stopAtSemi
    )
    return when {
      parenEndIdx == -1 -> {
        val targetIdx = indexOfFirst(target)
        if (targetIdx == -1) tokenCount else targetIdx
      }
      firstThingIdx == -1 -> tokenCount
      tokenAt(firstThingIdx).asPunct() == target -> firstThingIdx
      tokenAt(firstThingIdx).asPunct() == lparen -> {
        val targetIdx = indexOfFirst(parenEndIdx) { it.asPunct() == target }
        if (targetIdx == -1) tokenCount else targetIdx
      }
      else -> tokenCount
    }
  }
}
