package slak.ckompiler.parser

import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler
import slak.ckompiler.lexer.*

interface IExpressionParser {
  /**
   * Parses an expression. Eats it.
   *
   * C standard: A.2.1
   * @return null if there is no expression, the [Expression] otherwise
   */
  fun parseExpr(endIdx: Int): Expression?

  /** Factory for [ErrorExpression]. */
  fun errorExpr(): ErrorExpression
}

/**
 * This class is used by the expression parser. The list is not complete, and the standard does not
 * define these properties; they are derived from the grammar.
 * C standard: A.2.1
 */
enum class Operators(val op: Punctuators,
                             val precedence: Int,
                             val arity: Arity,
                             val assoc: Associativity) {
  // Unary
  REF(Punctuators.AMP, 100, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
  DEREF(Punctuators.STAR, 100, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
  PLUS(Punctuators.PLUS, 100, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
  MINUS(Punctuators.MINUS, 100, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
  BIT_NOT(Punctuators.TILDE, 100, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
  NOT(Punctuators.NOT, 100, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
  // Arithmetic
  MUL(Punctuators.STAR, 95, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  DIV(Punctuators.SLASH, 95, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  MOD(Punctuators.PERCENT, 95, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  ADD(Punctuators.PLUS, 90, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  SUB(Punctuators.MINUS, 90, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  // Bit-shift
  LSH(Punctuators.LSH, 80, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  RSH(Punctuators.RSH, 80, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  // Relational
  LT(Punctuators.LT, 70, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  GT(Punctuators.GT, 70, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  LEQ(Punctuators.LEQ, 70, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  GEQ(Punctuators.GEQ, 70, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  // Equality
  EQ(Punctuators.EQUALS, 60, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  NEQ(Punctuators.NEQUALS, 60, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  // Bitwise
  BIT_AND(Punctuators.AMP, 58, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  BIT_XOR(Punctuators.CARET, 54, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  BIT_OR(Punctuators.PIPE, 50, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  // Logical
  AND(Punctuators.AND, 45, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  OR(Punctuators.OR, 40, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  // Assignment
  ASSIGN(Punctuators.ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  MUL_ASSIGN(Punctuators.MUL_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  DIV_ASSIGN(Punctuators.DIV_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  MOD_ASSIGN(Punctuators.MOD_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  PLUS_ASSIGN(Punctuators.PLUS_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  SUB_ASSIGN(Punctuators.SUB_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  LSH_ASSIGN(Punctuators.LSH_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  RSH_ASSIGN(Punctuators.RSH_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  AND_ASSIGN(Punctuators.AND_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  XOR_ASSIGN(Punctuators.XOR_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  OR_ASSIGN(Punctuators.OR_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  // Comma
  COMMA(Punctuators.COMMA, 10, Arity.BINARY, Associativity.LEFT_TO_RIGHT);

  enum class Associativity { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

  enum class Arity { UNARY, BINARY, TERNARY }

  companion object {
    val binaryOperators = Operators.values().filter { it.arity == Arity.BINARY }
    /** C standard: 6.5.3, A.2.1 */
    val unaryOperators = Operators.values().filter { it.arity == Arity.UNARY }
  }
}

private fun Punctuators.asBinaryOperator() = Operators.binaryOperators.find { it.op == this }
private fun Punctuators.asUnaryOperator() = Operators.unaryOperators.find { it.op == this }

fun LexicalToken.asBinaryOperator(): Operators? = asPunct()?.asBinaryOperator()
fun LexicalToken.asUnaryOperator(): Operators? = asPunct()?.asUnaryOperator()

class ExpressionParser(scopeHandler: ScopeHandler, parenMatcher: ParenMatcher) :
    IExpressionParser,
    IDebugHandler by parenMatcher,
    ILexicalTokenHandler by parenMatcher,
    IScopeHandler by scopeHandler,
    IParenMatcher by parenMatcher {

  override fun errorExpr() = ErrorExpression().withRange(rangeOne())

  /**
   * Base case of precedence climbing method.
   * @see IExpressionParser.parseExpr
   * @see parseExprImpl
   */
  override fun parseExpr(endIdx: Int): Expression? = tokenContext(endIdx) {
    return@tokenContext parseExprImpl(parsePrimaryExpr() ?: return@tokenContext null, 0)
  }

  /**
   * Recursive case of precedence climbing method.
   * @see parseExpr
   */
  private fun parseExprImpl(lhsInit: Expression, minPrecedence: Int): Expression {
    var lhs = lhsInit
    outerLoop@ while (true) {
      if (isEaten()) break@outerLoop
      val op = current().asBinaryOperator() ?: break@outerLoop
      if (op.precedence < minPrecedence) break@outerLoop
      eat()
      var rhs: Expression = parsePrimaryExpr() ?: return errorExpr()
      innerLoop@ while (true) {
        if (isEaten()) break@innerLoop
        val innerOp = current().asBinaryOperator() ?: break@innerLoop
        if (innerOp.precedence <= op.precedence &&
            !(innerOp.assoc == Operators.Associativity.RIGHT_TO_LEFT &&
                innerOp.precedence == op.precedence)) {
          break@innerLoop
        }
        rhs = parseExprImpl(rhs, innerOp.precedence)
      }
      lhs = BinaryExpression(op, lhs, rhs).withRange(lhs..rhs)
    }
    return lhs
  }

  private fun parseBaseExpr(): Expression? = when {
    // FIXME: implement generic-selection (A.2.1/6.5.1.1)
    current().asPunct() == Punctuators.RPAREN -> {
      // This usually happens when there are unmatched parens
      eat()
      diagnostic {
        id = DiagnosticId.EXPECTED_EXPR
        errorOn(safeToken(0))
      }
      errorExpr()
    }
    current().asPunct() == Punctuators.LPAREN -> {
      if (relative(1).asPunct() == Punctuators.RPAREN) {
        diagnostic {
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

  private fun parseArgumentExprList(): Pair<List<Expression>, LexicalToken> {
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
        val r = expr..safeToken(0)
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
      if (c.asPunct() == Punctuators.INC) PrefixIncrement(expr).withRange(c..expr)
      else PrefixDecrement(expr).withRange(c..expr)
    }
    current().asUnaryOperator() != null -> {
      val c = current()
      val op = c.asUnaryOperator()!!
      eat() // The unary op
      val expr = parsePrimaryExpr() ?: errorExpr()
      UnaryExpression(op, expr).withRange(c..expr)
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
          SizeofExpression(expr).withRange(relative(-1)..expr)
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
   * @see parseExpr
   * @return null if no primary was found (generates diagnostic), or the [Expression] otherwise
   */
  private fun parsePrimaryExpr(): Expression? {
    if (isEaten()) {
      diagnostic {
        id = DiagnosticId.EXPECTED_EXPR
        if (tokenCount != 0) errorOn(safeToken(0))
        else errorOn(parentContext()[parentIdx()])
      }
      return errorExpr()
    }
    // FIXME: here we can also have a cast, that needs to be differentiated from `( expression )`
    val expr = parseUnaryExpression()
    if (expr == null) diagnostic {
      id = DiagnosticId.EXPECTED_PRIMARY
      errorOn(safeToken(0))
    }
    return expr
  }

  /**
   * All terminals are one token long. Does not eat anything.
   *
   * C standard: A.2.1, 6.5.1, 6.4.4.4
   * @see CharacterConstantNode
   * @return the [Terminal] node, or null if no terminal was found
   */
  private fun parseTerminal(): Expression? {
    val tok = current()
    when (tok) {
      is Identifier -> {
        val ident = IdentifierNode(tok.name)
        val existingIdent = searchIdent(ident)
        if (existingIdent == null) diagnostic {
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
        val char = if (tok.data.isNotEmpty()) tok.data[0].toInt() else 0
        return CharacterConstantNode(char, tok.encoding)
      }
      is StringLiteral -> return StringLiteralNode(tok.data, tok.encoding)
      else -> return null
    }
  }
}
