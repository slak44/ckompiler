package slak.ckompiler.backend

import slak.ckompiler.AtomicId
import slak.ckompiler.analysis.*

typealias Location = Pair<AllocatableValue, InstrLabel>
typealias BlockLocations = List<Location>

data class MinResult(val spills: BlockLocations, val reloads: BlockLocations)

typealias WBlockMap = Map<MachineRegisterClass, MutableSet<AllocatableValue>>

private fun TargetFunGenerator.insertSpill(
    value: AllocatableValue,
    location: InstrLabel,
    stackValue: StackValue?
): StackValue {
  val (blockId, idx) = location
  val targetStackValue = stackValue ?: StackValue(graph.registerIds(), value.type)
  val copy = createIRCopy(MemoryLocation(targetStackValue), value)
  graph[blockId].add(idx, copy)
  graph.defUseChains.getOrPut(value, ::mutableSetOf) += location
  return targetStackValue
}

private fun TargetFunGenerator.insertReload(
    original: AllocatableValue,
    toReload: StackValue,
    location: InstrLabel
): AllocatableValue {
  val (blockId, idx) = location
  val copyTarget = graph.createCopyOf(original, graph[blockId])
  val copy = createIRCopy(copyTarget, MemoryLocation(toReload))
  graph[blockId].add(idx, copy)
  return copyTarget
}

private fun TargetFunGenerator.nextUse(location: InstrLabel, v: AllocatableValue): Int {
  val (blockId, index) = location
  val nextUseIdx = graph[blockId].drop(index).indexOfFirst { mi -> v in mi.uses }
  if (nextUseIdx < 0) {
    return Int.MAX_VALUE
  }
  return nextUseIdx
}

/**
 * Apply the MIN algorithm on a block. While this implementation is based on the reference below, it does differ by
 * working on multiple register classes at the same time (ie int and float regs), and by the handling of constraints.
 * It is also significantly less sophisticated (it doesn't consider loops separately).
 *
 * Register Spilling and Live-Range Splitting for SSA-Form Programs, Braun & Hack
 *
 * @param maxPressure max register pressure for each register class
 */
private class BlockSpiller(
    val targetFunGenerator: TargetFunGenerator,
    val blockId: AtomicId,
    val maxPressure: Map<MachineRegisterClass, Int>,
    wBlockEntry: WBlockMap,
    sBlockEntry: Set<AllocatableValue>
) {
  private val reloads = mutableListOf<Location>()
  private val spills = mutableListOf<Location>()

  val minResult get() = MinResult(spills, reloads)

  /**
   * The set of values that are in a register. Initially set to the values in a register at the start of the block.
   */
  private val w = wBlockEntry

  val wExit: Map<MachineRegisterClass, Set<AllocatableValue>> get() = w

  /**
   * The set of values that were already spilled once (and only once is enough because SSA).
   */
  private val s = sBlockEntry.toMutableSet()

  val sExit: Set<AllocatableValue> get() = s

  /**
   * Register Spilling and Live-Range Splitting for SSA-Form Programs, Braun & Hack: Section 2, Algorithm 1
   */
  private fun TargetFunGenerator.limit(
      valueClass: MachineRegisterClass,
      insnIndex: Int,
      m: Int,
      spillTargetIndex: Int = insnIndex
  ) {
    val wClass = w.getValue(valueClass).sortedBy { nextUse(InstrLabel(blockId, insnIndex), it) }
    for (v in wClass.drop(m.coerceAtLeast(0))) {
      if (v !in s && !graph.isDeadAfter(v, InstrLabel(blockId, insnIndex))) {
        spills += Location(v, InstrLabel(blockId, spillTargetIndex))
      }
      s -= v
      // Instead of keeping the first m like in the algorithm, we remove items after the first m, to get the same effect
      w.getValue(valueClass) -= v
    }
  }

  /**
   * Register Spilling and Live-Range Splitting for SSA-Form Programs, Braun & Hack: Section 2, Algorithm 1
   */
  private fun TargetFunGenerator.minAlgorithm(maxPressure: Map<MachineRegisterClass, Int>) {
    val phiDefs = graph[blockId].phiDefs.groupBy { target.registerClassOf(it.type) }
    val phiUses = graph[blockId].phiUses.groupBy { target.registerClassOf(it.type) }
    for ((valueClass, k) in maxPressure) {
      val wClass = w.getValue(valueClass)
      limit(valueClass, 0, k - (phiDefs[valueClass]?.size ?: 0))
      wClass += phiDefs[valueClass] ?: emptyList()
      wClass -= phiUses[valueClass] ?: emptyList()
    }

    val it = graph[blockId].listIterator()
    while (it.hasNext()) {
      val insnIndex = it.nextIndex()
      val insn = it.next()
      val insnUses = insn.uses
          .filterIsInstance<AllocatableValue>()
          .filter { !it.isUndefined }
          .groupBy { target.registerClassOf(it.type) }
      val insnDefs = insn.defs
          .filterIsInstance<AllocatableValue>()
          .filter { !it.isUndefined }
          .groupBy { target.registerClassOf(it.type) }
      val constraintsMap = (insn.constrainedArgs + insn.constrainedRes)
          .filter { it.value is VirtualRegister && it.value.kind == VRegType.CONSTRAINED }
          .distinctBy { it.target }
          .groupBy { it.target.valueClass }
          .mapValues { it.value.size }
      // Result constrained to same register as live-though constrained variable: copy will be made
      // The two versions are certainly both alive at the constrained MI, but one of them also dies there
      // We don't really care about which, only that one does, so we accurately model register pressure
      // See TargetFunGenerator#prepareForColoring
      val duplicateCount = insn.constrainedArgs
          .groupBy { it.target.valueClass }
          .mapValues {
            it.value.count { (value, target) ->
              graph.livesThrough(value, InstrLabel(blockId, insnIndex)) &&
                  target in insn.constrainedRes.map(Constraint::target)
            }
          }
      for ((valueClass, k) in maxPressure) {
        val wClass = w.getValue(valueClass)
        val actualK = k - (constraintsMap[valueClass] ?: 0) - (duplicateCount[valueClass] ?: 0)
        // R is the set of reloaded variables at insn
        // Since they are reloads to registers, they are obviously in w
        val r = (insnUses[valueClass] ?: emptyList()) - wClass
        wClass += r
        s += r
        // Make room for insn's uses
        limit(valueClass, insnIndex, actualK)
        // Stuff that dies at this index should not count as "in a register"
        val dyingHere = insnUses[valueClass]?.filter { graph.isDeadAfter(it, InstrLabel(blockId, insnIndex)) }
        wClass -= dyingHere ?: emptyList()
        // Make room for insn's defs
        limit(valueClass, it.nextIndex(), actualK - (insnDefs[valueClass]?.size ?: 0), insnIndex)
        // Since we made space for the defs, they are now in w
        wClass += insnDefs[valueClass] ?: emptyList()
        for (value in r) {
          reloads += Location(value, InstrLabel(blockId, it.previousIndex()))
        }
      }
    }
  }

  fun runSpill() {
    targetFunGenerator.minAlgorithm(maxPressure)
  }
}

/**
 * Register Spilling and Live-Range Splitting for SSA-Form Programs, Braun & Hack: Section 4.2, Algorithm 2
 */
private fun TargetFunGenerator.initUsual(
    maxPressure: Map<MachineRegisterClass, Int>,
    blockId: AtomicId,
    spillers: Map<AtomicId, BlockSpiller>
): WBlockMap {
  val freq = mutableMapOf<MachineRegisterClass, MutableMap<AllocatableValue, Int>>()
  val take = mutableMapOf<MachineRegisterClass, MutableSet<AllocatableValue>>()
  val cand = mutableMapOf<MachineRegisterClass, MutableSet<AllocatableValue>>()

  val preds = graph.predecessors(blockId)
  for (pred in preds) {
    val spiller = spillers[pred.id] ?: continue
    for ((valueClass, wEnd) in spiller.wExit.entries) {
      for (variable in wEnd.filterIsInstance<Variable>()) {
        val map = freq.getOrPut(valueClass, ::mutableMapOf)
        if (variable in map) {
          val value = map.getValue(variable)
          map[variable] = value + 1
          if (value + 1 == preds.size) {
            cand.getOrPut(valueClass, ::mutableSetOf) -= variable
            take.getOrPut(valueClass, ::mutableSetOf) += variable
          }
        } else {
          map[variable] = 0
        }
        cand.getOrPut(valueClass, ::mutableSetOf) += variable
      }
    }
  }
  for ((valueClass, classCand) in cand) {
    val toTake = take.getOrPut(valueClass, ::mutableSetOf)
    val k = maxPressure.getValue(valueClass)
    if (toTake.size >= k) {
      // No need to sort, none in cand will be taken
      continue
    }
    toTake += classCand.asSequence().sortedByDescending { nextUse(InstrLabel(blockId, 0), it) }.take(k - toTake.size)
  }

  for (valueClass in target.registerClasses) {
    take.putIfAbsent(valueClass, mutableSetOf())
  }

  return take
}

private fun TargetFunGenerator.initSpilled(
    blockId: AtomicId,
    spillers: Map<AtomicId, BlockSpiller>,
    wBlockEntry: WBlockMap
): Set<AllocatableValue> {
  val sJoin = graph.predecessors(blockId).flatMap { spillers[it.id]?.sExit ?: emptyList() }
  return sJoin.intersect(wBlockEntry.values.flatten())
}

/**
 * Find all variables that should be in a register in [blockId], but aren't in a register coming from [predId].
 * If there are any, split the edge and insert the required reloads there.
 *
 * Find all variables that are in a register in [predId], but shouldn't be in a register coming into [blockId].
 * If there are any, split the edge and insert the required spills there.
 *
 * Register Spilling and Live-Range Splitting for SSA-Form Programs, Braun & Hack: Section 4.3
 */
private fun TargetFunGenerator.findEdgeSpillsReloads(
    blockId: AtomicId,
    wEntryB: WBlockMap,
    sEntryB: Set<AllocatableValue>,
    predId: AtomicId,
    wExitP: Map<MachineRegisterClass, Set<AllocatableValue>>,
    sExitP: Set<AllocatableValue>,
): SpillResult {
  // Find which variables in phi are for other paths, se we can remove them
  // This is because we care only about variables from our specific predecessor
  val otherPathVersions = graph[blockId].phi.values.flatMap { incoming ->
    incoming.entries.filter { it.key != predId }.map { it.value }
  }

  var splitBlock: InstrBlock? = null

  val toSpill = (sEntryB - sExitP - otherPathVersions).intersect(wExitP.values.flatten()).filterIsInstance<Variable>()

  if (toSpill.isNotEmpty()) {
    splitBlock = graph.splitEdge(graph[predId], graph[blockId], this::createJump)
  }

  val edgeReloads = mutableListOf<Variable>()

  for ((valueClass, wExit) in wExitP) {
    // Make deep clone, because this is used by the spiller, and we don't actually want to modify it
    val forClass = wEntryB.getValue(valueClass).toMutableSet()
    forClass -= wExit
    forClass -= otherPathVersions
    // Only consider things that are actually used by our block
    val aliveAndNotInW = forClass.intersect(graph.liveInsOf(blockId))
    if (aliveAndNotInW.isNotEmpty()) {
      if (splitBlock == null) {
        splitBlock = graph.splitEdge(graph[predId], graph[blockId], this::createJump)
      }
      edgeReloads += aliveAndNotInW.filterIsInstance<Variable>()
    }
  }

  if (splitBlock == null) return emptyMap()

  val syntheticResult = MinResult(
      toSpill.map { it to InstrLabel(splitBlock.id, 0) },
      edgeReloads.map { it to InstrLabel(splitBlock.id, 0) }
  )

  return mapOf(splitBlock.id to syntheticResult)
}

typealias SpillResult = Map<AtomicId, MinResult>

/**
 * Pre-allocation spilling. Reduces register pressure such that coloring will succeed.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Section 3.1.5.2
 */
fun TargetFunGenerator.runSpiller(): SpillResult {
  val maxPressure = target.maxPressure

  val spillers = mutableMapOf<AtomicId, BlockSpiller>()

  val splitEdgeResults = mutableMapOf<AtomicId, MinResult>()

  data class EdgeProcessData(
      val predId: AtomicId,
      val blockId: AtomicId,
      val wBlockEntry: WBlockMap,
      val sBlockEntry: Set<AllocatableValue>
  )

  val unprocessedEdges = mutableListOf<EdgeProcessData>()

  for (blockId in graph.postOrder().asReversed()) {
    val initialW = initUsual(maxPressure, blockId, spillers)
    val initialS = initSpilled(blockId, spillers, initialW)

    // Make copy, since we might split an edge and thus modify predecessors
    for (pred in graph.predecessors(blockId).toMutableList()) {
      val predSpiller = spillers[pred.id]
      if (predSpiller == null) {
        // This is mutable, and BlockSpiller actually mutates it
        val wBlockEntryCopy = initialW.toMutableMap().mapValues { it.value.toMutableSet() }
        unprocessedEdges += EdgeProcessData(pred.id, blockId, wBlockEntryCopy, initialS)
        continue
      }
      splitEdgeResults +=
          findEdgeSpillsReloads(blockId, initialW, initialS, pred.id, predSpiller.wExit, predSpiller.sExit)
    }

    val spiller = BlockSpiller(this, blockId, maxPressure, initialW, initialS)
    spillers[blockId] = spiller
    spiller.runSpill()
  }

  // Loop headers do not have all preds available on the first pass, for obvious reasons
  // Deal with the remaining edges here
  for ((predId, blockId, wBlockEntry, sBlockEntry) in unprocessedEdges) {
    val predSpiller = spillers.getValue(predId)
    splitEdgeResults +=
        findEdgeSpillsReloads(blockId, wBlockEntry, sBlockEntry, predId, predSpiller.wExit, predSpiller.sExit)
  }

  return spillers.mapValues { it.value.minResult } + splitEdgeResults
}

/** Maps a spilled value to a pointer to the stack where it was spilled. */
typealias SpillMap = Map<AllocatableValue, StackValue>
/** Maps a spilled value to the [InstrBlock] where it was spilled. */
typealias SpillBlocks = Map<AllocatableValue, AtomicId>

/**
 * Inserts the spill and reload code at the locations found by [runSpiller].
 *
 * @return the stack values used for each spilled value
 */
fun TargetFunGenerator.insertSpillReloadCode(result: SpillResult): Pair<SpillMap, SpillBlocks> {
  val spilled = mutableMapOf<AllocatableValue, StackValue>()
  val spillBlocks = mutableMapOf<AllocatableValue, AtomicId>()

  for ((blockId, minResult) in result.entries) {
    // All the labels in minResult contain indices from before inserting spill/reload instructions
    // We keep track of how many new instructions we inserted, so the original indices can be offset correctly
    var insnInserted = 0

    // The value, the index inside the current block, and whether it's a spill (true) or a reload (false)
    // This is ordered by the original index, so we can use insnInserted as an offset
    // We also want spills before reloads if their idx is the same. sortedBy is stable, and spills are added first.
    val modifications: List<Triple<AllocatableValue, Int, Boolean>> =
        (minResult.spills.map { (variable, label) -> Triple(variable, label.second, true) } +
            minResult.reloads.map { (variable, label) -> Triple(variable, label.second, false) })
            .sortedBy { it.second }

    for ((variable, originalIdx, isSpill) in modifications) {
      val offsetLabel = InstrLabel(blockId, originalIdx + insnInserted)

      if (isSpill) {
        spilled[variable] = insertSpill(variable, offsetLabel, spilled[variable])
        spillBlocks[variable] = blockId
      } else {
        val toReload = checkNotNull(spilled[variable]) {
          """|Trying to reload something that was never spilled: $variable
             |${graph[blockId]}
             |${graph[blockId].joinToString(separator = "\n", postfix = "\n")}""".trimMargin()
        }
        insertReload(variable, toReload, offsetLabel)
      }

      insnInserted++
    }
  }

  graph.ssaReconstruction(spilled.map { it.key }.filterIsInstance<Variable>().toSet())

  return spilled to spillBlocks
}

/**
 * Find the register pressure for all the labels in the program, and for all register classes.
 */
fun TargetFunGenerator.findRegisterPressure(): Map<MachineRegisterClass, Map<InstrLabel, Int>> {
  val pressure = target.registerClasses.associateWith { mutableMapOf<InstrLabel, Int>() }
  val current = target.registerClasses.associateWithTo(mutableMapOf()) { 0 }
  for (blockId in graph.domTreePreorder) {
    val block = graph[blockId]
    for (liveIn in (graph.liveInsOf(blockId) - block.phiUses)) {
      val classOf = target.registerClassOf(liveIn.type)
      current[classOf] = current.getValue(classOf) + 1
    }
    for ((index, mi) in block.withIndex()) {
      val dyingHere = mi.uses
          .filterIsInstance<AllocatableValue>()
          .filter { graph.isDeadAfter(it, InstrLabel(blockId, index)) }
      // Reduce pressure for values that die at this label
      for (value in dyingHere) {
        val classOf = target.registerClassOf(value.type)
        current[classOf] = current.getValue(classOf) - 1
      }
      val defined = mi.defs.filterIsInstance<AllocatableValue>()
      // Increase pressure for values defined at this label
      // If never used, then it shouldn't increase pressure, nor should undefined
      for (definition in defined.filter { graph.isUsed(it) && !it.isUndefined }) {
        val classOf = target.registerClassOf(definition.type)
        current[classOf] = current.getValue(classOf) + 1
      }
      val constraintsMap = (mi.constrainedArgs + mi.constrainedRes)
          .toSet()
          .filter { it.value is VirtualRegister && it.value.kind == VRegType.CONSTRAINED }
          .groupBy { it.target.valueClass }
          .mapValues { it.value.size }
      for ((classOf, count) in constraintsMap) {
        current[classOf] = current.getValue(classOf) + count
      }
      // Result constrained to same register as live-though constrained variable: copy will be made
      // The two versions are certainly both alive at the constrained MI, but one of them also dies there
      // We don't really care about which, only that one does, so we accurately model register pressure
      // See TargetFunGenerator#prepareForColoring
      val duplicateCount = mi.constrainedArgs
          .groupBy { it.target.valueClass }
          .mapValues {
            it.value.count { (value, target) ->
              graph.livesThrough(value, InstrLabel(blockId, index)) &&
                  target in mi.constrainedRes.map(Constraint::target)
            }
          }
      for ((classOf, count) in duplicateCount) {
        current[classOf] = current.getValue(classOf) + count
      }

      for (mrc in target.registerClasses) {
        pressure.getValue(mrc)[InstrLabel(blockId, index)] = current.getValue(mrc)
      }
      for ((classOf, count) in constraintsMap) {
        current[classOf] = current.getValue(classOf) - count
      }
      for ((classOf, count) in duplicateCount) {
        current[classOf] = current.getValue(classOf) - count
      }
    }
    current.replaceAll { _, _ -> 0 }
  }
  return pressure
}

private typealias UseDistance = Int

private fun TargetFunGenerator.spillClass(
    alreadySpilled: Set<IRValue>,
    registerClass: MachineRegisterClass,
    k: Int,
    block: InstrBlock
): List<IRValue> {
  require(k > 0)
  val markedSpill = mutableListOf<IRValue>()

  fun Iterable<IRValue>.ofClass() = filter { target.registerClassOf(it.type) == registerClass }

  val p = (graph.liveInsOf(block.id) - block.phiUses).ofClass()
  require(p.size <= k)
  val q = mutableSetOf<IRValue>()
  q += p
  for ((index, mi) in block.withIndex()) {
    val dyingHere = mi.uses
        .ofClass()
        .filter { it !is StackVariable && it !is MemoryLocation }
        .filter {
          (it is AllocatableValue && graph.isDeadAfter(it, InstrLabel(block.id, index))) || it is PhysicalRegister
        }
    for (value in dyingHere) {
      q -= value
    }
    val undefinedDefs = (mi.constrainedArgs + mi.constrainedRes).filter {
      it.value is VirtualRegister && it.value.kind == VRegType.CONSTRAINED && it.target.valueClass == registerClass
    }.map { it.value }
    q += undefinedDefs
    val duplicatedDefs = mi.constrainedArgs
        .filter { (value, target) ->
          graph.livesThrough(value, InstrLabel(block.id, index)) &&
              target in mi.constrainedRes.map(Constraint::target) &&
              target.valueClass == registerClass
        }
        .map { VirtualRegister(graph.registerIds(), it.value.type, VRegType.UNDEFINED) }
    q += duplicatedDefs
    val defined = mi.defs
        .ofClass()
        .filterIsInstance<AllocatableValue>()
        .filter { graph.isUsed(it) && it !in alreadySpilled }
    q += defined

    while (q.size > k) {
      // Spill the furthest use
      val spilled = q
          .filterIsInstance<AllocatableValue>()
          .filter { !it.isUndefined }
          .maxByOrNull { useDistance(graph, InstrLabel(block.id, index), it) }
      checkNotNull(spilled) {
        "No variable/virtual can be spilled! Maybe conflicting pre-coloring. see ref"
      }
      q -= spilled
      markedSpill += spilled
    }

    q -= undefinedDefs
    q -= duplicatedDefs
  }
  return markedSpill
}

/**
 * Spilling heuristic.
 *
 * Values for creating the permutation σ (see referenced section).
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Section 4.2.4
 */
private fun useDistance(
    graph: InstructionGraph,
    l: InstrLabel,
    v: AllocatableValue
): UseDistance {
  val isUsedAtL = graph[l.first][l.second.coerceAtLeast(0)].uses.any { it == v }
  if (isUsedAtL) return 0
  check(graph.isUsed(v))
  when (v) {
    is VirtualRegister -> {
      // Value is live-out, distance is rest of block from l
      if (graph.virtualDeaths[v]?.second == LabelIndex.MAX_VALUE) return graph[l.first].size - l.second
      // If l is after the last use, the distance is undefined
      if (l.first == graph.virtualDeaths[v]?.first && l.second > graph.virtualDeaths[v]?.second!!) return LabelIndex.MAX_VALUE
      // Simple distance
      return graph.virtualDeaths.getValue(v).second - l.second
    }
    is Variable -> {
      // FIXME: lol
      return 7
    }
  }
}
