package slak.ckompiler.analysis

import org.apache.logging.log4j.LogManager
import slak.ckompiler.MachineTargetData
import slak.ckompiler.lexer.Punctuators
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

private val logger = LogManager.getLogger()

/**
 * These (mostly) map to the analogous [slak.ckompiler.parser.BinaryOperators], but with the notable
 * exceptions of assignment and comma (which are treated separately).
 *
 * @param s exists for pretty-printing this class
 */
enum class BinaryComputations(private val s: String) {
  ADD("+"), SUBSTRACT("-"), MULTIPLY("*"), DIVIDE("/"), REMAINDER("%"),
  LEFT_SHIFT("<<"), RIGHT_SHIFT(">>"),
  LESS_THAN("<"), GREATER_THAN(">"), LESS_EQUAL_THAN("<="), GREATER_EQUAL_THAN(">="),
  EQUAL("=="), NOT_EQUAL("!="),
  BITWISE_AND("&"), BITWISE_OR("|"), BITWISE_XOR("^"),
  LOGICAL_AND("&&"), LOGICAL_OR("||"),
  SUBSCRIPT("[]");

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

/** @see BinaryComputations */
enum class UnaryComputations(private val op: String) {
  REF(Punctuators.AMP.s), DEREF(Punctuators.STAR.s),
  MINUS(Punctuators.MINUS.s), BIT_NOT(Punctuators.TILDE.s), NOT(Punctuators.NOT.s);

  override fun toString() = op
}

fun UnaryOperators.asUnaryOperations(): UnaryComputations = when (this) {
  UnaryOperators.REF -> UnaryComputations.REF
  UnaryOperators.DEREF -> UnaryComputations.DEREF
  UnaryOperators.PLUS -> logger.throwICE("Must be removed by IRLowering") { this }
  UnaryOperators.MINUS -> UnaryComputations.MINUS
  UnaryOperators.BIT_NOT -> UnaryComputations.BIT_NOT
  UnaryOperators.NOT -> UnaryComputations.NOT
}

// FIXME: retarded use of open instead of abstract properties
/** An expression between 2 known operands; not a tree. */
sealed class ComputeExpression(open val kind: OperationTarget, open val resType: TypeName)

/** Basic pieces of data for [ComputeExpression]s. */
sealed class ComputeConstant(
    override val kind: OperationTarget,
    override val resType: TypeName
) : ComputeExpression(kind, resType)

/** @see IntegerConstantNode */
data class ComputeInteger(
    val int: IntegerConstantNode
) : ComputeConstant(OperationTarget.INTEGER, int.type) {
  override fun toString() = int.toString()

  fun negate() = ComputeInteger(int.copy(value = -int.value))
}

/** @see FloatingConstantNode */
data class ComputeFloat(
    val float: FloatingConstantNode
) : ComputeConstant(OperationTarget.SSE, float.type) {
  override fun toString() = float.toString()

  fun negate() = ComputeFloat(float.copy(value = -float.value))
}

/** @see CharacterConstantNode */
data class ComputeChar(
    val char: CharacterConstantNode
) : ComputeConstant(OperationTarget.INTEGER, char.type) {
  override fun toString() = char.toString()

  fun negate() = ComputeChar(char.copy(char = -char.char))
}

/** @see StringLiteralNode */
data class ComputeString(
    val str: StringLiteralNode
) : ComputeConstant(OperationTarget.INTEGER, str.type) {
  override fun toString() = str.toString()
}

/**
 * Wraps [tid], adds [version] for variable renaming.
 * @see TypedIdentifier
 */
data class ComputeReference(
    val tid: TypedIdentifier,
    val isSynthetic: Boolean
) : ComputeConstant(tid.type.operationTarget(), tid.type), IRExpression {
  var version = 0

  /**
   * Sets the version from the [newerVersion] parameter.
   */
  fun replaceWith(newerVersion: ReachingDef?) {
    if (newerVersion == null) return
    if (tid.id != newerVersion.variable.tid.id || newerVersion.variable.version < version) {
      logger.throwICE("Illegal ComputeReference version replacement") { "$this -> $newerVersion" }
    }
    version = newerVersion.variable.version
  }

  override fun toString() = "$tid v$version"

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ComputeReference) return false

    if (tid != other.tid) return false
    if (tid.id != other.tid.id) return false
    if (version != other.version) return false

    return true
  }

  override fun hashCode(): Int {
    var result = tid.hashCode()
    result = 31 * result + version
    result = 31 * result + tid.id
    return result
  }
}

// FIXME: incomplete
enum class OperationTarget {
  INTEGER, SSE
}

private fun TypeName.operationTarget(): OperationTarget {
  return if (isSSEType()) OperationTarget.SSE else OperationTarget.INTEGER
}

private fun Expression.operationTarget() = type.operationTarget()

/**
 * Similar to [BinaryExpression], but doesn't support commas and assignments, and has
 * [ComputeConstant] arguments.
 */
data class BinaryComputation(
    val op: BinaryComputations,
    val lhs: ComputeConstant,
    val rhs: ComputeConstant,
    override val kind: OperationTarget,
    override val resType: TypeName
) : ComputeExpression(kind, resType) {
  override fun toString() = "$lhs $op $rhs"
}

/** @see UnaryExpression */
data class UnaryComputation(
    val op: UnaryComputations,
    val operand: ComputeConstant,
    override val kind: OperationTarget,
    override val resType: TypeName
) : ComputeExpression(kind, resType) {
  override fun toString() = "$op$operand"
}

/**
 * Represents a type cast in the IR. [kind] will refer to the resulting "thing" (ie [targetType]).
 */
data class CastComputation(
    val targetType: TypeName,
    val operand: ComputeConstant,
    override val kind: OperationTarget,
    override val resType: TypeName
) : ComputeExpression(kind, resType) {
  override fun toString() = "($targetType) $operand"
}

/** Represents an instruction in the intermediate representation. */
interface IRExpression

/** The instruction for a function call in IR. */
data class Call(
    val functionPointer: ComputeConstant,
    val args: List<ComputeConstant>,
    override val resType: TypeName
) : ComputeExpression(resType.operationTarget(), resType), IRExpression {
  override fun toString() =
      "${(functionPointer as? ComputeReference)?.tid?.name}(${args.joinToString(", ")})"
}

/**
 * The instruction to store a [ComputeExpression]'s result to a target [ComputeReference] variable.
 * @param isSynthetic if this store was generated when converting to the IR
 */
data class Store(
    val target: ComputeReference,
    val data: ComputeExpression,
    val isSynthetic: Boolean
) : IRExpression {
  override fun toString() = "${if (isSynthetic) "[SYNTHETIC] " else ""}$target = $data"
}

class IRLoweringContext {
  private val _src: MutableList<Expression> = mutableListOf()
  private val _ir: MutableList<IRExpression> = mutableListOf()
  val src: List<Expression> get() = _src
  val ir: List<IRExpression> get() = _ir
  private val enableFolding: Boolean
  private val tempIds: IdCounter
  private val targetData: MachineTargetData

  constructor(targetData: MachineTargetData, enableFolding: Boolean = true) {
    this.targetData = targetData
    this.enableFolding = enableFolding
    tempIds = IdCounter()
  }

  /**
   * Copy the synthetic variable counter from the [other] context. Useful when IR is in the same
   * block, but needs to be separated into 2 different contexts.
   *
   * Also copies [targetData] and [enableFolding] to avoid useless repetition.
   */
  constructor(other: IRLoweringContext) {
    tempIds = other.tempIds
    targetData = other.targetData
    enableFolding = other.enableFolding
  }

  private fun makeTemporary(type: TypeName): ComputeReference {
    val fakeTid = TypedIdentifier("__synthetic_block_temp_${tempIds()}", type)
    return ComputeReference(fakeTid, isSynthetic = true)
  }

  private fun getAssignable(target: Expression): ComputeReference {
    return if (target is TypedIdentifier) {
      ComputeReference(target, isSynthetic = false)
    } else {
      // FIXME: the way deref is handled is not really good, so this will kinda fail
      val tempTarget = makeTemporary(target.type)
      _ir += Store(tempTarget, transformExpr(target), isSynthetic = true)
      tempTarget
    }
  }

  /**
   * @return assigned variable
   */
  private fun transformAssign(target: Expression, data: Expression): ComputeReference {
    val irTarget = getAssignable(target)
    _ir += Store(irTarget, transformExpr(data), isSynthetic = false)
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
  private fun transformCompoundAssigns(expr: BinaryExpression): ComputeReference {
    if (expr.op == BinaryOperators.ASSIGN) return transformAssign(expr.lhs, expr.rhs)
    val additionalOperation = compoundAssignOps[expr.op]
        ?: logger.throwICE("Only assignments must get here") { expr }
    val assignTarget = getAssignable(expr.lhs)
    val additionalComputation = BinaryComputation(
        additionalOperation,
        assignTarget,
        transformExpr(expr.rhs),
        expr.operationTarget(),
        expr.type
    )
    val assignableResult = makeTemporary(expr.lhs.type)
    _ir += Store(assignableResult, additionalComputation, isSynthetic = true)
    _ir += Store(assignTarget, assignableResult, isSynthetic = false)
    return assignTarget
  }

  /**
   * FIXME: we could do constant folding around here maybe
   *
   * @return a variable containing the result of the expression
   */
  private fun transformBinary(expr: BinaryExpression): ComputeReference {
    if (expr.op in assignmentOps) return transformCompoundAssigns(expr)
    val operation = expr.op.asBinaryOperation()
    val binary = BinaryComputation(
        operation,
        transformExpr(expr.lhs),
        transformExpr(expr.rhs),
        expr.lhs.operationTarget(), // FIXME: always correct?
        expr.type
    )
    val target = makeTemporary(expr.type)
    _ir += Store(target, binary, isSynthetic = true)
    return target
  }

  private fun transformSubscript(arraySubscript: ArraySubscript): ComputeReference {
    val binary = BinaryComputation(
        BinaryComputations.SUBSCRIPT,
        transformExpr(arraySubscript.subscripted),
        transformExpr(arraySubscript.subscript),
        arraySubscript.operationTarget(),
        arraySubscript.type
    )
    val target = makeTemporary(arraySubscript.type)
    _ir += Store(target, binary, isSynthetic = true)
    return target
  }

  /**
   * Because of [sequentialize], [IncDecOperation]s can only be found by themselves, not as part of
   * other expressions, so we can just treat both prefix/postfix as being `+= 1` or `-= 1`.
   */
  private fun transformIncDec(expr: IncDecOperation, isDec: Boolean): ComputeReference {
    val op = if (isDec) BinaryOperators.SUB_ASSIGN else BinaryOperators.PLUS_ASSIGN
    // FIXME: this ignores a bunch of type checking, should do this entire thing in sequentialize
    val incremented = BinaryExpression(op, expr.expr, IntegerConstantNode(1), expr.expr.type)
    return transformCompoundAssigns(incremented)
  }

  /**
   * @return a variable containing the result of the expression, or any other kind of constant if
   * the operation was abstracted away
   */
  private fun transformUnary(expr: UnaryExpression): ComputeConstant {
    val irOperand = transformExpr(expr.operand)
    // This does absolutely nothing to the operand except that it performs integer promotions
    if (expr.op == UnaryOperators.PLUS) return irOperand
    // Same for minus, except it also negates the argument, so we might have to keep it
    if (expr.op == UnaryOperators.MINUS && irOperand !is ComputeReference) when (irOperand) {
      is ComputeInteger -> return irOperand.negate()
      is ComputeFloat -> return irOperand.negate()
      is ComputeChar -> return irOperand.negate()
      else -> logger.throwICE("Illegal type for unary minus") { expr }
    }
    val target = makeTemporary(expr.type)
    val unary =
        UnaryComputation(expr.op.asUnaryOperations(), irOperand, expr.operationTarget(), expr.type)
    _ir += Store(target, unary, isSynthetic = true)
    return target
  }

  /**
   * FIXME: how are void expressions handled? eg return f(); What ComputeReference do we return?
   */
  private fun transformCall(funCall: FunctionCall): Call {
    val funDesignator = transformExpr(funCall.calledExpr)
    val args = funCall.args.map(::transformExpr)
    return Call(funDesignator, args, funCall.type)
  }

  /**
   * @return a variable containing the function call result
   */
  private fun storeCall(call: Call, type: TypeName): ComputeReference {
    val target = makeTemporary(type)
    _ir += Store(target, call, isSynthetic = true)
    return target
  }

  private fun transformCast(cast: CastExpression): ComputeReference {
    val target = makeTemporary(cast.type)
    val castComp =
        CastComputation(cast.type, transformExpr(cast.target), cast.operationTarget(), cast.type)
    _ir += Store(target, castComp, isSynthetic = true)
    return target
  }

  private fun fold(expr: Expression) =
      if (enableFolding) targetData.doConstantFolding(expr) else expr

  private fun transformExpr(expr: Expression): ComputeConstant = when (val folded = fold(expr)) {
    is VoidExpression -> logger.throwICE("VoidExpression was removed")
    is ErrorExpression -> logger.throwICE("ErrorExpression was removed")
    is TernaryConditional -> logger.throwICE("TernaryConditional was removed")
    is SizeofTypeName -> logger.throwICE("SizeofTypeName was removed")
    is TypedIdentifier -> ComputeReference(folded, isSynthetic = false)
    is FunctionCall -> storeCall(transformCall(folded), folded.type)
    is UnaryExpression -> transformUnary(folded)
    is PrefixIncrement, is PostfixIncrement ->
      transformIncDec(folded as IncDecOperation, isDec = false)
    is PrefixDecrement, is PostfixDecrement ->
      transformIncDec(folded as IncDecOperation, isDec = true)
    is BinaryExpression -> transformBinary(folded)
    is ArraySubscript -> transformSubscript(folded)
    is CastExpression -> transformCast(folded)
    is IntegerConstantNode -> ComputeInteger(folded)
    is FloatingConstantNode -> ComputeFloat(folded)
    is CharacterConstantNode -> ComputeChar(folded)
    is StringLiteralNode -> ComputeString(folded)
  }

  /**
   * Transforms a top-level expression passed through [sequentialize] to a list of [IRExpression]s.
   */
  fun buildIR(topLevelExpr: Expression) {
    _src += topLevelExpr
    when (val folded = fold(topLevelExpr)) {
      is ErrorExpression -> logger.throwICE("ErrorExpression was removed")
      is TernaryConditional -> logger.throwICE("TernaryConditional was removed")
      is SizeofTypeName -> logger.throwICE("SizeofTypeName was removed")
      is VoidExpression -> logger.throwICE("VoidExpression was removed")
      // For top-level function calls, the return value is discarded, so don't bother storing it
      is FunctionCall -> _ir += transformCall(folded)
      is UnaryExpression -> transformUnary(folded)
      is PrefixIncrement, is PostfixIncrement ->
        transformIncDec(folded as IncDecOperation, isDec = false)
      is PrefixDecrement, is PostfixDecrement ->
        transformIncDec(folded as IncDecOperation, isDec = true)
      is BinaryExpression -> transformBinary(folded)
      is ArraySubscript -> transformSubscript(folded)
      is CastExpression -> transformCast(folded)
      // FIXME: except for volatile reads, this can go below, probably
      is TypedIdentifier -> _ir += ComputeReference(folded, isSynthetic = false)
      is IntegerConstantNode, is FloatingConstantNode,
      is StringLiteralNode, is CharacterConstantNode -> {
        // We don't discard those because they might be jump conditions/return values
        val target = makeTemporary(topLevelExpr.type)
        _ir += Store(target, transformExpr(topLevelExpr), isSynthetic = true)
        // FIXME: someone (probably the Parser) needs to warn about unused expression results
      }
    }
  }

  /** @see buildIR */
  fun buildIR(exprs: List<Expression>) {
    exprs.forEach(this::buildIR)
  }

  override fun toString() = src.toString()
}
