package slak.ckompiler.backend

import org.apache.logging.log4j.LogManager
import slak.ckompiler.AtomicId
import slak.ckompiler.IdCounter
import slak.ckompiler.analysis.*
import slak.ckompiler.parser.FunctionDefinition
import slak.ckompiler.throwICE
import java.util.*

private val logger = LogManager.getLogger()

typealias DefUseChains = MutableMap<AllocatableValue, MutableSet<InstrLabel>>
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

  /**
   * Stores immediate dominator for a block. Indexed by [InstrBlock.seqId].
   */
  private val idom = mutableListOf<AtomicId>()

  private val dominanceFrontiers = mutableMapOf<AtomicId, Set<AtomicId>>()

  /**
   * All returns actually jump to this synthetic block, which then really returns from the function.
   */
  val returnBlock: InstrBlock = newBlock(Int.MAX_VALUE, emptyList())

  /**
   * Store the block where a version of a [Variable] was defined.
   *
   * For [VirtualRegister]s, the definition is in the same block, and can be easily found by iterating the block.
   */
  val variableDefs = mutableMapOf<Variable, AtomicId>()

  lateinit var defUseChains: DefUseChains

  /**
   * Stores all variables used in parallel copies, for each block. Since these are basically φ instructions in the
   * middle of a block, we need to know who's getting redefined, to correctly compute the iterated dominance frontier.
   *
   * @see iteratedDominanceFrontier
   */
  val parallelCopies = mutableMapOf<AtomicId, MutableList<Variable>>()

  val virtualDeaths = mutableMapOf<VirtualRegister, InstrLabel>()

  /**
   * @see computeLiveSetsByVar
   */
  lateinit var liveSets: LiveSets

  /**
   * This function incrementally some maps, by updating all the indices that were pushed due to an instruction (or more)
   * being inserted. It can also be used for removals, with [inserted] set to a negative value.
   */
  fun updateIndices(block: AtomicId, index: LabelIndex, inserted: Int = 1) {
    for ((modifiedValue, oldLabel) in virtualDeaths.filterValues { it.first == block && it.second >= index }) {
      val nextIdx = if (oldLabel.second == Int.MAX_VALUE) Int.MAX_VALUE else oldLabel.second + inserted
      virtualDeaths[modifiedValue] = InstrLabel(block, nextIdx)
    }
    val toUpdate = defUseChains.map { (v, uses) -> v to uses.filter { it.first == block && it.second >= index } }
    for ((modifiedValue, oldLabels) in toUpdate) {
      val updated = mutableListOf<InstrLabel>()
      for (oldLabel in oldLabels) {
        val nextIdx = if (oldLabel.second == Int.MAX_VALUE) Int.MAX_VALUE else oldLabel.second + inserted
        defUseChains[modifiedValue]!! -= oldLabel
        updated += InstrLabel(block, nextIdx)
      }
      defUseChains[modifiedValue]!! += updated
    }
    liveSets = computeLiveSetsByVar()
  }

  /**
   * Creates a new virtual register, or a new version of a variable.
   */
  fun createCopyOf(value: AllocatableValue, block: InstrBlock): AllocatableValue {
    if (value.isUndefined) return value
    return when (value) {
      is Variable -> {
        val oldVersion = latestVersions.getValue(value.id)
        latestVersions[value.id] = oldVersion + 1
        val copy = value.copy(oldVersion + 1)
        // Also update definitions for variables
        variableDefs[copy] = block.id
        copy
      }
      is VirtualRegister -> {
        val copy = VirtualRegister(registerIds(), value.type)
        // The copy will die where the original died
        virtualDeaths[copy] = virtualDeaths.getValue(value)
        copy
      }
    }
  }

  fun domTreeChildren(id: AtomicId) = (nodes.keys - returnBlock.id)
      .filter { idom[this[it].seqId] == id }
      .sortedBy { this[it].domTreeHeight }
      .asReversed()

  val domTreePreorder
    get() = iterator<AtomicId> {
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
   * Produces a postorder traversal of this instruction graph's nodes.
   */
  fun postOrder(): List<AtomicId> {
    val postOrder = mutableListOf<AtomicId>()
    val visited = mutableSetOf<AtomicId>()
    val stack = mutableListOf<AtomicId>()
    stack += startId
    while (stack.isNotEmpty()) {
      val v = stack.last()
      val next = successors(v).map(InstrBlock::id).find { it !in visited }
      if (next == null) {
        stack.removeLast()
        postOrder += v
      } else {
        visited += next
        stack += next
      }
    }
    return postOrder
  }

  /**
   * Traverse the dominator tree from a certain node ([beginAt]) upwards to the root of the tree.
   */
  fun traverseDominatorTree(beginAt: AtomicId): Iterator<AtomicId> = iterator {
    if (beginAt == startId) {
      yield(startId)
      return@iterator
    }
    var node = beginAt
    do {
      yield(node)
      node = idom[nodes.getValue(node).seqId]
    } while (node != startId)
    yield(startId)
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

  fun predecessors(blockId: AtomicId): Set<InstrBlock> {
    return transposed.getValue(blockId)
  }

  fun isDeadAt(value: Variable, label: InstrLabel): Boolean {
    if (value.isUndefined || !isUsed(value)) return true

    val (block, index) = label
    val isLiveIn = value in liveSets.liveIn[block] ?: emptyList()
    val isLiveOut = value in liveSets.liveOut[block] ?: emptyList()

    // Live-through
    if (isLiveIn && isLiveOut) return false

    val lastUseHere = defUseChains.getValue(value).filter { it.first == block }.maxByOrNull { it.second }

    // Killed in block, check if index is after last use
    if (isLiveIn && !isLiveOut) {
      return lastUseHere == null || index > lastUseHere.second
    }

    // If the variable is:
    // - Not defined in this block
    // - Not used in this block
    // - Not live-in and not live-out
    // it is most definitely dead.
    if (variableDefs.getValue(value) != block && lastUseHere == null && !isLiveIn && !isLiveOut) {
      return true
    }

    val definitionHere = this[block].indexOfFirst { value in it.defs }
    check(definitionHere != -1) {
      "Not live-in, but there is no definition of this variable here (value=$value, label=$label)"
    }

    // Defined in this block, check definition index
    if (!isLiveIn && isLiveOut) {
      return index < definitionHere
    }

    if (!isLiveIn && !isLiveOut) {
      // If lastUseHere is null, and it's neither live-in nor live-out, this value should return false for isUsed
      // Which is checked at the top of this function, so it's a bug if no "last" use is found here
      checkNotNull(lastUseHere)
      // Otherwise, it is both defined and killed in this block
      // If the index is after the last use, or before the definition, it's dead
      return index > lastUseHere.second || index < definitionHere
    }
    logger.throwICE("Unreachable")
  }

  fun isDeadAfter(value: AllocatableValue, label: InstrLabel): Boolean {
    return when (value) {
      is VirtualRegister -> {
        // FIXME
        val last = virtualDeaths[value] ?: return true
        last == label
      }
      is Variable -> isDeadAt(value, label.first to label.second + 1)
    }
  }

  fun livesThrough(value: AllocatableValue, label: InstrLabel): Boolean {
    return when (value) {
      is VirtualRegister -> {
        // FIXME
        val (lastBlock, lastIndex) = virtualDeaths[value] ?: return false
        val (queryBlock, queriedIndex) = label
        check(lastBlock == queryBlock)
        lastIndex > queriedIndex
      }
      // FIXME: fairy inefficient
      is Variable -> !isDeadAt(value, label) && !isDeadAfter(value, label)
    }
  }

  fun isUsed(value: AllocatableValue): Boolean = when (value) {
    is VirtualRegister -> value in virtualDeaths
    is Variable -> value in defUseChains && defUseChains.getValue(value).isNotEmpty()
  }

  fun liveInsOf(blockId: AtomicId): Set<Variable> = liveSets.liveIn[blockId] ?: emptySet()

  /**
   * Create a new [InstrBlock], without a backing [BasicBlock].
   *
   * Use this function with care: [nodes], [adjacency], [transposed] and maybe other state must be updated.
   */
  private fun newBlock(treeHeight: Int, instrs: List<MachineInstruction>): InstrBlock {
    // Increase size of idom array, because the seqId is nodes.size
    idom += 0
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
    nodes[newBlock.id] = newBlock
    // Update dominator tree
    idom[newBlock.seqId] = src.id
    if (idom[dest.seqId] == src.id) {
      idom[dest.seqId] = newBlock.id
    }
    // Adjust existing edges
    adjacency.getValue(src.id) -= dest
    transposed.getValue(dest.id) -= src
    adjacency.getValue(src.id) += newBlock
    transposed.getValue(dest.id) += newBlock
    // Add new edge
    adjacency[newBlock.id] = mutableSetOf(dest)
    transposed[newBlock.id] = mutableSetOf(src)
    // Update successor's φs, for which the old block was incoming (now newBlock should be in incoming)
    for ((_, incoming) in dest.phi) {
      incoming[newBlock.id] = incoming[src.id] ?: continue
      incoming -= src.id
    }
    return newBlock
  }

  /**
   * Populate [virtualDeaths].
   */
  private fun findVirtualRanges() {
    for (blockId in domTreePreorder) {
      for ((index, mi) in this[blockId].withIndex()) {
        // Keep updating the map
        // Obviously, the last use will be the last update in the map
        for (it in mi.uses.filterIsInstance<VirtualRegister>()) {
          virtualDeaths[it] = InstrLabel(blockId, index)
        }
      }
    }
  }

  private fun findDefUseChains(): DefUseChains {
    val allUses = mutableMapOf<AllocatableValue, MutableSet<InstrLabel>>()

    for (blockId in domTreePreorder) {
      val block = this[blockId]
      for (phi in block.phi) {
        for (variable in phi.value.values) {
          allUses.getOrPut(variable, ::mutableSetOf) += InstrLabel(blockId, DEFINED_IN_PHI)
        }
      }
      for ((index, mi) in block.withIndex()) {
        val uses = mi.filterOperands { _, use -> use == VariableUse.USE }.filterIsInstance<AllocatableValue>()
        for (variable in uses + mi.constrainedArgs.map { it.value }) {
          allUses.getOrPut(variable, ::mutableSetOf) += InstrLabel(blockId, index)
        }
      }
    }

    return allUses
  }

  /**
   * Get the iterated dominance frontier of a set of [InstrBlock]s.
   */
  fun iteratedDominanceFrontier(blocks: Iterable<AtomicId>, variableDefIds: Set<AtomicId>): Set<AtomicId> {
    val visited = mutableSetOf<AtomicId>()
    val iteratedFront = mutableSetOf<AtomicId>()
    fun iterate(block: AtomicId) {
      if (block in visited) return
      visited += block
      val blockFront = dominanceFrontiers.getValue(block)
      iteratedFront += blockFront
      for (frontierBlock in blockFront.filter { node ->
        nodes.getValue(node).phiDefs.any { it.id in variableDefIds } ||
            parallelCopies[node]?.any { it.id in variableDefIds } == true
      }) {
        iterate(frontierBlock)
      }
    }
    for (block in blocks) iterate(block)
    return iteratedFront
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
    nodes[returnBlock.id] = returnBlock
    adjacency[returnBlock.id] = mutableSetOf()
    transposed[returnBlock.id] = mutableSetOf()
    for (node in cfg.domTreePreorder) {
      adjacency[node.nodeId] = node.successors.mapTo(mutableSetOf()) { nodes.getValue(it.nodeId) }
      transposed[node.nodeId] = node.preds.mapTo(mutableSetOf()) { nodes.getValue(it.nodeId) }
      if (node.terminator is ImpossibleJump) {
        transposed.getValue(returnBlock.id) += nodes.getValue(node.nodeId)
      }
    }
    dominanceFrontiers += cfg.nodes.associate { it.nodeId to it.dominanceFrontier.map(BasicBlock::nodeId).toSet() }
    variableDefs += cfg.definitions.mapValues { it.value.first.nodeId }
    defUseChains = findDefUseChains()
    liveSets = computeLiveSetsByVar()
    findVirtualRanges()
  }

  companion object {
    fun partiallyInitialize(cfg: CFG): InstructionGraph {
      val graph = InstructionGraph(cfg.f, cfg.startBlock.nodeId, cfg.registerIds, cfg.latestVersions)
      graph.idom += MutableList(cfg.nodes.size) { 0 }
      return graph
    }
  }
}
