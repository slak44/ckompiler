package slak.ckompiler.analysis

import org.apache.logging.log4j.LogManager
import slak.ckompiler.lexer.IntegralSuffix
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

private val logger = LogManager.getLogger("Lowering")

/**
 * These map to the analogous [slak.ckompiler.parser.BinaryOperators], but with the notable
 * exceptions of assignment and comma (which are treated separately).
 *
 * @param s exists for pretty-printing this class
 */
enum class BinaryComputations(val s: String) {
  ADD("+"), SUBSTRACT("-"), MULTIPLY("*"), DIVIDE("/"), REMAINDER("%"),
  LEFT_SHIFT("<<"), RIGHT_SHIFT(">>"),
  LESS_THAN("<"), GREATER_THAN(">"), LESS_EQUAL_THAN("<="), GREATER_EQUAL_THAN(">="),
  EQUAL("=="), NOT_EQUAL("!="),
  BITWISE_AND("&"), BITWISE_OR("|"), BITWISE_XOR("^"),
  LOGICAL_AND("&&"), LOGICAL_OR("||");

  override fun toString() = s
}

/**
 * [BinaryOperators] to [BinaryComputations] mapping.
 */
fun BinaryOperators.asBinaryOperation() = when (this) {
  in assignmentOps -> logger.throwICE("Assignment isn't a binary computation")
  BinaryOperators.COMMA -> logger.throwICE("Commas must be removed by sequentialize")
  BinaryOperators.MUL -> BinaryComputations.MULTIPLY
  BinaryOperators.DIV -> BinaryComputations.DIVIDE
  BinaryOperators.MOD -> BinaryComputations.REMAINDER
  BinaryOperators.ADD -> BinaryComputations.ADD
  BinaryOperators.SUB -> BinaryComputations.SUBSTRACT
  BinaryOperators.LSH -> BinaryComputations.LEFT_SHIFT
  BinaryOperators.RSH -> BinaryComputations.RIGHT_SHIFT
  BinaryOperators.LT -> BinaryComputations.LESS_THAN
  BinaryOperators.GT -> BinaryComputations.GREATER_THAN
  BinaryOperators.LEQ -> BinaryComputations.LESS_EQUAL_THAN
  BinaryOperators.GEQ -> BinaryComputations.GREATER_EQUAL_THAN
  BinaryOperators.EQ -> BinaryComputations.EQUAL
  BinaryOperators.NEQ -> BinaryComputations.NOT_EQUAL
  BinaryOperators.BIT_AND -> BinaryComputations.BITWISE_AND
  BinaryOperators.BIT_XOR -> BinaryComputations.BITWISE_XOR
  BinaryOperators.BIT_OR -> BinaryComputations.BITWISE_OR
  BinaryOperators.AND -> BinaryComputations.LOGICAL_AND
  BinaryOperators.OR -> BinaryComputations.LOGICAL_OR
  else -> logger.throwICE("Impossible branch (kotlin compiler can't see this)")
}

/** An expression between 2 known operands; not a tree. */
sealed class ComputeExpression

/** Basic pieces of data for [ComputeExpression]s. */
sealed class ComputeConstant : ComputeExpression()

/** @see IntegerConstantNode */
data class ComputeInteger(val int: IntegerConstantNode) : ComputeConstant() {
  override fun toString() = int.toString()
}

/** @see FloatingConstantNode */
data class ComputeFloat(val float: FloatingConstantNode) : ComputeConstant() {
  override fun toString() = float.toString()
}

/** @see CharacterConstantNode */
data class ComputeChar(val char: CharacterConstantNode) : ComputeConstant() {
  override fun toString() = char.toString()
}

/** @see StringLiteralNode */
data class ComputeString(val str: StringLiteralNode) : ComputeConstant() {
  override fun toString() = str.toString()
}

/** @see TypedIdentifier */
data class ComputeReference(val id: TypedIdentifier) : ComputeConstant() {
  override fun toString() = id.toString()
}

/**
 * Similar to [BinaryExpression], but doesn't support commas and assignments, and has
 * [ComputeConstant] arguments.
 */
data class BinaryComputation(val op: BinaryComputations,
                             val lhs: ComputeConstant,
                             val rhs: ComputeConstant) : ComputeExpression() {
  override fun toString() = "$lhs $op $rhs"
}

/** @see UnaryExpression */
data class UnaryComputation(val op: UnaryOperators,
                            val operand: ComputeConstant) : ComputeExpression() {
  override fun toString() = "$op$operand"
}

/** Represents an instruction in the intermediate representation. */
interface IRExpression

/** The instruction for a function call in IR. */
data class Call(val functionPointer: ComputeConstant,
                val args: List<ComputeConstant>) : ComputeExpression(), IRExpression {
  override fun toString() = "$functionPointer(${args.joinToString(", ")})"
}

/**
 * The instruction to store a [ComputeExpression]'s result to a target [ComputeReference] variable.
 * @param isSynthetic if this store was generated when converting to the IR
 */
data class Store(val target: ComputeReference,
                 val data: ComputeExpression,
                 val isSynthetic: Boolean) : IRExpression {
  override fun toString() = "${if (isSynthetic) "[SYNTHETIC] " else ""}$target = $data"
}

data class IRLoweringContext(val ir: MutableList<IRExpression> = mutableListOf()) {
  private val tempIds = IdCounter()

  fun makeTemporary(type: TypeName): ComputeReference {
    return ComputeReference(TypedIdentifier("__synthetic_block_temp_${tempIds()}", type))
  }
}

private fun getAssignable(target: Expression): ComputeReference {
  return if (target is TypedIdentifier) {
    ComputeReference(target)
  } else {
    TODO("no idea how to handle this case for now")
  }
}

/**
 * @return assigned variable
 */
private fun IRLoweringContext.transformAssign(target: Expression,
                                              data: Expression): ComputeReference {
  val irTarget = getAssignable(target)
  ir += Store(irTarget, transformExpr(data), isSynthetic = false)
  return irTarget
}

private val compoundAssignOps = mapOf(
    BinaryOperators.MUL_ASSIGN to BinaryComputations.MULTIPLY,
    BinaryOperators.DIV_ASSIGN to BinaryComputations.DIVIDE,
    BinaryOperators.MOD_ASSIGN to BinaryComputations.REMAINDER,
    BinaryOperators.PLUS_ASSIGN to BinaryComputations.ADD,
    BinaryOperators.SUB_ASSIGN to BinaryComputations.SUBSTRACT,
    BinaryOperators.LSH_ASSIGN to BinaryComputations.LEFT_SHIFT,
    BinaryOperators.RSH_ASSIGN to BinaryComputations.RIGHT_SHIFT,
    BinaryOperators.AND_ASSIGN to BinaryComputations.BITWISE_AND,
    BinaryOperators.XOR_ASSIGN to BinaryComputations.BITWISE_XOR,
    BinaryOperators.OR_ASSIGN to BinaryComputations.BITWISE_OR
)

/**
 * @return assigned variable
 */
private fun IRLoweringContext.transformCompoundAssigns(expr: BinaryExpression): ComputeReference {
  if (expr.op == BinaryOperators.ASSIGN) return transformAssign(expr.lhs, expr.rhs)
  val additionalOperation = compoundAssignOps[expr.op]
      ?: logger.throwICE("Only assignments must get here") { expr }
  val assignTarget = getAssignable(expr.lhs)
  val additionalComputation = BinaryComputation(
      additionalOperation, assignTarget, transformExpr(expr.rhs))
  val assignableResult = makeTemporary(expr.lhs.type)
  ir += Store(assignableResult, additionalComputation, isSynthetic = true)
  ir += Store(assignTarget, assignableResult, isSynthetic = false)
  return assignTarget
}

/**
 * FIXME: we could do constant folding around here maybe
 *
 * @return a variable containing the result of the expression
 */
private fun IRLoweringContext.transformBinary(expr: BinaryExpression): ComputeReference {
  if (expr.op in assignmentOps) return transformCompoundAssigns(expr)
  val operation = expr.op.asBinaryOperation()
  val binary = BinaryComputation(operation, transformExpr(expr.lhs), transformExpr(expr.rhs))
  val target = makeTemporary(expr.type)
  ir += Store(target, binary, isSynthetic = true)
  return target
}

/**
 * Because of [sequentialize], [IncDecOperation]s can only be found by themselves, not as part of
 * other expressions, so we can just treat both prefix/postfix as being `+= 1` or `-= 1`.
 */
private fun IRLoweringContext.transformIncDec(expr: IncDecOperation, isDec: Boolean): ComputeReference {
  val op = if (isDec) BinaryOperators.SUB_ASSIGN else BinaryOperators.PLUS_ASSIGN
  val one = IntegerConstantNode(1, IntegralSuffix.NONE)
  val incremented = BinaryExpression(op, expr.expr, one)
  return transformCompoundAssigns(incremented)
}

/**
 * @return a variable containing the result of the expression
 */
private fun IRLoweringContext.transformUnary(expr: UnaryExpression): ComputeReference {
  val target = makeTemporary(expr.type)
  ir += Store(target, UnaryComputation(expr.op, transformExpr(expr.operand)), isSynthetic = true)
  return target
}

private fun IRLoweringContext.transformExpr(expr: Expression): ComputeConstant = when (expr) {
  is ErrorExpression -> logger.throwICE("ErrorExpression was removed")
  is TypedIdentifier -> ComputeReference(expr)
  is FunctionCall -> TODO("can't implement this before dealing with them in sequentialize")
  is UnaryExpression -> transformUnary(expr)
  is PrefixIncrement, is PostfixIncrement ->
    transformIncDec(expr as IncDecOperation, isDec = false)
  is PrefixDecrement, is PostfixDecrement ->
    transformIncDec(expr as IncDecOperation, isDec = true)
  is BinaryExpression -> transformBinary(expr)
  is SizeofExpression, is SizeofTypeName ->
    TODO("these are also sort of constants, have to be integrated into IRConstantExpression")
  is IntegerConstantNode -> ComputeInteger(expr)
  is FloatingConstantNode -> ComputeFloat(expr)
  is CharacterConstantNode -> ComputeChar(expr)
  is StringLiteralNode -> ComputeString(expr)
}

/**
 * Transforms a top-level expression passed through [sequentialize] to a list of [IRExpression]s.
 */
private fun IRLoweringContext.transform(topLevelExpr: Expression) {
  when (topLevelExpr) {
    is ErrorExpression -> logger.throwICE("ErrorExpression was removed")
    is FunctionCall -> TODO()
    is UnaryExpression -> transformUnary(topLevelExpr)
    is PrefixIncrement, is PostfixIncrement ->
      transformIncDec(topLevelExpr as IncDecOperation, isDec = false)
    is PrefixDecrement, is PostfixDecrement ->
      transformIncDec(topLevelExpr as IncDecOperation, isDec = true)
    is BinaryExpression -> transformBinary(topLevelExpr)
    is SizeofExpression, is SizeofTypeName, is IntegerConstantNode, is FloatingConstantNode,
    is StringLiteralNode, is CharacterConstantNode, is TypedIdentifier -> {
      // Do nothing.
      // If any of these are found "floating" by themselves, they come from the program source, or
      // they are a result of [sequentialize]
      // Either way, they do nothing by themselves, so they can be discarded
      // FIXME: someone (probably the Parser) needs to warn about unused expression results
      // FIXME: a read to a TypedIdentifier might not be discarded if it is volatile
    }
  }
}

/**
 * Transforms a block of expressions to the equivalent IR version.
 */
fun List<Expression>.toIRList(): List<IRExpression> {
  val context = IRLoweringContext()
  forEach(context::transform)
  return context.ir
}
