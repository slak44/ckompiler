package slak.ckompiler.backend

import mu.KotlinLogging
import slak.ckompiler.analysis.*
import slak.ckompiler.error
import slak.ckompiler.exhaustive
import slak.ckompiler.throwICE

private val logger = KotlinLogging.logger {}

/**
 * Coloring of interference graph, assignment of register to [IRValue].
 */
typealias AllocationMap = Map<IRValue, MachineRegister>

/**
 * For each block, store the list of assigned registers at the end of the block.
 */
typealias RegisterUseMap = Map<InstrBlock, List<MachineRegister>>

data class AllocationResult(
    val graph: InstructionGraph,
    val allocations: AllocationMap,
    val registerUseMap: RegisterUseMap,
) {
  val stackSlots get() = allocations.values.filterIsInstance<StackSlot>()

  fun generateStackSlotOffsets(initialOffset: Int): Map<StackSlot, Int> {
    var currentStackOffset = initialOffset
    return stackSlots.associateWith {
      val offset = currentStackOffset
      currentStackOffset += it.sizeBytes
      offset
    }
  }
}

private fun MachineTarget<*>.selectRegisterWhitelist(
    whitelist: Set<MachineRegister>,
    value: IRValue,
): MachineRegister? {
  val validClass = registerClassOf(value.type)
  val validSize = machineTargetData.sizeOf(value.type.unqualify().normalize())
  return whitelist.firstOrNull { candidate ->
    candidate.valueClass == validClass &&
        (candidate.sizeBytes == validSize || validSize in candidate.aliases.map { it.second })
  }
}

private fun MachineTarget<*>.selectRegisterBlacklist(
    blacklist: Set<MachineRegister>,
    value: IRValue,
) = requireNotNull(selectRegisterWhitelist(registers.toSet() - forbidden - blacklist, value)) {
  "Failed to find an empty register for the given value: $value"
}

/**
 * Rewrite a [MachineInstruction]'s operands that match any of the [rewriteMap]'s keys, with the associated value from
 * the map. Only cares about [VariableUse.USE], and also rewrites constrained args.
 *
 * @see InstructionGraph.ssaReconstruction
 */
fun MachineInstruction.rewriteBy(rewriteMap: Map<AllocatableValue, AllocatableValue>): MachineInstruction {
  if (template is ParallelCopyTemplate) {
    val rewrittenValues = template.values.mapKeys {
      if (it.key in rewriteMap.keys) {
        rewriteMap.getValue(it.key)
      } else {
        it.key
      }
    }
    val newOperands = rewrittenValues.keys.toList() + rewrittenValues.values
    return copy(template = template.copy(values = rewrittenValues), operands = newOperands)
  }
  val newOperands = operands.zip(template.operandUse).map {
    // Explicitly not care about DEF_USE
    if (it.first in rewriteMap.keys && it.second == VariableUse.USE && it.first is AllocatableValue) {
      rewriteMap.getValue(it.first as AllocatableValue)
    } else {
      it.first
    }
  }
  val newConstrainedArgs = constrainedArgs.map {
    if (it.value in rewriteMap.keys) {
      it.copy(value = rewriteMap.getValue(it.value))
    } else {
      it
    }
  }
  return copy(operands = newOperands, constrainedArgs = newConstrainedArgs)
}

/**
 * Get the values used in this [mi], that die at this instruction.
 */
fun InstructionGraph.dyingAt(label: InstrLabel, mi: MachineInstruction): List<AllocatableValue> {
  val uses = mi.uses.filterIsInstance<AllocatableValue>()
  return if (mi.template is ParallelCopyTemplate) {
    uses
  } else {
    uses.filter { liveness.isDeadAfter(it, label) }
  }
}

/**
 * For non-vregs, the SSA reconstruction will update uses, but for the vregs it must be done separately.
 */
fun rewriteVregBlockUses(block: InstrBlock, rewriteFromIdx: Int, rewriteMap: Map<AllocatableValue, AllocatableValue>) {
  val vregRewriteMap = rewriteMap.filterKeys { it is VirtualRegister }

  for (index in rewriteFromIdx until block.size) {
    block[index] = block[index].rewriteBy(vregRewriteMap)
  }
}

/**
 * Insert a copy.
 *
 * [value] is a constrained argument of [constrainedInstr].
 */
private fun AnyFunGenerator.insertSingleCopy(
    block: InstrBlock,
    index: Int,
    value: AllocatableValue,
    constrainedInstr: MachineInstruction,
) {
  // No point in inserting a copy for something that's never used
  if (!graph.liveness.isUsed(value) || value is DerefStackValue) return
  val copiedValue = graph.liveness.createCopyOf(value, block)
  val copyInstr = createIRCopy(copiedValue, value)
  copyInstr.irLabelIndex = constrainedInstr.irLabelIndex
  block.add(index, copyInstr)

  rewriteVregBlockUses(block, index + 1, mapOf(value to copiedValue))

  // This is the case where the value is an undefined dummy
  // We want to keep the original and correct last use, instead of destroying it here
  if (value === copiedValue) return
  return when (value) {
    is VersionedValue -> {
      // The value is a constrained argument: it is still used at the constrained MI, after the copy
      // We know that is the last use, because all the ones afterwards were just rewritten
      // The death is put at the current index, so the SSA reconstruction doesn't pick it up as alive
      // This is corrected in prepareForColoring
      graph.liveness.addUse(value, block.id, index)
    }
    is VirtualRegister -> graph.liveness.virtualDeaths[value] = InstrLabel(block.id, index)
  }
}

/**
 * Splits the live-range of each value in [splitFor], by creating copies and inserting them [atIndex].
 *
 * @see prepareForColoring
 */
private fun splitLiveRanges(
    graph: InstructionGraph,
    splitFor: Set<AllocatableValue>,
    atIndex: Int,
    block: InstrBlock,
) {
  // No point in making a copy for something that's never used, or for spilled values
  val actualValues = splitFor.filter { graph.liveness.isUsed(it) && it !is DerefStackValue }
  val rewriteMap = actualValues.associateWith { graph.liveness.createCopyOf(it, block) }
  val phi = ParallelCopyTemplate.createCopy(rewriteMap)
  phi.irLabelIndex = atIndex
  for (copiedValue in rewriteMap.values.filterIsInstance<Variable>()) {
    graph.parallelCopies.getOrPut(block.id, ::mutableListOf) += copiedValue
  }

  // Splice the copy in the instructions
  block.add(atIndex, phi)
  // Also rewrite the constrained instr too
  block[atIndex + 1] = block[atIndex + 1].rewriteBy(rewriteMap)

  rewriteVregBlockUses(block, atIndex + 2, rewriteMap)

  for ((value, rewritten) in rewriteMap) {
    when (value) {
      is VirtualRegister -> {
        // The constrained instr was rewritten, and the value cannot have been used there, so it dies when it is copied
        graph.liveness.virtualDeaths[value] = InstrLabel(block.id, atIndex)
      }
      is VersionedValue -> {
        // The use got pushed by adding the copy, remove that
        graph.liveness.removeUse(value, block.id, atIndex + 1)
        // Add back the variable use from atIndex: the parallel copy itself does indeed use the variable
        graph.liveness.addUse(value, block.id, atIndex)

        if (rewritten in block[atIndex + 1].uses) {
          // We also need to add the copy's use at the rewritten instruction, but only if it that was actually used there
          graph.liveness.addUse(rewritten as VersionedValue, block.id, atIndex + 1)
        }
        Unit
      }
    }.exhaustive
  }
  // Don't rebuild liveness, ssaReconstruction will do it for us
}

/**
 * Prepares the code to be colored.
 *
 * Firstly, it inserts copies for constrained instructions, that respect the Simple Constraint Property
 * (Definition 4.8).
 *
 * Secondly, it turns Precol-Ext (NP Complete for chordal graphs) into 1-Precol-Ext (P for chordal graphs). Basically,
 * to solve register targeting constraints, we precolor those nodes, and then extend the coloring to a complete
 * k-coloring. The general problem is hard, but if we split the live ranges of all the living variables at the
 * constrained label, the problem becomes solvable in polynomial time. See reference for more details and proofs.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: 4.6.1, 4.6.3
 */
private fun RegisterAllocationContext.prepareForColoring() {
  for (blockId in graph.domTreePreorder) {
    val block = graph[blockId]
    // Track which variables are alive at any point in this block
    // Initialize with the block live-ins, and with φ vars
    val alive: MutableSet<AllocatableValue> = graph.liveness.liveInsOf(blockId).toMutableSet()
    // The φ-uses are not alive at index 0; they die at the φ itself
    alive -= block.phiUses

    var index = 0

    fun updateAlive(index: Int) {
      val mi = block[index]
      val dyingHere = graph.dyingAt(InstrLabel(blockId, index), mi)
      alive += mi.defs.filterIsInstance<AllocatableValue>().filter { graph.liveness.isUsed(it) }
      alive -= dyingHere
    }

    while (index < block.size) {
      val mi = block[index]
      if (!mi.isConstrained) {
        updateAlive(index)
        index++
        continue
      }

      splitLiveRanges(graph, alive, index, block)
      graph.ssaReconstruction(alive.filterIsInstance<Variable>().toSet(), target, spilled)
      // The parallel copy needs to have updateAlive run on it, before skipping it
      updateAlive(index)
      index++

      val startMi = block[index]
      val startIndex = index
      val usesToRewrite = mutableListOf<VersionedValue>()
      for ((value, target) in startMi.constrainedArgs) {
        // Result constrained to same register as live-though constrained variable: copy must be made
        if (
          graph.liveness.livesThrough(value, InstrLabel(blockId, index)) &&
          target in startMi.constrainedRes.map { it.target }
        ) {
          generator.insertSingleCopy(block, index, value, startMi)
          if (value is VersionedValue) {
            usesToRewrite += value
          }
          index++
        }
      }
      graph.ssaReconstruction(alive.filterIsInstance<Variable>().toSet(), target, spilled)
      for (value in usesToRewrite) {
        // Add the use for the inserted copy
        graph.liveness.addUse(value, blockId, index)
      }
      for (copyIdx in startIndex until index) {
        // Process the inserted copies
        updateAlive(copyIdx)
      }
      // We don't want to rewrite the constrained instr in this case
      block[index] = startMi

      // Update and skip the original constrained label
      updateAlive(index)
      index++
    }
  }
}

private class RegisterAllocationContext(val generator: AnyFunGenerator) {
  /** The allocation itself. */
  val coloring = mutableMapOf<IRValue, MachineRegister>()

  /** The value of [assigned] at the end of each [allocBlock]. */
  val registerUseMap = mutableMapOf<InstrBlock, List<MachineRegister>>()

  /** The registers in use at a moment in time. Should be only used internally by the allocator. */
  val assigned = mutableSetOf<MachineRegister>()

  /** Store the copy sequences to replace each label with a parallel copy. */
  val parallelCopies = mutableMapOf<InstrLabel, List<MachineInstruction>>()

  /** Mapping of spilled values to their associated stack value. */
  val spilled = mutableMapOf<AllocatableValue, StackValue>()

  val target get() = generator.target
  val graph get() = generator.graph

  /** Begin a DFS from the starting block. */
  fun performColoringAllocation() {
    for ((_, stackValue) in spilled) {
      coloring[stackValue] = SpillSlot(stackValue, generator.stackSlotIds(), target.machineTargetData)
    }
    for (blockId in graph.domTreePreorder) {
      allocBlock(graph[blockId])
    }
  }

  fun getAllocationResult(): AllocationResult {
    val allocations = coloring.filterKeys { it !is ConstantValue }

    return AllocationResult(graph, allocations, registerUseMap)
  }
}

/**
 * Colors a single constrained label. See reference for variable notations and algorithm.
 *
 * For clarity on arguments:
 * - arg(l) is the set of all arguments to the label, constrained and unconstrained.
 * - T is a subset of arg(l), whose elements live though the constrained label
 * - A is the complement of T in arg(l) (ie everything in arg(l) and not in T)
 *
 * In the constrained argument loop, values are removed from T. After the loop T becomes the set of unconstrained
 * arguments that lives through the label. For example, `div x`, followed by some other use of x.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Algorithm 4.8, 4.6.4
 */
private fun RegisterAllocationContext.constrainedColoring(label: InstrLabel, mi: MachineInstruction) {
  val arg = mi.uses.filterIsInstance<AllocatableValue>()
  val t = arg.filter { graph.liveness.livesThrough(it, label) }.toMutableSet()
  val a = (arg - t).toMutableSet()
  val d = mi.defs.filterIsInstance<AllocatableValue>().toMutableSet()
  val cA = mutableSetOf<MachineRegister>()
  val cD = mutableSetOf<MachineRegister>()

  for ((value, target) in mi.constrainedArgs) {
    require(value !is DerefStackValue) { "Cannot constrain DerefStackValue to register $target at label $label" }
    cA += target
    a -= value
    val oldColor = coloring[value]
    if (oldColor != null && oldColor != target) {
      assigned -= oldColor
    }
    coloring[value] = target
    if (value in t) {
      assigned += target
    }
    t -= value
  }
  for ((value, target) in mi.constrainedRes) {
    cD += target
    d -= value
    coloring[value] = target
  }
  for (x in a) {
    val oldColor = coloring[x]
    // We differ here a bit, because cD - cA might have a register, but of the wrong class
    val reg = target.selectRegisterWhitelist(cD - cA, x)
    if (reg != null) {
      coloring[x] = reg
    } else {
      coloring[x] = target.selectRegisterBlacklist(assigned + cA, x)
    }
    if (oldColor != null && oldColor != target) {
      assigned -= oldColor
    }
  }
  for (x in d) {
    val oldColor = coloring[x]
    val reg = target.selectRegisterWhitelist(cA - cD, x)
    if (reg != null) {
      coloring[x] = reg
    } else {
      coloring[x] = target.selectRegisterBlacklist(assigned + cD, x)
    }
    if (oldColor != null && oldColor != target) {
      assigned -= oldColor
    }
  }

  // Assign leftovers
  val forbidden = assigned + cD + cA
  for (x in t) {
    val oldColor = checkNotNull(coloring[x])
    assigned -= oldColor
    val color = target.selectRegisterBlacklist(forbidden, x)
    coloring[x] = color
    assigned += color
  }
}

/**
 * Deallocate registers of values that die at the specified label.
 */
private fun RegisterAllocationContext.unassignDyingAt(label: InstrLabel, mi: MachineInstruction) {
  val dyingHere = graph.dyingAt(label, mi)
  for (value in dyingHere) {
    val color = coloring[value] ?: continue
    assigned -= color
  }
  // Pretend registers "die" immediately
  // This is only true if the instructions with phys reg we generate respect that
  // They're mostly operations on rsp/rbp, which don't matter, or on rax/xmm0 for returns which also don't matter
  // This really exists here for function parameters passed in registers
  assigned -= mi.uses.filterIsInstance<PhysicalRegister>().map { it.reg }
}

/**
 * Allocate registers at and around a constrained instruction.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: 4.6.4
 *
 * @see constrainedColoring
 */
private fun RegisterAllocationContext.allocConstrainedMI(label: InstrLabel, mi: MachineInstruction) {
  require(mi.isConstrained)

  val (block, constrainedIndex) = label
  require(constrainedIndex > 0) { "No parallel copy found for constrained instruction" }

  val parallelCopyIdx = graph[block].take(constrainedIndex).indexOfLast { it.template is ParallelCopyTemplate }
  require(parallelCopyIdx >= 0) { "No parallel copy preceding constrained" }
  val parallelCopy = graph[block][parallelCopyIdx]

  val copyTemplate = parallelCopy.template as ParallelCopyTemplate
  val copyMap = copyTemplate.values

  val spills = mutableSetOf<AllocatableValue>()
  val extraCopies = mutableMapOf<AllocatableValue, Pair<Int, AllocatableValue>>()
  val toErase = mutableSetOf<Variable>()

  // There should be exclusively copies defined in this interval, along with spills to memory
  for (intervalIndex in parallelCopyIdx + 1 until constrainedIndex) {
    val intervalMi = graph[block][intervalIndex]

    // FIXME: first? should be something custom for the copies maybe
    val use = intervalMi.uses.filterIsInstance<AllocatableValue>().first()
    val def = intervalMi.defs.filterIsInstance<AllocatableValue>().first()

    // FIXME: better spill detection
    if (def !is DerefStackValue) {
      extraCopies += def to (intervalIndex to use)
      continue
    }

    val copyData = extraCopies[use]

    if (copyData != null) {
      // Convert a sequence like:
      //   a v3 ← a v2
      //   ... unrelated copies ...
      //   spill a v3
      // into:
      //   spill a v2
      //   ... unrelated copies ...
      //   empty placeholder
      // The reason why this is fine, is because the reloads for "a" are already in place, since the spill already existed. And since this
      // is a copy for a constraint, we know there are no other uses except those after the constrained instruction.
      val (copyIndex, copiedValue) = copyData

      val updatedSpill = intervalMi.rewriteBy(mapOf(use to copiedValue))
      graph[block][copyIndex] = updatedSpill
      graph[block][intervalIndex] = PlaceholderTemplate.createMI()

      if (use is Variable) {
        toErase += use
      }

      extraCopies -= use
      spills += copiedValue
    } else {
      spills += use
    }
  }

  // This will remove the dangling uses of the erased variables, and fix the dominance property of SSA
  graph.ssaReconstruction(toErase, target, spilled)

  constrainedColoring(label, mi)
  unassignDyingAt(label, mi)

  // Correctly color copies inserted by the live range split (and the other copies from 4.6.1)
  // See last paragraph of section 4.6.4 for why we have to do this

  val usedAtL = mi.uses.filterIsInstance<AllocatableValue>().toSet()
  val definedAtL = mi.defs.filterIsInstance<AllocatableValue>()
  val operandsAtL = usedAtL + definedAtL

  val colorsAtL = operandsAtL.mapTo(mutableSetOf()) { coloring.getValue(it) }

  val nonDummyArgColorsAtL = usedAtL
      .filterNot { it.isUndefined }
      .mapTo(mutableSetOf()) { coloring.getValue(it) }

  val toRecolorFromParallel = copyMap.values - usedAtL

  // First, un-assign all the old colors
  assigned -= toRecolorFromParallel.mapTo(mutableSetOf()) { checkNotNull(coloring[it]) }
  assigned -= (extraCopies.keys - usedAtL).mapTo(mutableSetOf()) { checkNotNull(coloring[it]) }
  // And only then allocate them again
  // If an old assignment had been in rax, and the recoloring puts a value in rax before the old assignment is
  // processed, rax would have gotten removed from assigned, even though the recoloring assigned something to there
  for (copy in toRecolorFromParallel) {
    // Section 4.6.4 says we should recolor with colors not used by arguments or results of the constrained instruction. However, the spills
    // set contains values that are spilled after the live range split, but before the constrained instruction. This means we can use colors
    // from the constrained argument dummies and constrained results for the spills, since the colors will be freed by the time of the
    // constrained instruction. In fact, we are required to do this, since there would not be enough registers for the live range split in
    // several situations with high pressure.
    val blacklist = if (copy in spills) assigned + nonDummyArgColorsAtL else assigned + colorsAtL

    val color = target.selectRegisterBlacklist(blacklist, copy)
    coloring[copy] = color
    assigned += color
  }
  for (intervalMi in graph[block].subList(parallelCopyIdx + 1, constrainedIndex)) {
    val defs = intervalMi.defs.filterIsInstance<AllocatableValue>()
    val uses = intervalMi.uses.filterIsInstance<AllocatableValue>()
    // FIXME: better spill detection
    if (defs.any { it is DerefStackValue }) {
      // Spilled values must be unassigned
      assigned -= uses.mapTo(mutableSetOf()) { coloring.getValue(it) }
    } else if (uses.any { it is DerefStackValue }) {
      // FIXME: better reload detection ↑
      // Intentionally do nothing
      // A reload here means the reloaded value is used in the constrained instruction, so we're not supposed to recolor it (uses of L are
      // already colored by constrainedColoring)
    } else {
      // If it's neither a spill nor a reload, then it's a copy from 4.6.1, which we need to recolor
      // Unless, it's a new copy we inserted in the spiller, in which case it is constrained + used at L => we can skip it
      for (copy in defs - usedAtL) {
        val color = target.selectRegisterBlacklist(assigned + colorsAtL, copy)
        coloring[copy] = color
        assigned += color
      }
    }
  }
  // assigned is a set because each reg can only be assigned to one value at a time, making it safe to add/remove the
  // entry in assigned when the value is gen'd/killed
  // The loop above then assumes it can remove old colors from assigned, since they were reallocated, but that's not
  // necessarily true for values in usedAtL, because they might be live-through the constrained label
  for (inUse in usedAtL.filterNot { it.isUndefined }) {
    assigned += coloring.getValue(inUse)
  }

  // Create the copy sequence to replace the parallel copy
  val assignedForParallel = assigned + colorsAtL + copyMap.keys.map { coloring.getValue(it) }
  parallelCopies[InstrLabel(block, parallelCopyIdx)] = generator.replaceParallel(copyMap, coloring, assignedForParallel)

  for (def in definedAtL.filter { graph.liveness.isUsed(it) && !it.isUndefined }) {
    val color = checkNotNull(coloring[def]) { "Value defined at constrained label not colored: $def" }
    assigned += color
  }
}

/**
 * Register allocation for one [block]. Recursive DFS case.
 */
private fun RegisterAllocationContext.allocBlock(block: InstrBlock) {
  for (x in graph.liveness.liveInsOf(block.id) - block.phiDefs - block.phiUses) {
    // If the live-in wasn't colored, and is null, that's a bug
    // Live-ins in the block's φ are not considered
    assigned += requireNotNull(coloring[x]) { "Live-in not colored: $x in $block" }
  }
  // Add the parameter register constraints to assigned
  if (block.id == graph.startId) {
    assigned += generator.parameterMap.values.mapNotNull { (it as? PhysicalRegister)?.reg }
  }
  // Remove incoming φ-uses from assigned
  for (used in block.phiUses) {
    val color = coloring[used] ?: continue
    assigned -= color
  }
  // Allocate φ-definitions
  for ((phiDef, _) in block.phi) {
    when (phiDef) {
      is DerefStackValue -> {
        // DerefStackValue can only be allocated to the spill slot that it was assigned to
        coloring[phiDef] = coloring.getValue(phiDef.stackValue)
      }
      is Variable -> {
        val color = target.selectRegisterBlacklist(assigned, phiDef)
        coloring[phiDef] = color
        assigned += color
      }
    }
  }
  val colored = mutableSetOf<IRValue>()
  for ((index, mi) in block.withIndex()) {
    val label = InstrLabel(block.id, index)
    val stackVariables = mi.operands
        .map { if (it is MemoryLocation) it.ptr else it }
        .filterIsInstance<StackVariable>()
    // Also add stack variables to coloring
    coloring += stackVariables.associateWith {
      FullVariableSlot(it, generator.stackSlotIds(), target.machineTargetData)
    }
    if (mi.isConstrained) {
      allocConstrainedMI(label, mi)
      continue
    }
    // Handle unconstrained instructions
    unassignDyingAt(label, mi)
    val defined = mi.defs
        .asSequence()
        .filter { it !in colored }
        .filterIsInstance<AllocatableValue>()

    for (definition in defined) {
      if (definition is DerefStackValue) {
        // DerefStackValue can only be allocated to the spill slot that it was assigned to
        coloring[definition] = coloring.getValue(definition.stackValue)
        continue
      }
      // This is already spilled, no need to allocate
      if (coloring[definition] is StackSlot) continue
      // Allocate registers for values defined at this label
      val color = target.selectRegisterBlacklist(assigned, definition)
      // Coloring the same thing twice is almost always a bug (and for the cases it's not, we have the "colored" list)
      if (coloring[definition] != null) {
        logger.throwICE("Coloring the same definition twice") {
          "def: $definition, old color: ${coloring[definition]}, new color: $color"
        }
      }
      coloring[definition] = color
      colored += definition
      // Don't mark the register as assigned unless this value is actually used
      if (graph.liveness.isUsed(definition)) assigned += color
    }
  }
  registerUseMap[block] = assigned.toList()
  assigned.clear()
}

private fun validateAllocation(result: AllocationResult) {
  val failedCheck = result.walkGraphAllocs { register, (blockId, index), type ->
    if (type == ViolationType.SOFT) return@walkGraphAllocs false
    logger.error("Hard violation of allocation for $register at (block: $blockId, index $index)")
    return@walkGraphAllocs true
  }
  check(!failedCheck) { "Hard violation. See above errors." }
}

/**
 * Modify the [InstructionGraph] to have all [ParallelCopyTemplate] MIs removed.
 */
private fun RegisterAllocationContext.replaceParallelInstructions() {
  for ((block, blockPositions) in parallelCopies.entries.groupBy { it.key.first }) {
    var intraBlockOffset = 0
    for ((label, copySequence) in blockPositions.sortedBy { it.key.second }) {
      val index = label.second + intraBlockOffset
      // Update the instructions directly, because we don't care about liveness anymore
      // Also it crashes, since this replacement breaks some invariants
      val instrs = graph[block].unsafelyGetInstructions()
      // Drop the copy, add the new sequence in its place
      instrs.removeAt(index)
      instrs.addAll(index, copySequence)
      // Added N copy MIs, removed the parallel MI, so the indices should offset by N - 1
      intraBlockOffset += copySequence.size - 1
    }
  }
}

/**
 * Performs target-independent spilling and register allocation.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Algorithm 4.2
 *
 * @param debugNoPostColoring only for debugging: if true, post-coloring transforms will not be applied
 * @param debugNoCheckAlloc only for debugging: if true, do not run [walkGraphAllocs]
 * @param debugReturnAfterSpill only for debugging: if true, only run the spiller, do not run the coloring algorithm
 * @see prepareForColoring
 * @see runSpiller
 * @see insertSpillReloadCode
 * @see allocBlock
 * @see replaceParallelInstructions
 * @see removePhi
 */
fun TargetFunGenerator<out AsmInstruction>.regAlloc(
    debugNoPostColoring: Boolean = false,
    debugNoCheckAlloc: Boolean = false,
    debugReturnAfterSpill: Boolean = false
): AllocationResult {
  val ctx = RegisterAllocationContext(this)
  ctx.prepareForColoring()
  val spillResult = runSpiller()
  insertSpillReloadCode(spillResult, ctx.spilled)

  if (debugReturnAfterSpill) {
    return ctx.getAllocationResult()
  }

  // FIXME: coalescing?
  ctx.performColoringAllocation()
  val result = ctx.getAllocationResult()

  if (!debugNoCheckAlloc) {
    validateAllocation(result)
  }

  if (debugNoPostColoring) {
    return result
  }

  ctx.replaceParallelInstructions()
  return removePhi(result)
}

/**
 * Replace all φs.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Section 4.4
 * @return modified [AllocationResult]
 * @see removeOnePhi
 */
private fun AnyFunGenerator.removePhi(allocationResult: AllocationResult): AllocationResult {
  val (graph, _, regUseMap) = allocationResult
  val newRegUseMap = regUseMap.toMutableMap()
  // Force eager evaluation of the dom tree traversal, because we will change the graph, which will change the traversal
  for (blockId in graph.domTreePreorder.asSequence().toList()) {
    val block = graph[blockId]
    if (block.isEmpty()) continue
    // Make an explicit copy here to avoid ConcurrentModificationException, since splitting edges changes the preds
    for (pred in graph.predecessors(block).toList()) {
      val copies = removeOnePhi(allocationResult.copy(registerUseMap = newRegUseMap), block, pred)
      if (copies.isEmpty()) continue
      val insertHere = splitCriticalForCopies(pred, block)
      newRegUseMap[insertHere] = newRegUseMap.getValue(block)
      insertPhiCopies(insertHere, copies)
    }
  }
  return allocationResult.copy(registerUseMap = newRegUseMap)
}

/**
 * If the edge between [src] and [dest] is critical, insert a new block between them and return it. Otherwise, return
 * [src].
 *
 * This is to avoid the "lost copies" problem, the copies must be inserted "on the edge". This can be done by inserting
 * in the predecessor, as long as the edge is not critical.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Section 2.2.3
 */
private fun AnyFunGenerator.splitCriticalForCopies(src: InstrBlock, dest: InstrBlock): InstrBlock {
  // If the edge isn't critical, don't split it
  if (graph.successors(src).size <= 1 || graph.predecessors(dest).size <= 1) {
    return src
  }
  return graph.splitEdge(src, dest, this::createJump)
}

/**
 * Creates the register transfer graph for one φ instruction (that means all of [InstrBlock.phi]),
 * for a given predecessor, and generates the correct copy sequence to replace the φ with.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Section 4.4, pp 55-58
 * @param block the l in the paper
 * @param pred the l' in the paper
 * @return the copy instruction sequence
 */
private fun AnyFunGenerator.removeOnePhi(
    allocationResult: AllocationResult,
    block: InstrBlock,
    pred: InstrBlock,
): List<MachineInstruction> {
  val (_, coloring, registerUseMap) = allocationResult
  // Step 1: the set of undefined registers U is excluded from the graph
  val phis = block.phi.filterNot { it.value.getValue(pred.id).isUndefined }
  // regs is the set R of registers in the register transfer graph T(R, T_E)
  val regs = phis.flatMap {
    val betaY = it.value.getValue(pred.id)
    listOf(coloring.getValue(it.key), coloring.getValue(betaY))
  }
  // Adjacency lists for T
  val adjacency = mutableMapOf<MachineRegister, MutableSet<MachineRegister>>()
  for (it in regs) adjacency[it] = mutableSetOf()
  // variable here is the y in the paper
  for ((variable, incoming) in phis) {
    // predIncoming is β(y) in the paper
    val predIncoming = incoming.getValue(pred.id)
    val rhoY = coloring.getValue(variable)
    val rhoBetaY = coloring.getValue(predIncoming)
    adjacency.getValue(rhoBetaY) += rhoY
  }
  // Step 2: the unused register set F
  val free =
      (target.registers - target.forbidden - registerUseMap.getValue(pred)).toMutableList()
  return solveTransferGraph(adjacency, free)
}

/**
 * Creates the register transfer graph for one φ instruction (a [MachineInstruction] with [ParallelCopyTemplate]), and
 * generates the correct copy sequence to replace the φ with.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Section 4.4, pp 55-58
 * @return the copy instruction sequence
 * @see removeOnePhi
 */
private fun AnyFunGenerator.replaceParallel(
    parallelCopyValues: Map<AllocatableValue, AllocatableValue>,
    coloring: AllocationMap,
    assigned: Set<MachineRegister>,
): List<MachineInstruction> {
  // Step 1: the set of undefined registers U is excluded from the graph
  val vals = (parallelCopyValues.keys + parallelCopyValues.values).filterNot { it.isUndefined }
  // regs is the set R of registers in the register transfer graph T(R, T_E)
  val regs = vals.map { coloring.getValue(it) }
  // Adjacency lists for T
  val adjacency = mutableMapOf<MachineRegister, MutableSet<MachineRegister>>()
  for (it in regs) adjacency[it] = mutableSetOf()
  for ((old, new) in parallelCopyValues) {
    val rhoBetaY = coloring.getValue(old)
    val rhoY = coloring.getValue(new)
    adjacency.getValue(rhoBetaY) += rhoY
  }
  // Step 2: the unused register set F
  val free = (target.registers - target.forbidden - assigned).toMutableList()
  return solveTransferGraph(adjacency, free)
}

/**
 * Common steps for register transfer graph solving.
 *
 * @param adjacency this represents the value transfer. That means that the keys are copy sources.
 * @see replaceParallel
 */
private fun AnyFunGenerator.solveTransferGraph(
    adjacency: MutableMap<MachineRegister, MutableSet<MachineRegister>>,
    free: MutableList<MachineRegister>,
): List<MachineInstruction> {
  if (adjacency.isEmpty()) return emptyList()

  val copies = mutableListOf<MachineInstruction>()
  // Step 3: For each edge e = (r, s), r ≠ s, out degree of s == 0
  // We do this for as long as an edge e with the above property exists
  do {
    var hasValidEdge = false
    for (r in adjacency.keys) {
      val outgoing = adjacency.getValue(r).filter { s -> r != s && adjacency.getValue(s).size == 0 }
      if (outgoing.isNotEmpty()) hasValidEdge = true
      for (s in outgoing) {
        // Insert s ← r copy
        copies += createRegisterCopy(s, r)
        // Remove edge e
        adjacency.getValue(r) -= s
        // s is no longer free
        free -= s
        // r is now free
        free += r
        // Redirect outgoing edges of r to s (except self-loops)
        val hasSelfLoop = r in adjacency.getValue(r)
        adjacency.getValue(s) += (adjacency.getValue(r) - r)
        adjacency.getValue(r).clear()
        if (hasSelfLoop) adjacency.getValue(r) += r
      }
    }
  } while (hasValidEdge)
  // Step 4: T is either empty, or it just contains cycles
  do {
    for (r1 in adjacency.keys) {
      // Step 4a: self-loops generate nothing (obviously)
      adjacency.getValue(r1) -= r1
      if (adjacency.getValue(r1).isEmpty()) continue
      val freeTemp = free.firstOrNull { it.valueClass == r1.valueClass }
      if (freeTemp != null) {
        // Step 4b: F is not empty
        copies += solveTransferGraphCycle(adjacency, r1, freeTemp)
      } else {
        // Step 4c: F is empty
        // Make r1 free, and remove one edge of the cycle from the graph
        // FIXME: if r1 has memory value class, this will not work
        copies += createLocalPush(r1)
        val nextR1 = adjacency.getValue(r1).first()
        adjacency.getValue(r1) -= nextR1
        // Use r1 as the free temporary
        copies += solveTransferGraphCycle(adjacency, nextR1, r1)
        // The final copy of the cycle moves r1 into nextR1, which is redundant
        copies.removeLast()
        // Since the edge was r1 -> nextR1, we pop the stored r1 into nextR1
        copies += createLocalPop(nextR1)
      }
    }
    // While T is not empty
  } while (adjacency.values.any { it.isNotEmpty() })
  return copies
}

/**
 * Create the copy sequence for a cycle in the register transfer graph.
 *
 * The copies inside this function are created in reverse order.
 *
 * This is because a graph edge like `rax → rcx` actually means `mov rcx, rax`, which would hint that we need to
 * operate on the transposed graph T'. It is, however, far cheaper and easier to reverse things than to it is
 * to transpose the graph.
 *
 * We reverse the copy operand order, so the edge `rLast → nextInCycle` creates the copy `mov nextInCycle, rLast`, and
 * we also reverse the instruction order (see after the loop).
 *
 * Not dealing with T' can work in many (most?) common cases, where the cycle has only 2 nodes, but do not be fooled:
 * it definitely produces wrong results for >2 nodes.
 *
 * @param adjacency the graph T
 * @param firstInCycle any node in the cycle, it is irrelevant which
 * @param freeTemp a free register to be used as a temporary for swaps. This register can also be part of the cycle, if
 * edges going out from it are handled.
 *
 * @see solveTransferGraph
 */
private fun AnyFunGenerator.solveTransferGraphCycle(
    adjacency: MutableMap<MachineRegister, MutableSet<MachineRegister>>,
    firstInCycle: MachineRegister,
    freeTemp: MachineRegister,
): List<MachineInstruction> {
  var rLast: MachineRegister = freeTemp
  var nextInCycle = firstInCycle
  val cycleCopies = mutableListOf<MachineInstruction>()
  do {
    if (adjacency.getValue(nextInCycle).size > 1) TODO("deal with multiple cycles")
    cycleCopies += createRegisterCopy(nextInCycle, rLast)
    rLast = nextInCycle
    val next = adjacency.getValue(nextInCycle).single()
    adjacency.getValue(nextInCycle).clear()
    nextInCycle = next
  } while (nextInCycle != firstInCycle && nextInCycle != freeTemp)
  cycleCopies += createRegisterCopy(freeTemp, rLast)

  return cycleCopies.asReversed()
}
