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
    for (mi in instrMap.getValue(block)) {
      val dyingHere = mi.uses
          .filter { it !is StackVariable && it !is MemoryLocation }
          .filter { lastUses[it] == (block to mi.irLabelIndex) }
      // Reduce pressure for values that die at this label
      for (value in dyingHere) {
        val classOf = target.registerClassOf(value.type)
        current[classOf] = current.getValue(classOf) - 1
      }
      val defined = mi.defs.filter { it !is StackVariable && it !is MemoryLocation }
      // Increase pressure for values defined at this label
      // If never used, then it shouldn't increase pressure
      for (definition in defined.filter { it in lastUses }) {
        val classOf = target.registerClassOf(definition.type)
        current[classOf] = current.getValue(classOf) + 1
      }
      // If pressure is too high, add it to the list
      for (mrc in target.registerClasses) {
        if (current.getValue(mrc) > maxPressure.getValue(mrc)) {
          pressure.getValue(mrc) += block to mi.irLabelIndex
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

  fun Iterable<IRValue>.ofClass() = filter { target.registerClassOf(it.type) == registerClass }

  val p = (cfg.liveIns.getValue(block) + block.phi.map { it.variable }).ofClass()
  require(p.size <= k)
  val q = mutableListOf<IRValue>()
  q += p
  for (mi in instructions) {
    val usesOfClass = mi.uses
        .ofClass()
        .filter { it !is StackVariable && it !is MemoryLocation }
    val dyingHere = usesOfClass.filter { lastUses[it] == (block to mi.irLabelIndex) }
    for (value in dyingHere) {
      q -= value
    }
    val defined = mi.defs.ofClass().filter { it !is StackVariable && it !is MemoryLocation }
    for (definition in defined.filter { it in lastUses }) {
      if (definition in alreadySpilled) continue
      q += definition
      if (q.size > k) {
        // Spill the furthest use
        val spilled = q
            .filter { it is Variable || it is VirtualRegister }
            .maxBy { useDistance(lastUses, instructions, Label(block, mi.irLabelIndex), it) }
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
  // If l is after the last use, the distance is undefined
  if (l.first == lastUses[v]?.first && l.second > lastUses[v]?.second!!) return Int.MAX_VALUE
  var currentDistance = 1
  for (mi in instructions.drop(l.second.coerceAtLeast(0) + 1)) {
    val dyingHere = mi.uses
        .filter { it !is StackVariable && it !is MemoryLocation }
        .filter { lastUses[it] == (l.first to mi.irLabelIndex) }
    for (value in dyingHere) {
      if (value == v) return currentDistance
    }
    currentDistance++
  }
  TODO("unreachable?")
}
