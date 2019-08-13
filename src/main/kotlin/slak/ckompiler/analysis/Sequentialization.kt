package slak.ckompiler.analysis

import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

/**
 * A bunch of expressions that should be equivalent to the original expression that was
 * sequentialized.
 *
 * What remains of the original expression after sequentialization will be the [remaining]
 * expression. The other lists can be empty.
 *
 * [remaining] acts as a sequence point for the other 2 lists.
 */
data class SequentialExpression(val before: List<Expression>,
                                val remaining: Expression,
                                val after: List<Expression>) {
  operator fun iterator(): Iterator<Expression> = iterator {
    yieldAll(before.iterator())
    yield(remaining)
    yieldAll(after.iterator())
  }

  fun toList(): List<Expression> = before + remaining + after
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
 * after returning the result, so we are allowed to move the update after the expression.
 *
 * The comma operator is a sequence point (6.5.17), so the lhs expression is sequenced before the
 * rhs. We can simply separate them because lhs is evaluated as a void expression, and can't be used
 * as an operand to anything.
 *
 * For function calls, there is a sequence point after the evaluation of the function designator and
 * the arguments, and before the actual call. This means two function calls cannot interleave (see
 * note 94).
 *
 * C standard: C.1, 5.1.2.3, 6.5.16.0.3, 6.5.3.1.0.2, 6.5.2.4.0.2, 6.5.17, 6.5.2.2.0.10
 * @see SequentialExpression
 */
fun IDebugHandler.sequentialize(expr: Expression): SequentialExpression {
  val sequencedBefore = mutableListOf<Expression>()
  val sequencedAfter = mutableListOf<Expression>()
  val modifications = mutableMapOf<TypedIdentifier, MutableList<Expression>>()
  fun Expression.seqImpl(): Expression = when (this) {
    is ErrorExpression -> logger.throwICE("ErrorExpression was removed")
    is FunctionCall -> {
      FunctionCall(calledExpr.seqImpl(), args.map(Expression::seqImpl)).withRange(tokenRange)
    }
    is PrefixIncrement, is PrefixDecrement, is PostfixIncrement, is PostfixDecrement -> {
      val incDec = (this as IncDecOperation).expr
      if (this is PrefixIncrement || this is PrefixDecrement) sequencedBefore += this
      else sequencedAfter += this
      if (incDec is TypedIdentifier) {
        modifications.getOrPut(incDec, ::mutableListOf).add(this)
        incDec.copy().withRange(incDec.tokenRange)
      } else {
        // FIXME: we might need to make a copy of lhs, like we do for the other case
        //  let's just see if this is a problem first
        incDec.seqImpl()
      }
    }
    is BinaryExpression -> {
      // FIXME: there are sequence points with && || (also short-circuiting)
      //  so we can't just pull out the assignment in something like a == null && a = 42
      if (op in assignmentOps) {
        // Hoist assignments out of expressions
        sequencedBefore += this
        if (lhs is TypedIdentifier) {
          modifications.getOrPut(lhs, ::mutableListOf).add(this)
          lhs.copy().withRange(lhs.tokenRange)
        } else {
          // FIXME: we might need to make a copy of lhs, like we do for the other case
          //  let's just see if this is a problem first
          lhs.seqImpl()
        }
      } else if (op == BinaryOperators.COMMA) {
        sequencedBefore += sequentialize(lhs).toList()
        rhs.seqImpl()
      } else {
        BinaryExpression(op, lhs.seqImpl(), rhs.seqImpl()).withRange(tokenRange)
      }
    }
    is CastExpression, is ArraySubscript, is UnaryExpression,
    is SizeofExpression, is SizeofTypeName, is TypedIdentifier, is IntegerConstantNode,
    is FloatingConstantNode, is CharacterConstantNode, is StringLiteralNode -> {
      // Do nothing. These do not pose the problem of being sequenced before or after.
      this
    }
  }

  val remaining = expr.seqImpl()
  // Take every variable with more than 1 modification and print diagnostics
  for ((variable, modList) in modifications.filter { it.value.size > 1 }) diagnostic {
    id = DiagnosticId.UNSEQUENCED_MODS
    formatArgs(variable.name)
    for (mod in modList) when (mod) {
      is BinaryExpression -> columns(mod.lhs.tokenRange)
      is IncDecOperation -> columns(mod.tokenRange)
      else -> logger.throwICE("Modification doesn't modify anything") { mod }
    }
  }
  return SequentialExpression(sequencedBefore, remaining, sequencedAfter)
}
