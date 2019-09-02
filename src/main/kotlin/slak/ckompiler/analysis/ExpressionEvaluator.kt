package slak.ckompiler.analysis

import org.apache.logging.log4j.LogManager
import slak.ckompiler.lexer.FloatingSuffix
import slak.ckompiler.lexer.IntegralSuffix
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

private val logger = LogManager.getLogger()

fun convertToFloat(e: ExprConstantNode): Double = when (e) {
  is IntegerConstantNode -> e.value.toDouble()
  is FloatingConstantNode -> e.value
  is CharacterConstantNode -> e.char.toDouble()
  is ErrorExpression, is VoidExpression,
  is StringLiteralNode -> logger.throwICE("This is not allowed here")
}

fun convertToInt(e: ExprConstantNode): Long = when (e) {
  is IntegerConstantNode -> e.value
  is FloatingConstantNode -> e.value.toLong()
  is CharacterConstantNode -> e.char.toLong()
  is ErrorExpression, is VoidExpression,
  is StringLiteralNode -> logger.throwICE("This is not allowed here")
}

fun evalBasicArithmetic(
    lhs: ExprConstantNode,
    rhs: ExprConstantNode,
    op: BinaryOperators,
    resType: TypeName
) = when (resType) {
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
fun evalBasicComparable(
    lhs: ExprConstantNode,
    rhs: ExprConstantNode,
    op: BinaryOperators,
    resType: TypeName
) = when (resType) {
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
    IntegerConstantNode(if (result) 1L else 0L)
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
    IntegerConstantNode(if (result) 1L else 0L)
  }
  else -> TODO("unknown result type")
}

fun evalIntOnlyOps(
    lhs: ExprConstantNode,
    rhs: ExprConstantNode,
    op: BinaryOperators
): IntegerConstantNode {
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
  return IntegerConstantNode(result)
}

fun evalBinary(
    lhs: ExprConstantNode,
    rhs: ExprConstantNode,
    op: BinaryOperators,
    resType: TypeName
): ExprConstantNode = when (op) {
  BinaryOperators.LT, BinaryOperators.GT, BinaryOperators.LEQ, BinaryOperators.GEQ,
  BinaryOperators.EQ, BinaryOperators.NEQ -> {
    evalBasicComparable(lhs, rhs, op, resType)
  }
  BinaryOperators.MUL, BinaryOperators.DIV, BinaryOperators.ADD, BinaryOperators.SUB -> {
    evalBasicArithmetic(lhs, rhs, op, resType)
  }
  BinaryOperators.MOD, BinaryOperators.LSH, BinaryOperators.RSH, BinaryOperators.BIT_AND,
  BinaryOperators.BIT_XOR, BinaryOperators.BIT_OR, BinaryOperators.AND, BinaryOperators.OR -> {
    evalIntOnlyOps(lhs, rhs, op)
  }
  else -> logger.throwICE("Illegal constant binary operator") { op }
}

fun evalUnary(
    operand: ExprConstantNode,
    op: UnaryOperators
): ExprConstantNode = when (operand.type) {
  is FloatingType -> {
    val toApply = convertToFloat(operand)
    when (op) {
      UnaryOperators.REF, UnaryOperators.DEREF, UnaryOperators.BIT_NOT -> {
        logger.throwICE("Illegal constant unary operator") { op }
      }
      UnaryOperators.PLUS -> FloatingConstantNode(+toApply, FloatingSuffix.NONE)
      UnaryOperators.MINUS -> FloatingConstantNode(-toApply, FloatingSuffix.NONE)
      UnaryOperators.NOT -> IntegerConstantNode(if (toApply != 0.0) 1L else 0L)
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
    IntegerConstantNode(result)
  }
  else -> TODO("unknown result type")
}

fun evalCast(type: TypeName, target: ExprConstantNode): ExprConstantNode = when (type) {
  ErrorType -> ErrorExpression().withRange(target)
  VoidType -> VoidExpression().withRange(target)
  is IntegralType -> IntegerConstantNode(convertToInt(target))
  is FloatingType -> FloatingConstantNode(convertToFloat(target), FloatingSuffix.NONE)
  is PointerType -> TODO()
  is FunctionType -> TODO()
  is ArrayType -> TODO()
  is BitfieldType -> TODO()
  is StructureType -> TODO()
  is UnionType -> TODO()
}
