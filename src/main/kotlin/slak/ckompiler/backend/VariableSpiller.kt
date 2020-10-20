package slak.ckompiler.backend

import slak.ckompiler.analysis.*

/**
 * Pre-allocation spilling. Reduces register pressure such that coloring will succeed.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Section 3.1.5.2
 */
fun TargetFunGenerator.runSpiller() {
  val maxPressure = target.registerClasses.associateWith { mrc ->
    (target.registers - target.forbidden).count { it.valueClass == mrc }
  }
  val pressure = findRegisterPressure(maxPressure)
  val allSpills = mutableSetOf<IRValue>()
  for ((machineClass, labels) in pressure) {
    for ((block) in labels) {
      allSpills += spillClass(allSpills, machineClass, maxPressure.getValue(machineClass), graph[block])
    }
  }
  check(pressure.all { it.value.isEmpty() } || allSpills.isNotEmpty()) {
    "Either there is no pressure, or there are spills, can't have pressure but no spills"
  }
  // FIXME: deal with spilled virtuals, don't just filter them
  insertSpillCode(allSpills.filterIsInstance<Variable>().mapTo(mutableSetOf()) { it.id })
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
