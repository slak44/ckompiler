package slak.ckompiler.backend

import slak.ckompiler.AtomicId
import slak.ckompiler.IdCounter
import slak.ckompiler.analysis.*

typealias InstrLabel = Pair<AtomicId, Int>

class InstructionGraph private constructor(
    val functionName: String,
    val startId: AtomicId,
    val registerIds: IdCounter,
    latestVersions: MutableMap<AtomicId, Int>,
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

  /** @see LivenessData */
  val liveness = LivenessData(this, latestVersions)

  /**
   * Stores all variables used in parallel copies, for each block. Since these are basically φ instructions in the
   * middle of a block, we need to know who's getting redefined, to correctly compute the iterated dominance frontier.
   *
   * @see iteratedDominanceFrontier
   */
  val parallelCopies = mutableMapOf<AtomicId, MutableList<Variable>>()

  /**
   * Used to determine if a variable died in a block due to being spilled there. This is empty before spilling runs.
   *
   * @see computeLiveSetsByVar
   * @see insertSpillReloadCode
   */
  var spillBlocks: SpillBlocks = emptyMap()

  private fun domTreeChildren(id: AtomicId) = (nodes.keys - returnBlock.id)
      .filter { idom[this[it].seqId] == id }
      .sortedBy { this[it].domTreeHeight }
      .asReversed()

  val domTreePreorder
    get() = iterator {
      val visited = mutableSetOf<AtomicId>()
      val stack = mutableListOf<AtomicId>()
      stack += startId
      while (stack.isNotEmpty()) {
        val blockId = stack.removeLast()
        if (blockId in visited) continue
        yield(blockId)
        visited += blockId
        stack += domTreeChildren(blockId)
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
   * Useful for inserting φ copies.
   *
   * Also useful in the spiller, where coupling code must be inserted if the [src] register file differs from the
   * [dest] register file (eg a variable is spilled in [src] but not in [dest]).
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
    dominanceFrontiers[newBlock.id] = dominanceFrontiers.getValue(dest.id) + dest.id
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
        nodes.getValue(node).phiDefs.any { it.identityId in variableDefIds } ||
            parallelCopies[node]?.any { it.identityId in variableDefIds } == true
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
    liveness.initializeVariableDefs(cfg.definitions)
    liveness.findDefUseChainsAndPruneDeadPhis(cfg.definitions)
    liveness.recomputeLiveSets()
  }

  companion object {
    fun partiallyInitialize(cfg: CFG): InstructionGraph {
      val graph = InstructionGraph(cfg.functionIdentifier.name, cfg.startBlock.nodeId, cfg.registerIds, cfg.latestVersions.toMutableMap())
      graph.idom += MutableList(cfg.nodes.size) { 0 }
      return graph
    }
  }
}
