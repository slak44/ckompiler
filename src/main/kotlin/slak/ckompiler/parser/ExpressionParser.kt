package slak.ckompiler.parser

import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.*

interface IExpressionParser {
  /**
   * Parses an expression. Eats it.
   * C standard: A.2.1
   * @return null if there is no expression, the [Expression] otherwise
   */
  fun parseExpr(endIdx: Int): Expression?
}

class ExpressionParser(scopeHandler: ScopeHandler, parenMatcher: ParenMatcher) :
    IExpressionParser,
    IDebugHandler by parenMatcher,
    ITokenHandler by parenMatcher,
    IScopeHandler by scopeHandler,
    IParenMatcher by parenMatcher {

  /** @see IExpressionParser.parseExpr */
  override fun parseExpr(endIdx: Int): Expression? = tokenContext(endIdx) {
    val primary: Expression = parsePrimaryExpr().let { expr ->
      if (expr != null) return@let expr
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_PRIMARY
        errorOn(safeToken(1))
      }
      return@tokenContext null
    }
    return@tokenContext parseExprImpl(primary, 0)
  }

  private fun errorExpr() = ErrorExpression().withRange(rangeOne())

  private fun parseExprImpl(lhsInit: Expression, minPrecedence: Int): Expression {
    var lhs = lhsInit
    while (true) {
      if (isEaten()) break
      val op = current().asBinaryOperator() ?: break
      if (op.precedence < minPrecedence) break
      eat()
      var rhs: Expression = parsePrimaryExpr().let {
        if (it != null) return@let it
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_PRIMARY
          errorOn(safeToken(0))
        }
        return@parseExprImpl errorExpr()
      }
      while (true) {
        if (isEaten()) break
        val innerOp = current().asBinaryOperator() ?: break
        if (innerOp.precedence <= op.precedence &&
            !(innerOp.assoc == Associativity.RIGHT_TO_LEFT &&
                innerOp.precedence == op.precedence)) {
          break
        }
        rhs = parseExprImpl(rhs, innerOp.precedence)
      }
      lhs = BinaryExpression(op, lhs, rhs)
          .withRange(lhs.tokenRange.start..rhs.tokenRange.endInclusive)
    }
    return lhs
  }

  private fun parseBaseExpr(): Expression? = when {
    // FIXME: implement generic-selection (A.2.1/6.5.1.1)
    current().asPunct() == Punctuators.RPAREN -> {
      // This usually happens when there are unmatched parens
      eat()
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_EXPR
        errorOn(safeToken(0))
      }
      errorExpr()
    }
    current().asPunct() == Punctuators.LPAREN -> {
      if (relative(1).asPunct() == Punctuators.RPAREN) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_EXPR
          errorOn(safeToken(1))
        }
        errorExpr()
      } else {
        val endParenIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
        if (endParenIdx == -1) {
          eatToSemi()
          errorExpr()
        } else {
          eat() // Get rid of the LPAREN
          val expr = parseExpr(endParenIdx)
          eat() // Get rid of the RPAREN
          expr
        }
      }
    }
    else -> parseTerminal()?.let {
      val term = it.withRange(rangeOne())
      eat()
      return@let term
    }
  }

  private fun parseArgumentExprList(): Pair<List<Expression>, Token> {
    val funcArgs = mutableListOf<Expression>()
    val callEnd = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
    eat() // The '('
    if (callEnd == -1) {
      TODO("error where function call has unmatched paren")
    }
    if (current().asPunct() == Punctuators.RPAREN) {
      val endParenTok = current()
      eat() // The ')'
      // No parameters; this is not an error case
      return Pair(emptyList(), endParenTok)
    }
    tokenContext(callEnd) {
      while (isNotEaten()) {
        // The arguments can have parens with commas in them
        // We're interested in the comma that comes after the argument expression
        // So balance the parens, and look for the first comma after them
        // Also, we do not eat what we find; we're only searching for the end of the current arg
        // Once found, parseExpr handles parsing the arg and eating it
        val parenEndIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
        if (parenEndIdx == -1) {
          TODO("handle error case where there is an unmatched" +
              "paren in the argument-expression-list")
        }
        val commaIdx = indexOfFirst { c -> c == Punctuators.COMMA }
        val arg = parseExpr(if (commaIdx == -1) it.size else commaIdx)
            ?: TODO("handle error case with a null (error'd) expr")
        funcArgs += arg
        if (isNotEaten() && current().asPunct() == Punctuators.COMMA) {
          // Expected case; found comma that separates args
          eat()
        }
      }
    }
    eat() // The ')'
    return Pair(funcArgs, tokenAt(callEnd))
  }

  private fun parsePostfixExpression(): Expression? {
    // FIXME: implement initializer-lists (6.5.2)
    val expr = parseBaseExpr()
    return when {
      isEaten() || expr == null -> return expr
      current().asPunct() == Punctuators.LSQPAREN -> {
        TODO("implement subscript operator")
      }
      current().asPunct() == Punctuators.LPAREN -> {
        val (args, endParenTok) = parseArgumentExprList()
        return FunctionCall(expr, args).withRange(expr.tokenRange.start until endParenTok.startIdx)
      }
      current().asPunct() == Punctuators.DOT -> {
        TODO("implement direct struct/union access operator")
      }
      current().asPunct() == Punctuators.ARROW -> {
        TODO("implement indirect struct/union access operator")
      }
      current().asPunct() == Punctuators.INC || current().asPunct() == Punctuators.DEC -> {
        val c = current().asPunct()
        val r = expr.tokenRange.start..rangeOne().endInclusive
        eat() // The postfix op
        if (c == Punctuators.INC) PostfixIncrement(expr).withRange(r)
        else PostfixDecrement(expr).withRange(r)
      }
      else -> return expr
    }
  }

  private fun parseUnaryExpression(): Expression? = when {
    current().asPunct() == Punctuators.INC || current().asPunct() == Punctuators.DEC -> {
      val c = current()
      eat() // The prefix op
      val expr = parseUnaryExpression() ?: errorExpr()
      val range = c.startIdx until expr.tokenRange.endInclusive + 1
      if (c.asPunct() == Punctuators.INC) PrefixIncrement(expr).withRange(range)
      else PrefixDecrement(expr).withRange(range)
    }
    current().asUnaryOperator() != null -> {
      val c = current()
      val op = c.asUnaryOperator()!!
      eat() // The unary op
      val expr = parsePrimaryExpr() ?: errorExpr()
      UnaryExpression(op, expr).withRange(c.startIdx until expr.tokenRange.endInclusive + 1)
    }
    current().asKeyword() == Keywords.ALIGNOF -> {
      eat() // The ALIGNOF
      if (isEaten() || current().asPunct() != Punctuators.LPAREN) {
        TODO("throw some error here; the standard wants parens for this")
      } else {
        TODO("implement `_Alignof ( type-name )` expressions")
      }
    }
    current().asKeyword() == Keywords.SIZEOF -> {
      eat() // The SIZEOF
      when {
        isEaten() -> errorExpr()
        current().asPunct() == Punctuators.LPAREN -> {
          TODO("implement `sizeof ( type-name )` expressions")
        }
        else -> {
          val expr = parseUnaryExpression() ?: errorExpr()
          SizeofExpression(expr)
              .withRange(relative(-1).startIdx until expr.tokenRange.endInclusive + 1)
        }
      }
    }
    else -> parsePostfixExpression()
  }

  /**
   * Looks for a primary expression. Eats what it finds.
   *
   * As a note, this function does not parse what the standard calls "primary-expression" (that
   * would be [parseBaseExpr]); it parses "cast-expression".
   *
   * C standard: A.2.1, 6.4.4, 6.5.3
   * @see parseTerminal
   * @return null if no primary was found, or the [Expression] otherwise
   */
  private fun parsePrimaryExpr(): Expression? {
    if (isEaten()) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_EXPR
        if (tokenCount != 0) errorOn(safeToken(0))
        else errorOn(parentContext()[parentIdx()])
      }
      return errorExpr()
    }
    // FIXME: here we can also have a cast, that needs to be differentiated from `( expression )`
    return parseUnaryExpression()
  }

  /**
   * All terminals are one token long. Does not eat anything.
   * C standard: A.2.1, 6.5.1, 6.4.4.4
   * @see CharacterConstantNode
   * @return the [Terminal] node, or null if no terminal was found
   */
  private fun parseTerminal(): Expression? {
    val tok = current()
    when (tok) {
      is Identifier -> {
        val ident = IdentifierNode(tok.name)
        val existingIdent = searchInScope(ident)
        if (existingIdent == null) parserDiagnostic {
          id = DiagnosticId.USE_UNDECLARED
          formatArgs(tok.name)
          errorOn(safeToken(0))
        }
        // When this ident is being used as undeclared, we can report the error but keep going with
        // it undeclared
        return ident
      }
      is IntegralConstant -> {
        // FIXME conversions might fail here?
        return IntegerConstantNode(tok.n.toLong(tok.radix.toInt()), tok.suffix)
      }
      is FloatingConstant -> {
        // FIXME conversions might fail here?
        return FloatingConstantNode(tok.f.toDouble(), tok.suffix)
      }
      // FIXME handle enum constants
      is CharLiteral -> {
        val char = if (tok.data.isNotEmpty()) {
          tok.data[0].toInt()
        } else {
          parserDiagnostic {
            id = DiagnosticId.EMPTY_CHAR_CONSTANT
            errorOn(safeToken(0))
          }
          0
        }
        return CharacterConstantNode(char, tok.encoding)
      }
      is StringLiteral -> return StringLiteralNode(tok.data, tok.encoding)
      else -> return null
    }
  }
}
