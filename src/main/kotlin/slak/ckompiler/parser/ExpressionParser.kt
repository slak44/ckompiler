package slak.ckompiler.parser

import org.apache.logging.log4j.LogManager
import slak.ckompiler.*
import slak.ckompiler.lexer.*

private val logger = LogManager.getLogger()

interface IExpressionParser {
  /**
   * Parses an expression. Eats it.
   *
   * C standard: A.2.1
   * @return null if there is no expression, the [Expression] otherwise
   */
  fun parseExpr(endIdx: Int): Expression?
}

/**
 * C standard: 6.5.3, A.2.1
 * @see BinaryOperators
 */
enum class UnaryOperators(val op: Punctuators) {
  REF(Punctuators.AMP), DEREF(Punctuators.STAR),
  PLUS(Punctuators.PLUS), MINUS(Punctuators.MINUS),
  BIT_NOT(Punctuators.TILDE), NOT(Punctuators.NOT)
}

/**
 * This class is used by the expression parser. The list is not complete, and the standard does not
 * define these properties; they are derived from the grammar.
 * C standard: A.2.1
 */
enum class BinaryOperators(val op: Punctuators, val precedence: Int, val assoc: Associativity) {
  // Arithmetic
  MUL(Punctuators.STAR, 95, Associativity.LEFT_TO_RIGHT),
  DIV(Punctuators.SLASH, 95, Associativity.LEFT_TO_RIGHT),
  MOD(Punctuators.PERCENT, 95, Associativity.LEFT_TO_RIGHT),
  ADD(Punctuators.PLUS, 90, Associativity.LEFT_TO_RIGHT),
  SUB(Punctuators.MINUS, 90, Associativity.LEFT_TO_RIGHT),
  // Bit-shift
  LSH(Punctuators.LSH, 80, Associativity.LEFT_TO_RIGHT),
  RSH(Punctuators.RSH, 80, Associativity.LEFT_TO_RIGHT),
  // Relational
  LT(Punctuators.LT, 70, Associativity.LEFT_TO_RIGHT),
  GT(Punctuators.GT, 70, Associativity.LEFT_TO_RIGHT),
  LEQ(Punctuators.LEQ, 70, Associativity.LEFT_TO_RIGHT),
  GEQ(Punctuators.GEQ, 70, Associativity.LEFT_TO_RIGHT),
  // Equality
  EQ(Punctuators.EQUALS, 60, Associativity.LEFT_TO_RIGHT),
  NEQ(Punctuators.NEQUALS, 60, Associativity.LEFT_TO_RIGHT),
  // Bitwise
  BIT_AND(Punctuators.AMP, 58, Associativity.LEFT_TO_RIGHT),
  BIT_XOR(Punctuators.CARET, 54, Associativity.LEFT_TO_RIGHT),
  BIT_OR(Punctuators.PIPE, 50, Associativity.LEFT_TO_RIGHT),
  // Logical
  AND(Punctuators.AND, 45, Associativity.LEFT_TO_RIGHT),
  OR(Punctuators.OR, 40, Associativity.LEFT_TO_RIGHT),
  // Assignment
  ASSIGN(Punctuators.ASSIGN, 20, Associativity.RIGHT_TO_LEFT),
  MUL_ASSIGN(Punctuators.MUL_ASSIGN, 20, Associativity.RIGHT_TO_LEFT),
  DIV_ASSIGN(Punctuators.DIV_ASSIGN, 20, Associativity.RIGHT_TO_LEFT),
  MOD_ASSIGN(Punctuators.MOD_ASSIGN, 20, Associativity.RIGHT_TO_LEFT),
  PLUS_ASSIGN(Punctuators.PLUS_ASSIGN, 20, Associativity.RIGHT_TO_LEFT),
  SUB_ASSIGN(Punctuators.SUB_ASSIGN, 20, Associativity.RIGHT_TO_LEFT),
  LSH_ASSIGN(Punctuators.LSH_ASSIGN, 20, Associativity.RIGHT_TO_LEFT),
  RSH_ASSIGN(Punctuators.RSH_ASSIGN, 20, Associativity.RIGHT_TO_LEFT),
  AND_ASSIGN(Punctuators.AND_ASSIGN, 20, Associativity.RIGHT_TO_LEFT),
  XOR_ASSIGN(Punctuators.XOR_ASSIGN, 20, Associativity.RIGHT_TO_LEFT),
  OR_ASSIGN(Punctuators.OR_ASSIGN, 20, Associativity.RIGHT_TO_LEFT),
  // Comma
  COMMA(Punctuators.COMMA, 10, Associativity.LEFT_TO_RIGHT);

  enum class Associativity { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

  override fun toString() = op.s
}

val assignmentOps = setOf(BinaryOperators.ASSIGN, BinaryOperators.MUL_ASSIGN,
    BinaryOperators.DIV_ASSIGN, BinaryOperators.MOD_ASSIGN, BinaryOperators.PLUS_ASSIGN,
    BinaryOperators.SUB_ASSIGN, BinaryOperators.LSH_ASSIGN, BinaryOperators.RSH_ASSIGN,
    BinaryOperators.AND_ASSIGN, BinaryOperators.XOR_ASSIGN, BinaryOperators.OR_ASSIGN)

val compoundAssignOps = mapOf(
    BinaryOperators.MUL_ASSIGN to BinaryOperators.MUL,
    BinaryOperators.DIV_ASSIGN to BinaryOperators.DIV,
    BinaryOperators.MOD_ASSIGN to BinaryOperators.MOD,
    BinaryOperators.PLUS_ASSIGN to BinaryOperators.ADD,
    BinaryOperators.SUB_ASSIGN to BinaryOperators.SUB,
    BinaryOperators.LSH_ASSIGN to BinaryOperators.LSH,
    BinaryOperators.RSH_ASSIGN to BinaryOperators.RSH,
    BinaryOperators.AND_ASSIGN to BinaryOperators.BIT_AND,
    BinaryOperators.XOR_ASSIGN to BinaryOperators.BIT_XOR,
    BinaryOperators.OR_ASSIGN to BinaryOperators.BIT_OR
)

private fun Punctuators.asBinaryOperator() = BinaryOperators.values().find { it.op == this }
private fun Punctuators.asUnaryOperator() = UnaryOperators.values().find { it.op == this }

fun LexicalToken.asBinaryOperator(): BinaryOperators? = asPunct()?.asBinaryOperator()
fun LexicalToken.asUnaryOperator(): UnaryOperators? = asPunct()?.asUnaryOperator()

class ExpressionParser(
    parenMatcher: ParenMatcher,
    identSearchable: IdentSearchable,
    typeNameParser: TypeNameParser,
    val machineTargetData: MachineTargetData
) : IExpressionParser,
    IDebugHandler by parenMatcher,
    ITokenHandler by parenMatcher,
    IParenMatcher by parenMatcher,
    IdentSearchable by identSearchable,
    TypeNameParser by typeNameParser {

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
      val opTok = current()
      if (opTok.asPunct() == Punctuators.COLON) {
        diagnostic {
          id = DiagnosticId.EXPECTED_SEMI_AFTER
          formatArgs("expression")
          errorOn(opTok)
        }
        eatToSemi()
        return lhs
      }
      if (opTok.asPunct() == Punctuators.QMARK) return parseTernaryExpr(lhs)
      val op = opTok.asBinaryOperator() ?: break@outerLoop
      if (op.precedence < minPrecedence) break@outerLoop
      eat()
      var rhs: Expression = parsePrimaryExpr() ?: return error<ErrorExpression>()
      innerLoop@ while (true) {
        if (isEaten()) break@innerLoop
        val innerOp = current().asBinaryOperator() ?: break@innerLoop
        if (innerOp.precedence <= op.precedence &&
            !(innerOp.assoc == BinaryOperators.Associativity.RIGHT_TO_LEFT &&
                innerOp.precedence == op.precedence)) {
          break@innerLoop
        }
        rhs = parseExprImpl(rhs, innerOp.precedence)
      }
      val (exprType, commonType) = binaryDiags(opTok as Punctuator, lhs, rhs)
      validateAssignment(opTok, lhs, rhs)
      if (op !in assignmentOps) {
        lhs = convertToCommon(commonType, lhs)
        rhs = convertToCommon(commonType, rhs)
      } else if (op == BinaryOperators.ASSIGN) {
        // Compounds are dealt with later
        rhs = convertToCommon(commonType, rhs)
      }
      lhs = BinaryExpression(op, lhs, rhs, exprType).withRange(lhs..rhs)
    }
    return lhs
  }

  private fun parseTernaryExpr(cond: Expression): Expression {
    val colonIdx = findParenMatch(Punctuators.QMARK, Punctuators.COLON)
    val qmark = current()
    eat() // The ?
    if (colonIdx == -1) {
      eatToSemi()
      return error<ErrorExpression>()
    }
    val success = parseExpr(colonIdx) ?: error<ErrorExpression>()
    eat() // The :
    val failure = parseExpr(tokenCount) ?: error<ErrorExpression>()
    val resType = resultOfTernary(success, failure)
    if (resType == ErrorType) diagnostic {
      id = DiagnosticId.INVALID_ARGS_TERNARY
      formatArgs(success.type.toString(), failure.type.toString())
      errorOn(qmark)
      errorOn(success)
      errorOn(failure)
    }
    return TernaryConditional(
        cond,
        convertToCommon(resType, success),
        convertToCommon(resType, failure)
    ).withRange(cond..failure)
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
      error<ErrorExpression>()
    }
    current().asPunct() == Punctuators.LPAREN -> {
      if (relative(1).asPunct() == Punctuators.RPAREN) {
        diagnostic {
          id = DiagnosticId.EXPECTED_EXPR
          errorOn(safeToken(1))
        }
        error<ErrorExpression>()
      } else {
        val endParenIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
        if (endParenIdx == -1) {
          eatToSemi()
          error<ErrorExpression>()
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
      return Pair(emptyList(), current())
    }
    val endParenTok = tokenAt(callEnd)
    if (current().asPunct() == Punctuators.RPAREN) {
      eat() // The ')'
      // No parameters; this is not an error case
      return Pair(emptyList(), endParenTok)
    }
    tokenContext(callEnd) {
      while (isNotEaten()) {
        // The arguments can have parens with commas in them
        val commaIdx = firstOutsideParens(
            Punctuators.COMMA, Punctuators.LPAREN, Punctuators.RPAREN, stopAtSemi = true)
        funcArgs += tokenContext(commaIdx) { argToks ->
          if (argToks.isEmpty()) {
            // Missing argument in the middle of the argument list
            diagnostic {
              id = DiagnosticId.EXPECTED_EXPR
              column(endParenTok.startIdx)
            }
            error<ErrorExpression>()
          } else {
            val expr = parseExpr(argToks.size)
            if (expr == null) {
              eatToSemi()
              error<ErrorExpression>()
            } else {
              expr
            }
          }
        }
        if (isNotEaten() && current().asPunct() == Punctuators.COMMA) {
          // Expected case; found comma that separates args
          eat()
          // If the last thing is a comma, we're missing an expression
          if (isEaten()) diagnostic {
            id = DiagnosticId.EXPECTED_EXPR
            column(endParenTok.startIdx)
          }
        }
      }
    }
    eat() // The ')'
    return Pair(funcArgs, tokenAt(callEnd))
  }

  /**
   * C standard: 6.5.2.2
   */
  private fun parseFunctionCall(called: Expression): Expression {
    val (args, endParenTok) = parseArgumentExprList()
    val callable = called.type.asCallable()
    if (callable == null) {
      // Don't report bogus error when the type is bullshit; a diagnostic was printed somewhere
      if (called.type != ErrorType) diagnostic {
        id = DiagnosticId.CALL_OBJECT_TYPE
        formatArgs(called.type)
        errorOn(called)
      }
      return error<ErrorExpression>()
    }
    val transformedArgs = matchFunctionArguments(called, args) ?: return error<ErrorExpression>()
    return FunctionCall(called, transformedArgs).withRange(called..endParenTok)
  }

  private fun parseSubscript(subscripted: Expression): Expression {
    val endParenIdx = findParenMatch(Punctuators.LSQPAREN, Punctuators.RSQPAREN)
    if (endParenIdx == -1) {
      eatToSemi()
      return error<ErrorExpression>()
    }
    eat() // The [
    val subscript = parseExpr(endParenIdx)
    eat() // The ]
    if (subscript == null) return error<ErrorExpression>()
    val endParenTok = tokenAt(endParenIdx)
    val (resultingType, areSwapped) = typeOfSubscript(subscripted, subscript, endParenTok)
    val realSubscripted = if (!areSwapped) subscripted else subscript
    val realSubscript = if (!areSwapped) subscript else subscripted
    if (realSubscript.type is SignedCharType || realSubscript.type is UnsignedCharType) diagnostic {
      id = DiagnosticId.SUBSCRIPT_TYPE_CHAR
      formatArgs(realSubscript.type.toString())
      errorOn(realSubscript)
    }
    return ArraySubscript(realSubscripted, realSubscript, resultingType)
        .withRange(realSubscripted..endParenTok)
  }

  /**
   * C standard: 6.5.2.3
   */
  private fun parseMemberAccess(baseExpr: Expression): Expression {
    val accessOp = current() as Punctuator
    eat() // The . or ->
    if (isEaten() || current() !is Identifier) {
      diagnostic {
        id = DiagnosticId.EXPECTED_IDENT
        errorOn(safeToken(0))
      }
      eatToSemi()
      return error<ErrorExpression>()
    }
    val ident = IdentifierNode.from(current())
    eat() // The identifier
    val lhsType = baseExpr.type.unqualify()
    if (accessOp.pct == Punctuators.ARROW && lhsType !is PointerType) {
      diagnostic {
        id = DiagnosticId.MEMBER_REFERENCE_NOT_PTR
        formatArgs(baseExpr.type.toString())
        errorOn(baseExpr)
        errorOn(accessOp)
      }
      return error<ErrorExpression>()
    }
    val baseOperandType =
        if (accessOp.pct == Punctuators.ARROW) (lhsType as PointerType).referencedType
        else lhsType
    val baseUnqual = baseOperandType.unqualify()
    if (baseUnqual !is TagType) {
      diagnostic {
        id = DiagnosticId.MEMBER_BASE_NOT_TAG
        formatArgs(ident.name, baseOperandType.toString())
        errorOn(baseExpr)
        errorOn(accessOp)
      }
      return error<ErrorExpression>()
    }
    // If the members are null, something is bad in either spec parser or scope handing for tags
    val memberType = requireNotNull(baseUnqual.members).find { it.first == ident }?.second
    if (memberType == null) {
      diagnostic {
        id = DiagnosticId.MEMBER_NAME_NOT_FOUND
        formatArgs(ident.name, baseUnqual.toString())
        errorOn(ident)
        errorOn(baseExpr)
      }
      return error<ErrorExpression>()
    }
    // FIXME: 6.5.2.3.0.1, 6.5.2.3.0.2
    //   qualify member type with lhs qualifiers
    return MemberAccessExpression(baseExpr, accessOp, ident, memberType).withRange(baseExpr..ident)
  }

  private fun parseOnePostfixExpression(baseExpr: Expression?): Expression? = when {
    isEaten() || baseExpr == null -> baseExpr
    current().asPunct() == Punctuators.LSQPAREN -> parseSubscript(baseExpr)
    current().asPunct() == Punctuators.LPAREN -> parseFunctionCall(baseExpr)
    current().asPunct() == Punctuators.DOT || current().asPunct() == Punctuators.ARROW -> {
      parseMemberAccess(baseExpr)
    }
    current().asPunct() == Punctuators.INC || current().asPunct() == Punctuators.DEC -> {
      val c = current().asPunct()
      val r = baseExpr..safeToken(0)
      eat() // The postfix op
      IncDecOperation(baseExpr, isDecrement = c == Punctuators.DEC, isPostfix = true).withRange(r)
    }
    else -> baseExpr
  }

  private fun parsePostfixExpression(baseExpr: Expression?): Expression? {
    // FIXME: implement initializer-lists (6.5.2)
    var final = baseExpr
    while (isNotEaten()) {
      val next = parseOnePostfixExpression(final)
      if (final === next) break
      final = next
    }
    return final
  }

  /** C standard: 6.5.3.1 */
  private fun parsePrefixIncDec(): Expression {
    val c = current()
    val isDec = c.asPunct() == Punctuators.DEC
    eat() // The prefix op
    val expr = parseUnaryExpression() ?: error<ErrorExpression>()
    val resType = checkIncDec(expr, isDec, c..expr)
    val exprChecked =
        if (resType != ErrorType || expr.type == ErrorType) expr
        else error<ErrorExpression>()
    return IncDecOperation(exprChecked, isDecrement = isDec, isPostfix = false).withRange(c..expr)
  }

  /** C standard: 6.5.3.2, 6.5.3.3 */
  private fun parseSimpleUnaryOps(): UnaryExpression {
    val c = current()
    val op = c.asUnaryOperator()!!
    eat() // The unary op
    val expr = parsePrimaryExpr() ?: error<ErrorExpression>()
    val resType = op.applyTo(expr.type)
    val exprChecked = if (resType == ErrorType && expr.type != ErrorType) {
      diagnostic {
        id = DiagnosticId.INVALID_ARGUMENT_UNARY
        formatArgs(expr.type, op.op.s)
        errorOn(c..expr)
      }
      error<ErrorExpression>()
    } else {
      expr
    }
    val unary = UnaryExpression(op, convertToCommon(resType, exprChecked), resType)
        .withRange(c..expr)
    if (op == UnaryOperators.REF) validateAddressOf(unary)
    return unary
  }

  /** C standard: 6.5.3.4 */
  private fun parseSizeofExpression(): Expression {
    val sizeOf = current()
    eat() // The 'sizeof'
    if (isEaten()) return error<ErrorExpression>()
    if (current().asPunct() != Punctuators.LPAREN) {
      val col = colPastTheEnd(0)
      val badTypeName = parseTypeName(tokenCount)
      if (badTypeName != null) {
        diagnostic {
          id = DiagnosticId.SIZEOF_TYPENAME_PARENS
          column(col)
        }
        eatToSemi()
        return error<ErrorExpression>()
      }
      val expr = parseUnaryExpression() ?: error<ErrorExpression>()
      val type = checkSizeofType(expr.type, sizeOf, expr)
      return SizeofTypeName(type, machineTargetData.sizeType).withRange(sizeOf..expr)
    }
    val rParenIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
    if (rParenIdx == -1) {
      eatToSemi()
      return error<ErrorExpression>()
    }
    val lParen = current()
    eat() // The '('
    val typeName = parseTypeName(rParenIdx)
    val resType = if (typeName == null) {
      // Not a type name, so it's a parenthesized expression
      val expr = parseExpr(rParenIdx)
      val retExpr = if (expr == null) {
        eatToSemi()
        error<ErrorExpression>()
      } else {
        expr
      }
      eat() // The ')'
      checkSizeofType(retExpr.type, sizeOf, retExpr)
    } else {
      eat() // The ')'
      checkSizeofType(typeName, sizeOf, lParen..tokenAt(rParenIdx))
    }
    return SizeofTypeName(resType, machineTargetData.sizeType).withRange(sizeOf..tokenAt(rParenIdx))
  }

  /** C standard: 6.5.3 */
  private fun parseUnaryExpression(): Expression? = when {
    current().asPunct() == Punctuators.INC ||
        current().asPunct() == Punctuators.DEC -> parsePrefixIncDec()
    current().asUnaryOperator() != null -> parseSimpleUnaryOps()
    current().asKeyword() == Keywords.ALIGNOF -> {
      eat() // The ALIGNOF
      if (isEaten() || current().asPunct() != Punctuators.LPAREN) {
        TODO("throw some error here; the standard wants parens for this")
      } else {
        TODO("implement `_Alignof ( type-name )` expressions")
      }
    }
    current().asKeyword() == Keywords.SIZEOF -> parseSizeofExpression()
    else -> parsePostfixExpression(parseBaseExpr())
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
        errorOn(safeToken(0))
      }
      return error<ErrorExpression>()
    }
    // Either cast or parenthesised expression
    if (current().asPunct() == Punctuators.LPAREN) {
      val lparen = current()
      val endParenIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
      if (endParenIdx == -1) {
        eatToSemi()
        return error<ErrorExpression>()
      }
      eat() // Eat the (
      val possibleName = tokenContext(endParenIdx) {
        if (isEaten()) return@tokenContext null
        return@tokenContext parseTypeName(it.size)
      }
      return if (possibleName != null) {
        eat() // Eat the )
        // This is a cast
        val exprToCast = parsePrimaryExpr() ?: error<ErrorExpression>()
        validateCast(exprToCast.type, possibleName, lparen..tokenAt(endParenIdx))
        CastExpression(exprToCast, possibleName).withRange(lparen..exprToCast)
      } else {
        val parenExpr = parseExpr(endParenIdx)
        eat() // Eat the )
        parsePostfixExpression(parenExpr)
      }
    }
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
    when (val tok = current()) {
      is Identifier -> {
        val ident = tok.name
        when (val existingIdent = searchIdent(ident)) {
          null -> diagnostic {
            id = DiagnosticId.USE_UNDECLARED
            formatArgs(tok.name)
            errorOn(safeToken(0))
          }
          is TypedefName -> diagnostic {
            id = DiagnosticId.UNEXPECTED_TYPEDEF_USE
            formatArgs(existingIdent.name, existingIdent.typedefedToString())
            errorOn(safeToken(0))
          }
          is Enumerator -> return existingIdent.computedValue.copy().withRange(tok)
          is TypedIdentifier -> {
            // Change the token range from the original declaration's name to this particular occurrence
            return existingIdent.copy().withRange(tok)
          }
          else -> logger.warn("Unhandled branch: implementor of OrdinaryIdentifier not handled ($existingIdent)")
        }
        // When this ident not a valid thing to put in an expression, we can report the error(s) but keep going with it
        // anyway. That allows us to report errors more sensibly by not eating more tokens than necessary.
        return TypedIdentifier(ident, ErrorType).withRange(rangeOne())
      }
      is IntegralConstant -> {
        // FIXME conversions might fail here?
        return IntegerConstantNode(tok.n.toLong(tok.radix.toInt()), tok.suffix)
      }
      is FloatingConstant -> {
        // FIXME conversions might fail here?
        // FIXME: this conversion is retarded and ignores exponent, and radix
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
