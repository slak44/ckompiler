package slak.ckompiler.analysis

import kotlinx.serialization.Serializable
import mu.KotlinLogging
import slak.ckompiler.AtomicId
import slak.ckompiler.IdCounter
import slak.ckompiler.parser.ExprConstantNode
import slak.ckompiler.parser.Expression
import slak.ckompiler.parser.ReturnStatement
import slak.ckompiler.throwICE
import kotlin.js.JsExport

private val logger = KotlinLogging.logger {}

/**
 * Instances represent terminators for [BasicBlock]s.
 */
@Serializable
@JsExport
sealed class Jump {
  /**
   * List of blocks that this [Jump] could reach.
   */
  abstract val successors: List<BasicBlock>
}

/**
 * If [cond] is true, jump to [target], otherwise jump to [other].
 *
 * @param src debug range for [cond]
 */
@Serializable(with = CondJumpSerializer::class)
@JsExport
data class CondJump(
    val cond: List<IRInstruction>,
    val src: Expression,
    val target: BasicBlock,
    val other: BasicBlock
) : Jump() {
  override val successors = listOf(target, other)
  override fun toString() = "CondJump<${target.hashCode()}, ${other.hashCode()}>$cond"
}

/**
 * Select jump target from [options] based on [cond], or pick [default] otherwise.
 *
 * @param src debug range for [cond]
 */
@Serializable(with = SelectJumpSerializer::class)
@JsExport
data class SelectJump(
    val cond: List<IRInstruction>,
    val src: Expression,
    val options: Map<ExprConstantNode, BasicBlock>,
    val default: BasicBlock
) : Jump() {
  override val successors = options.values + default
  override fun toString(): String {
    val opts = options.entries.joinToString(" ") { it.value.hashCode().toString() }
    return "SelectJump<$opts, default: ${default.hashCode()}>($cond)"
  }
}

/** Unconditionally jump to [target]. */
@Serializable(with = UncondJumpSerializer::class)
@JsExport
data class UncondJump(val target: BasicBlock) : Jump() {
  override val successors = listOf(target)
  override fun toString() = "UncondJump<${target.hashCode()}>"
}

/**
 * A so-called "impossible edge" of the CFG. Similar to [UncondJump], but will never be traversed.
 * It is created by [ReturnStatement].
 *
 * @param src debug range for [returned] (is null if [returned] is null)
 */
@Serializable(with = ImpossibleJumpSerializer::class)
@JsExport
data class ImpossibleJump(
    val target: BasicBlock,
    val returned: List<IRInstruction>?,
    val src: Expression?
) : Jump() {
  override val successors = emptyList<BasicBlock>()
  override fun toString() = "ImpossibleJump($returned)"
}

/**
 * Similar to a combination of [UncondJump] and [ImpossibleJump].
 * Always jumps to [target], never to [impossible].
 */
@Serializable(with = ConstantJumpSerializer::class)
@JsExport
data class ConstantJump(val target: BasicBlock, val impossible: BasicBlock) : Jump() {
  override val successors = listOf(target)
  override fun toString() = "ConstantJump<${target.hashCode()}>$"
}

/** Indicates an incomplete [BasicBlock]. */
@Serializable
@JsExport
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
@Serializable(with = BasicBlockSerializer::class)
@JsExport
class BasicBlock(val isRoot: Boolean = false) {
  /**
   * List of SSA φ-functions at the start of this block. Basically a prefix to [ir].
   *
   * φ functions are a parallel copy operation: they happen simultaneously. The order of the
   * [PhiInstruction]s in this set does not matter.
   */
  val phi = mutableSetOf<PhiInstruction>()
  /**
   * Contains this block's IR expression list.
   */
  val ir = mutableListOf<IRInstruction>()
  /**
   * Debug ranges of the original source code. May not map to [ir].
   */
  val src = mutableListOf<Expression>()
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
  val nodeId: AtomicId = nodeCounter()
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
        is SelectJump -> {
          for (target in value.successors) target.preds += this
        }
        is ConstantJump -> value.target.preds += this
        is UncondJump -> value.target.preds += this
        is ImpossibleJump, MissingJump -> {
          // Intentionally left empty
        }
      }
    }
  /**
   * Allows iteration over this [BasicBlock]'s IR, excluding φs, but including [CondJump.cond] and
   * [ImpossibleJump.returned], if available.
   */
  val instructions
    get() = iterator {
      yieldAll(ir)
      (terminator as? SelectJump)?.cond?.let { yieldAll(it) }
      (terminator as? CondJump)?.cond?.let { yieldAll(it) }
      (terminator as? ImpossibleJump)?.returned?.let { yieldAll(it) }
    }

  fun isTerminated() = terminator !is MissingJump

  fun isEmpty() = !instructions.hasNext()

  /** Returns whether or not this block is reachable from its [preds]. */
  fun isReachable(): Boolean {
    if (isRoot) return true
    return preds.any { pred ->
      when (val t = pred.terminator) {
        is UncondJump -> true
        is ImpossibleJump -> false
        is ConstantJump -> t.target == this
        is CondJump -> t.target == this || t.other == this
        is SelectJump -> this in t.successors
        MissingJump -> logger.throwICE("BasicBlock predecessor has MissingJump terminator")
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
          is ImpossibleJump -> {
            emptyBlockPred.terminator = ImpossibleJump(this, oldTerm.returned, oldTerm.src)
          }
          is CondJump -> {
            emptyBlockPred.terminator = CondJump(
                oldTerm.cond,
                oldTerm.src,
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
      "BasicBlock<id$nodeId/post$postOrderId>(${terminator::class.simpleName})"

  companion object {
    val nodeCounter = IdCounter()
  }
}
