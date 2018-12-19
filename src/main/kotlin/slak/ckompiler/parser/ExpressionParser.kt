package slak.ckompiler.parser

import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.*

interface IExpressionParser {
  /**
   * Parses an expression.
   * C standard: A.2.1
   * @return null if there is no expression, the [Expression] otherwise
   */
  fun parseExpr(endIdx: Int): Expression?
}

class ExpressionParser(parenMatcher: ParenMatcher) :
    IExpressionParser,
    IDebugHandler by parenMatcher,
    ITokenHandler by parenMatcher,
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
        return@parseExprImpl ErrorExpression()
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
      ErrorExpression()
    }
    current().asPunct() == Punctuators.LPAREN -> {
      if (relative(1).asPunct() == Punctuators.RPAREN) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_EXPR
          errorOn(safeToken(1))
        }
        ErrorExpression()
      } else {
        val endParenIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
        if (endParenIdx == -1) {
          eatToSemi()
          ErrorExpression()
        } else {
          eat() // Get rid of the LPAREN
          val expr = parseExpr(endParenIdx)
          eat() // Get rid of the RPAREN
          expr
        }
      }
    }
    else -> parseTerminal()?.let {
      eat()
      it
    }
  }

  private fun parseArgumentExprList(): List<Expression> {
    val funcArgs = mutableListOf<Expression>()
    val callEnd = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
    eat() // The '('
    if (callEnd == -1) {
      TODO("error where function call has unmatched paren")
    }
    if (current().asPunct() == Punctuators.RPAREN) {
      eat() // The ')'
      // No parameters; this is not an error case
      return emptyList()
    }
    tokenContext(callEnd) {
      while (!isEaten()) {
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
        funcArgs.add(arg)
        if (!isEaten() && current().asPunct() == Punctuators.COMMA) {
          // Expected case; found comma that separates args
          eat()
        }
      }
    }
    eat() // The ')'
    return funcArgs
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
        return FunctionCall(expr, parseArgumentExprList())
      }
      current().asPunct() == Punctuators.DOT -> {
        TODO("implement direct struct/union access operator")
      }
      current().asPunct() == Punctuators.ARROW -> {
        TODO("implement indirect struct/union access operator")
      }
      current().asPunct() == Punctuators.INC || current().asPunct() == Punctuators.DEC -> {
        val c = current().asPunct()
        eat() // The postfix op
        if (c == Punctuators.INC) PostfixIncrement(expr)
        else PostfixDecrement(expr)
      }
      else -> return expr
    }
  }

  private fun parseUnaryExpression(): Expression? = when {
    current().asPunct() == Punctuators.INC || current().asPunct() == Punctuators.DEC -> {
      val c = current().asPunct()
      eat() // The prefix op
      val expr = parseUnaryExpression() ?: ErrorExpression()
      if (c == Punctuators.INC) PrefixIncrement(expr)
      else PrefixDecrement(expr)
    }
    current().asUnaryOperator() != null -> {
      val c = current().asUnaryOperator()!!
      eat() // The unary op
      val expr = parsePrimaryExpr() ?: ErrorExpression()
      UnaryExpression(c, expr)
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
        isEaten() -> ErrorExpression()
        current().asPunct() == Punctuators.LPAREN -> {
          TODO("implement `sizeof ( type-name )` expressions")
        }
        else -> SizeofExpression(parseUnaryExpression() ?: ErrorExpression())
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
      return ErrorExpression()
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
      is Identifier -> return IdentifierNode(tok.name)
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
