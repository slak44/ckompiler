package slak.ckompiler.analysis

import io.github.oshai.kotlinlogging.KMarkerFactory.getMarker
import io.github.oshai.kotlinlogging.KotlinLogging
import slak.ckompiler.*
import slak.ckompiler.parser.*
import kotlin.js.JsExport

private val logger = KotlinLogging.logger {}

typealias Definitions = Map<Variable, Label>
typealias DefUseChains = Map<Variable, List<Label>>

data class CFGOptions(
    val forceAllNodes: Boolean = false,
    val forceReturnZero: Boolean = false,
    val skipSSARename: Boolean = false,
)

/**
 * To avoid mutable state in the finished [CFG] object, this class is used to first build all the necessary data.
 */
class CFGFactory(
    val f: FunctionDefinition,
    val targetData: MachineTargetData,
    srcFileName: SourceFileName,
    srcText: String,
    private val cfgOptions: CFGOptions = CFGOptions()
) : IDebugHandler by DebugHandler("CFG", srcFileName, srcText) {
  val startBlock = BasicBlock(isRoot = true)

  val allNodes = mutableSetOf(startBlock)

  /**
   * List of [Variable] used in this function, with definition locations.
   *
   * A "definition" of a variable is assignment to that variable. We consider a definition to be a
   * property of the block it's in; as a result, we can ignore *where* in the block it was defined.
   * The parser has already checked that a variable cannot be used until it is declared.
   *
   * Only useful for φs, since after renaming, each block will have its own definition.
   *
   * @see insertPhiFunctions
   */
  val exprDefinitions = mutableMapOf<Variable, MutableSet<BasicBlock>>()

  val stackVariableIds = mutableSetOf<AtomicId>()

  val registerIds = IdCounter()

  private lateinit var doms: DominatorList
  lateinit var domTreePreorder: Set<BasicBlock>

  /**
   * 1. [graph]
   * 2. [collapseEmptyBlocks]
   * 3. [filterReachable]
   * 4. [handleUnterminatedBlocks]
   * 5. [postOrderNodes]
   * 6. [findDomFrontiers]
   * 7. [createDomTreePreOrderNodes]
   * 8. [insertPhiFunctions]
   * 9. [VariableRenamer.variableRenaming]
   */
  fun create(skipPrintDiagnostics: Boolean = false): CFG {
    graph(this)

    val nodes = if (cfgOptions.forceAllNodes) {
      allNodes
    } else {
      collapseEmptyBlocks(allNodes)
      filterReachable(allNodes)
    }

    handleUnterminatedBlocks(nodes, cfgOptions.forceReturnZero)

    val renamer = VariableRenamer(this)

    // SSA conversion
    if (!cfgOptions.forceAllNodes) {
      val postOrderNodes = postOrderNodes(startBlock, nodes)
      doms = findDomFrontiers(startBlock, postOrderNodes)
      domTreePreorder = createDomTreePreOrderNodes(doms, startBlock, nodes)

      insertPhiFunctions(exprDefinitions.filter { it.key.identityId !in stackVariableIds })

      if (!cfgOptions.skipSSARename) {
        renamer.variableRenaming()
      }
    } else {
      doms = DominatorList(nodes.size)
      domTreePreorder = createDomTreePreOrderNodes(doms, startBlock, nodes)
    }

    if (!skipPrintDiagnostics) {
      diags.forEach(Diagnostic::print)
    }

    return CFG(
        functionIdentifier = f.funcIdent,
        functionParameters = f.parameters,
        startBlock = startBlock,
        allNodes = allNodes,
        nodes = nodes,
        domTreePreorder = domTreePreorder,
        doms = doms,
        exprDefinitions = exprDefinitions,
        stackVariableIds = stackVariableIds,
        definitions = renamer.definitions,
        defUseChains = renamer.defUseChains,
        latestVersions = renamer.latestVersions,
        registerIds = registerIds
    )
  }

  fun newBlock(): BasicBlock {
    val block = BasicBlock(isRoot = false)
    allNodes += block
    return block
  }

  private fun handleUnterminatedBlocks(
      nodes: Set<BasicBlock>,
      forceReturnZero: Boolean,
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
      val fakeRegister = VirtualRegister(registerIds(), SignedIntType)
      listOf(MoveInstr(fakeRegister, IntConstant(fakeZero)))
    } else {
      null
    }
    // Either way, terminate the blocks with a fake return
    it.terminator = ImpossibleJump(newBlock(), returned = ret, src = fakeZero)
  }

  /**
   * If [other] strictly dominates [this], return true.
   */
  infix fun BasicBlock.isDominatedBy(other: BasicBlock): Boolean {
    var block = this
    // Walk dominator tree path to root node
    do {
      block = doms[block]!!
      // `other` was somewhere above `this` in the dominator tree
      if (block == other) return true
    } while (block != startBlock)
    return false
  }
}

/**
 * An instance of a [FunctionDefinition]'s control flow graph, including many precomputed data structures.
 *
 * @param functionIdentifier see [FunctionDefinition.funcIdent]
 * @param functionParameters see [FunctionDefinition.parameters]
 * @param startBlock Root block of the CFG
 * @param allNodes Raw set of nodes as obtained from [graph]
 * @param nodes Filtered set of nodes that only contains reachable, non-empty nodes
 * @param domTreePreorder see [createDomTreePreOrderNodes]
 * @param doms Stores the immediate dominator (IDom) of a particular node. See [findDomFrontiers]
 * @param exprDefinitions see [CFGFactory.exprDefinitions]
 * @param stackVariableIds [Variable.identityId] that will be stored as [StackVariable]s
 * @param definitions see [VariableRenamer.definitions]
 * @param defUseChains see [VariableRenamer.defUseChains]
 * @param latestVersions see [VariableRenamer.latestVersions]
 * @param registerIds IDs for [VirtualRegister]
 *
 * @see CFGFactory
 */
@JsExport
data class CFG(
    val functionIdentifier: TypedIdentifier,
    val functionParameters: List<TypedIdentifier>,
    val startBlock: BasicBlock,
    val allNodes: Set<BasicBlock>,
    val nodes: Set<BasicBlock>,
    val domTreePreorder: Set<BasicBlock>,
    val doms: DominatorList,
    val exprDefinitions: Map<Variable, Set<BasicBlock>>,
    val stackVariableIds: Set<AtomicId>,
    val definitions: Definitions,
    val defUseChains: DefUseChains,
    val latestVersions: Map<AtomicId, Int>,
    val registerIds: IdCounter,
) {
  init {
    check(startBlock.isRoot) { "Start block is not root" }
  }
}

/**
 * φ-functions happen just before the instructions in a [BasicBlock]. This is a pseudo-index that's
 * less than 0.
 */
const val DEFINED_IN_PHI: LabelIndex = -1

/**
 * Holds state required for SSA phase 2.
 * @see VariableRenamer.variableRenaming
 */
private class VariableRenamer(val cfg: CFGFactory) {
  /**
   * Stores what is the last created version of a particular variable (maps id to version).
   * @see variableRenaming
   */
  val latestVersions = mutableMapOf<AtomicId, Int>().withDefault { 0 }

  /** @see latestVersions */
  private var Variable.latestVersion: Int
    get() = latestVersions.getValue(identityId)
    set(value) {
      latestVersions[identityId] = value
    }

  /**
   * Maps each variable to its [ReachingDefinition].
   *
   * Track which definition was in use until a current definition. That is, if x2's reaching
   * definition is x1, all uses of x between x1's definition and x2's definition are going to be
   * renamed to x1, and all uses of x that come _after_ x2's definition will be renamed to x2.
   *
   * ```
   * +--> int x = 0; // rename def, x -> x1
   * |    f(x);      // rename use, x -> x1
   * +--- x = 2;     // rename def, x -> x2
   *      f(x);      // rename use, x -> x2
   * ```
   * This map tracks that arrow on the left, the one that identifies the previous definition.
   *
   * See section 3.1.3 in [http://ssabook.gforge.inria.fr/latest/book.pdf].
   * @see ReachingDefinition
   * @see Variable.reachingDef
   * @see variableRenaming
   */
  private val reachingDefs = mutableMapOf<Variable, ReachingDefinition?>()

  /**
   * Provides access to [reachingDefs] using property extension syntax (to resemble the original
   * algorithm).
   * @see variableRenaming
   */
  private var Variable.reachingDef: ReachingDefinition?
    get() = reachingDefs[this]
    set(value) {
      // Make a copy, don't bait and switch map keys (Variable.version is mutable)
      reachingDefs[this.copy()] = value
    }

  /** @see Variable.reachingDef */
  private var ReachingDefinition.reachingDef: ReachingDefinition?
    get() = variable.reachingDef
    set(value) {
      variable.reachingDef = value
    }

  /**
   * Def-use chains for each variable definition. Stores location of all uses.
   */
  val defUseChains = mutableMapOf<Variable, MutableList<Label>>()

  /**
   * Map of all versions' definitions, and their exact location.
   */
  val definitions = mutableMapOf<Variable, Label>()

  /**
   * Creates a new version of a variable. Updates [latestVersions].
   */
  private fun Variable.newVersion(): Variable = copy(++latestVersion)

  /**
   * See page 34 in [http://ssabook.gforge.inria.fr/latest/book.pdf].
   */
  private fun ReachingDefinition.dominates(block: BasicBlock, instrIdx: LabelIndex): Boolean {
    return if (definedIn == block) {
      if (definitionIdx == instrIdx && instrIdx == DEFINED_IN_PHI) {
        logger.throwICE("Multiple phi functions for same variable in the same block") {
          "$this dominates $block $instrIdx"
        }
      }
      definitionIdx < instrIdx
    } else {
      with(cfg) {
        block isDominatedBy definedIn
      }
    }
  }

  /**
   * See page 34 in [http://ssabook.gforge.inria.fr/latest/book.pdf].
   */
  private fun updateReachingDef(v: Variable, block: BasicBlock, instrIdx: LabelIndex) {
    var r = v.reachingDef
    while (!(r == null || r.dominates(block, instrIdx))) {
      r = r.reachingDef
    }
    v.reachingDef = r
  }

  /**
   * Does the renaming for a variable definition.
   */
  private fun handleDef(bb: BasicBlock, def: Variable, instrIdx: LabelIndex) {
    val oldReachingDef = def.reachingDef
    updateReachingDef(def, bb, instrIdx)
    val vPrime = def.newVersion()
    definitions[vPrime.copy()] = bb to instrIdx
    val reachingToPrime = ReachingDefinition(vPrime, bb, instrIdx)
    def.reachingDef = reachingToPrime
    def.replaceWith(reachingToPrime)
    vPrime.reachingDef = oldReachingDef
    traceVarDefinitionRename(bb, def, vPrime)
  }

  private fun findVariableUses(i: IRInstruction): List<Variable> = when (i) {
    is BinaryInstruction -> listOfNotNull(i.lhs as? Variable, i.rhs as? Variable)
    is StructuralCast -> listOfNotNull(i.operand as? Variable)
    is ReinterpretCast -> listOfNotNull(i.operand as? Variable)
    is NamedCall -> i.args.mapNotNull { it as? Variable }
    is IndirectCall -> i.args.mapNotNull { it as? Variable }
    is IntInvert -> listOfNotNull(i.operand as? Variable)
    is IntNeg -> listOfNotNull(i.operand as? Variable)
    is FltNeg -> listOfNotNull(i.operand as? Variable)
    is MoveInstr -> listOfNotNull(i.value as? Variable)
    is StoreMemory -> listOfNotNull(i.storeTo as? Variable, i.value as? Variable)
    is LoadMemory -> listOfNotNull(i.loadFrom as? Variable)
    else -> logger.throwICE("Unreachable")
  }

  /**
   * Update/create the def-use chain ([defUseChains]) for the given variable, and add the current
   * use to it (variable was used in [bb] at label [idx]). No-op if [Variable.reachingDef] is null.
   */
  private fun updateUsesFor(variable: Variable, bb: BasicBlock, idx: LabelIndex) {
    if (variable.reachingDef != null) {
      defUseChains.getOrPut(variable.reachingDef!!.variable, ::mutableListOf) += bb to idx
    }
  }

  /** @see variableRenaming */
  private fun variableRenamingImpl() = cfg.domTreePreorder.forEach { BB ->
    for (phi in BB.phi) {
      handleDef(BB, phi.variable, DEFINED_IN_PHI)
    }
    for ((idx, i) in BB.instructions.withIndex()) {
      val def = if (i is MoveInstr) i.result else null
      for (variable in findVariableUses(i)) {
        val oldReachingVar = variable.reachingDef?.variable
        updateReachingDef(variable, BB, idx)
        updateUsesFor(variable, BB, idx)
        variable.replaceWith(variable.reachingDef)
        traceVarUsageRename(BB, oldReachingVar, variable)
      }
      if (def == null || def !is Variable) continue
      handleDef(BB, def, idx)
    }
    for (succ in BB.successors) {
      for ((_, incoming) in succ.phi) {
        val incFromBB = incoming.getValue(BB)
        val oldReachingVar = incFromBB.reachingDef?.variable
        updateReachingDef(incFromBB, BB, Int.MAX_VALUE)
        updateUsesFor(incFromBB, succ, DEFINED_IN_PHI)
        incFromBB.replaceWith(incFromBB.reachingDef)
        traceVarUsageRename(succ, oldReachingVar, incFromBB, isInPhi = true)
      }
    }
  }

  /**
   * Perform second phase of SSA construction.
   * See Algorithm 3.3 in [http://ssabook.gforge.inria.fr/latest/book.pdf].
   */
  fun variableRenaming() {
    logger.trace(null as Throwable?, varRenamesTrace) { "BB| x mention   | x.reachingDef" }
    logger.trace(null as Throwable?, varRenamesTrace) { "-------------------------------" }
    variableRenamingImpl()
  }

  /**
   * Debug trace for variable usage renames.
   */
  private fun traceVarUsageRename(
      bb: BasicBlock,
      oldReachingVar: Variable?,
      v: Variable,
      isInPhi: Boolean = false,
  ) {
    if (v.name == "x") logger.trace(null as Throwable?, varRenamesTrace) {
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
      ).joinToString(" | ")
    }
  }

  /**
   * Debug trace for variable definition renames.
   */
  private fun traceVarDefinitionRename(
      bb: BasicBlock,
      def: Variable,
      vPrime: Variable,
  ) {
    if (def.name == "x") logger.trace(null as Throwable?, varRenamesTrace) {
      val oldReachingVar = def.reachingDef?.variable
      val oldReachingStr =
          if (oldReachingVar == null) "⊥" else "${oldReachingVar.name}${oldReachingVar.version}"
      listOf(
          "${bb.hashCode()}",
          "def ${def.name}${def.version}".padEnd(TRACE_COL2_LENGTH, ' '),
          "$oldReachingStr then ${vPrime.name}${vPrime.version}"
      ).joinToString(" | ")
    }
  }

  companion object {
    private val varRenamesTrace = getMarker("ControlFlowVariableRenames")
    private const val TRACE_COL2_LENGTH = 11
  }
}

private fun Iterable<AtomicId>.maybeReplace(it: IRValue): IRValue {
  return if (it is Variable && it.identityId in this) MemoryLocation(StackVariable(it.tid)) else it
}

private fun Iterable<AtomicId>.maybeReplace(it: LoadableValue): LoadableValue {
  return if (it is Variable && it.identityId in this) MemoryLocation(StackVariable(it.tid)) else it
}

// FIXME: this is possibly the dumbest way to implement this
fun Iterable<AtomicId>.replaceSpilled(i: IRInstruction): IRInstruction = when (i) {
  is StructuralCast -> i.copy(result = maybeReplace(i.result), operand = maybeReplace(i.operand))
  is ReinterpretCast -> i.copy(result = maybeReplace(i.result), operand = maybeReplace(i.operand))
  is NamedCall -> i.copy(args = i.args.map { maybeReplace(it) }, result = maybeReplace(i.result))
  is IndirectCall -> i.copy(args = i.args.map { maybeReplace(it) }, result = maybeReplace(i.result))
  is IntInvert -> i.copy(result = maybeReplace(i.result), operand = maybeReplace(i.operand))
  is IntNeg -> i.copy(result = maybeReplace(i.result), operand = maybeReplace(i.operand))
  is FltNeg -> i.copy(result = maybeReplace(i.result), operand = maybeReplace(i.operand))
  is IntBinary ->
    i.copy(result = maybeReplace(i.result), lhs = maybeReplace(i.lhs), rhs = maybeReplace(i.rhs))
  is IntCmp ->
    i.copy(result = maybeReplace(i.result), lhs = maybeReplace(i.lhs), rhs = maybeReplace(i.rhs))
  is FltBinary ->
    i.copy(result = maybeReplace(i.result), lhs = maybeReplace(i.lhs), rhs = maybeReplace(i.rhs))
  is FltCmp ->
    i.copy(result = maybeReplace(i.result), lhs = maybeReplace(i.lhs), rhs = maybeReplace(i.rhs))
  is StoreMemory -> i.copy(storeTo = maybeReplace(i.storeTo), value = maybeReplace(i.value))
  is LoadMemory -> i.copy(loadFrom = maybeReplace(i.loadFrom), result = maybeReplace(i.result))
  is MoveInstr -> {
    val result = i.result
    val value = i.value
    if (result is Variable && result.identityId in this) {
      StoreMemory(MemoryLocation(StackVariable(result.tid)), value)
    } else if (value is Variable && value.identityId in this) {
      LoadMemory(result, MemoryLocation(StackVariable(value.tid)))
    } else {
      i
    }
  }
}

// FIXME: this needs to go away. calling it in codegen is ??? and calling it for &x expressions is iffy at best
fun CFG.insertSpillCode(spilled: List<AtomicId>) = nodes.forEach { node ->
  val newIR = node.ir.map(spilled::replaceSpilled)
  node.ir.clear()
  node.ir += newIR
  val term = node.terminator
  when {
    term is ImpossibleJump && term.returned != null -> {
      node.terminator = term.copy(returned = term.returned.map(spilled::replaceSpilled))
    }
    term is CondJump -> {
      node.terminator = term.copy(cond = term.cond.map(spilled::replaceSpilled))
    }
    term is SelectJump -> {
      node.terminator = term.copy(cond = term.cond.map(spilled::replaceSpilled))
    }
  }
}

/**
 * φ-function insertion.
 *
 * See Algorithm 3.1 in [http://ssabook.gforge.inria.fr/latest/book.pdf] for variable notations.
 */
private fun insertPhiFunctions(definitions: Map<Variable, MutableSet<BasicBlock>>) {
  for ((v, defsV) in definitions) {
    val f = mutableSetOf<BasicBlock>()
    val w = mutableSetOf(*defsV.toTypedArray())
    while (w.isNotEmpty()) {
      val x = w.first()
      w -= x
      for (y in x.dominanceFrontier) {
        if (y !in f) {
          y.phi += PhiInstruction(v.copy(0), y.preds.associateWith { v.copy(0) })
          f += y
          if (y !in defsV) w += y
        }
      }
    }
  }
}

/**
 * Orders [BasicBlock]s by doing a pre-order (NLR) traversal of the dominator tree.
 *
 * ```
 * This dominator tree:
 *           A
 *         /  \
 *        B    C
 *           / | \
 *          D  E  F
 * ```
 * Would be traversed: A B C D E F
 *
 * The size of [doms], [nodes], and the resulting set must be identical.
 */
fun createDomTreePreOrderNodes(
    doms: DominatorList,
    root: BasicBlock,
    nodes: Set<BasicBlock>,
): Set<BasicBlock> {
  val visited = mutableSetOf<BasicBlock>()
  val stack = mutableListOf<BasicBlock>()
  stack += root
  while (stack.isNotEmpty()) {
    val block = stack.removeLast()
    if (block in visited) continue
    visited += block
    stack += nodes.filter { doms[it] == block }.sortedBy { it.height }.asReversed()
  }
  return visited
}

/** A [MutableList] of [BasicBlock]s, indexed by [BasicBlock]s. */
@JsExport
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
 *
 * Doesn't return a [Sequence] because we will need it in reverse.
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
  val checkReachableQueue = nodes.toMutableList()
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
  val collapseCandidates = nodes.toMutableList()
  val visited = mutableSetOf<BasicBlock>()
  while (collapseCandidates.isNotEmpty()) {
    val node = collapseCandidates.removeFirst()
    if (node in visited) continue
    while (node.collapseEmptyPreds());
    visited += node
    collapseCandidates += node.preds
  }
}
