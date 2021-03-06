package slak.ckompiler.parser

import slak.ckompiler.*
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
   * @param isInLoop prints diagnostic if false
   * @return null if there is no continue, the [ContinueStatement] otherwise
   */
  fun parseContinue(isInLoop: Boolean): ContinueStatement?

  /**
   * Print diagnostic if neither of [isInLoop] and [isInSwitch] is true.
   *
   * C standard: A.2.3, 6.8.6.3
   */
  fun parseBreak(isInSwitch: Boolean, isInLoop: Boolean): BreakStatement?

  /**
   * Print diagnostic if the returned value does not match [expectedType].
   *
   * C standard: A.2.3, 6.8.6.3
   */
  fun parseReturn(expectedType: TypeName, funcName: String): ReturnStatement?
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

  override fun parseContinue(isInLoop: Boolean): ContinueStatement? {
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
    if (!isInLoop) diagnostic {
      id = DiagnosticId.CONTINUE_OUTSIDE_LOOP
      errorOn(continueTok)
    }
    return ContinueStatement().withRange(continueTok..(semiTok ?: continueTok))
  }

  override fun parseBreak(isInSwitch: Boolean, isInLoop: Boolean): BreakStatement? {
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
    if (!isInLoop && !isInSwitch) diagnostic {
      id = DiagnosticId.BREAK_OUTSIDE_LOOP_SWITCH
      errorOn(breakTok)
    }
    return BreakStatement().withRange(breakTok..(semiTok ?: breakTok))
  }

  override fun parseReturn(expectedType: TypeName, funcName: String): ReturnStatement? {
    val retKey = current()
    if (current().asKeyword() != Keywords.RETURN) return null
    eat() // The 'return'
    if (current().asPunct() == Punctuators.SEMICOLON) {
      val semi = current()
      eat() // The ';'
      validateReturnValue(retKey, null, expectedType, funcName)
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
    } else {
      eat() // The ';'
    }
    val ret = validateReturnValue(retKey, expr, expectedType, funcName)
    return ReturnStatement(ret).withRange(retKey until safeToken(0))
  }
}