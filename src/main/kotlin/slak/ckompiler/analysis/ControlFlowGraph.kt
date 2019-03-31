package slak.ckompiler.analysis

import mu.KotlinLogging
import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE
import java.util.*

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
          forceAllNodes: Boolean = false) {
  val startBlock = BasicBlock(true)
  val exitBlock: BasicBlock
  /** Raw set of nodes as obtained from [GraphingContext.graphCompound]. */
  val allNodes = mutableSetOf(startBlock)
  /** Filtered set of nodes that only contains reachable, non-empty nodes. */
  val nodes: Set<BasicBlock>
  private val postOrderNodes: Set<BasicBlock>

  /** Recursively filter unreachable nodes from the given [nodes] set. */
  private fun filterReachable(nodes: Set<BasicBlock>): MutableSet<BasicBlock> {
    val checkReachableQueue = LinkedList<BasicBlock>(nodes)
    val nodesImpl = mutableSetOf<BasicBlock>()
    while (checkReachableQueue.isNotEmpty()) {
      val node = checkReachableQueue.removeFirst()
      if (node.isReachable()) {
        nodesImpl += node
        continue
      }
      checkReachableQueue += node.successors
      if (node.isDead) continue
      node.isDead = true
      nodesImpl -= node
      for (succ in node.successors) succ.preds -= node
      for (deadCode in node.data) debug.diagnostic {
        id = DiagnosticId.UNREACHABLE_CODE
        columns(deadCode.tokenRange)
      }
    }
    return nodesImpl
  }

  init {
    exitBlock = GraphingContext(root = this).graphCompound(startBlock, f.block)
    if (!forceAllNodes) exitBlock.collapseIfEmptyRecursively()
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
  private val doms = DominatorList(postOrderNodes.size)

  /**
   * Constructs the dominator set and the identifies the dominance frontiers of each node.
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
    for (b in postOrderNodes) {
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

  init {
    findDomFrontiers()
    // Ensure all node data is of correct types
    fun isValidData(it: ASTNode) = it is Expression || it is Declaration || it is ReturnStatement
    for (node in allNodes) {
      if (node.data.any { !isValidData(it) }) {
        // Some other kind of [ASTNode] snuck in here, so we have a problem
        logger.throwICE("Bad ASTNode subclass(es) found in CFG node data") {
          node.data.filterNot(::isValidData)
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

/**
 * Stores a node of the [CFG], a basic block of [ASTNode]s who do not affect the control flow.
 *
 * Predecessors and successors do not track impossible edges.
 *
 * [preds], [data], [terminator], [postOrderId], [isDead] and [dominanceFrontier] are only mutable
 * as implementation details. They should not be modified outside this file.
 */
class BasicBlock(val isRoot: Boolean = false) {
  /**
   * Kinds of possible nodes:
   * 1. [Declaration]
   * 2. [Expression]
   * 3. [ReturnStatement]
   *
   * All other [ASTNode]s should have been eliminated by the conversion to a graph.
   */
  val data: MutableList<ASTNode> = mutableListOf()

  val nodeId = NodeIdCounter()
  var postOrderId = -1
  var isDead = false
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
   * Collapses empty predecessor blocks to this one if possible, and does so recursively up the
   * graph. Run on the exit block to collapse everything that can be collapsed in the graph (the
   * exit block should post-dominate all other blocks).
   */
  fun collapseIfEmptyRecursively() {
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
          is UncondJump -> {
            emptyBlockPred.terminator = UncondJump(this)
            preds += emptyBlockPred
          }
          is ImpossibleJump -> emptyBlockPred.terminator = ImpossibleJump(this)
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
    }
    // Make copy of preds set, to prevent ConcurrentModificationException when preds get removed
    for (pred in setOf(*preds.toTypedArray())) {
      pred.collapseImpl(nodes)
    }
  }

  override fun equals(other: Any?) = nodeId == (other as? BasicBlock)?.nodeId
  override fun hashCode() = nodeId

  override fun toString() =
      "BasicBlock(${data.joinToString(";")}, ${terminator.javaClass.simpleName})"

  companion object {
    private object NodeIdCounter {
      private var counter = 0
      operator fun invoke() = counter++
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
  is Expression -> {
    current.data += s
    current
  }
  is Noop -> {
    // Intentionally left empty
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
      is EmptyInitializer -> {
        // Intentionally left empty
      }
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
