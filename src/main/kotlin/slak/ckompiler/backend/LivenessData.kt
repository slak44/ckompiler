package slak.ckompiler.backend

import mu.KotlinLogging
import slak.ckompiler.AtomicId
import slak.ckompiler.analysis.*
import slak.ckompiler.throwICE

typealias DefUseChains = MutableMap<VersionedValue, MutableSet<InstrLabel>>

private val logger = KotlinLogging.logger {}

/**
 * Owns all liveness data for an [InstructionGraph].
 */
class LivenessData(private val graph: InstructionGraph, private val latestVersions: MutableMap<AtomicId, Int>) {
  /**
   * Store the block where a version of a [Variable] was defined.
   *
   * For [VirtualRegister]s, the definition is in the same block, and can be easily found by iterating the block.
   */
  val variableDefs = mutableMapOf<Variable, AtomicId>()

  lateinit var defUseChains: DefUseChains

  /** Store the index where each [VirtualRegister] dies. */
  val virtualDeaths = mutableMapOf<VirtualRegister, InstrLabel>()

  /** @see computeLiveSetsByVar */
  lateinit var liveSets: LiveSets

  /**
   * Creates a new virtual register, or a new version of a variable.
   */
  fun createCopyOf(value: AllocatableValue, block: InstrBlock): AllocatableValue {
    if (value.isUndefined) return value
    return when (value) {
      is Variable -> {
        val oldVersion = latestVersions.getValue(value.identityId)
        latestVersions[value.identityId] = oldVersion + 1
        val copy = value.copy(oldVersion + 1)
        // Also update definitions for variables
        variableDefs[copy] = block.id
        copy
      }
      is VirtualRegister -> {
        val copy = VirtualRegister(graph.registerIds(), value.type)
        // The copy will die where the original died
        virtualDeaths[copy] = virtualDeaths.getValue(value)
        copy
      }
      is DerefStackValue -> value
    }
  }

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
    liveSets = graph.computeLiveSetsByVar()
  }

  fun isUsed(value: AllocatableValue): Boolean = when (value) {
    is VirtualRegister -> value in virtualDeaths
    is VersionedValue -> value in defUseChains && defUseChains.getValue(value).isNotEmpty()
  }

  fun liveInsOf(blockId: AtomicId): Set<Variable> = liveSets.liveIn[blockId] ?: emptySet()

  private fun isDeadAt(value: Variable, label: InstrLabel): Boolean {
    if (value.isUndefined || !isUsed(value)) return true

    val (block, index) = label
    val isLiveIn = value in (liveSets.liveIn[block] ?: emptyList())
    val isLiveOut = value in (liveSets.liveOut[block] ?: emptyList())

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

    // This check doesn't consider MemoryLocations, because there will be no use without reload anyway
    val definitionHere = graph[block].indexOfFirst { value in it.defs }
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
      is DerefStackValue -> false
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
      is DerefStackValue -> false
      // FIXME: fairy inefficient
      is Variable -> !isDeadAt(value, label) && !isDeadAfter(value, label)
    }
  }

  /**
   * Populate [virtualDeaths].
   */
  fun findVirtualRanges() {
    for (blockId in graph.domTreePreorder) {
      for ((index, mi) in graph[blockId].withIndex()) {
        // Keep updating the map
        // Obviously, the last use will be the last update in the map
        for (it in mi.uses.filterIsInstance<VirtualRegister>()) {
          virtualDeaths[it] = InstrLabel(blockId, index)
        }
      }
    }
  }

  fun initializeVariableDefs(cfgDefinitions: Definitions) {
    variableDefs += cfgDefinitions.mapValues { it.value.first.nodeId }
  }

  /**
   * For the φ pruning, see Algorithm 3.7 in the SSA book.
   */
  fun findDefUseChainsAndPruneDeadPhis(cfgDefinitions: Definitions) {
    val uselessPhiDefs = mutableSetOf<Variable>()
    val usefulnessStack = mutableListOf<Variable>()

    val allUses = mutableMapOf<VersionedValue, MutableSet<InstrLabel>>()

    // Iteration through each MI, to find def-use chains and the initial marking phase for pruning
    for (blockId in graph.domTreePreorder) {
      val block = graph[blockId]
      for (phi in block.phi) {
        uselessPhiDefs += phi.key as Variable // This cast should never fail at this point
        for (variable in phi.value.values) {
          allUses.getOrPut(variable, ::mutableSetOf) += InstrLabel(blockId, DEFINED_IN_PHI)
        }
      }
      for ((index, mi) in block.withIndex()) {
        val uses = mi.filterOperands(listOf(VariableUse.USE), takeIndirectUses = true).filterIsInstance<AllocatableValue>()
        for (variable in uses + mi.constrainedArgs.map { it.value }) {
          if (variable is VersionedValue) {
            allUses.getOrPut(variable, ::mutableSetOf) += InstrLabel(blockId, index)
          }
          if (!variable.isUndefined && variable is Variable && cfgDefinitions.getValue(variable).second == DEFINED_IN_PHI) {
            uselessPhiDefs -= variable
            usefulnessStack += variable
          }
        }
      }
    }

    // Usefulness propagation for pruning
    while (usefulnessStack.isNotEmpty()) {
      val a = usefulnessStack.removeLast()
      val definitionPhiIncoming = graph[variableDefs.getValue(a)].phi.getValue(a)
      for (phiUse in definitionPhiIncoming.values) {
        phiUse as Variable // Should never fail at this point
        if (phiUse in uselessPhiDefs) {
          uselessPhiDefs -= phiUse
          usefulnessStack += phiUse
        }
      }
    }

    // Final pruning
    // Remember to clear out all references to the pruned variable, but also to the pruned φ-uses
    for (uselessDef in uselessPhiDefs) {
      val uselessDefBlock = graph[variableDefs.getValue(uselessDef)]
      val uselessIncoming = uselessDefBlock.phi.getValue(uselessDef)
      uselessDefBlock.phi -= uselessDef
      variableDefs -= uselessDef
      allUses -= uselessDef

      // Remove delete φ-uses
      for ((_, incValue) in uselessIncoming) {
        if (incValue !in allUses) continue
        allUses.getValue(incValue) -= (uselessDefBlock.id to DEFINED_IN_PHI)
      }
    }

    defUseChains = allUses
  }
}
