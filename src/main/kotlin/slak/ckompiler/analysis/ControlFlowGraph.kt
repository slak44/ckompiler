package slak.ckompiler.analysis

import mu.KotlinLogging
import slak.ckompiler.*
import slak.ckompiler.parser.*
import java.util.*

private val logger = KotlinLogging.logger("ControlFlow")

sealed class Jump {
  abstract val successors: List<BasicBlock>
}

/** If [cond] is true, jump to [target], otherwise jump to [other]. */
data class CondJump(val cond: Expression, val target: BasicBlock, val other: BasicBlock) : Jump() {
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
data class ImpossibleJump(val target: BasicBlock, val returned: Expression?) : Jump() {
  override val successors = emptyList<BasicBlock>()
  override fun toString() = "ImpossibleJump($returned)$"
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

/** An instance of a [FunctionDefinition]'s control flow graph. */
class CFG(val f: FunctionDefinition,
          srcFileName: SourceFileName,
          srcText: String,
          forceAllNodes: Boolean = false
) : IDebugHandler by DebugHandler("CFG", srcFileName, srcText) {
  val startBlock = BasicBlock(true)
  /** Raw set of nodes as obtained from [GraphingContext.graphCompound]. */
  val allNodes = mutableSetOf(startBlock)
  /** Filtered set of nodes that only contains reachable, non-empty nodes. */
  val nodes: Set<BasicBlock>
  /** [nodes], but in post order. */
  private val postOrderNodes: Set<BasicBlock>
  /** Stores the immediate dominator (IDom) of a particular node. */
  private val doms: DominatorList
  /**
   * List of [TypedIdentifier] used in this function, with definition locations.
   *
   * A "definition" of a variable is assignment to that variable. We consider a definition to be a
   * property of the block it's in; as a result, we can ignore *where* in the block it was defined.
   * The parser has already checked that a variable cannot be used until it is declared.
   *
   * The [TypedIdentifier.id] is not considered in [TypedIdentifier.equals], so we explicitly make
   * it part of the key.
   */
  val definitions = mutableMapOf<Pair<TypedIdentifier, Int>, MutableSet<BasicBlock>>()

  init {
    graph(this)
    if (!forceAllNodes) collapseEmptyBlocks(allNodes)
    nodes = if (forceAllNodes) allNodes else filterReachable(allNodes)
    postOrderNodes = postOrderNodes(startBlock, nodes)
    doms = DominatorList(postOrderNodes.size)
    if (!forceAllNodes) findDomFrontiers(doms, startBlock, postOrderNodes)
    // Implicit definition in the root block
    for (v in definitions) v.value += startBlock
    if (!forceAllNodes) insertPhiFunctions()
    diags.forEach(Diagnostic::print)
  }

  /**
   * φ-function insertion.
   *
   * See Algorithm 3.1 in [http://ssabook.gforge.inria.fr/latest/book.pdf] for variable notations.
   */
  private fun insertPhiFunctions() {
    for ((pair, defsV) in definitions) {
      val v = pair.first
      val f = mutableSetOf<BasicBlock>()
      // We already store the basic blocks as a set, so just make a copy
      val w = mutableSetOf(*defsV.toTypedArray())
      while (w.isNotEmpty()) {
        val x = w.first()
        w -= x
        for (y in x.dominanceFrontier) {
          if (y !in f) {
            /* FIXME: should we actually include all the preds like this?
                maybe we should leave this empty, or maybe check if the var was defined in that pred
             */
            y.phiFunctions += PhiFunction(v,
                mutableListOf(*(y.preds.map { it to v }).toTypedArray()))
            f += y
            if (y !in defsV) w += y
          }
        }
      }
    }
  }

  private val domTreePreorder = generateSequence(startBlock) {
    TODO()
  }

  private fun findVariableUsage(e: Expression): Pair<Set<TypedIdentifier>, Set<TypedIdentifier>> {
    val defs = mutableSetOf<TypedIdentifier>()
    val uses = mutableSetOf<TypedIdentifier>()
    fun findVarsRec(e: Expression): Unit = when (e) {
      is ErrorExpression -> logger.throwICE("ErrorExpression was removed") {}
      is TypedIdentifier -> uses += e
      is BinaryExpression -> {
        getAssignmentTarget(e)?.let { defs += it }
        findVarsRec(e.lhs)
        findVarsRec(e.rhs)
      }
      is FunctionCall -> {
        findVarsRec(e.calledExpr)
        for (arg in e.args) findVarsRec(arg)
      }
      is UnaryExpression -> findVarsRec(e.operand)
      is SizeofExpression -> findVarsRec(e.sizeExpr)
      is PrefixIncrement -> findVarsRec(e.expr)
      is PrefixDecrement -> findVarsRec(e.expr)
      is PostfixIncrement -> findVarsRec(e.expr)
      is PostfixDecrement -> findVarsRec(e.expr)
      is SizeofTypeName, is IntegerConstantNode, is FloatingConstantNode, is CharacterConstantNode,
      is StringLiteralNode -> Unit
    }
    findVarsRec(e)
    return uses to defs
  }

  /**
   * Utility function for variable renaming.
   *
   * See page 34 in [http://ssabook.gforge.inria.fr/latest/book.pdf] for variable notations.
   */
  private fun updateReachingDef(v: TypedIdentifier, i: Expression) {
    // FIXME: not done
//    var r = v.reachingDef
//    while (!(r == null || )) {
//      r = r.reachingDef
//    }
//    v.reachingDef = r
  }

  /**
   * Second phase of SSA construction.
   *
   * See Algorithm 3.3 in [http://ssabook.gforge.inria.fr/latest/book.pdf] for variable notations.
   */
  private fun variableRenaming() {
    // All v.reachingDef are already initialized to null
    for (BB in domTreePreorder) {
      val cond = (BB.terminator as? CondJump)?.cond
      for (i in BB.data.apply { if (cond != null) add(cond) }) {
        val (uses, defs) = findVariableUsage(i)
        for (v in uses) {
          updateReachingDef(v, i)
          // FIXME: this nullness coercion is potentially bad
          v.replaceWith(v.reachingDef!!)
        }
        for (v in defs + BB.phiFunctions.map(PhiFunction::target)) {
          updateReachingDef(v, i)
          val vPrime = v.nextVersion()
          v.replaceWith(vPrime)
          vPrime.reachingDef = v.reachingDef
          v.reachingDef = vPrime
        }
      }
      for (phi in BB.successors.flatMap(BasicBlock::phiFunctions)) {
        for (v in phi.incoming.map { it.second }) {
//          updateReachingDef(v, phi)
          // FIXME: this nullness coercion is potentially bad
          v.replaceWith(v.reachingDef!!)
        }
      }
    }
  }

  fun newBlock(): BasicBlock {
    val block = BasicBlock(false)
    allNodes += block
    return block
  }
}

/** A [MutableList] of [BasicBlock]s, indexed by [BasicBlock]s. */
private class DominatorList(size: Int) {
  private val domsImpl = MutableList<BasicBlock?>(size) { null }
  operator fun get(b: BasicBlock) = domsImpl[b.postOrderId]
  operator fun set(b: BasicBlock, new: BasicBlock) {
    domsImpl[b.postOrderId] = new
  }
}

/**
 * Constructs the dominator set and the identifies the dominance frontiers of each node.
 * For the variable notations and the algorithm(s), see figure 3 at:
 * https://www.cs.rice.edu/~keith/EMBED/dom.pdf
 * @param doms uninitialized [DominatorList] to fill
 */
private fun findDomFrontiers(doms: DominatorList,
                             startNode: BasicBlock,
                             postOrder: Set<BasicBlock>) {
  // Compute the dominators, storing it as a list (doms).
  fun intersect(b1: BasicBlock, b2: BasicBlock): BasicBlock {
    var finger1 = b1
    var finger2 = b2
    while (finger1 != finger2) {
      while (finger1.postOrderId < finger2.postOrderId) {
        finger1 = doms[finger1]!!
      }
      while (finger2.postOrderId < finger1.postOrderId) {
        finger2 = doms[finger2]!!
      }
    }
    return finger1
  }
  doms[startNode] = startNode
  var changed = true
  // Loop invariant; help out the JIT compiler and get it out:
  val postOrderRev = postOrder.reversed()
  while (changed) {
    changed = false
    for (b in postOrderRev) {
      if (b == startNode) continue
      // First _processed_ predecessor
      var newIdom = b.preds.first { doms[it] != null }
      for (p in b.preds) {
        // Iterate the other predecessors
        if (p == newIdom) continue
        if (doms[p] != null) {
          newIdom = intersect(p, newIdom)
        }
      }
      if (doms[b] != newIdom) {
        doms[b] = newIdom
        changed = true
      }
    }
  }
  // Compute dominance frontiers.
  // See figure 5.
  for (b in postOrder) {
    if (b.preds.size >= 2) {
      for (p in b.preds) {
        var runner = p
        while (runner != doms[b]) {
          runner.dominanceFrontier += b
          runner = doms[runner]!!
        }
      }
    }
  }
}

/** Compute the post order for a set of nodes, and return it. */
private fun postOrderNodes(startNode: BasicBlock, nodes: Set<BasicBlock>): Set<BasicBlock> {
  if (startNode !in nodes) logger.throwICE("startNode not in nodes") { "$startNode/$nodes" }
  val visited = mutableSetOf<BasicBlock>()
  val postOrder = mutableSetOf<BasicBlock>()
  // Recursively compute post order
  fun visit(block: BasicBlock) {
    visited += block
    for (succ in block.successors) {
      if (succ !in visited) visit(succ)
    }
    postOrder += block
  }
  visit(startNode)
  if (postOrder.size != nodes.size) {
    // Our graph may not always be completely connected
    // So get the disconnected nodes and add them to postOrder
    postOrder += nodes - postOrder
  }
  for ((idx, node) in postOrder.withIndex()) node.postOrderId = idx
  return postOrder
}

/** Filter unreachable nodes from the given [nodes] set and return the ones that are reachable. */
private fun IDebugHandler.filterReachable(nodes: Set<BasicBlock>): Set<BasicBlock> {
  val checkReachableQueue = LinkedList<BasicBlock>(nodes)
  val visited = mutableSetOf<BasicBlock>()
  val nodesImpl = mutableSetOf<BasicBlock>()
  while (checkReachableQueue.isNotEmpty()) {
    val node = checkReachableQueue.removeFirst()
    if (node.isReachable()) {
      nodesImpl += node
      continue
    }
    checkReachableQueue += node.successors
    if (node in visited) continue
    visited += node
    nodesImpl -= node
    for (succ in node.successors) succ.preds -= node
    for (deadCode in node.data) diagnostic {
      id = DiagnosticId.UNREACHABLE_CODE
      columns(deadCode.tokenRange)
    }
  }
  return nodesImpl
}

/** Apply [BasicBlock.collapseEmptyPreds] on an entire graph ([nodes]). */
private fun collapseEmptyBlocks(nodes: Set<BasicBlock>) {
  val collapseCandidates = LinkedList<BasicBlock>(nodes)
  val visited = mutableSetOf<BasicBlock>()
  while (collapseCandidates.isNotEmpty()) {
    val node = collapseCandidates.removeFirst()
    if (node in visited) continue
    while (node.collapseEmptyPreds());
    visited += node
    collapseCandidates += node.preds
  }
}

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
data class PhiFunction(val target: TypedIdentifier,
                       val incoming: MutableList<Pair<BasicBlock, TypedIdentifier>>) {
  override fun toString() =
      "$target = φ(${incoming.joinToString(", ") { "${it.first.nodeId}" }})"
}

/**
 * Stores a node of the [CFG], a basic block of [ASTNode]s who do not affect the control flow.
 *
 * Predecessors and successors do not track impossible edges.
 *
 * [preds], [data], [terminator], [postOrderId] and [dominanceFrontier] are
 * only mutable as implementation details. They should not be modified outside this file.
 */
class BasicBlock(val isRoot: Boolean = false) {
  val phiFunctions = mutableListOf<PhiFunction>()
  /**
   * Contains an ordered sequence of [Expression]s. All other [ASTNode] types should have been
   * eliminated by the conversion to a graph.
   */
  val data: MutableList<Expression> = mutableListOf()

  val nodeId = nodeCounter()
  var postOrderId = -1
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

  fun isTerminated() = terminator !is MissingJump

  fun isEmpty() = data.isEmpty() && terminator !is CondJump

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
      "BasicBlock<$nodeId>(${data.joinToString(";")}, $terminator)"

  companion object {
    private val nodeCounter = IdCounter()
  }
}

