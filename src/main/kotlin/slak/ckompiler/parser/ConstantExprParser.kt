package slak.ckompiler.parser

import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.FloatingSuffix
import slak.ckompiler.lexer.IntegralSuffix
import slak.ckompiler.throwICE

private object ConstExprIdents : IdentSearchable, TypeNameParser {
  override fun parseTypeName(endIdx: Int): TypeName? {
    return null
  }

  override fun searchIdent(target: String): OrdinaryIdentifier? {
    return TypedIdentifier(target, SignedIntType)
  }
}

enum class ConstantExprType {
  // FIXME: add all uses of constant-expression in the standard
  PREPROCESSOR
}

/**
 * FIXME: the evaluation of constant expressions cuts A LOT of corners
 *
 * C standard: 6.6
 */
class ConstantExprParser(parenMatcher: ParenMatcher, val type: ConstantExprType) :
    ExpressionParser(parenMatcher, ConstExprIdents, ConstExprIdents) {

  /**
   * Calls [ExpressionParser.parseExpr], then parses the constant expression.
   * Generates diagnostics and calculates the final value of the constant.
   */
  override fun parseExpr(endIdx: Int): ExprConstantNode? {
    if (endIdx == 0) return null
    val expr = super.parseExpr(endIdx) ?: return null
    return traverseExpr(expr)
  }

  /**
   * Go through the parsed expression and forbid certain constructs, while evaluating the constant
   * expression.
   */
  private fun traverseExpr(expr: Expression): ExprConstantNode = when (expr) {
    is TernaryConditional -> TODO("deal with this")
    is ErrorExpression, is IntegerConstantNode, is CharacterConstantNode -> expr as ExprConstantNode
    is FunctionCall, is ArraySubscript,
    is PrefixIncrement, is PrefixDecrement,
    is PostfixIncrement, is PostfixDecrement -> {
      diagnostic {
        id = DiagnosticId.EXPR_NOT_CONSTANT
        errorOn(expr)
      }
      error<ErrorExpression>()
    }
    is TypedIdentifier -> {
      if (type == ConstantExprType.PREPROCESSOR) {
        logger.throwICE("Impossible to get here; identifiers evaluate to 0 in PP")
      } else {
        TODO()
      }
    }
    is StringLiteralNode, is FloatingConstantNode -> {
      if (type == ConstantExprType.PREPROCESSOR) {
        diagnostic {
          id = DiagnosticId.INVALID_LITERAL_IN_PP
          formatArgs(if (expr is StringLiteralNode) "string" else "floating")
          errorOn(expr)
        }
        error<ErrorExpression>()
      } else {
        TODO()
      }
    }
    is CastExpression -> {
      if (type == ConstantExprType.PREPROCESSOR) {
        logger.throwICE("Impossible to get here; type names cannot be" +
            " recognized in preprocessor, so casts can't either")
      } else {
        TODO()
      }
    }
    is UnaryExpression -> {
      val operand = traverseExpr(expr.operand)
      when {
        expr.op == UnaryOperators.REF || expr.op == UnaryOperators.DEREF -> {
          diagnostic {
            id = DiagnosticId.EXPR_NOT_CONSTANT
            errorOn(expr)
          }
          error<ErrorExpression>()
        }
        operand.type is ErrorType -> error<ErrorExpression>()
        else -> doUnary(operand, expr.op)
      }
    }
    is BinaryExpression -> {
      val lhs = traverseExpr(expr.lhs)
      val rhs = traverseExpr(expr.rhs)
      when {
        expr.op in assignmentOps || expr.op == BinaryOperators.COMMA -> {
          diagnostic {
            id = DiagnosticId.EXPR_NOT_CONSTANT
            errorOn(expr)
          }
          error<ErrorExpression>()
        }
        expr.type is ErrorType || lhs.type is ErrorType || rhs.type is ErrorType -> {
          error<ErrorExpression>()
        }
        else -> doBinary(lhs, rhs, expr.op, expr.type)
      }
    }
    is SizeofExpression, is SizeofTypeName -> TODO("deal with this after we implement sizeof")
  }

  private fun convertToFloat(e: ExprConstantNode): Double = when (e) {
    is IntegerConstantNode -> e.value.toDouble()
    is FloatingConstantNode -> e.value
    is CharacterConstantNode -> e.char.toDouble()
    is ErrorExpression, is StringLiteralNode -> logger.throwICE("This is not allowed here")
  }

  private fun convertToInt(e: ExprConstantNode): Long = when (e) {
    is IntegerConstantNode -> e.value
    is FloatingConstantNode -> e.value.toLong()
    is CharacterConstantNode -> e.char.toLong()
    is ErrorExpression, is StringLiteralNode -> logger.throwICE("This is not allowed here")
  }

  private fun basicArithm(lhs: ExprConstantNode,
                          rhs: ExprConstantNode,
                          op: BinaryOperators,
                          resType: TypeName) = when (resType) {
    is FloatingType -> {
      val lhsConst = convertToFloat(lhs)
      val rhsConst = convertToFloat(rhs)
      val result = when (op) {
        BinaryOperators.MUL -> lhsConst * rhsConst
        BinaryOperators.DIV -> lhsConst / rhsConst
        BinaryOperators.ADD -> lhsConst + rhsConst
        BinaryOperators.SUB -> lhsConst - rhsConst
        else -> logger.throwICE("Logically impossible condition")
      }
      FloatingConstantNode(result, FloatingSuffix.NONE)
    }
    is IntegralType -> {
      val lhsConst = convertToInt(lhs)
      val rhsConst = convertToInt(rhs)
      val result = when (op) {
        BinaryOperators.MUL -> lhsConst * rhsConst
        BinaryOperators.DIV -> lhsConst / rhsConst
        BinaryOperators.ADD -> lhsConst + rhsConst
        BinaryOperators.SUB -> lhsConst - rhsConst
        else -> logger.throwICE("Logically impossible condition")
      }
      IntegerConstantNode(result, IntegralSuffix.NONE)
    }
    else -> TODO("unknown result type")
  }

  // Just because it has the same syntax, it doesn't mean it does the same thing
  @Suppress("DuplicatedCode")
  private fun basicComparable(lhs: ExprConstantNode,
                              rhs: ExprConstantNode,
                              op: BinaryOperators,
                              resType: TypeName) = when (resType) {
    is FloatingType -> {
      val lhsConst = convertToFloat(lhs)
      val rhsConst = convertToFloat(rhs)
      val result = when (op) {
        BinaryOperators.LT -> lhsConst < rhsConst
        BinaryOperators.GT -> lhsConst > rhsConst
        BinaryOperators.LEQ -> lhsConst <= rhsConst
        BinaryOperators.GEQ -> lhsConst >= rhsConst
        BinaryOperators.EQ -> lhsConst == rhsConst
        BinaryOperators.NEQ -> lhsConst != rhsConst
        else -> logger.throwICE("Logically impossible condition")
      }
      IntegerConstantNode(if (result) 1L else 0L, IntegralSuffix.NONE)
    }
    is IntegralType -> {
      val lhsConst = convertToInt(lhs)
      val rhsConst = convertToInt(rhs)
      val result = when (op) {
        BinaryOperators.LT -> lhsConst < rhsConst
        BinaryOperators.GT -> lhsConst > rhsConst
        BinaryOperators.LEQ -> lhsConst <= rhsConst
        BinaryOperators.GEQ -> lhsConst >= rhsConst
        BinaryOperators.EQ -> lhsConst == rhsConst
        BinaryOperators.NEQ -> lhsConst != rhsConst
        else -> logger.throwICE("Logically impossible condition")
      }
      IntegerConstantNode(if (result) 1L else 0L, IntegralSuffix.NONE)
    }
    else -> TODO("unknown result type")
  }

  private fun intOnlyOps(lhs: ExprConstantNode,
                         rhs: ExprConstantNode,
                         op: BinaryOperators): IntegerConstantNode {
    val lhsConst = convertToInt(lhs)
    val rhsConst = convertToInt(rhs)
    val result = when (op) {
      BinaryOperators.MOD -> lhsConst % rhsConst
      BinaryOperators.LSH -> lhsConst shl rhsConst.toInt()
      BinaryOperators.RSH -> lhsConst shr rhsConst.toInt()
      BinaryOperators.BIT_AND -> lhsConst and rhsConst
      BinaryOperators.BIT_XOR -> lhsConst xor rhsConst
      BinaryOperators.BIT_OR -> lhsConst or rhsConst
      BinaryOperators.AND -> (lhsConst != 0L && rhsConst != 0L).let { if (it) 1L else 0L }
      BinaryOperators.OR -> (lhsConst != 0L || rhsConst != 0L).let { if (it) 1L else 0L }
      else -> logger.throwICE("Logically impossible condition")
    }
   return IntegerConstantNode(result, IntegralSuffix.NONE)
  }

  private fun doBinary(lhs: ExprConstantNode,
                       rhs: ExprConstantNode,
                       op: BinaryOperators,
                       resType: TypeName): ExprConstantNode = when (op) {
    BinaryOperators.LT, BinaryOperators.GT, BinaryOperators.LEQ, BinaryOperators.GEQ,
    BinaryOperators.EQ, BinaryOperators.NEQ -> {
      basicComparable(lhs, rhs, op, resType)
    }
    BinaryOperators.MUL, BinaryOperators.DIV, BinaryOperators.ADD, BinaryOperators.SUB -> {
      basicArithm(lhs, rhs, op, resType)
    }
    BinaryOperators.MOD, BinaryOperators.LSH, BinaryOperators.RSH, BinaryOperators.BIT_AND,
    BinaryOperators.BIT_XOR, BinaryOperators.BIT_OR, BinaryOperators.AND, BinaryOperators.OR -> {
      intOnlyOps(lhs, rhs, op)
    }
    else -> logger.throwICE("Illegal constant binary operator") { op }
  }

  private fun doUnary(operand: ExprConstantNode,
                      op: UnaryOperators): ExprConstantNode = when (operand.type) {
    is FloatingType -> {
      val toApply = convertToFloat(operand)
      when (op) {
        UnaryOperators.REF, UnaryOperators.DEREF, UnaryOperators.BIT_NOT -> {
          logger.throwICE("Illegal constant unary operator") { op }
        }
        UnaryOperators.PLUS -> FloatingConstantNode(+toApply, FloatingSuffix.NONE)
        UnaryOperators.MINUS -> FloatingConstantNode(-toApply, FloatingSuffix.NONE)
        UnaryOperators.NOT -> {
          IntegerConstantNode(if (toApply != 0.0) 1L else 0L, IntegralSuffix.NONE)
        }
      }
    }
    is IntegralType -> {
      val toApply = convertToInt(operand)
      val result = when (op) {
        UnaryOperators.REF, UnaryOperators.DEREF -> {
          logger.throwICE("Illegal constant unary operator") { op }
        }
        UnaryOperators.PLUS -> +toApply
        UnaryOperators.MINUS -> -toApply
        UnaryOperators.BIT_NOT -> toApply.inv()
        UnaryOperators.NOT -> if (toApply != 0L) 0L else 1L
      }
      IntegerConstantNode(result, IntegralSuffix.NONE)
    }
    else -> TODO("unknown result type")
  }
}
