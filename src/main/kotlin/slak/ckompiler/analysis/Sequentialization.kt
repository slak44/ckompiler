package slak.ckompiler.analysis

import io.github.oshai.kotlinlogging.KotlinLogging
import slak.ckompiler.*
import slak.ckompiler.parser.*

private val logger = KotlinLogging.logger {}

/**
 * A bunch of expressions that should be equivalent to the original expression that was
 * sequentialized.
 *
 * What remains of the original expression after sequentialization will be the [remaining]
 * expression. The other list can be empty.
 *
 * [remaining] is sequenced AFTER [before].
 */
data class SequentialExpression(
    val before: List<Expression>,
    val remaining: Expression,
) {
  operator fun iterator(): Iterator<Expression> = iterator {
    yieldAll(before.iterator())
    yield(remaining)
  }

  fun toList(): List<Expression> = before + remaining
}

private data class SequentializationContext(
    val sequencedBefore: MutableList<Expression> = mutableListOf(),
    val modifications: MutableMap<TypedIdentifier, MutableList<Expression>> = mutableMapOf(),
    val debugHandler: IDebugHandler,
) : IDebugHandler by debugHandler

private val syntheticIds = IdCounter()

private fun SequentializationContext.makeAssignmentTarget(
    modified: Expression,
    modExpr: Expression,
): Expression {
  require(modified.valueType != ValueType.RVALUE) {
    "Assignment target can't be an rvalue"
  }
  return if (modified is TypedIdentifier) {
    modifications.getOrPut(modified, ::mutableListOf).add(modExpr)
    modified.copy().withRange(modified)
  } else {
    // FIXME: we might need to make a copy of lhs, like we do for the other case
    //  let's just see if this is a problem first
    seqImpl(modified)
  }
}

/**
 * Breaks down compound assignments into the equivalent simple assignment, eg `a += 2 + 3` becomes
 * `a = a + (2 + 3)`.
 */
private fun dismantleCompound(
    op: BinaryOperators,
    assignTarget: Expression,
    rhs: Expression,
    range: SourcedRange,
): BinaryExpression {
  val extraOp = compoundAssignOps.getValue(op)
  val (extraOpType, extraCommon) = extraOp.applyTo(assignTarget.type, rhs.type)
  val nLhs = convertToCommon(extraCommon, assignTarget)
  val nRhs = convertToCommon(extraCommon, rhs)
  val extraExpr = BinaryExpression(extraOp, nLhs, nRhs, extraOpType).withRange(range)
  return BinaryExpression(
      op = BinaryOperators.ASSIGN,
      lhs = assignTarget,
      rhs = convertToCommon(assignTarget.type, extraExpr),
      type = assignTarget.type
  ).withRange(range)
}

private fun SequentializationContext.handleAssignments(e: BinaryExpression): Expression {
  val assignTarget = makeAssignmentTarget(e.lhs, e)
  // Move assignments before the expression
  sequencedBefore += if (e.op != BinaryOperators.ASSIGN) {
    dismantleCompound(e.op, assignTarget, seqImpl(e.rhs), e)
  } else {
    BinaryExpression(e.op, seqImpl(e.lhs), seqImpl(e.rhs), e.type).withRange(e)
  }
  return assignTarget
}

/**
 * `x == 1 && y == 1` is equivalent to `x == 1 ? (y == 1) : 0`.
 */
private fun SequentializationContext.seqLogicalAnd(e: BinaryExpression): Expression {
  require(e.op == BinaryOperators.AND)
  return seqImpl(
      TernaryConditional(e.lhs, e.rhs, IntegerConstantNode(0).withRange(e)).withRange(e)
  )
}

/**
 * `x == 1 || y == 1` is equivalent to `x == 1 ? 1 : (y == 1)`.
 */
private fun SequentializationContext.seqLogicalOr(e: BinaryExpression): Expression {
  require(e.op == BinaryOperators.OR)
  return seqImpl(
      TernaryConditional(e.lhs, IntegerConstantNode(1).withRange(e), e.rhs).withRange(e)
  )
}

/**
 * Fold certain no-ops.
 *
 * C standard: 6.5.3.2.0.3
 */
private fun SequentializationContext.foldUnary(e: UnaryExpression): Expression = when {
  // Other unary ops are not "interesting"
  e.op != UnaryOperators.REF -> e.copy(operand = seqImpl(e.operand)).withRange(e)
  // Fold &*a into just a
  e.operand is UnaryExpression && e.operand.op == UnaryOperators.DEREF -> seqImpl(e.operand.operand)
  // Fold &v[20] into v + 20
  e.operand is ArraySubscript -> BinaryExpression(
      BinaryOperators.ADD,
      seqImpl(e.operand.subscripted),
      seqImpl(e.operand.subscript),
      e.operand.subscripted.type.normalize()
  ).withRange(e)
  else -> e.copy(operand = seqImpl(e.operand)).withRange(e)
}

/** Recursive case of [sequentialize]. */
private fun SequentializationContext.seqImpl(e: Expression): Expression = when (e) {
  is ErrorExpression -> logger.throwICE("ErrorExpression was removed")
  is VoidExpression -> logger.throwICE("VoidExpression was removed")
  is FunctionCall -> FunctionCall(seqImpl(e.calledExpr), e.args.map(::seqImpl)).withRange(e)
  is MemberAccessExpression -> e.copy(target = seqImpl(e.target).withRange(e.target))
  is IncDecOperation -> {
    val incDecTarget = makeAssignmentTarget(e.expr, e)
    val op = if (e.isDecrement) BinaryOperators.SUB_ASSIGN else BinaryOperators.PLUS_ASSIGN
    // These are equivalent to compound assignments, so reuse that code
    val dismantled = dismantleCompound(op, incDecTarget, IntegerConstantNode(1).withRange(e), e)
    if (e.isPostfix) {
      // Make copy of pre-inc/dec value
      val copyBeforeOp = TypedIdentifier("__postfix_copy_${syntheticIds()}", e.type).withRange(e)
      sequencedBefore += BinaryExpression(
          BinaryOperators.ASSIGN,
          copyBeforeOp,
          incDecTarget,
          incDecTarget.type
      ).withRange(e.expr)
      sequencedBefore += dismantled
      copyBeforeOp
    } else {
      sequencedBefore += dismantled
      incDecTarget
    }
  }
  is BinaryExpression -> when (e.op) {
    BinaryOperators.AND -> seqLogicalAnd(e)
    BinaryOperators.OR -> seqLogicalOr(e)
    in assignmentOps -> handleAssignments(e)
    BinaryOperators.COMMA -> {
      sequencedBefore += sequentialize(e.lhs).toList()
      seqImpl(e.rhs)
    }
    else -> e.copy(lhs = seqImpl(e.lhs), rhs = seqImpl(e.rhs)).withRange(e)
  }
  is TernaryConditional -> {
    // This is synthetic, but it must participate in SSA construction, unlike the synthetic
    // temporaries, because unlike them, it is guaranteed to be used in multiple basic blocks
    val fakeAssignable = TypedIdentifier("__ternary_target_${syntheticIds()}", e.type).withRange(e)
    // Don't sequentialize e.success/e.failure, because one of them will not be executed
    // This is dealt with more in ASTGraphing
    val fakeAssignment =
        BinaryExpression(BinaryOperators.ASSIGN, fakeAssignable, e, fakeAssignable.type)
    sequencedBefore += fakeAssignment.withRange(e)
    fakeAssignable
  }
  is UnaryExpression -> foldUnary(e)
  is ArraySubscript -> {
    e.copy(subscripted = seqImpl(e.subscripted), subscript = seqImpl(e.subscript)).withRange(e)
  }
  is CastExpression -> e.copy(target = seqImpl(e.target)).withRange(e)
  is SizeofTypeName, is TypedIdentifier, is IntegerConstantNode,
  is FloatingConstantNode, is CharacterConstantNode, is StringLiteralNode,
  -> {
    // Do nothing. These do not pose the problem of being sequenced before or after.
    e
  }
}

/**
 * Resolve sequencing issues within an expression.
 *
 * Assignment expressions have the value of the left operand after the assignment; we are allowed
 * by note 111 to read the stored object to determine the value, which means we can put the
 * assignment first, and replace it with a read of its target.
 *
 * According to 6.5.3.1.0.2, prefix ++ and -- are equivalent to `(E+=1)` and `(E-=1)`, so the same
 * considerations for assignment expressions apply for them too.
 *
 * According to 6.5.2.4.0.2, for postfix ++ and --, updating the value of the operand is sequenced
 * after returning the result, so we are allowed to move the update after the expression. This is
 * achieved by making a copy, updating the original value, and replacing the use of the value in the
 * postfix expression. Things like `int y = x++ + x;` are unsequenced, so we can do this and be
 * correct.
 *
 * The comma operator is a sequence point (6.5.17), so the lhs expression is sequenced before the
 * rhs. We can simply separate them because lhs is evaluated as a void expression, and can't be used
 * as an operand to anything.
 *
 * For function calls, there is a sequence point after the evaluation of the function designator and
 * the arguments, and before the actual call. This means two function calls cannot interleave (see
 * note 94).
 *
 * The ternary conditional operator has a sequence point between the evaluation of its first operand
 * (the condition) and whichever of the second/third operands is chosen to be executed. This
 * behaviour is achieved by deferring to the CFG: the ternary is hoisted out of the expression, its
 * result assigned to a synthetic temporary, which is then used in the conditional's original place.
 *
 * Logical ANDs and Logical ORs are implemented by lowering them to ternary conditionals. This means
 * we won't be able to implement them with efficient CPU instructions in codegen (eg `and`/`or`),
 * but observing their short-circuit behaviour prevented that anyway.
 *
 * C standard: C.1, 5.1.2.3, 6.5.16.0.3, 6.5.3.1.0.2, 6.5.2.4.0.2, 6.5.17, 6.5.2.2.0.10, 6.5.15.0.4,
 *   6.5.13.0.4, 6.5.14.0.4
 * @see SequentialExpression
 */
fun IDebugHandler.sequentialize(expr: Expression): SequentialExpression {
  val ctx = SequentializationContext(debugHandler = this)
  val remaining = ctx.seqImpl(expr)
  // FIXME: unsequenced mod + access is also a problem: x++ + x
  // Take every variable with more than 1 modification and print diagnostics
  for ((variable, modList) in ctx.modifications.filter { it.value.size > 1 }) diagnostic {
    id = DiagnosticId.UNSEQUENCED_MODS
    formatArgs(variable.name)
    for (mod in modList) when (mod) {
      is BinaryExpression -> errorOn(mod.lhs)
      is IncDecOperation -> errorOn(mod)
      else -> logger.throwICE("Modification doesn't modify anything") { mod }
    }
  }
  return SequentialExpression(ctx.sequencedBefore, remaining)
}
