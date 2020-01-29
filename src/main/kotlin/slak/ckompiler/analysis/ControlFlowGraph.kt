package slak.ckompiler.analysis

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.MarkerManager
import slak.ckompiler.*
import slak.ckompiler.parser.*
import java.util.*

private val logger = LogManager.getLogger()

/** An instance of a [FunctionDefinition]'s control flow graph. */
class CFG(
    val f: FunctionDefinition,
    val targetData: MachineTargetData,
    srcFileName: SourceFileName,
    srcText: String,
    forceReturnZero: Boolean,
    forceAllNodes: Boolean
) : IDebugHandler by DebugHandler("CFG", srcFileName, srcText) {
  val startBlock = BasicBlock(isRoot = true)
  /** Raw set of nodes as obtained from [graph]. */
  val allNodes = mutableSetOf(startBlock)
  /** Filtered set of nodes that only contains reachable, non-empty nodes. */
  val nodes: Set<BasicBlock>
  /** [nodes], but sorted in post-order. Not a [Sequence] because we will need it in reverse. */
  val postOrderNodes: Set<BasicBlock>
  /** @see createDomTreePreOrderSequence */
  val domTreePreorder: Sequence<BasicBlock>
  /**
   * Stores the immediate dominator (IDom) of a particular node.
   * @see findDomFrontiers
   */
  val doms: DominatorList
  /**
   * List of [Variable] used in this function, with definition locations.
   *
   * A "definition" of a variable is assignment to that variable. We consider a definition to be a
   * property of the block it's in; as a result, we can ignore *where* in the block it was defined.
   * The parser has already checked that a variable cannot be used until it is declared.
   *
   * @see insertPhiFunctions
   */
  val definitions = mutableMapOf<Variable, MutableSet<BasicBlock>>()

  val memoryIds = IdCounter()
  val registerIds = IdCounter()

  init {
    graph(this)
    nodes = if (forceAllNodes) {
      allNodes
    } else {
      collapseEmptyBlocks(allNodes)
      filterReachable(allNodes)
    }

    handleUnterminatedBlocks(forceReturnZero)

    postOrderNodes = postOrderNodes(startBlock, nodes)

    // SSA conversion
    if (!forceAllNodes) {
      doms = findDomFrontiers(startBlock, postOrderNodes)
      domTreePreorder = createDomTreePreOrderSequence(doms, startBlock, nodes)
      insertPhiFunctions(definitions)
      val renamer = VariableRenamer(doms, startBlock, domTreePreorder)
      renamer.variableRenaming()
    } else {
      doms = DominatorList(nodes.size)
      domTreePreorder = createDomTreePreOrderSequence(doms, startBlock, nodes)
    }

    diags.forEach(Diagnostic::print)
  }

  fun newBlock(): BasicBlock {
    val block = BasicBlock(isRoot = false)
    allNodes += block
    return block
  }

  private fun handleUnterminatedBlocks(
      forceReturnZero: Boolean
  ) = nodes.filterNot(BasicBlock::isTerminated).forEach {
    // For some functions (read: for main), it is desirable to return 0 if there are no explicit
    // returns found; so don't print diagnostics and add the relevant IR
    // If blocks are unterminated, and the function isn't void, the function is missing returns
    if (f.functionType.returnType != VoidType && !forceReturnZero) diagnostic {
      id = DiagnosticId.CONTROL_END_OF_NON_VOID
      formatArgs(f.name, f.functionType.returnType.toString())
      column(f.range.last)
    }
    // C standard: 5.1.2.2.3
    val fakeZero = IntegerConstantNode(0).withRange(object : SourcedRange {
      override val sourceFileName = f.sourceFileName
      override val sourceText = "0"
      override val range = 0..0
      override val expandedName: String? = null
      override val expandedFrom: SourcedRange? = null
    })
    val ret = if (forceReturnZero) {
      val fakeRegister = VirtualRegister(definitions.size, SignedIntType)
      listOf(ConstantRegisterInstr(fakeRegister, IntConstant(fakeZero)))
    } else {
      null
    }
    // Either way, terminate the blocks with a fake return
    it.terminator = ImpossibleJump(newBlock(), returned = ret, src = fakeZero)
  }
}

/**
 * Holds state required for SSA phase 2.
 * @see VariableRenamer.variableRenaming
 */
private class VariableRenamer(
    val doms: DominatorList,
    val startBlock: BasicBlock,
    val domTreePreorder: Sequence<BasicBlock>
) {
  /**
   * Stores what is the last created version of a particular variable (maps id to version).
   * @see variableRenaming
   */
  private val latestVersions = mutableMapOf<Int, Int>().withDefault { 0 }
  /** @see latestVersions */
  private var Variable.latestVersion: Int
    get() = latestVersions.getValue(id)
    set(value) {
      latestVersions[id] = value
    }
  /**
   * Maps each variable to a [ReachingDefinition] (maps a variable's id/version pair to
   * [ReachingDefinition]).
   *
   * See section 3.1.3 in [http://ssabook.gforge.inria.fr/latest/book.pdf].
   * @see ReachingDefinition
   * @see Variable.reachingDef
   * @see variableRenaming
   */
  private val reachingDefs = mutableMapOf<Pair<Int, Int>, ReachingDefinition?>()
  /**
   * Provides access to [reachingDefs] using property extension syntax (to resemble the original
   * algorithm).
   * @see variableRenaming
   */
  private var Variable.reachingDef: ReachingDefinition?
    get() = reachingDefs[id to version]
    set(value) {
      reachingDefs[id to version] = value
    }
  /** @see Variable.reachingDef */
  private var ReachingDefinition.reachingDef: ReachingDefinition?
    get() = reachingDefs[variable.id to variable.version]
    set(value) {
      reachingDefs[variable.id to variable.version] = value
    }

  /**
   * Creates a new version of a variable. Updates [latestVersions].
   */
  private fun Variable.newVersion(): Variable = copy(++latestVersion)

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
  private fun ReachingDefinition.dominates(block: BasicBlock, instrIdx: Int): Boolean {
    return if (definedIn == block) {
      if (definitionIdx == instrIdx && instrIdx < block.phiFunctions.size) {
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
  private fun updateReachingDef(v: Variable, block: BasicBlock, instrIdx: Int) {
    var r = v.reachingDef
    while (!(r == null || r.dominates(block, instrIdx))) {
      r = r.reachingDef
    }
    v.reachingDef = r
  }

  /**
   * Does the renaming for a variable definition.
   */
  private fun handleDef(bb: BasicBlock, def: Variable, instrIdx: Int) {
    val oldReachingDef = def.reachingDef
    updateReachingDef(def, bb, instrIdx)
    val vPrime = def.newVersion()
    val reachingToPrime = ReachingDefinition(vPrime, bb, instrIdx)
    def.reachingDef = reachingToPrime
    def.replaceWith(reachingToPrime)
    vPrime.reachingDef = oldReachingDef
    traceVarDefinitionRename(bb, def, vPrime)
  }

  // FIXME: simplify this disaster
  private fun findVariableUses(i: IRInstruction): List<Variable> {
    val vars = mutableListOf<Variable>()
    when (i) {
      is ConstantRegisterInstr, is PhiInstr -> {
        // Do nothing
      }
      is LoadInstr -> if (i.target is Variable) vars += i.target
      is StructuralCast -> if (i.operand is Variable) vars += i.operand
      is ReinterpretCast -> if (i.operand is Variable) vars += i.operand
      is NamedCall -> vars += i.args.filterIsInstance(Variable::class.java)
      is IndirectCall -> vars += i.args.filterIsInstance(Variable::class.java)
      is IntBinary -> {
        if (i.lhs is Variable) vars += i.lhs
        if (i.rhs is Variable) vars += i.rhs
      }
      is IntCmp -> {
        if (i.lhs is Variable) vars += i.lhs
        if (i.rhs is Variable) vars += i.rhs
      }
      is IntInvert -> if (i.operand is Variable) vars += i.operand
      is IntNeg -> if (i.operand is Variable) vars += i.operand
      is FltBinary -> {
        if (i.lhs is Variable) vars += i.lhs
        if (i.rhs is Variable) vars += i.rhs
      }
      is FltCmp -> {
        if (i.lhs is Variable) vars += i.lhs
        if (i.rhs is Variable) vars += i.rhs
      }
      is FltNeg -> if (i.operand is Variable) vars += i.operand
      is StoreInstr -> if (i.value is Variable) vars += i.value
    }
    return vars
  }

  /** @see variableRenaming */
  private fun variableRenamingImpl() = domTreePreorder.forEach { BB ->
    for ((idx, i) in BB.instructions.withIndex()) {
      val def = if (i is SideEffectInstruction) i.target else null
      for (variable in findVariableUses(i)) {
        val oldReachingVar = variable.reachingDef?.variable
        updateReachingDef(variable, BB, idx)
        variable.replaceWith(variable.reachingDef)
        traceVarUsageRename(BB, oldReachingVar, variable)
      }
      if (def == null || def !is Variable) continue
      handleDef(BB, def, idx)
    }
    for (succ in BB.successors) for ((_, incoming) in succ.phiFunctions) {
      // FIXME: incomplete?
      val oldReachingVar = incoming.getValue(BB).reachingDef?.variable
      updateReachingDef(incoming.getValue(BB), BB, Int.MAX_VALUE)
      incoming.getValue(BB).replaceWith(incoming.getValue(BB).reachingDef)
      traceVarUsageRename(succ, oldReachingVar, incoming.getValue(BB), isInPhi = true)
    }
  }

  /**
   * Perform second phase of SSA construction.
   * See Algorithm 3.3 in [http://ssabook.gforge.inria.fr/latest/book.pdf].
   */
  fun variableRenaming() {
    logger.trace(varRenamesTrace, "BB| x mention   | x.reachingDef")
    logger.trace(varRenamesTrace, "-------------------------------")
    variableRenamingImpl()
  }

  /**
   * Debug trace for variable usage renames.
   */
  private fun traceVarUsageRename(
      bb: BasicBlock,
      oldReachingVar: Variable?,
      v: Variable,
      isInPhi: Boolean = false
  ) {
    if (v.name == "x") logger.trace(varRenamesTrace) {
      val oldReachingStr =
          if (oldReachingVar == null) "⊥" else "${oldReachingVar.name}${oldReachingVar.version}"
      val newReachingStr =
          if (v.reachingDef == null) "⊥"
          else "${v.reachingDef!!.variable.name}${v.reachingDef!!.variable.version}"
      listOf(
          "${bb.hashCode()}",
          "${if (isInPhi) " " else ""}${v.name}${v.version} ${if (isInPhi) "φuse" else "use "}"
              .padStart(TRACE_COL2_LENGTH, ' '),
          "$oldReachingStr updated into $newReachingStr"
      ).joinToString(" | ").toObjectMessage()
    }
  }

  /**
   * Debug trace for variable definition renames.
   */
  private fun traceVarDefinitionRename(
      bb: BasicBlock,
      def: Variable,
      vPrime: Variable
  ) {
    if (def.name == "x") logger.trace(varRenamesTrace) {
      val oldReachingVar = def.reachingDef?.variable
      val oldReachingStr =
          if (oldReachingVar == null) "⊥" else "${oldReachingVar.name}${oldReachingVar.version}"
      listOf(
          "${bb.hashCode()}",
          "def ${def.name}${def.version}".padEnd(TRACE_COL2_LENGTH, ' '),
          "$oldReachingStr then ${vPrime.name}${vPrime.version}"
      ).joinToString(" | ").toObjectMessage()
    }
  }

  companion object {
    private val varRenamesTrace = MarkerManager.getMarker("ControlFlowVariableRenames")
    private const val TRACE_COL2_LENGTH = 11
  }
}

/**
 * φ-function insertion.
 *
 * See Algorithm 3.1 in [http://ssabook.gforge.inria.fr/latest/book.pdf] for variable notations.
 */
@Suppress("NestedBlockDepth")
private fun insertPhiFunctions(definitions: Map<Variable, MutableSet<BasicBlock>>) {
  for ((v, defsV) in definitions) {
    val f = mutableSetOf<BasicBlock>()
    // We already store the basic blocks as a set, so just make a copy
    @Suppress("SpreadOperator")
    val w = mutableSetOf(*defsV.toTypedArray())
    while (w.isNotEmpty()) {
      val x = w.first()
      w -= x
      for (y in x.dominanceFrontier) {
        if (y !in f) {
          y.phiFunctions += PhiInstr(v.copy(0), y.preds.associateWith { v.copy(0) })
          f += y
          if (y !in defsV) w += y
        }
      }
    }
  }
}

/**
 * Orders [BasicBlock]s by doing a pre-order traversal of the dominator tree.
 *
 * The size of [doms], [nodes], and the resulting sequence must be identical.
 */
fun createDomTreePreOrderSequence(
    doms: DominatorList,
    root: BasicBlock,
    nodes: Set<BasicBlock>
): Sequence<BasicBlock> = sequence {
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

/**
 * Compute the post order for a set of nodes, and return it.
 *
 * Also sets [BasicBlock.postOrderId] and [BasicBlock.height] accordingly.
 */
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
  val checkReachableQueue = LinkedList(nodes)
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
    for (deadCode in node.src.filterNot { it is Terminal }) diagnostic {
      id = DiagnosticId.UNREACHABLE_CODE
      errorOn(deadCode)
    }
  }
  return nodesImpl
}

/** Apply [BasicBlock.collapseEmptyPreds] on an entire graph ([nodes]). */
@Suppress("ControlFlowWithEmptyBody")
private fun collapseEmptyBlocks(nodes: Set<BasicBlock>) {
  val collapseCandidates = LinkedList(nodes)
  val visited = mutableSetOf<BasicBlock>()
  while (collapseCandidates.isNotEmpty()) {
    val node = collapseCandidates.removeFirst()
    if (node in visited) continue
    while (node.collapseEmptyPreds());
    visited += node
    collapseCandidates += node.preds
  }
}
