package slak.ckompiler.backend

import slak.ckompiler.analysis.*

/**
 * Pre-allocation spilling. Reduces register pressure such that coloring will succeed.
 *
 * Calls [CFG.insertSpillCode], re-runs [TargetFunGenerator.instructionSelection].
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Section 3.1.5.2
 */
fun TargetFunGenerator.spiller(
    instrMap: InstructionMap,
    lastUses: Map<IRValue, Label>
): InstructionMap {
  val maxPressure = target.registerClasses.associateWith { mrc ->
    (target.registers - target.forbidden).count { it.valueClass == mrc }
  }
  val pressure = findRegisterPressure(instrMap, lastUses, maxPressure)
  val allSpills = mutableSetOf<IRValue>()
  for ((machineClass, labels) in pressure) {
    for ((block) in labels) {
      allSpills += spillClass(
          allSpills,
          lastUses,
          machineClass,
          maxPressure.getValue(machineClass),
          block,
          instrMap.getValue(block)
      )
    }
  }
  check(pressure.all { it.value.isEmpty() } || allSpills.isNotEmpty()) {
    "Either there is no pressure, or there are spills, can't have pressure but no spills"
  }
  // FIXME: deal with spilled virtuals, don't just filter them
  cfg.insertSpillCode(allSpills.filterIsInstance<Variable>().map { it.id })
  return instructionSelection()
}

/**
 * Find all labels in the program, for which a [MachineRegisterClass] has higher pressure than there
 * are registers of that class.
 */
private fun TargetFunGenerator.findRegisterPressure(
    instrMap: InstructionMap,
    lastUses: Map<IRValue, Label>,
    maxPressure: Map<MachineRegisterClass, Int>
): Map<MachineRegisterClass, Set<Label>> {
  val pressure = target.registerClasses.associateWith { mutableSetOf<Label>() }
  val current = target.registerClasses.associateWithTo(mutableMapOf()) { 0 }
  for (block in cfg.domTreePreorder) {
    for (liveIn in cfg.liveIns.getValue(block)) {
      val classOf = target.registerClassOf(liveIn.type)
      current[classOf] = 1
    }
    for ((phiDefined, _) in block.phi) {
      val classOf = target.registerClassOf(phiDefined.type)
      current[classOf] = current.getValue(classOf) + 1
    }
    for ((index, mi) in instrMap.getValue(block).withIndex()) {
      val dyingHere = mi.uses
          .filterIsInstance<AllocatableValue>()
          .filter { lastUses[it] == Label(block, index) }
      // Reduce pressure for values that die at this label
      for (value in dyingHere) {
        val classOf = target.registerClassOf(value.type)
        current[classOf] = current.getValue(classOf) - 1
      }
      val defined = mi.defs.filterIsInstance<AllocatableValue>()
      // Increase pressure for values defined at this label
      // If never used, then it shouldn't increase pressure, nor should undefined
      for (definition in defined.filter { it in lastUses && !it.isUndefined }) {
        val classOf = target.registerClassOf(definition.type)
        current[classOf] = current.getValue(classOf) + 1
      }
      // If pressure is too high, add it to the list
      for (mrc in target.registerClasses) {
        if (current.getValue(mrc) > maxPressure.getValue(mrc)) {
          pressure.getValue(mrc) += Label(block, index)
        }
      }
    }
    current.replaceAll { _, _ -> 0 }
  }
  return pressure
}

private typealias UseDistance = Int

private fun TargetFunGenerator.spillClass(
    alreadySpilled: Set<IRValue>,
    lastUses: Map<IRValue, Label>,
    registerClass: MachineRegisterClass,
    k: Int,
    block: BasicBlock,
    instructions: List<MachineInstruction>
): List<IRValue> {
  require(k > 0)
  val markedSpill = mutableListOf<IRValue>()

  fun Sequence<IRValue>.ofClass() = filter { target.registerClassOf(it.type) == registerClass }

  val p = (cfg.liveIns.getValue(block) + block.phi.map { it.variable })
      .asSequence().ofClass().toList()
  require(p.size <= k)
  val q = mutableSetOf<IRValue>()
  q += p
  for ((index, mi) in instructions.withIndex()) {
    val dyingHere = mi.uses
        .asSequence()
        .ofClass()
        .filter { it !is StackVariable && it !is MemoryLocation }
        .filter { lastUses[it] == Label(block, index) || it is PhysicalRegister }
    for (value in dyingHere) {
      q -= value
    }
    val defined = mi.defs
        .asSequence()
        .ofClass()
        .filter { it !is StackVariable && it !is MemoryLocation }
        .filter { it in lastUses }
        .filter { it !in alreadySpilled }
        .filterNot { it is PhysicalRegister && it.reg in target.forbidden }
    for (definition in defined) {
      q += definition
      if (q.size > k) {
        // Spill the furthest use
        val spilled = q
            .filterIsInstance<AllocatableValue>()
            .maxBy { useDistance(lastUses, instructions, Label(block, index), it) }
            ?: TODO("No variable/virtual can be spilled! Maybe conflicting pre-coloring. see ref")
        q -= spilled
        markedSpill += spilled
      }
    }
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
    lastUses: Map<IRValue, Label>,
    instructions: List<MachineInstruction>,
    l: Label,
    v: IRValue
): UseDistance {
  val isUsedAtL = instructions[l.second.coerceAtLeast(0)]
      .uses
      .filter { it !is StackVariable && it !is MemoryLocation }
      .any { it == v }
  if (isUsedAtL) return 0
  check(v in lastUses)
  // Value is live-out, distance is rest of block from l
  if (lastUses[v]?.second == LabelIndex.MAX_VALUE) return instructions.size - l.second
  // If l is after the last use, the distance is undefined
  if (l.first == lastUses[v]?.first && l.second > lastUses[v]?.second!!) return LabelIndex.MAX_VALUE
  // Simple distance
  return lastUses.getValue(v).second - l.second
}
