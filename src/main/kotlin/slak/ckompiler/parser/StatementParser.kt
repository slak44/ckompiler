package slak.ckompiler.parser

import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.*

interface IStatementParser {
  /**
   * Parses a compound-statement, including the { } brackets.
   *
   * C standard: A.2.3
   * @param functionScope the scope of the function for which this is a block, null otherwise
   * @return null if there is no compound statement, or the [CompoundStatement] otherwise
   */
  fun parseCompoundStatement(functionScope: LexicalScope? = null): Statement?
}

class StatementParser(declarationParser: DeclarationParser,
                      controlKeywordParser: ControlKeywordParser) :
    IStatementParser,
    IDebugHandler by declarationParser,
    ITokenHandler by declarationParser,
    IScopeHandler by declarationParser,
    IParenMatcher by declarationParser,
    IExpressionParser by declarationParser,
    IDeclarationParser by declarationParser,
    IControlKeywordParser by controlKeywordParser {

  /** @see [IStatementParser.parseCompoundStatement] */
  override fun parseCompoundStatement(functionScope: LexicalScope?): Statement? {
    if (current().asPunct() != Punctuators.LBRACKET) return null
    val rbracket = findParenMatch(Punctuators.LBRACKET, Punctuators.RBRACKET, false)
    eat() // Get rid of '{'
    if (rbracket == -1) {
      // Try to recover
      eatToSemi()
      if (!isEaten()) eat()
      return ErrorStatement()
    }
    fun parseCompoundItems(scope: LexicalScope): CompoundStatement {
      val items = mutableListOf<BlockItem>()
      while (!isEaten()) {
        val item = parseDeclaration()?.let { d -> DeclarationItem(d) }
            ?: parseStatement()?.let { s -> StatementItem(s) }
            ?: continue
        items.add(item)
      }
      return CompoundStatement(items, scope)
    }
    // Parsing the items happens both inside a lexical scope and inside a token context
    val compound = (functionScope ?: LexicalScope()).withScope {
      tokenContext(rbracket) { parseCompoundItems(this) }
    }
    eat() // Get rid of '}'
    return compound
  }


  /**
   * C standard: A.2.3, 6.8.1
   * @return the [LabeledStatement] if it is there, or null if there is no such statement
   */
  private fun parseLabeledStatement(): Statement? {
    // FIXME: this only parses the first kind of labeled statement (6.8.1)
    if (current() !is Identifier || relative(1).asPunct() != Punctuators.COLON) return null
    val label = IdentifierNode((current() as Identifier).name)
    newIdentifier(label, isLabel = true)
    eat() // Get rid of ident
    eat() // Get rid of ':'
    val labeled = parseStatement()
    if (labeled == null) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        errorOn(relative(-1))
      }
      return ErrorStatement()
    }
    return LabeledStatement(label, labeled)
  }

  /**
   * C standard: A.2.3, 6.8.4.1
   * @return the [IfStatement] if it is there, or null if it isn't
   */
  private fun parseIfStatement(): Statement? {
    if (current().asKeyword() != Keywords.IF) return null
    eat() // The 'if'
    val condParenEnd = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
    if (condParenEnd == -1) return ErrorStatement()
    eat() // The '(' from the if
    val condExpr = parseExpr(condParenEnd)
    val cond = if (condExpr == null) {
      // Eat everything between parens
      tokenContext(condParenEnd) {
        while (!isEaten()) eat()
      }
      ErrorExpression()
    } else {
      condExpr
    }
    eat() // The ')' from the if
    val statementSuccess = if (!isEaten() && current().asKeyword() == Keywords.ELSE) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        errorOn(safeToken(0))
      }
      ErrorStatement()
    } else {
      val statement = parseStatement()
      if (statement == null) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_STATEMENT
          errorOn(safeToken(0))
        }
        // Attempt to eat the error
        while (!isEaten() &&
            current().asPunct() != Punctuators.SEMICOLON &&
            current().asKeyword() != Keywords.ELSE) eat()
        ErrorStatement()
      } else {
        statement
      }
    }
    if (!isEaten() && current().asKeyword() == Keywords.ELSE) {
      eat() // The 'else'
      val elseStatement = parseStatement()
      val statementFailure = if (elseStatement == null) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_STATEMENT
          errorOn(safeToken(0))
        }
        // Eat until the next thing
        eatToSemi()
        if (!isEaten()) eat()
        ErrorStatement()
      } else {
        elseStatement
      }
      return IfStatement(cond, statementSuccess, statementFailure)
    } else {
      return IfStatement(cond, statementSuccess, null)
    }
  }

  /** Wraps [parseExpr] with a check for [Punctuators.SEMICOLON] at the end. */
  private fun parseExpressionStatement(): Expression? {
    val expr = parseExpr(tokenCount) ?: return null
    if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_SEMI_AFTER
        formatArgs("expression")
        column(colPastTheEnd(0))
      }
    } else {
      eat() // The semicolon
    }
    return expr
  }

  /**
   * C standard: 6.8.5
   *
   * @return null if there is no while, the [WhileStatement] otherwise
   */
  private fun parseWhile(): Statement? {
    if (current().asKeyword() != Keywords.WHILE) return null
    eat() // The WHILE
    if (isEaten() || current().asPunct() != Punctuators.LPAREN) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_LPAREN_AFTER
        formatArgs(Keywords.WHILE.keyword)
        errorOn(safeToken(0))
      }
      val end = indexOfFirst {
        it.asPunct() == Punctuators.LBRACKET || it.asPunct() == Punctuators.SEMICOLON
      }
      eatUntil(end)
      if (!isEaten() && current().asPunct() == Punctuators.SEMICOLON) eat()
      return ErrorStatement()
    }
    val rparen = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN, stopAtSemi = false)
    eat() // The '('
    if (rparen == -1) return ErrorStatement()
    val cond = parseExpr(rparen)
    val condition = if (cond == null) {
      // Eat everything between parens
      eatUntil(rparen)
      ErrorExpression()
    } else {
      cond
    }
    eat() // The ')'
    val statement = parseStatement()
    val loopable = if (statement == null) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        errorOn(safeToken(0))
      }
      // Attempt to eat the error
      eatToSemi()
      if (!isEaten()) eat()
      ErrorStatement()
    } else {
      statement
    }
    return WhileStatement(condition, loopable)
  }

  /**
   * C standard: 6.8.5
   *
   * @return null if there is no while, the [DoWhileStatement] otherwise
   */
  private fun parseDoWhile(): Statement? {
    if (current().asKeyword() != Keywords.DO) return null
    val theWhile = findKeywordMatch(Keywords.DO, Keywords.WHILE, stopAtSemi = false)
    eat() // The DO
    if (theWhile == -1) return ErrorStatement()
    val statement = tokenContext(theWhile) { parseStatement() }
    val loopable = if (statement == null) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        errorOn(safeToken(0))
      }
      // Attempt to eat the error
      val end = indexOfFirst {
        it.asPunct() == Punctuators.SEMICOLON || it.asKeyword() == Keywords.WHILE
      }
      if (end == -1) eatToSemi()
      eatUntil(end)
      ErrorStatement()
    } else {
      statement
    }
    eat() // The WHILE
    if (isEaten() || current().asPunct() != Punctuators.LPAREN) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_LPAREN_AFTER
        formatArgs(Keywords.WHILE.keyword)
        errorOn(safeToken(0))
      }
      eatToSemi()
      if (!isEaten()) eat()
      return DoWhileStatement(ErrorExpression(), loopable)
    }
    val condRParen = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
    if (condRParen == -1) return DoWhileStatement(ErrorExpression(), loopable)
    eat() // The '('
    val cond = parseExpr(condRParen)
    val condition = if (cond == null) {
      // Eat everything between parens
      eatUntil(condRParen)
      ErrorExpression()
    } else {
      cond
    }
    eat() // The ')'
    if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_SEMI_AFTER
        formatArgs("do/while statement")
        errorOn(safeToken(0))
      }
      eatToSemi()
      if (!isEaten()) eat()

    } else {
      eat() // The ';'
    }
    return DoWhileStatement(condition, loopable)
  }

  /**
   * C standard: 6.8.5, 6.8.5.3
   *
   * @return null if there is no while, the [ForStatement] otherwise
   */
  private fun parseFor(): Statement? {
    if (current().asKeyword() != Keywords.FOR) return null
    eat() // The FOR
    if (isEaten() || current().asPunct() != Punctuators.LPAREN) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_LPAREN_AFTER
        formatArgs(Keywords.FOR.keyword)
        errorOn(safeToken(0))
      }
      eatToSemi()
      if (!isEaten()) eat()
      return ErrorStatement()
    }
    val rparen = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN, stopAtSemi = false)
    eat() // The '('
    if (rparen == -1) return ErrorStatement()
    // The 3 components of a for loop
    val (clause1, expr2, expr3) = tokenContext(rparen) {
      val firstSemi = indexOfFirst { c -> c.asPunct() == Punctuators.SEMICOLON }
      if (firstSemi == -1) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_IN_FOR
          if (it.isNotEmpty()) errorOn(safeToken(it.size))
          else errorOn(parentContext()[rparen])
        }
        return@tokenContext Triple(ErrorInitializer(), ErrorExpression(), ErrorExpression())
      }
      // Handle the case where we have an empty clause1
      val clause1 = if (firstSemi == 0) {
        EmptyInitializer()
      } else {
        // parseDeclaration wants to see the semicolon as well, so +1
        tokenContext(firstSemi + 1) {
          parseDeclaration()?.let { d -> DeclarationInitializer(d) }
        } ?: parseExpr(firstSemi)?.let { e -> ExpressionInitializer(e) } ?: ErrorInitializer()
      }
      // We only eat the first ';' if parseDeclaration didn't do that
      if (!isEaten() && current().asPunct() == Punctuators.SEMICOLON) eat()
      val secondSemi = indexOfFirst { c -> c.asPunct() == Punctuators.SEMICOLON }
      if (secondSemi == -1) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_IN_FOR
          errorOn(safeToken(it.size))
        }
        return@tokenContext Triple(clause1, ErrorExpression(), ErrorExpression())
      }
      // Handle the case where we have an empty expr2
      val expr2 = if (secondSemi == firstSemi + 1) null else parseExpr(secondSemi)
      eat() // The second ';'
      // Handle the case where we have an empty expr3
      val expr3 =
          if (secondSemi + 1 == tokenCount) null else parseExpr(tokenCount)
      if (!isEaten()) {
        parserDiagnostic {
          id = DiagnosticId.UNEXPECTED_IN_FOR
          errorOn(safeToken(0))
        }
      }
      return@tokenContext Triple(clause1, expr2, expr3)
    }
    eatUntil(rparen)
    eat() // The ')'
    val loopable = parseStatement()
    if (loopable == null) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        errorOn(safeToken(0))
      }
      // Attempt to eat the error
      eatToSemi()
      if (!isEaten()) eat()
      return ForStatement(clause1, expr2, expr3, ErrorExpression())
    }
    return ForStatement(clause1, expr2, expr3, loopable)
  }

  /**
   * C standard: A.2.3
   * @return null if no statement was found, or the [Statement] otherwise
   */
  private fun parseStatement(): Statement? {
    if (isEaten()) return null
    if (current().asPunct() == Punctuators.SEMICOLON) {
      eat()
      return Noop()
    }
    return parseLabeledStatement()
        ?: parseCompoundStatement()
        ?: parseIfStatement()
        ?: parseGotoStatement()
        ?: parseWhile()
        ?: parseDoWhile()
        ?: parseFor()
        ?: parseContinue()
        ?: parseBreak()
        ?: parseReturn()
        ?: parseExpressionStatement()
        ?: TODO("unimplemented grammar")
  }
}