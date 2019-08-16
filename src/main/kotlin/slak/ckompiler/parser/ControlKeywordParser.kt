package slak.ckompiler.parser

import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler
import slak.ckompiler.ITokenHandler
import slak.ckompiler.lexer.*

interface IControlKeywordParser {
  /**
   * C standard: A.2.3, 6.8.6.1
   *
   * @return null if there is no goto, or the [GotoStatement] otherwise
   */
  fun parseGotoStatement(): Statement?

  /**
   * C standard: A.2.3, 6.8.6.2
   *
   * @return null if there is no continue, the [ContinueStatement] otherwise
   */
  fun parseContinue(): ContinueStatement?

  /** C standard: A.2.3, 6.8.6.3 */
  fun parseBreak(): BreakStatement?

  /** C standard: A.2.3, 6.8.6.3 */
  fun parseReturn(): ReturnStatement?
}

class ControlKeywordParser(expressionParser: ExpressionParser) :
    IControlKeywordParser,
    IDebugHandler by expressionParser,
    ITokenHandler by expressionParser,
    IExpressionParser by expressionParser {
  override fun parseGotoStatement(): Statement? {
    if (current().asKeyword() != Keywords.GOTO) return null
    val gotoTok = current()
    eat()
    if (current() !is Identifier) {
      diagnostic {
        id = DiagnosticId.EXPECTED_IDENT
        errorOn(safeToken(0))
      }
      eatToSemi()
      if (isNotEaten()) eat()
      return error<ErrorStatement>()
    } else {
      val ident = IdentifierNode.from(current())
      eat() // The ident
      if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
        diagnostic {
          id = DiagnosticId.EXPECTED_SEMI_AFTER
          formatArgs("goto statement")
          column(colPastTheEnd(0))
        }
        eatToSemi()
        if (isNotEaten()) eatToSemi()
      } else {
        eat() // The ';'
      }
      return GotoStatement(ident).withRange(gotoTok..safeToken(0))
    }
  }

  override fun parseContinue(): ContinueStatement? {
    if (current().asKeyword() != Keywords.CONTINUE) return null
    val continueTok = current()
    eat()
    val semiTok: LexicalToken?
    if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
      diagnostic {
        id = DiagnosticId.EXPECTED_SEMI_AFTER
        formatArgs("continue statement")
        column(colPastTheEnd(0))
      }
      semiTok = null
    } else {
      semiTok = current()
      eat() // The ';'
    }
    return ContinueStatement().withRange(continueTok..(semiTok ?: continueTok))
  }

  override fun parseBreak(): BreakStatement? {
    if (current().asKeyword() != Keywords.BREAK) return null
    val breakTok = current()
    eat()
    val semiTok: LexicalToken?
    if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
      diagnostic {
        id = DiagnosticId.EXPECTED_SEMI_AFTER
        formatArgs("break statement")
        column(colPastTheEnd(0))
      }
      semiTok = null
    } else {
      semiTok = current()
      eat() // The ';'
    }
    return BreakStatement().withRange(breakTok..(semiTok ?: breakTok))
  }

  /* FIXME: make sure return type matches function
      stuff like this too:
      warning: void function 'g' should not return void expression */
  override fun parseReturn(): ReturnStatement? {
    val retKey = current()
    if (current().asKeyword() != Keywords.RETURN) return null
    eat() // The 'return'
    if (current().asPunct() == Punctuators.SEMICOLON) {
      val semi = current()
      eat() // The ';'
      return ReturnStatement(null).withRange(retKey..semi)
    }
    val semiIdx = indexOfFirst(Punctuators.SEMICOLON)
    val finalIdx = if (semiIdx == -1) tokenCount else semiIdx
    val expr = parseExpr(finalIdx)
    if (semiIdx == -1 || (isNotEaten() && current().asPunct() != Punctuators.SEMICOLON)) {
      diagnostic {
        id = DiagnosticId.EXPECTED_SEMI_AFTER
        formatArgs("return statement")
        column(colPastTheEnd(0))
      }
      return ReturnStatement(expr).withRange(retKey until safeToken(0))
    }
    eat() // The ';'
    return ReturnStatement(expr).withRange(retKey until safeToken(0))
  }
}