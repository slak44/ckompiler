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
      for (oldLabel in oldLabels) {
        val nextIdx = if (oldLabel.second == Int.MAX_VALUE) Int.MAX_VALUE else oldLabel.second + inserted
        defUseChains[modifiedValue]!! -= oldLabel
        defUseChains[modifiedValue]!! += InstrLabel(block, nextIdx)
      }
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

    val lastUseHere = defUseChains.getValue(value).filter { it.first == block }.maxBy { it.second }

    // Killed in block, check if index is after last use
    if (isLiveIn && !isLiveOut) {
      return lastUseHere == null || index > lastUseHere.second
    }

    val definitionHere = this[block].indexOfFirst { value in it.defs }
    check(definitionHere != -1) { "Not live-in, but there is no definition of this variable here" }

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
    // Adjust existing edges
    adjacency.getValue(src.id) -= dest
    transposed.getValue(dest.id) -= src
    adjacency.getValue(src.id) += newBlock
    transposed.getValue(dest.id) += newBlock
    // Add new edge
    adjacency[newBlock.id] = mutableSetOf(dest)
    transposed.getValue(dest.id) += newBlock
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
   * Rebuild SSA for some affected variables. See reference for some notations.
   *
   * Register Allocation for Programs in SSA Form, Sebastian Hack: 4.2.1.2, Algorithm 4.1
   */
  fun ssaReconstruction(reconstruct: Set<Variable>) {
    // vars is the set D in the algorithm
    val vars = reconstruct.toMutableSet()
    val ids = vars.mapTo(mutableSetOf()) { it.id }
    val blocks = vars.flatMap { variableDefs.filterKeys { defVar -> defVar.id == it.id }.values }.toMutableSet() +
        parallelCopies.filterValues { it.any { variable -> variable.id in ids } }.map { it.key }
    val f = iteratedDominanceFrontier(blocks, ids)

    /**
     * This function looks for the closest definition of [variable].
     *
     * It starts at the [label], and checks each MI of the block in reverse (excluding the MI at the [label] itself).
     * After the MIs, the φs are also checked, since they too define new versions of variables.
     * If a definition is found, the function returns.
     * Otherwise, it repeats the process for the immediate dominator of the [label]'s block. For those blocks, we do not
     * start in the middle of the block (as we do for the initial [label]). All MIs are considered (index is set
     * to [Int.MAX_VALUE]).
     *
     * If the dominator tree root is reached without finding a definition, an error is thrown,
     * since it means the variable was used without being defined. Though this is technically possible in C, our
     * internal representation doesn't permit it, so it should never happen. C's use-before-define is turned into
     * variables with version 0, which are marked as undefined.
     *
     * The parameter [p] is mostly an implementation detail. When looking at a [label] that points to a φ, we need to
     * know which predecessor to go to. This information is available only to the caller of this function, which is why
     * we receive it as a parameter. It is null when the [label] does not refer to a φ.
     *
     * For certain variables in certain graphs, reaching the definition requires crossing that definition's dominance
     * frontier. This is problematic: at such nodes in the frontier, there is by definition at least another path that
     * can reach the node, beside the one containing our definition. Since we are in the business of ensuring the SSA
     * property, we can't just skip over these nodes. If the node already had a φ row for our variable, we can ignore
     * it, since it will be handled by the use-rewriting below. However, if there was no φ, we have to insert one. This
     * is the real problem, since we have no way of knowing the definitions for the other paths. Unless, of course, we
     * look for them, and it so happens that this function searches for definitions of variables.
     * We can break the problem in two using this observation: the original call that brought us to this frontier node,
     * and the finding definitions for all the predecessor paths to this block, so we can correctly create the φ.
     * The original call is solved; inserting the φ here creates a definition, which will become the closest definition
     * for the original caller.
     * For the second problem, we recursively call this function for all the predecessor paths: we will have a map of
     * predecessors to the versions live-out in them, which is exactly what we need to create the φ.
     *
     * There is also the issue of identifying these frontier blocks. We use the iterated dominance frontier of all the
     * variables we need to reconstruct (we apply iterated DF on the set of all definitions for these variables).
     *
     * An example for this case of inserting a φ is a diamond-looking graph:
     * ```
     *    A
     *   / \
     *  B   C
     *   \ /
     *    D
     * ```
     *
     * Consider a variable defined at the top in A (version 1), and used in all other 3 blocks (never redefined).
     * Assume something triggered [ssaReconstruction] for this variable in block C (a function call, for instance).
     * Blocks A and B do not require changes. Block C will contain the definition that triggered the reconstruction, and
     * will have all uses after the definition rewritten. Block D, though, has a problem: it's in the dominance frontier
     * of C. While a φ wasn't necessary before (there was only one version initially, never redefined), it is now;
     * Version 1 is live-out in block B, but the new definition in block C means version 2 is live-out in that block.
     * Block D now has to choose between these versions, which is exactly the purpose of φs.
     *
     * If the φ was already there in D, it would have been ok: just update the incoming value from C with the new
     * version. When we insert a new φ, we know the incoming from our block (it's the new version), but we don't
     * know the incoming from the other blocks. This is exactly why this complicated SSA reconstruction is required
     * rather than some simple copy-and-paste of the new version at the uses.
     */
    fun findDef(label: InstrLabel, p: AtomicId?, variable: Variable): Variable {
      // p indicates the "path" to take at a phi
      val (blockId, index) = if (label.second == DEFINED_IN_PHI) InstrLabel(p!!, Int.MAX_VALUE) else label

      for (u in traverseDominatorTree(blockId)) {
        val uBlock = this[u]
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
          // Insert new φ for variable
          val yPrime = createCopyOf(variable, uBlock) as Variable
          uBlock.phi[yPrime] = mutableMapOf()
          vars += yPrime
          val incoming = predecessors(uBlock).map { it.id }
              .associateWithTo(mutableMapOf()) { findDef(InstrLabel(u, DEFINED_IN_PHI), it, variable) }
          uBlock.phi[yPrime] = incoming
          // Update def-use chains for the vars used in the φ
          for ((_, incVar) in incoming) {
            defUseChains.getOrPut(incVar, ::mutableSetOf) += InstrLabel(uBlock.id, DEFINED_IN_PHI)
          }
          return yPrime
        }
      }
      logger.throwICE("Unreachable: no definition for $variable was found up the tree from $label")
    }

    // Take all affected variables
    for (x in reconstruct) {
      // Make a copy, avoid concurrent modifications
      val uses = defUseChains.getValue(x).toMutableList()
      // And rewrite all of their uses
      for (label in uses) {
        val (blockId, index) = label
        val block = this[blockId]
        // Call findDef to get the latest definition for this use, and update the use with the version of the definition
        val newVersion = if (index == DEFINED_IN_PHI) {
          // If it's a φuse, then call findDef with the correct predecessor to search for defs in
          val incoming = block.phi.entries.first { it.key.id == x.id }.value
          val target = incoming.entries.first { it.value == x }.key
          val newVersion = findDef(label, target, x)
          // If already wired correctly, don't bother updating anything
          if (x == newVersion) continue
          incoming[target] = newVersion
          newVersion
        } else {
          // Otherwise just go up the dominator tree looking for definitions
          val newVersion = findDef(label, null, x)
          // If already wired correctly, don't bother rewriting anything
          if (x == newVersion) continue
          // Rewrite use
          block[index] = block[index].rewriteBy(mapOf(x to newVersion))
          newVersion
        }
        if (x != newVersion) {
          // Update uses
          defUseChains.getOrPut(x, ::mutableSetOf) -= label
          defUseChains.getOrPut(newVersion, ::mutableSetOf) += label
        }
      }
    }
    liveSets = computeLiveSetsByVar()
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
