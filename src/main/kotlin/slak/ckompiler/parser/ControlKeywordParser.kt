package slak.ckompiler.parser

import slak.ckompiler.DiagnosticId
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
    eat()
    if (current() !is Identifier) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_IDENT
        errorOn(safeToken(0))
      }
      eatToSemi()
      if (isNotEaten()) eat()
      return ErrorStatement().withRange(rangeOne())
    } else {
      val ident = IdentifierNode((current() as Identifier).name).withRange(rangeOne())
      eat() // The ident
      if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_AFTER
          formatArgs("goto statement")
          column(colPastTheEnd(0))
        }
        eatToSemi()
        if (isNotEaten()) eatToSemi()
      } else {
        eat() // The ';'
      }
      return GotoStatement(ident).withRange(safeToken(-3) until safeToken(0))
    }
  }

  override fun parseContinue(): ContinueStatement? {
    if (current().asKeyword() != Keywords.CONTINUE) return null
    eat()
    if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_SEMI_AFTER
        formatArgs("continue statement")
        column(colPastTheEnd(0))
      }
    } else {
      eat() // The ';'
    }
    return ContinueStatement().withRange(safeToken(-2) until safeToken(0))
  }

  override fun parseBreak(): BreakStatement? {
    if (current().asKeyword() != Keywords.BREAK) return null
    eat()
    if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_SEMI_AFTER
        formatArgs("break statement")
        column(colPastTheEnd(0))
      }
    } else {
      eat() // The ';'
    }
    return BreakStatement().withRange(safeToken(-2) until safeToken(0))
  }

  override fun parseReturn(): ReturnStatement? {
    val retKey = current()
    if (current().asKeyword() != Keywords.RETURN) return null
    eat()
    val semiIdx = indexOfFirst { it.asPunct() == Punctuators.SEMICOLON }
    val finalIdx = if (semiIdx == -1) tokenCount else semiIdx
    val expr = parseExpr(finalIdx)
    if (semiIdx == -1 || (isNotEaten() && current().asPunct() != Punctuators.SEMICOLON)) {
      parserDiagnostic {
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