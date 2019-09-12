package slak.ckompiler.parser

import slak.ckompiler.*
import slak.ckompiler.lexer.*

interface IStatementParser {
  /**
   * Parses a `compound-statement`, including the { } brackets.
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
    IExpressionParser by controlKeywordParser,
    IDeclarationParser by declarationParser,
    IControlKeywordParser by controlKeywordParser {

  /** @see [IStatementParser.parseCompoundStatement] */
  override fun parseCompoundStatement(functionScope: LexicalScope?): Statement? {
    val lbracket = current()
    if (current().asPunct() != Punctuators.LBRACKET) return null
    val rbracket = findParenMatch(Punctuators.LBRACKET, Punctuators.RBRACKET, stopAtSemi = false)
    eat() // Get rid of '{'
    if (rbracket == -1) {
      // Try to recover
      eatToSemi()
      if (isNotEaten()) eat()
      return error<ErrorStatement>()
    }
    fun parseCompoundItems(scope: LexicalScope): CompoundStatement {
      val items = mutableListOf<BlockItem>()
      while (isNotEaten()) {
        // Parse declarations after statements (if the first thing is an identifier, the
        // [SpecParser] gets confused)
        // Parse expressions after declarations (we get fake diagnostics about expecting a primary
        // expression, when the construct was actually just part of a [DeclarationSpecifier])
        val item = parseStatement(parseExpressionStatement = false)?.let(::StatementItem)
            ?: parseDeclaration(SpecValidationRules.NONE)?.let(::DeclarationItem)
            ?: parseExpressionStatement()?.let(::StatementItem)
            ?: continue
        items += item
      }
      return CompoundStatement(items, scope)
    }
    // Parsing the items happens both inside a lexical scope and inside a token context
    val compound = (functionScope ?: newScope()).withScope {
      tokenContext(rbracket) { parseCompoundItems(this) }
    }
    eat() // Get rid of '}'
    return compound.withRange(lbracket..tokenAt(rbracket))
  }

  /**
   * C standard: A.2.3, 6.8.1
   * @return the [LabeledStatement] if it is there, or null if there is no such statement
   */
  private fun parseLabeledStatement(): Statement? {
    val ident = current()
    // FIXME: this only parses the first kind of labeled statement (6.8.1)
    if (ident !is Identifier || relative(1).asPunct() != Punctuators.COLON) return null
    val label = IdentifierNode.from(ident)
    newLabel(label)
    eat() // Get rid of ident
    eat() // Get rid of ':'
    val labeled = parseStatement()
    if (labeled == null) {
      diagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        errorOn(relative(-1))
      }
      return error<ErrorStatement>()
    }
    return LabeledStatement(label, labeled).withRange(ident..labeled)
  }

  /**
   * C standard: A.2.3, 6.8.4.1
   * @return the [IfStatement] if it is there, or null if it isn't
   */
  private fun parseIfStatement(): Statement? {
    if (current().asKeyword() != Keywords.IF) return null
    val ifTok = current()
    eat() // The 'if'
    val condParenEnd = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
    if (condParenEnd == -1) return error<ErrorStatement>()
    eat() // The '(' from the if
    val condExpr = parseExpr(condParenEnd)
    val cond = if (condExpr == null) {
      // Eat everything between parens
      tokenContext(condParenEnd) {
        while (isNotEaten()) eat()
      }
      error<ErrorExpression>()
    } else {
      condExpr
    }
    eat() // The ')' from the if
    val statementSuccess = if (isNotEaten() && current().asKeyword() == Keywords.ELSE) {
      diagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        errorOn(safeToken(0))
      }
      error<ErrorStatement>()
    } else {
      val statement = parseStatement()
      if (statement == null) {
        diagnostic {
          id = DiagnosticId.EXPECTED_STATEMENT
          errorOn(safeToken(0))
        }
        // Attempt to eat the error
        while (isNotEaten() &&
            current().asPunct() != Punctuators.SEMICOLON &&
            current().asKeyword() != Keywords.ELSE) eat()
        error<ErrorStatement>()
      } else {
        statement
      }
    }
    if (isNotEaten() && current().asKeyword() == Keywords.ELSE) {
      eat() // The 'else'
      val elseStatement = parseStatement()
      val statementFailure = if (elseStatement == null) {
        diagnostic {
          id = DiagnosticId.EXPECTED_STATEMENT
          errorOn(safeToken(0))
        }
        // Eat until the next thing
        eatToSemi()
        if (isNotEaten()) eat()
        error<ErrorStatement>()
      } else {
        elseStatement
      }
      return IfStatement(cond, statementSuccess, statementFailure)
          .withRange(ifTok..statementFailure)
    } else {
      return IfStatement(cond, statementSuccess, null).withRange(ifTok..statementSuccess)
    }
  }

  /** Wraps [parseExpr] with a check for [Punctuators.SEMICOLON] at the end. */
  private fun parseExpressionStatement(): Expression? {
    val expr = parseExpr(tokenCount) ?: return null
    if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
      diagnostic {
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
    val whileTok = current()
    eat() // The WHILE
    if (isEaten() || current().asPunct() != Punctuators.LPAREN) {
      diagnostic {
        id = DiagnosticId.EXPECTED_LPAREN_AFTER
        formatArgs(Keywords.WHILE.keyword)
        errorOn(safeToken(0))
      }
      val end = indexOfFirst(Punctuators.LBRACKET, Punctuators.SEMICOLON)
      eatUntil(end)
      if (isNotEaten() && current().asPunct() == Punctuators.SEMICOLON) eat()
      return error<ErrorStatement>()
    }
    val rparen = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN, stopAtSemi = false)
    eat() // The '('
    if (rparen == -1) return error<ErrorStatement>()
    val cond = parseExpr(rparen)
    val condition = if (cond == null) {
      // Eat everything between parens
      eatUntil(rparen)
      error<ErrorExpression>()
    } else {
      cond
    }
    eat() // The ')'
    val statement = parseStatement()
    val loopable = if (statement == null) {
      diagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        errorOn(safeToken(0))
      }
      // Attempt to eat the error
      eatToSemi()
      if (isNotEaten()) eat()
      error<ErrorStatement>()
    } else {
      statement
    }
    return WhileStatement(condition, loopable).withRange(whileTok..loopable)
  }

  /**
   * C standard: 6.8.5
   *
   * @return null if there is no while, the [DoWhileStatement] otherwise
   */
  private fun parseDoWhile(): Statement? {
    if (current().asKeyword() != Keywords.DO) return null
    val doTok = current()
    val theWhile = findKeywordMatch(Keywords.DO, Keywords.WHILE, stopAtSemi = false)
    eat() // The DO
    if (theWhile == -1) return error<ErrorStatement>()
    val statement = tokenContext(theWhile) { parseStatement() }
    val loopable = if (statement == null) {
      diagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        errorOn(safeToken(0))
      }
      // Attempt to eat the error
      val end = indexOfFirst(Punctuators.SEMICOLON, Keywords.WHILE)
      if (end == -1) eatToSemi()
      eatUntil(end)
      error<ErrorStatement>()
    } else {
      statement
    }
    eat() // The WHILE
    if (isEaten() || current().asPunct() != Punctuators.LPAREN) {
      diagnostic {
        id = DiagnosticId.EXPECTED_LPAREN_AFTER
        formatArgs(Keywords.WHILE.keyword)
        errorOn(safeToken(0))
      }
      eatToSemi()
      if (isNotEaten()) eat()
      return DoWhileStatement(error<ErrorExpression>(), loopable)
    }
    val condRParen = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
    if (condRParen == -1) return DoWhileStatement(error<ErrorExpression>(), loopable)
    eat() // The '('
    val cond = parseExpr(condRParen)
    val condition = if (cond == null) {
      // Eat everything between parens
      eatUntil(condRParen)
      error<ErrorExpression>()
    } else {
      cond
    }
    eat() // The ')'
    if (isEaten() || current().asPunct() != Punctuators.SEMICOLON) {
      diagnostic {
        id = DiagnosticId.EXPECTED_SEMI_AFTER
        formatArgs("do/while statement")
        errorOn(safeToken(0))
      }
      eatToSemi()
      if (isNotEaten()) eat()

    } else {
      eat() // The ';'
    }
    return DoWhileStatement(condition, loopable).withRange(doTok..condition)
  }

  /**
   * Parses the stuff between the for's parenthesis.
   * @see parseFor
   */
  private fun parseForLoopInner(
      rparenIdx: Int,
      rparen: LexicalToken
  ): Triple<ForInitializer, Expression?, Expression?> = tokenContext(rparenIdx) {
    val firstSemi = firstOutsideParens(
        Punctuators.SEMICOLON, Punctuators.LBRACKET, Punctuators.RBRACKET, stopAtSemi = false)
    if (firstSemi == tokenCount) {
      diagnostic {
        id = DiagnosticId.EXPECTED_SEMI_IN_FOR
        if (it.isNotEmpty()) errorOn(safeToken(it.size))
        else errorOn(rparen)
      }
      return@tokenContext Triple(error<ErrorInitializer>(),
          error<ErrorExpression>(), error<ErrorExpression>())
    }
    // Handle the case where we have an empty clause1
    val clause1 = if (firstSemi == 0) {
      EmptyInitializer()
    } else {
      // parseDeclaration wants to see the semicolon as well, so +1
      tokenContext(firstSemi + 1) {
        parseDeclaration(SpecValidationRules.FOR_INIT_DECLARATION)?.let(::DeclarationInitializer)
      } ?: parseExpr(firstSemi)?.let(::ForExpressionInitializer) ?: error<ErrorInitializer>()
    }
    // We only eat the first ';' if parseDeclaration didn't do that
    // And when parseDeclaration did eat the semi, ensure that we don't accidentally eat again
    val parseDeclarationLeftSemi =
        firstSemi == 0 || safeToken(-1).asPunct() != Punctuators.SEMICOLON
    if (isNotEaten() && current().asPunct() == Punctuators.SEMICOLON && parseDeclarationLeftSemi) {
      eat()
    }
    val secondSemi = indexOfFirst(Punctuators.SEMICOLON)
    if (secondSemi == -1) {
      diagnostic {
        id = DiagnosticId.EXPECTED_SEMI_IN_FOR
        errorOn(safeToken(it.size))
      }
      return@tokenContext Triple(clause1, error<ErrorExpression>(), error<ErrorExpression>())
    }
    // Handle the case where we have an empty expr2
    val expr2 = if (secondSemi == firstSemi + 1) null else parseExpr(secondSemi)
    eat() // The second ';'
    // Handle the case where we have an empty expr3
    val expr3 =
        if (secondSemi + 1 == tokenCount) null else parseExpr(tokenCount)
    if (isNotEaten()) {
      diagnostic {
        id = DiagnosticId.UNEXPECTED_IN_FOR
        errorOn(safeToken(0))
      }
    }
    return@tokenContext Triple(clause1, expr2, expr3)
  }

  /**
   * C standard: 6.8.5, 6.8.5.3
   *
   * @return null if there is no while, the [ForStatement] otherwise
   */
  private fun parseFor(): Statement? {
    if (current().asKeyword() != Keywords.FOR) return null
    val forTok = current()
    eat() // The FOR
    if (isEaten() || current().asPunct() != Punctuators.LPAREN) {
      diagnostic {
        id = DiagnosticId.EXPECTED_LPAREN_AFTER
        formatArgs(Keywords.FOR.keyword)
        errorOn(safeToken(0))
      }
      eatToSemi()
      if (isNotEaten()) eat()
      return error<ErrorStatement>()
    }
    val rparen = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN, stopAtSemi = false)
    eat() // The '('
    if (rparen == -1) return error<ErrorStatement>()
    val forScope = newScope()
    // The 3 components of a for loop
    val (clause1, expr2, expr3) = forScope.withScope { parseForLoopInner(rparen, tokenAt(rparen)) }
    eatUntil(rparen)
    eat() // The ')'
    val loopable = forScope.withScope { parseStatement() }
    if (loopable == null) {
      diagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        errorOn(safeToken(0))
      }
      // Attempt to eat the error
      eatToSemi()
      if (isNotEaten()) eat()
      return ForStatement(clause1, expr2, expr3, error<ErrorExpression>(), forScope)
          .withRange(forTok until safeToken(0))
    }
    return ForStatement(clause1, expr2, expr3, loopable, forScope).withRange(forTok..loopable)
  }

  /**
   * C standard: A.2.3
   * @param parseExpressionStatement if false, this function will not parse expression statements
   * @return null if no statement was found, or the [Statement] otherwise
   */
  private fun parseStatement(parseExpressionStatement: Boolean = true): Statement? {
    if (isEaten()) return null
    if (current().asPunct() == Punctuators.SEMICOLON) {
      val n = Noop().withRange(rangeOne())
      eat()
      return n
    }
    val res = parseLabeledStatement()
        ?: parseCompoundStatement()
        ?: parseIfStatement()
        ?: parseGotoStatement()
        ?: parseWhile()
        ?: parseDoWhile()
        ?: parseFor()
        ?: parseContinue()
        ?: parseBreak()
        ?: parseReturn()
    if (res != null) return res
    return if (parseExpressionStatement) parseExpressionStatement() else null
  }
}