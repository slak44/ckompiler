package slak.ckompiler.analysis

import slak.ckompiler.MachineTargetData
import slak.ckompiler.parser.*

/**
 * Run this AFTER [sequentialize]. This assumes that stuff like assignments and increment were
 * hoisted out of their expressions.
 *
 * FIXME: constant propagation
 */
fun MachineTargetData.doConstantFolding(expr: Expression): Expression = when (expr) {
  is ExprConstantNode -> expr
  is SizeofTypeName -> IntegerConstantNode(sizeOf(expr.sizeOfWho).toLong()).withRange(expr)
  is UnaryExpression -> {
    val foldedOperand = doConstantFolding(expr.operand).withRange(expr.operand)
    if (foldedOperand is ExprConstantNode) {
      evalUnary(foldedOperand, expr.op).withRange(expr)
    } else {
      expr.copy(operand = foldedOperand).withRange(expr)
    }
  }
  is BinaryExpression -> {
    val lhs = doConstantFolding(expr.lhs).withRange(expr.lhs)
    val rhs = doConstantFolding(expr.rhs).withRange(expr.rhs)
    if (lhs is ExprConstantNode && rhs is ExprConstantNode) {
      evalBinary(lhs, rhs, expr.op, expr.type).withRange(expr)
    } else {
      expr.copy(lhs = lhs, rhs = rhs).withRange(expr)
    }
  }
  is CastExpression -> {
    val target = doConstantFolding(expr.target).withRange(expr.target)
    if (target is ExprConstantNode) {
      evalCast(expr.type, target).withRange(expr)
    } else {
      expr.copy(target = target).withRange(expr)
    }
  }
  is ArraySubscript -> ArraySubscript(
      subscripted = doConstantFolding(expr.subscripted).withRange(expr.subscripted),
      subscript = doConstantFolding(expr.subscript).withRange(expr.subscript),
      type = expr.type
  ).withRange(expr)
  is FunctionCall -> FunctionCall(
      calledExpr = doConstantFolding(expr.calledExpr).withRange(expr.calledExpr),
      args = expr.args.map { doConstantFolding(it).withRange(it) }
  ).withRange(expr)
  is TernaryConditional -> TernaryConditional(
      cond = doConstantFolding(expr.cond).withRange(expr.cond),
      success = doConstantFolding(expr.success).withRange(expr.success),
      failure = doConstantFolding(expr.failure).withRange(expr.failure)
  ).withRange(expr)
  is TypedIdentifier -> expr
  is PrefixIncrement -> expr
  is PrefixDecrement -> expr
  is PostfixIncrement -> expr
  is PostfixDecrement -> expr
}
