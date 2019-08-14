package slak.ckompiler.analysis

import slak.ckompiler.parser.Expression
import slak.ckompiler.parser.ReturnStatement

/** Returns a sequential integer on [invoke]. */
class IdCounter {
  private var counter = 0
  operator fun invoke() = counter++
}

/**
 * A φ-function that's part of a [BasicBlock]. [target] is the original pre-SSA variable, while
 * [incoming] stores the blocks that [target] can come from (ie the list of versions that the φ has
 * to choose from).
 */
data class PhiFunction(val target: ComputeReference,
                       val incoming: MutableMap<BasicBlock, ComputeReference>) {
  override fun toString() = "$target = φ(${incoming.entries.joinToString(", ") {
    "n${it.key.nodeId} v${it.value.version}"
  }})"
}

sealed class Jump {
  abstract val successors: List<BasicBlock>
}

/** If [cond] is true, jump to [target], otherwise jump to [other]. */
data class CondJump(val cond: IRLoweringContext,
                    val target: BasicBlock,
                    val other: BasicBlock) : Jump() {
  override val successors = listOf(target, other)
  override fun toString() = "CondJump<${target.nodeId}, ${other.nodeId}>$cond"
}

/** Unconditionally jump to [target]. */
data class UncondJump(val target: BasicBlock) : Jump() {
  override val successors = listOf(target)
  override fun toString() = "UncondJump<${target.nodeId}>"
}

/**
 * A so-called "impossible edge" of the CFG. Similar to [UncondJump], but will never be traversed.
 * It is created by [ReturnStatement].
 */
data class ImpossibleJump(val target: BasicBlock, val returned: IRLoweringContext?) : Jump() {
  override val successors = emptyList<BasicBlock>()
  override fun toString() = "ImpossibleJump($returned)"
}

/**
 * Similar to a combination of [UncondJump] and [ImpossibleJump].
 * Always jumps to [target], never to [impossible].
 */
data class ConstantJump(val target: BasicBlock, val impossible: BasicBlock) : Jump() {
  override val successors = listOf(target)
  override fun toString() = "ConstantJump<${target.nodeId}>$"
}

/** Indicates an incomplete [BasicBlock]. */
object MissingJump : Jump() {
  override val successors = emptyList<BasicBlock>()
}

/**
 * Stores a node of the [CFG], a basic block of [Expression]s who do not affect the control flow.
 *
 * Predecessors and successors do not track impossible edges.
 */
class BasicBlock(val isRoot: Boolean = false) {
  /**
   * List of SSA φ-functions in this block.
   */
  val phiFunctions = mutableListOf<PhiFunction>()
  /**
   * Contains this block's IR expression list.
   */
  val irContext = IRLoweringContext()

  val nodeId = nodeCounter()
  var postOrderId = -1
  var height = -1
  val preds: MutableSet<BasicBlock> = mutableSetOf()
  val successors get() = terminator.successors
  val dominanceFrontier: MutableSet<BasicBlock> = mutableSetOf()
  var terminator: Jump = MissingJump
    set(value) {
      field = value
      when (value) {
        is CondJump -> {
          value.target.preds += this
          value.other.preds += this
        }
        is ConstantJump -> value.target.preds += this
        is UncondJump -> value.target.preds += this
        is ImpossibleJump, MissingJump -> {
          // Intentionally left empty
        }
      }
    }
  val instructions
    get() = iterator {
      yieldAll(irContext.ir)
      (terminator as? CondJump)?.cond?.let { yieldAll(it.ir) }
      (terminator as? ImpossibleJump)?.returned?.let { yieldAll(it.ir) }
    }

  fun isTerminated() = terminator !is MissingJump

  fun isEmpty() = irContext.ir.isEmpty() && terminator !is CondJump

  /** Returns whether or not this block is reachable from its [preds]. */
  fun isReachable(): Boolean {
    if (isRoot) return true
    return preds.any { pred ->
      when (pred.terminator) {
        is UncondJump -> true
        is ConstantJump -> (pred.terminator as ConstantJump).target == this
        is CondJump -> {
          val t = pred.terminator as CondJump
          t.target == this || t.other == this
        }
        else -> false
      }
    }
  }

  /**
   * Collapses empty predecessor blocks to this one, if possible. Return true if a pred was
   * collapsed.
   */
  fun collapseEmptyPreds(): Boolean {
    var wasCollapsed = false
    emptyBlockLoop@ for (emptyBlock in preds.filter { it.isEmpty() }) {
      if (emptyBlock.isRoot) continue@emptyBlockLoop
      for (emptyBlockPred in emptyBlock.preds) {
        when (val oldTerm = emptyBlockPred.terminator) {
          is UncondJump -> {
            emptyBlockPred.terminator = UncondJump(this)
            preds += emptyBlockPred
          }
          is ImpossibleJump -> emptyBlockPred.terminator = ImpossibleJump(this, oldTerm.returned)
          is CondJump -> {
            emptyBlockPred.terminator = CondJump(
                oldTerm.cond,
                if (oldTerm.target == emptyBlock) this else oldTerm.target,
                if (oldTerm.other == emptyBlock) this else oldTerm.other
            )
            preds += emptyBlockPred
          }
          is ConstantJump -> {
            emptyBlockPred.terminator = ConstantJump(
                if (oldTerm.target == emptyBlock) this else oldTerm.target,
                if (oldTerm.impossible == emptyBlock) this else oldTerm.impossible
            )
            // Only add this to preds if it was not the impossible jump
            if (oldTerm.target == emptyBlock) preds += emptyBlockPred
          }
          else -> continue@emptyBlockLoop
        }
      }
      emptyBlock.preds.clear()
      preds -= emptyBlock
      wasCollapsed = true
    }
    return wasCollapsed
  }

  override fun equals(other: Any?) = nodeId == (other as? BasicBlock)?.nodeId
  override fun hashCode() = nodeId

  override fun toString() =
      "BasicBlock<$nodeId>(${irContext.ir.joinToString(";")}, $terminator)"

  companion object {
    private val nodeCounter = IdCounter()
  }
}