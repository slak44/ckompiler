package slak.ckompiler.analysis

import org.apache.logging.log4j.LogManager
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

private val logger = LogManager.getLogger()

/**
 * Find which [Variable]s in the [cfg] interfere (are concurrently alive).
 */
fun findInterferenceIn(cfg: CFG): List<Pair<Variable, Variable>> {
  val interference = mutableListOf<Pair<Variable, Variable>>()
  for ((variable, blocks) in cfg.definitions) {
    cfg.definitions
        .map { Pair(it.key, blocks.intersect(it.value)) }
        .filter { it.first != variable }
        .filter { (var2, possibleBlocks) ->
          possibleBlocks.any { doUsesIntersect(variable, var2, it) }
        }
        .forEach { interference += Pair(variable, it.first) }
  }
  return interference
}

/**
 * Check if two live ranges intersect.
 */
private fun doUsesIntersect(var1: Variable, var2: Variable, block: BasicBlock): Boolean {
  return liveRangeOf(var1, block).intersect(liveRangeOf(var2, block)).isNotEmpty()
}

private fun isVariableUsed(e: Expression, tid: TypedIdentifier): Boolean = when (e) {
  is ErrorExpression -> logger.throwICE("ErrorExpression was removed")
  is TernaryConditional -> logger.throwICE("TernaryConditional was removed")
  is SizeofTypeName -> logger.throwICE("SizeofTypeName was removed")
  is VoidExpression -> logger.throwICE("VoidExpression was removed")
  is IncDecOperation -> logger.throwICE("IncDecOperation was removed")
  is ExprConstantNode -> false
  is TypedIdentifier -> e == tid
  is FunctionCall -> e.args.any { isVariableUsed(it, tid) }
  is UnaryExpression -> isVariableUsed(e.operand, tid)
  is MemberAccessExpression -> isVariableUsed(e.target, tid)
  is BinaryExpression -> isVariableUsed(e.lhs, tid) || isVariableUsed(e.rhs, tid)
  is ArraySubscript -> isVariableUsed(e.subscripted, tid) || isVariableUsed(e.subscript, tid)
  is CastExpression -> isVariableUsed(e.target, tid)
}

/**
 * Return the live-range of a variable in a block, as indices in the [BasicBlock.src].
 *
 * Definitions in φ-functions count as -1 for the resulting range.
 */
private fun liveRangeOf(v: Variable, block: BasicBlock): IntRange {
  val start = if (v in block.phiFunctions.map { it.variable }) {
    -1
  } else {
    block.src.indexOfFirst {
      it is BinaryExpression &&
          it.op == BinaryOperators.ASSIGN &&
          it.lhs is TypedIdentifier &&
          it.lhs == v.tid
    }
  }
  val end = block.src.indexOfLast { isVariableUsed(it, v.tid) }
  return start..end
}
