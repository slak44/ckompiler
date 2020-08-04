package slak.ckompiler.backend

import org.apache.logging.log4j.LogManager
import slak.ckompiler.AtomicId
import slak.ckompiler.IdCounter
import slak.ckompiler.analysis.*
import slak.ckompiler.parser.FunctionDefinition
import slak.ckompiler.throwICE
import java.util.*

private val logger = LogManager.getLogger()

typealias InstrLabel = Pair<AtomicId, Int>

class InstructionGraph private constructor(
    val f: FunctionDefinition,
    val startId: AtomicId,
    val registerIds: IdCounter,
    private val latestVersions: MutableMap<AtomicId, Int>
) {
  private val nodes = mutableMapOf<AtomicId, InstrBlock>()
  private val adjacency = mutableMapOf<AtomicId, MutableSet<InstrBlock>>()
  private val transposed = mutableMapOf<AtomicId, MutableSet<InstrBlock>>()

  private val idom = mutableListOf<AtomicId>()
  private val dominanceFrontiers = mutableMapOf<AtomicId, Set<AtomicId>>()

  /**
   * All returns actually jump to this synthetic block, which then really returns from the function.
   */
  val returnBlock: InstrBlock = newBlock(Int.MAX_VALUE, emptyList())

  private val liveIns = mutableMapOf<AtomicId, MutableSet<Variable>>()

  /**
   * Store the block where a version of a [Variable] was defined.
   *
   * For [VirtualRegister]s, the definition is in the same block, and can be easily found by iterating the block.
   *
   * This is really only useful for SSA reconstruction.
   */
  private val variableDefs = mutableMapOf<Variable, AtomicId>()

  val deaths = mutableMapOf<AllocatableValue, InstrLabel>()

  /**
   * This function incrementally updates a "last uses" map, by updating all the last use indices that were pushed due to
   * an instruction (or more) being inserted. It can also be used for removals, with [inserted] set to a negative value.
   */
  fun updateLastUses(
      block: AtomicId,
      index: LabelIndex,
      inserted: Int = 1
  ) {
    for ((modifiedValue, oldDeath) in deaths.filterValues { it.first == block && it.second >= index }) {
      val nextIdx = if (oldDeath.second == Int.MAX_VALUE) Int.MAX_VALUE else oldDeath.second + inserted
      deaths[modifiedValue] = InstrLabel(block, nextIdx)
    }
  }

  /**
   * Creates a new virtual register, or a new version of a variable.
   */
  fun createCopyOf(value: AllocatableValue, block: InstrBlock): AllocatableValue {
    if (value.isUndefined) return value
    val copiedValue = if (value is Variable) {
      val oldVersion = latestVersions.getValue(value.id)
      latestVersions[value.id] = oldVersion + 1
      val copy = value.copy(oldVersion + 1)
      // Also update definitions for variables
      variableDefs[copy] = block.id
      copy
    } else {
      VirtualRegister(registerIds(), value.type)
    }
    // The copy will die where the original died
    deaths[copiedValue] = deaths.getValue(value)
    return copiedValue
  }

  fun domTreeChildren(id: AtomicId) = nodes.keys
      .filter { idom[this[id].seqId] == id }
      .sortedBy { this[id].domTreeHeight }
      .asReversed()

  val domTreePreorder get() = iterator<AtomicId> {
    val visited = mutableSetOf<AtomicId>()
    val stack = Stack<AtomicId>()
    stack.push(startId)
    while (stack.isNotEmpty()) {
      val blockId = stack.pop()
      if (blockId in visited) continue
      yield(blockId)
      visited += blockId
      for (child in domTreeChildren(blockId)) {
        stack.push(child)
      }
    }
  }

  /**
   * Traverse the dominator tree from a certain node ([beginAt]) upwards to the root of the tree.
   */
  fun traverseDominatorTree(beginAt: AtomicId): Iterator<AtomicId> = iterator {
    var node = beginAt
    do {
      yield(node)
      node = idom[nodes.getValue(node).seqId]
    } while (node != startId)
    if (node != startId) yield(startId)
  }

  val blocks: Set<AtomicId> get() = nodes.keys

  operator fun get(id: AtomicId): InstrBlock = nodes.getValue(id)

  fun successors(block: InstrBlock): Set<InstrBlock> {
    return adjacency.getValue(block.id)
  }

  fun successors(blockId: AtomicId): Set<InstrBlock> {
    return adjacency.getValue(blockId)
  }

  fun predecessors(block: InstrBlock): Set<InstrBlock> {
    return transposed.getValue(block.id)
  }

  fun lastUseOf(value: AllocatableValue): InstrLabel? = deaths[value]

  fun isLastUse(value: AllocatableValue, label: InstrLabel): Boolean {
    val last = lastUseOf(value) ?: return true
    return last == label
  }

  fun livesThrough(value: AllocatableValue, label: InstrLabel): Boolean {
    val (lastBlock, lastIndex) = lastUseOf(value) ?: return false
    return lastBlock == label.first && lastIndex > label.second
  }

  fun isUsed(value: AllocatableValue): Boolean = value in deaths

  fun liveInsOf(blockId: AtomicId): Set<Variable> = liveIns[blockId] ?: emptySet()

  fun newBlock(treeHeight: Int, instrs: List<MachineInstruction>): InstrBlock {
    return InstrBlock(BasicBlock.nodeCounter(), nodes.size, treeHeight, this, instrs.toMutableList(), mutableMapOf())
  }

  /**
   * Replace all mentions of [old] with [new] inside [inBlock], for when [old] is no longer a successor of [inBlock].
   */
  private fun replaceJump(inBlock: InstrBlock, old: InstrBlock, new: InstrBlock) {
    for ((index, mi) in inBlock.withIndex()) {
      var updated = false
      val newOperands = mi.operands.map {
        if (it is JumpTargetConstant && it.target == old.id) {
          updated = true
          JumpTargetConstant(new.id)
        } else {
          it
        }
      }
      if (updated) {
        inBlock[index] = mi.copy(operands = newOperands)
      }
    }
  }

  /**
   * Split an edge in the graph, and insert another [InstrBlock] there.
   *
   * @param createJump should generate an unconditional jump to the given block
   */
  fun splitEdge(src: InstrBlock, dest: InstrBlock, createJump: (InstrBlock) -> MachineInstruction): InstrBlock {
    require(dest in adjacency.getValue(src.id)) { "No edge between given blocks" }
    val newBlock = newBlock(dest.domTreeHeight, listOf(createJump(dest)))
    replaceJump(src, dest, newBlock)
    // Adjust edges
    adjacency.getValue(src.id) -= dest
    transposed.getValue(dest.id) -= src
    adjacency.getValue(src.id) += newBlock
    transposed.getValue(dest.id) += newBlock
    // Update successor's φs, for which the old block was incoming (now newBlock should be in incoming)
    for ((_, incoming) in dest.phi) {
      incoming[newBlock.id] = incoming[src.id] ?: continue
      incoming -= src.id
    }
    return newBlock
  }

  /**
   * Populate [liveIns].
   */
  private fun computeLiveIns(allUses: Map<AllocatableValue, List<InstrLabel>>) {
    fun newLiveIn(value: Variable, blockId: AtomicId) {
      liveIns.putIfAbsent(blockId, mutableSetOf())
      liveIns.getValue(blockId) += value
    }

    for ((value, uses) in allUses.filterKeys { it is Variable }) {
      for ((blockId, _) in uses) {
        // If a variable is used in a block, it's live-in in that block...
        newLiveIn(value as Variable, blockId)
      }
    }
    for ((variable, definitionBlockId) in variableDefs) {
      if (definitionBlockId in liveIns) {
        // ...except for the block it's defined in
        liveIns.getValue(definitionBlockId) -= variable
      }
    }
  }

  /**
   * Populate [deaths].
   */
  private fun findLastUses() {
    for (blockId in domTreePreorder) {
      for ((index, mi) in this[blockId].withIndex()) {
        // For "variables", keep updating the map
        // Obviously, the last use will be the last update in the map
        for (it in mi.uses.filterIsInstance<AllocatableValue>()) {
          deaths[it] = InstrLabel(blockId, index)
        }
      }
      // We need to check successor phis: if something is used there, it means it is live-out in this block
      // If it's live-out in this block, its "last use" is not what was recorded above
      for (succ in successors(blockId)) {
        for ((_, incoming) in succ.phi) {
          val usedInSuccPhi = incoming.getValue(blockId)
          deaths[usedInSuccPhi] = InstrLabel(blockId, LabelIndex.MAX_VALUE)
        }
      }
    }
  }

  private fun findDefUseChains(): Map<AllocatableValue, List<InstrLabel>> {
    val allUses = mutableMapOf<AllocatableValue, MutableList<InstrLabel>>()

    fun newUse(value: AllocatableValue, label: InstrLabel) {
      allUses.putIfAbsent(value, mutableListOf())
      allUses.getValue(value) += label
    }

    for (blockId in domTreePreorder) {
      val block = this[blockId]
      for (phi in block.phi) {
        for (variable in phi.value.values) {
          newUse(variable, InstrLabel(blockId, DEFINED_IN_PHI))
        }
      }
      for ((index, mi) in block.withIndex()) {
        for (variable in mi.filterOperands { _, use -> use == VariableUse.USE }.filterIsInstance<AllocatableValue>()) {
          newUse(variable, InstrLabel(blockId, index))
        }
      }
    }

    return allUses
  }

  /**
   * Get the iterated dominance frontier of a set of [InstrBlock]s.
   */
  fun Iterable<AtomicId>.iteratedDominanceFrontier(variableDefIds: Set<AtomicId>): Set<AtomicId> {
    val visited = mutableSetOf<AtomicId>()
    val iteratedFront = mutableSetOf<AtomicId>()
    fun iterate(block: AtomicId) {
      if (block in visited) return
      visited += block
      val blockFront = dominanceFrontiers.getValue(block)
      iteratedFront += blockFront
      for (frontierBlock in blockFront.filter { node ->
        nodes.getValue(node).phi.keys.any { it.id in variableDefIds }
      }) {
        iterate(frontierBlock)
      }
    }
    for (block in this) iterate(block)
    return iteratedFront
  }

  /**
   * Rebuild SSA for some affected variables. See reference for some notations.
   *
   * Register Allocation for Programs in SSA Form, Sebastian Hack: 4.2.1.2, Algorithm 4.1
   */
  fun ssaReconstruction(reconstruct: Set<Variable>) {
    val defUseChains = findDefUseChains()
    val vars = reconstruct.toMutableSet()
    val ids = vars.mapTo(mutableSetOf()) { it.id }
    val blocks = vars.map { variableDefs.getValue(it) }.toMutableSet()
    val f = blocks.iteratedDominanceFrontier(ids)

    fun findDef(label: InstrLabel, variable: Variable): Variable {
      val (blockId, index) = label

      for (u in traverseDominatorTree(blockId)) {
        val uBlock = this[u]
        // If this is a phi, we need to skip the first block
        if (u == blockId && index == DEFINED_IN_PHI) continue
        // Ignore things after our given label (including the label)
        for (mi in uBlock.take(if (u == blockId) index else Int.MAX_VALUE).asReversed()) {
          val maybeDefined = mi.defs.filterIsInstance<Variable>().firstOrNull { it.id == variable.id }
          if (maybeDefined != null) {
            return maybeDefined
          }
        }
        val maybeDefinedPhi = uBlock.phi.entries.firstOrNull { it.key.id == variable.id }
        if (maybeDefinedPhi != null) {
          return maybeDefinedPhi.key
        }
        if (u in f) {
          val incoming =
              predecessors(uBlock).map { it.id }.associateWith { findDef(InstrLabel(it, Int.MAX_VALUE), variable) }
          uBlock.phi[variable] = incoming.toMutableMap()
        }
      }
      logger.throwICE("Unreachable: no definition for $variable was found up the tree from $label")
    }

    for (x in reconstruct) {
      for (label in defUseChains.getValue(x)) {
        val (blockId, index) = label
        val block = this[blockId]
        val newVersion = findDef(label, x)
        // Already wired correctly
        if (x == newVersion) continue
        if (index == DEFINED_IN_PHI) {
          val incoming = block.phi.getValue(x)
          val target = incoming.entries.first { it.value == x }.key
          incoming[target] = newVersion
        } else {
          block[index] = block[index].rewriteBy(mapOf(x to newVersion))
        }
      }
    }
  }

  /**
   * This function is separate from [InstructionGraph.partiallyInitialize] purely because [obtainMI] can and does make
   * use of the graph before it's built (eg [registerIds]).
   */
  fun copyStructureFrom(cfg: CFG, obtainMI: (BasicBlock) -> List<MachineInstruction>) {
    for (node in cfg.nodes) {
      val block = InstrBlock.fromBasic(this, node, obtainMI(node))
      nodes[node.nodeId] = block
      idom[block.seqId] = cfg.doms[node]!!.nodeId
    }
    for (node in cfg.domTreePreorder) {
      adjacency[node.nodeId] = node.successors.mapTo(mutableSetOf()) { nodes.getValue(it.nodeId) }
      transposed[node.nodeId] = node.preds.mapTo(mutableSetOf()) { nodes.getValue(it.nodeId) }
    }
    dominanceFrontiers +=
        cfg.nodes.associate { it.nodeId to it.dominanceFrontier.map(BasicBlock::nodeId).toSet() }
    variableDefs += cfg.definitions.mapValues { it.value.first.nodeId }
    findLastUses()
    computeLiveIns(findDefUseChains())
  }

  companion object {
    fun partiallyInitialize(cfg: CFG): InstructionGraph {
      val graph = InstructionGraph(cfg.f, cfg.startBlock.nodeId, cfg.registerIds, cfg.latestVersions)
      graph.idom += MutableList(cfg.nodes.size) { 0 }
      return graph
    }
  }
}
