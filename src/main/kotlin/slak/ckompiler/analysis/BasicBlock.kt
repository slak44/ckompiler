package slak.ckompiler.analysis

import slak.ckompiler.parser.Expression
import slak.ckompiler.parser.ReturnStatement
import java.util.concurrent.atomic.AtomicInteger

/**
 * Returns a sequential integer ID on [invoke].
 *
 * This operation is atomic. If multiple threads access this value in parallel, each thread's IDs
 * will not be sequential (but they will be distinct).
 */
class IdCounter {
  private val counter = AtomicInteger()
  operator fun invoke() = counter.getAndIncrement()
}

/**
 * A φ-function that's part of a [BasicBlock]. [target] is the original pre-SSA variable, while
 * [incoming] stores the blocks that [target] can come from (ie the list of versions that the φ has
 * to choose from).
 */
data class PhiFunction(val target: ComputeReference,
                       val incoming: MutableMap<BasicBlock, ComputeReference>) {
  override fun toString() = "$target = φ(${incoming.entries.joinToString(", ") {
    "n${it.key.hashCode()} v${it.value.version}"
  }})"
}

/**
 * Instances represent terminators for [BasicBlock]s.
 */
sealed class Jump {
  /**
   * List of blocks that this [Jump] could reach.
   */
  abstract val successors: List<BasicBlock>
}

/** If [cond] is true, jump to [target], otherwise jump to [other]. */
data class CondJump(val cond: IRLoweringContext,
                    val target: BasicBlock,
                    val other: BasicBlock) : Jump() {
  override val successors = listOf(target, other)
  override fun toString() = "CondJump<${target.hashCode()}, ${other.hashCode()}>$cond"
}

/** Unconditionally jump to [target]. */
data class UncondJump(val target: BasicBlock) : Jump() {
  override val successors = listOf(target)
  override fun toString() = "UncondJump<${target.hashCode()}>"
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
  override fun toString() = "ConstantJump<${target.hashCode()}>$"
}

/** Indicates an incomplete [BasicBlock]. */
object MissingJump : Jump() {
  override val successors = emptyList<BasicBlock>()
}

/**
 * Stores a node of the [CFG], a basic block of [Expression]s who do not affect the control flow.
 *
 * Predecessors and successors do not track impossible edges.
 *
 * FIXME: a lot of things in here should not be mutable
 */
class BasicBlock(val isRoot: Boolean = false) {
  /**
   * List of SSA φ-functions at the start of this block.
   */
  val phiFunctions = mutableListOf<PhiFunction>()
  /**
   * Contains this block's IR expression list.
   */
  val irContext = IRLoweringContext()
  /**
   * Unique for each basic block. No other guarantees are provided about this value; it is opaque.
   *
   * Multiple instances of [CFG] being created at the same time can and will update the underlying
   * counter in a (likely) unpredictable order, so in particular, these ids should not be assumed
   * consecutive.
   *
   * @see hashCode
   * @see equals
   */
  private val nodeId = nodeCounter()
  /**
   * If not -1, this value represents the post order of [BasicBlock]s in their respective [CFG].
   *
   * If set by [postOrderNodes], the values are guaranteed to be in the range [0..nodes.size) for
   * a particular [CFG] instance.
   */
  var postOrderId = -1
  /**
   * If not -1, this value represents the distance from the start block to this one in a [CFG].
   */
  var height = -1
  /**
   * Set of blocks whose terminator leads to this one.
   */
  val preds: MutableSet<BasicBlock> = mutableSetOf()
  /** @see [Jump.successors] */
  val successors get() = terminator.successors
  /** @see findDomFrontiers */
  val dominanceFrontier: MutableSet<BasicBlock> = mutableSetOf()
  /** @see Jump */
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
  /**
   * Allows iteration over this [BasicBlock]'s IR, including [CondJump.cond] and
   * [ImpossibleJump.returned], if they're available.
   */
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
    emptyBlockLoop@ for (emptyBlock in preds.filter(BasicBlock::isEmpty)) {
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
  /**
   * @see nodeId
   * @see Object.hashCode
   */
  override fun hashCode() = nodeId

  override fun toString() =
      "BasicBlock<$nodeId/$postOrderId>(${irContext.ir.joinToString(";")}, $terminator)"

  companion object {
    private val nodeCounter = IdCounter()
  }
}