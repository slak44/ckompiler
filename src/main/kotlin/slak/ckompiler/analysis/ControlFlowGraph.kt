package slak.ckompiler.analysis

import mu.KotlinLogging
import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE
import java.util.*

// FIXME: this file is a disaster.

private val logger = KotlinLogging.logger("ControlFlow")

sealed class Jump {
  abstract val successors: List<BasicBlock>
}

/** If [cond] is true, jump to [target], otherwise jump to [other]. */
data class CondJump(val cond: Expression, val target: BasicBlock, val other: BasicBlock) : Jump() {
  override val successors = listOf(target, other)
}

/** Unconditionally jump to [target]. */
data class UncondJump(val target: BasicBlock) : Jump() {
  override val successors = listOf(target)
}

/** A so-called "impossible edge" of the CFG. Like a [UncondJump], but will never be traversed. */
data class ImpossibleJump(val target: BasicBlock) : Jump() {
  override val successors = emptyList<BasicBlock>()
}

/**
 * Combination of [UncondJump] and [ImpossibleJump].
 * Always jumps to [target], never to [impossible].
 */
data class ConstantJump(val target: BasicBlock, val impossible: BasicBlock) : Jump() {
  override val successors = listOf(target)
}

/** Indicates an incomplete [BasicBlock]. */
object MissingJump : Jump() {
  override val successors = emptyList<BasicBlock>()
}

/**
 * An instance of a [FunctionDefinition]'s control flow graph.
 */
class CFG(val f: FunctionDefinition,
          private val debug: IDebugHandler,
          computeFrontier: Boolean,
          forceAllNodes: Boolean = false) {
  private var nodeIdCounter = 0
  val startBlock = BasicBlock(true, nextNodeId())
  val exitBlock: BasicBlock
  val allNodes = mutableSetOf(startBlock)
  val nodes: Set<BasicBlock>
  private val postOrderNodes: Set<BasicBlock>

  /**
   * Recursively filter unreachable nodes.
   */
  private fun filterReachable(nodes: Set<BasicBlock>): MutableSet<BasicBlock> {
    val checkReachableQueue = LinkedList<BasicBlock>(nodes)
    val nodesImpl = mutableSetOf<BasicBlock>()
    while (checkReachableQueue.isNotEmpty()) {
      val node = checkReachableQueue.removeFirst()
      node.recomputeReachability()
      if (node.isReachable) {
        nodesImpl += node
        continue
      }
      checkReachableQueue += node.successors
      if (node.isDead) continue
      node.isDead = true
      nodesImpl -= node
      node.nodeId = 1000000 + node.nodeId
      node.successors.forEach {
        it.preds -= node
        it.recomputeReachability()
      }
      for (deadCode in node.data) debug.diagnostic {
        id = DiagnosticId.UNREACHABLE_CODE
        columns(deadCode.tokenRange)
      }
    }
    nodeIdCounter = 0
    nodesImpl.forEach { it.nodeId = nextNodeId() }
    return nodesImpl
  }

  init {
    exitBlock = GraphingContext(root = this).graphCompound(startBlock, f.block)
    if (!forceAllNodes) exitBlock.collapseIfEmptyRecusively()
    nodes = if (forceAllNodes) allNodes else filterReachable(allNodes)
    // Compute post order
    val visited = mutableSetOf<BasicBlock>()
    val postOrder = mutableSetOf<BasicBlock>()
    fun visit(block: BasicBlock) {
      visited += block
      for (succ in block.successors) {
        if (succ !in visited) visit(succ)
      }
      postOrder += block
    }
    visit(startBlock)
    if (postOrder.size != nodes.size) {
      // Our graph is not always completely connected
      // So get the disconnected nodes and add them to postOrder
      postOrder += nodes - postOrder
    }
    for ((idx, node) in postOrder.withIndex()) node.postOrderId = idx
    postOrderNodes = postOrder
  }

  private class DominatorList(size: Int) {
    private val domsImpl = MutableList<BasicBlock?>(size) { null }
    operator fun get(b: BasicBlock) = domsImpl[b.postOrderId]
    operator fun set(b: BasicBlock, new: BasicBlock) {
      domsImpl[b.postOrderId] = new
    }
  }

  /** Stores the immediate dominator (IDom) of a particular node. */
  private val doms = DominatorList(nodes.size)

  init {
    if (computeFrontier) findDomFrontiers()
  }

  /**
   * For the variable notations and the algorithm(s), see figure 3 at:
   * https://www.cs.rice.edu/~keith/EMBED/dom.pdf
   */
  private fun findDomFrontiers() {
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
    doms[startBlock] = startBlock
    var changed = true
    // Loop invariant; help out the JIT compiler and get it out:
    val postOrderRev = postOrderNodes.reversed()
    while (changed) {
      changed = false
      for (b in postOrderRev) {
        if (b == startBlock) continue
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
    for (b in nodes) {
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

  private fun nextNodeId() = nodeIdCounter++

  fun newBlock(): BasicBlock {
    val block = BasicBlock(false, nextNodeId())
    allNodes += block
    return block
  }
}

/**
 * Stores a node of the [CFG], a basic block of [ASTNode] who do not affect the control flow.
 * FIXME: this class has a garbage public interface and a garbage implementation
 * [preds], [data], [terminator], [nodeId], [postOrderId] are only mutable as implementation
 * details. Do not modify them outside this file.
 */
class BasicBlock(val isRoot: Boolean = false, var nodeId: Int) {
  val preds: MutableSet<BasicBlock> = mutableSetOf()
  val data: MutableList<ASTNode> = mutableListOf()
  var terminator: Jump = MissingJump
    set(value) {
      field = value
      when (value) {
        is CondJump -> {
          value.target.preds += this
          value.other.preds += this
          value.target.isReachable = true
          value.other.isReachable = true
        }
        is ConstantJump -> {
          value.target.preds += this
          value.impossible.preds += this
          value.target.isReachable = true
        }
        is UncondJump -> {
          value.target.preds += this
          value.target.isReachable = true
        }
        is ImpossibleJump -> value.target.preds += this
      }
    }

  var isDead = false

  var postOrderId = -1

  var isReachable = false
    private set

  val dominanceFrontier: MutableSet<BasicBlock> = mutableSetOf()

  val successors get() = terminator.successors

  init {
    if (isRoot) isReachable = true
  }

  override fun toString() =
      "BasicBlock(${data.joinToString(";")}, ${terminator.javaClass.simpleName})"

  fun isTerminated() = terminator !is MissingJump

  fun isEmpty() = data.isEmpty() && terminator !is CondJump

  fun recomputeReachability() {
    if (isRoot) return
    isReachable = preds.any { pred ->
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
   * Collapses empty predecessor blocks to this one if possible, and does so recursively up the
   * graph. Run on the exit block to collapse everything in the graph (the exit block should
   * post-dominate all other blocks).
   */
  fun collapseIfEmptyRecusively() {
    collapseImpl(mutableSetOf())
  }

  private fun collapseImpl(nodes: MutableSet<BasicBlock>) {
    if (this in nodes) return
    nodes += this
    emptyBlockLoop@ for (emptyBlock in preds.filter { it.isEmpty() }) {
      if (emptyBlock.isRoot) continue@emptyBlockLoop
      for (emptyBlockPred in emptyBlock.preds) {
        val oldTerm = emptyBlockPred.terminator
        when (oldTerm) {
          is UncondJump -> emptyBlockPred.terminator = UncondJump(this)
          is ImpossibleJump -> emptyBlockPred.terminator = ImpossibleJump(this)
          is CondJump -> {
            emptyBlockPred.terminator = CondJump(
                oldTerm.cond,
                if (oldTerm.target == emptyBlock) this else oldTerm.target,
                if (oldTerm.other == emptyBlock) this else oldTerm.other
            )
          }
          is ConstantJump -> {
            emptyBlockPred.terminator = ConstantJump(
                if (oldTerm.target == emptyBlock) this else oldTerm.target,
                if (oldTerm.impossible == emptyBlock) this else oldTerm.impossible
            )
          }
          else -> continue@emptyBlockLoop
        }
        this.preds += emptyBlockPred
        this.recomputeReachability()
      }
      emptyBlock.preds.clear()
      emptyBlock.recomputeReachability()
      this.preds -= emptyBlock
      this.recomputeReachability()
    }
    // Make copy of preds set, to prevent ConcurrentModificationException when preds get removed
    for (pred in setOf(*preds.toTypedArray())) {
      pred.collapseImpl(nodes)
    }
  }
}

/**
 * The current context in which the CFG is being built. Tracks relevant loop blocks, and possible
 * labels to jump to using goto.
 */
private data class GraphingContext(val root: CFG,
                                   val currentLoopBlock: BasicBlock? = null,
                                   val loopAfterBlock: BasicBlock? = null,
                                   val labels: MutableMap<String, BasicBlock> = mutableMapOf()) {
  fun labelBlockFor(labelName: String): BasicBlock {
    val blockOrNull = labels[labelName]
    if (blockOrNull == null) {
      val block = root.newBlock()
      labels[labelName] = block
      return block
    }
    return blockOrNull
  }
}

private fun GraphingContext.graphCompound(current: BasicBlock,
                                          compoundStatement: CompoundStatement): BasicBlock {
  var block = current
  for ((name) in compoundStatement.scope.labels) {
    labelBlockFor(name)
  }
  for (item in compoundStatement.items) {
    when (item) {
      is StatementItem -> block = graphStatement(block, item.statement)
      is DeclarationItem -> block.data += item.declaration
    }
  }
  return block
}

private fun GraphingContext.graphStatement(current: BasicBlock,
                                           s: Statement): BasicBlock = when (s) {
  is ErrorStatement,
  is ErrorExpression -> logger.throwICE("ErrorNode in CFG creation") { "$current/$s" }
  is Expression, is Noop -> {
    current.data += s
    current
  }
  is LabeledStatement -> {
    val blockWithLabel = labelBlockFor(s.label.name)
    current.terminator = UncondJump(blockWithLabel)
    val nextBlock = graphStatement(blockWithLabel, s.statement)
    nextBlock
  }
  is CompoundStatement -> graphCompound(current, s)
  is IfStatement -> {
    val ifBlock = root.newBlock()
    val elseBlock = root.newBlock()
    val ifNext = graphStatement(ifBlock, s.success)
    val elseNext = s.failure?.let { graphStatement(elseBlock, it) }
    val afterIfBlock = root.newBlock()
    current.terminator = run {
      val falseBlock = if (elseNext != null) elseBlock else afterIfBlock
      CondJump(s.cond, ifBlock, falseBlock)
    }
    ifNext.terminator = UncondJump(afterIfBlock)
    elseNext?.terminator = UncondJump(afterIfBlock)
    afterIfBlock
  }
  is SwitchStatement -> TODO("implement switches")
  is WhileStatement -> {
    val loopBlock = root.newBlock()
    val afterLoopBlock = root.newBlock()
    val loopContext = copy(currentLoopBlock = loopBlock, loopAfterBlock = afterLoopBlock)
    val loopNext = loopContext.graphStatement(loopBlock, s.loopable)
    current.terminator = CondJump(s.cond, loopBlock, afterLoopBlock)
    loopNext.terminator = UncondJump(current)
    afterLoopBlock
  }
  is DoWhileStatement -> {
    val loopBlock = root.newBlock()
    val afterLoopBlock = root.newBlock()
    val loopContext = copy(currentLoopBlock = loopBlock, loopAfterBlock = afterLoopBlock)
    val loopNext = loopContext.graphStatement(loopBlock, s.loopable)
    current.terminator = UncondJump(loopBlock)
    loopNext.terminator = CondJump(s.cond, loopBlock, afterLoopBlock)
    afterLoopBlock
  }
  is ForStatement -> {
    when (s.init) {
      is EmptyInitializer -> { /* Intentionally left empty */ }
      is ErrorInitializer -> logger.throwICE("ErrorNode in CFG creation") { "$current/$s" }
      is ForExpressionInitializer -> current.data += s.init.value
      is DeclarationInitializer -> current.data += s.init.value
    }
    val loopBlock = root.newBlock()
    val afterLoopBlock = root.newBlock()
    val loopContext = copy(currentLoopBlock = loopBlock, loopAfterBlock = afterLoopBlock)
    val loopNext = loopContext.graphStatement(loopBlock, s.loopable)
    s.loopEnd?.let { graphStatement(loopNext, it) }
    if (s.cond == null) {
      // No for condition means unconditional jump to loop block
      current.terminator = UncondJump(loopBlock)
    } else {
      current.terminator = CondJump(s.cond, loopBlock, afterLoopBlock)
    }
    loopNext.terminator = current.terminator
    afterLoopBlock
  }
  is ContinueStatement -> {
    val afterContinue = root.newBlock()
    current.terminator = ConstantJump(currentLoopBlock!!, afterContinue)
    afterContinue
  }
  is BreakStatement -> {
    val afterBreak = root.newBlock()
    current.terminator = ConstantJump(loopAfterBlock!!, afterBreak)
    afterBreak
  }
  is GotoStatement -> {
    val labelBlock = labelBlockFor(s.identifier.name)
    val afterGoto = root.newBlock()
    current.terminator = ConstantJump(labelBlock, afterGoto)
    afterGoto
  }
  is ReturnStatement -> {
    current.data += s
    val deadCodeBlock = root.newBlock()
    current.terminator = ImpossibleJump(deadCodeBlock)
    deadCodeBlock
  }
}
