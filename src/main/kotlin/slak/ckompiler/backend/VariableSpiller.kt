package slak.ckompiler.backend

import slak.ckompiler.AtomicId
import slak.ckompiler.analysis.*

typealias Location = Pair<AllocatableValue, InstrLabel>
typealias BlockLocations = List<Location>

data class MinResult(val spills: BlockLocations, val reloads: BlockLocations)

private fun TargetFunGenerator.insertSpill(
  value: AllocatableValue,
  location: InstrLabel,
  stackValue: StackValue?
): StackValue {
  val (blockId, idx) = location
  val targetStackValue = stackValue ?: StackValue(stackValueIds(), value.type)
  val copy = createIRCopy(MemoryLocation(targetStackValue), value)
  graph[blockId].add(idx, copy)
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
    wBlockEntry: Map<MachineRegisterClass, MutableList<AllocatableValue>>,
    sBlockEntry: Set<AllocatableValue>
) {
  private val reloads = mutableListOf<Location>()
  private val spills = mutableListOf<Location>()

  val minResult get() = MinResult(spills, reloads)

  /**
   * The set of values that are in a register. Initially set to the values in a register at the start of the block.
   */
  private val w = wBlockEntry.toMutableMap()

  val wExit: Map<MachineRegisterClass, List<AllocatableValue>> get() = w

  /**
   * The set of values that were already spilled once (and only once is enough because SSA).
   */
  private val s = sBlockEntry.toMutableSet()

  val sExit: Set<AllocatableValue> get() = s

  /**
   * Register Spilling and Live-Range Splitting for SSA-Form Programs, Braun & Hack: Section 2, Algorithm 1
   */
  private fun TargetFunGenerator.limit(valueClass: MachineRegisterClass, insnIndex: Int, m: Int) {
    val wClass = w.getValue(valueClass)
    wClass.sortBy { nextUse(InstrLabel(blockId, insnIndex), it) }
    for (v in wClass.drop(m.coerceAtLeast(0))) {
      if (v !in s && nextUse(InstrLabel(blockId, insnIndex), v) != Int.MAX_VALUE) {
        spills += Location(v, InstrLabel(blockId, insnIndex))
      }
      s -= v
      wClass -= v
    }
  }

  /**
   * Register Spilling and Live-Range Splitting for SSA-Form Programs, Braun & Hack: Section 2, Algorithm 1
   */
  private fun TargetFunGenerator.minAlgorithm(maxPressure: Map<MachineRegisterClass, Int>) {
    val phiDefs = graph[blockId].phiDefs.groupBy { target.registerClassOf(it.type) }
    for ((valueClass, k) in maxPressure) {
      val wClass = w.getValue(valueClass)
      limit(valueClass, 0, k - (phiDefs[valueClass]?.size ?: 0))
      wClass += phiDefs[valueClass] ?: emptyList()
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
          .toSet()
          .filter { it.value is VirtualRegister && it.value.kind == VRegType.CONSTRAINED }
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
        // Make room for insn's defs
        limit(valueClass, it.nextIndex(), actualK - (insnDefs[valueClass]?.size ?: 0))
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
): Map<MachineRegisterClass, MutableList<AllocatableValue>> {
  val freq = mutableMapOf<MachineRegisterClass, MutableMap<AllocatableValue, Int>>()
  val take = mutableMapOf<MachineRegisterClass, MutableList<AllocatableValue>>()
  val cand = mutableMapOf<MachineRegisterClass, MutableList<AllocatableValue>>()

  val preds = graph.predecessors(blockId)
  for (pred in preds) {
    val spiller = spillers[pred.id] ?: continue
    for ((valueClass, wEnd) in spiller.wExit.entries) {
      for (variable in wEnd) {
        val map = freq.getOrPut(valueClass, ::mutableMapOf)
        if (variable in map) {
          val value = map.getValue(variable)
          map[variable] = value + 1
          if (value + 1 == preds.size) {
            cand.getOrPut(valueClass, ::mutableListOf) -= variable
            take.getOrPut(valueClass, ::mutableListOf) += variable
          }
        } else {
          map[variable] = 0
        }
        cand.getOrPut(valueClass, ::mutableListOf) += variable
      }
    }
  }
  for ((valueClass, classCand) in cand) {
    val toTake = take.getOrPut(valueClass, ::mutableListOf)
    val k = maxPressure.getValue(valueClass)
    if (toTake.size >= k) {
      // No need to sort, none in cand will be taken
      continue
    }
    classCand.sortByDescending { nextUse(InstrLabel(blockId, 0), it) }
    toTake += classCand.take(k - toTake.size)
  }

  for (valueClass in target.registerClasses) {
    take.putIfAbsent(valueClass, mutableListOf())
  }

  return take
}

private fun TargetFunGenerator.initSpilled(
    blockId: AtomicId,
    spillers: Map<AtomicId, BlockSpiller>,
    wBlockEntry: Map<MachineRegisterClass, MutableList<AllocatableValue>>
): Set<AllocatableValue> {
  val sJoin = graph.predecessors(blockId).flatMap { spillers[it.id]?.sExit ?: emptyList() }
  return sJoin.intersect(wBlockEntry.values.flatten())
}

typealias SpillResult = Map<AtomicId, MinResult>

/**
 * Pre-allocation spilling. Reduces register pressure such that coloring will succeed.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Section 3.1.5.2
 */
fun TargetFunGenerator.runSpiller(): SpillResult {
  val maxPressure = (target.registers - target.forbidden).groupBy { it.valueClass }.mapValues { it.value.size }

  val spillers = mutableMapOf<AtomicId, BlockSpiller>()

  for (blockId in graph.postOrder().asReversed()) {
    val initialW = initUsual(maxPressure, blockId, spillers)
    val initialS = initSpilled(blockId, spillers, initialW)
    val spiller = BlockSpiller(this, blockId, maxPressure, initialW, initialS)
    spillers[blockId] = spiller
    spiller.runSpill()
  }

  return spillers.mapValues { it.value.minResult }
}

/**
 * Inserts the spill and reload code at the locations found by [runSpiller].
 *
 * @return the stack values used for each spilled value
 */
fun TargetFunGenerator.insertSpillReloadCode(result: SpillResult): Map<AllocatableValue, StackValue> {
  val spilled = mutableMapOf<AllocatableValue, StackValue>()

  val allSpills = result.flatMap { it.value.spills }
  for ((spilledVar, location) in allSpills) {
    spilled[spilledVar] = insertSpill(spilledVar, location, spilled[spilledVar])
  }

  val allReloads = result.flatMap { it.value.reloads }
  for ((spilledVar, location) in allReloads) {
    val toReload = checkNotNull(spilled[spilledVar]) {
      "Trying to reload something that was never spilled: $spilledVar"
    }
    insertReload(spilledVar, toReload, location)
  }

  graph.ssaReconstruction(allSpills.map { it.first }.filterIsInstance<Variable>().toSet())

  return spilled
}

/**
 * Find all labels in the program, for which a [MachineRegisterClass] has higher pressure than there
 * are registers of that class.
 */
private fun TargetFunGenerator.findRegisterPressure(
    maxPressure: Map<MachineRegisterClass, Int>
): Map<MachineRegisterClass, Set<InstrLabel>> {
  val pressure = target.registerClasses.associateWith { mutableSetOf<InstrLabel>() }
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

      // If pressure is too high, add it to the list
      for (mrc in target.registerClasses) {
        if (current.getValue(mrc) > maxPressure.getValue(mrc)) {
          pressure.getValue(mrc) += InstrLabel(blockId, index)
        }
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
