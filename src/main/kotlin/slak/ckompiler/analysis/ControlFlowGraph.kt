package slak.ckompiler.analysis

import mu.KotlinLogging
import slak.ckompiler.*
import slak.ckompiler.parser.*
import java.util.*

private val logger = KotlinLogging.logger("ControlFlow")

/**
 * [TypedIdentifier] and its id.
 *
 * The [TypedIdentifier.id] is not considered in [TypedIdentifier.equals], so we explicitly make
 * it count by using this class.
 */
data class UniqueIdent(val variable: TypedIdentifier, val id: Int)
fun TypedIdentifier.toUniqueId() = UniqueIdent(this, id)

/** An instance of a [FunctionDefinition]'s control flow graph. */
class CFG(val f: FunctionDefinition,
          srcFileName: SourceFileName,
          srcText: String,
          forceAllNodes: Boolean = false,
          convertToSSA: Boolean = true
) : IDebugHandler by DebugHandler("CFG", srcFileName, srcText) {
  val startBlock = BasicBlock(true)
  /** Raw set of nodes as obtained from [graph]. */
  val allNodes = mutableSetOf(startBlock)
  /** Filtered set of nodes that only contains reachable, non-empty nodes. */
  val nodes: Set<BasicBlock>
  /** [nodes], but sorted in post-order. Not a [Sequence] because we will need it in reverse. */
  private val postOrderNodes: Set<BasicBlock>
  /**
   * Stores the immediate dominator (IDom) of a particular node.
   * @see findDomFrontiers
   */
  val doms: DominatorList
  /**
   * List of [TypedIdentifier] used in this function, with definition locations.
   *
   * A "definition" of a variable is assignment to that variable. We consider a definition to be a
   * property of the block it's in; as a result, we can ignore *where* in the block it was defined.
   * The parser has already checked that a variable cannot be used until it is declared.
   *
   * @see insertPhiFunctions
   */
  val definitions = mutableMapOf<UniqueIdent, MutableSet<BasicBlock>>()

  init {
    graph(this)
    if (forceAllNodes) {
      nodes = allNodes
    } else {
      collapseEmptyBlocks(allNodes)
      nodes = filterReachable(allNodes)
    }

    postOrderNodes = postOrderNodes(startBlock, nodes)
    doms = findDomFrontiers(startBlock, postOrderNodes)

    // SSA conversion
    if (convertToSSA) {
      insertPhiFunctions(definitions)
      val renamer = VariableRenamer(doms, startBlock, nodes)
      slak.ckompiler.analysis.logger.trace { "BB| x mention  | x.reachingDef" }
      slak.ckompiler.analysis.logger.trace { "------------------------------" }
      renamer.variableRenaming()
    }

    diags.forEach(Diagnostic::print)
  }

  fun newBlock(): BasicBlock {
    val block = BasicBlock(false)
    allNodes += block
    return block
  }
}

/**
 * The [variable] definition that reaches a point in the CFG, along with information about where it
 * was defined. If [definitionIdx] is -1, the definition occurred in a φ-function.
 *
 * @see VariableRenamer.reachingDefs
 */
data class ReachingDef(val variable: TypedIdentifier,
                       val definedIn: BasicBlock,
                       val definitionIdx: Int)

/**
 * Holds state required for SSA phase 2.
 * @see VariableRenamer.variableRenaming
 */
private class VariableRenamer(val doms: DominatorList,
                              val startBlock: BasicBlock,
                              nodes: Set<BasicBlock>) {
  /** Returns [BasicBlock]s by doing a pre-order traversal of the dominator tree. */
  private val domTreePreorder = createDomTreePreOrderSequence(doms, startBlock, nodes)

  /**
   * Stores what is the last created version of a particular variable (maps id to version).
   * @see variableRenaming
   */
  private val latestVersions = mutableMapOf<Int, Int>().withDefault { 0 }
  /** @see latestVersions */
  private var TypedIdentifier.latestVersion: Int
    get() = latestVersions.getValue(id)
    set(value) {
      latestVersions[id] = value
    }
  /**
   * Maps each variable to a [ReachingDef] (maps a variable's id/version pair to [ReachingDef]).
   *
   * See section 3.1.3 in [http://ssabook.gforge.inria.fr/latest/book.pdf].
   * @see ReachingDef
   * @see TypedIdentifier.reachingDef
   * @see variableRenaming
   */
  private val reachingDefs = mutableMapOf<Pair<Int, Int>, ReachingDef?>()
  /**
   * Provides access to [reachingDefs] using property extension syntax (to resemble the original
   * algorithm).
   * @see variableRenaming
   */
  private var TypedIdentifier.reachingDef: ReachingDef?
    get() = reachingDefs[id to version]
    set(value) {
      reachingDefs[id to version] = value
    }
  /** @see TypedIdentifier.reachingDef */
  private var ReachingDef.reachingDef: ReachingDef?
    get() = reachingDefs[variable.id to variable.version]
    set(value) {
      reachingDefs[variable.id to variable.version] = value
    }

  /**
   * Creates a new version of a variable. Updates [latestVersions].
   */
  private fun TypedIdentifier.newVersion(): TypedIdentifier {
    val new = copy()
    new.version = ++new.latestVersion
    return new
  }

  /**
   * Finds all uses of all variables in the given expression.
   */
  private fun findVariableUsage(e: Expression): List<TypedIdentifier> {
    val uses = mutableListOf<TypedIdentifier>()
    fun findVarsRec(e: Expression): Unit = when (e) {
      is ErrorExpression -> logger.throwICE("ErrorExpression was removed") {}
      is TypedIdentifier -> uses += e
      is BinaryExpression -> {
        if (e.op in assignmentOps) {
          // FIXME: a bunch of other things can be on the left side of an =
          if (e.lhs !is TypedIdentifier) logger.throwICE("Unimplemented branch") { e }
          // Assigment targets ar definitions, not uses, so skip the recursive call on lhs here
        } else {
          findVarsRec(e.lhs)
        }
        findVarsRec(e.rhs)
      }
      is PrefixIncrement, is PrefixDecrement,
      is PostfixIncrement, is PostfixDecrement -> {
        val expr = (e as IncDecOperation).expr
        if (expr is TypedIdentifier) {
          // Inc/Dec targets are definitions, not uses; skip recursive call
        } else {
          findVarsRec(expr)
        }
      }
      is FunctionCall -> {
        findVarsRec(e.calledExpr)
        for (arg in e.args) findVarsRec(arg)
      }
      is UnaryExpression -> findVarsRec(e.operand)
      is SizeofExpression -> findVarsRec(e.sizeExpr)
      is SizeofTypeName, is IntegerConstantNode, is FloatingConstantNode, is CharacterConstantNode,
      is StringLiteralNode -> Unit
    }
    findVarsRec(e)
    return uses
  }

  /**
   * If [other] dominates [this], return true.
   */
  private infix fun BasicBlock.isDominatedBy(other: BasicBlock): Boolean {
    var block = this
    // Walk dominator tree path to root node
    do {
      block = doms[block]!!
      // `other` was somewhere above `this` in the dominator tree
      if (block == other) return true
    } while (block != startBlock)
    return false
  }

  /**
   * See page 34 in [http://ssabook.gforge.inria.fr/latest/book.pdf].
   */
  private fun ReachingDef.dominates(block: BasicBlock, instrIdx: Int): Boolean {
    return if (definedIn == block) {
      if (definitionIdx == -1 && instrIdx == -1) {
        logger.throwICE("Multiple phi functions for same variable in the same block") {
          "$this dominates $block $instrIdx"
        }
      }
      definitionIdx < instrIdx
    } else {
      block isDominatedBy definedIn
    }
  }

  /**
   * See page 34 in [http://ssabook.gforge.inria.fr/latest/book.pdf].
   */
  private fun updateReachingDef(v: TypedIdentifier, block: BasicBlock, instrIdx: Int) {
    var r = v.reachingDef
    while (!(r == null || r.dominates(block, instrIdx))) {
      r = r.reachingDef
    }
    v.reachingDef = r
  }

  /**
   * Debug trace for variable usage renames.
   */
  private fun traceVarUsageRename(BB: BasicBlock,
                                  oldReachingVar: TypedIdentifier?,
                                  v: TypedIdentifier) {
    if (v.name == "x") logger.trace {
      val oldReachingStr =
          if (oldReachingVar == null) "⊥" else "${oldReachingVar.name}${oldReachingVar.version}"
      val newReachingStr =
          if (v.reachingDef == null) "⊥"
          else "${v.reachingDef!!.variable.name}${v.reachingDef!!.variable.version}"
      listOf(
          "${BB.nodeId}",
          "${v.name}${v.version} use".padStart(10, ' '),
          "$oldReachingStr updated into $newReachingStr"
      ).joinToString(" | ")
    }
  }

  /**
   * Debug trace for variable definition renames.
   */
  private fun traceVarDefinitionRename(BB: BasicBlock,
                                       def: TypedIdentifier,
                                       vPrime: TypedIdentifier) {
    if (def.name == "x") logger.trace {
      val oldReachingVar = def.reachingDef?.variable
      val oldReachingStr =
          if (oldReachingVar == null) "⊥" else "${oldReachingVar.name}${oldReachingVar.version}"
      listOf(
          "${BB.nodeId}",
          "def ${def.name}${def.version}".padEnd(10, ' '),
          "$oldReachingStr then ${vPrime.name}${vPrime.version}"
      ).joinToString(" | ")
    }
  }

  /**
   * Does the renaming for a variable definition.
   */
  private fun handleDef(BB: BasicBlock, def: TypedIdentifier, instrIdx: Int) {
    val oldReachingDef = def.reachingDef
    updateReachingDef(def, BB, instrIdx)
    val vPrime = def.newVersion()
    val reachingToPrime = ReachingDef(vPrime, BB, instrIdx)
    def.reachingDef = reachingToPrime
    def.replaceWith(reachingToPrime)
    vPrime.reachingDef = oldReachingDef
    traceVarDefinitionRename(BB, def, vPrime)
  }

  /**
   * Perform second phase of SSA construction.
   * See Algorithm 3.3 in [http://ssabook.gforge.inria.fr/latest/book.pdf].
   */
  fun variableRenaming() = domTreePreorder.forEach { BB ->
    for ((def) in BB.phiFunctions) handleDef(BB, def, -1)
    for ((idx, i) in BB.instructions.withIndex()) {
      // Each expression can only have one definition, and it will be in the root expression
      val def: TypedIdentifier? = when {
        // FIXME: a bunch of other things can be on the left side of an =
        i is BinaryExpression && i.op in assignmentOps -> i.lhs as TypedIdentifier
        i is IncDecOperation -> i.expr as TypedIdentifier
        else -> null
      }
      for (v in findVariableUsage(i)) {
        val oldReachingVar = v.reachingDef?.variable
        updateReachingDef(v, BB, idx)
        v.replaceWith(v.reachingDef)
        traceVarUsageRename(BB, oldReachingVar, v)
      }
      if (def == null) continue
      handleDef(BB, def, idx)
    }
//    for (succ in BB.successors) for ((def) in succ.phiFunctions) {
//      updateReachingDef(def, succ, -1)
//      def.replaceWith(def.reachingDef)
//    }
  }
}

/**
 * φ-function insertion.
 *
 * See Algorithm 3.1 in [http://ssabook.gforge.inria.fr/latest/book.pdf] for variable notations.
 */
private fun insertPhiFunctions(definitions: Map<UniqueIdent, MutableSet<BasicBlock>>) {
  for ((uniqueId, defsV) in definitions) {
    val v = uniqueId.variable
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
          y.phiFunctions += PhiFunction(v.copy(),
              mutableListOf(*(y.preds.map { it to v }).toTypedArray()))
          f += y
          if (y !in defsV) w += y
        }
      }
    }
  }
}

/** @see VariableRenamer.domTreePreorder */
fun createDomTreePreOrderSequence(
    doms: DominatorList,
    root: BasicBlock,
    nodes: Set<BasicBlock>
) = sequence {
  val visited = mutableSetOf<BasicBlock>()
  val stack = Stack<BasicBlock>()
  stack.push(root)
  while (stack.isNotEmpty()) {
    val block = stack.pop()
    if (block in visited) continue
    yield(block)
    visited += block
    for (child in nodes.filter { doms[it] == block }.sortedBy { it.height }.asReversed()) {
      stack.push(child)
    }
  }
}

/** A [MutableList] of [BasicBlock]s, indexed by [BasicBlock]s. */
class DominatorList(size: Int) {
  private val domsImpl = MutableList<BasicBlock?>(size) { null }
  operator fun get(b: BasicBlock) = domsImpl[b.postOrderId]
  operator fun set(b: BasicBlock, new: BasicBlock) {
    domsImpl[b.postOrderId] = new
  }
}

/**
 * Constructs the dominator tree, and the identifies the dominance frontiers of
 * each node (fills [BasicBlock.dominanceFrontier]).
 *
 * The dominator tree is stored as a list, where each [BasicBlock] stores its immediate dominator.
 *
 * For the variable notations and the algorithm(s), see figure 3 at:
 * [https://www.cs.rice.edu/~keith/EMBED/dom.pdf]
 */
private fun findDomFrontiers(startNode: BasicBlock, postOrder: Set<BasicBlock>): DominatorList {
  if (startNode !in postOrder) logger.throwICE("startNode not in nodes") { "$startNode/$postOrder" }
  // Compute the dominators, storing it as a list
  val doms = DominatorList(postOrder.size)
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
  return doms
}

/** Compute the post order for a set of nodes, and return it. */
private fun postOrderNodes(startNode: BasicBlock, nodes: Set<BasicBlock>): Set<BasicBlock> {
  if (startNode !in nodes) logger.throwICE("startNode not in nodes") { "$startNode/$nodes" }
  val visited = mutableSetOf<BasicBlock>()
  val postOrder = mutableSetOf<BasicBlock>()
  // Recursively compute post order
  fun visit(block: BasicBlock, height: Int) {
    visited += block
    for (succ in block.successors) {
      if (succ !in visited) visit(succ, height + 1)
    }
    block.height = height
    postOrder += block
  }
  visit(startNode, 0)
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
