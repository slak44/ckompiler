package slak.ckompiler.backend

import org.apache.logging.log4j.LogManager
import slak.ckompiler.analysis.*
import slak.ckompiler.throwICE

private val logger = LogManager.getLogger()

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
    val spillResult: SpillResult
) {
  val stackSlots get() = allocations.values.filterIsInstance<StackSlot>()
}

private fun MachineTarget.selectRegisterWhitelist(
    whitelist: Set<MachineRegister>,
    value: IRValue
): MachineRegister? {
  val validClass = registerClassOf(value.type)
  val validSize = machineTargetData.sizeOf(value.type.unqualify().normalize())
  return whitelist.firstOrNull { candidate ->
    candidate.valueClass == validClass &&
        (candidate.sizeBytes == validSize || validSize in candidate.aliases.map { it.second })
  }
}

private fun MachineTarget.selectRegisterBlacklist(
    blacklist: Set<MachineRegister>,
    value: IRValue
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
    uses.filter { isDeadAfter(it, label) }
  }
}

/**
 * Insert a copy.
 *
 * [value] is a constrained argument of [constrainedInstr].
 */
private fun TargetFunGenerator.insertSingleCopy(
    block: InstrBlock,
    index: Int,
    value: AllocatableValue,
    constrainedInstr: MachineInstruction
) {
  // No point in inserting a copy for something that's never used
  if (!graph.isUsed(value)) return
  val copiedValue = graph.createCopyOf(value, block)
  val copyInstr = createIRCopy(copiedValue, value)
  copyInstr.irLabelIndex = constrainedInstr.irLabelIndex
  block.add(index, copyInstr)
  // This is the case where the value is an undefined dummy
  // We want to keep the original and correct last use, instead of destroying it here
  if (value === copiedValue) return
  // The value is a constrained argument: it is still used at the constrained MI, after the copy
  // We know that is the last use, because all the ones afterwards were just rewritten
  // The death is put at the current index, so the SSA reconstruction doesn't pick it up as alive
  if (value is Variable) {
    graph.defUseChains.getOrPut(value, ::mutableSetOf) += InstrLabel(block.id, index)
    return
  }
  graph.virtualDeaths[value as VirtualRegister] = InstrLabel(block.id, index)
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
    block: InstrBlock
) {
  // No point in making a copy for something that's never used
  val actualValues = splitFor.filter(graph::isUsed)
  val rewriteMap = actualValues.associateWith { graph.createCopyOf(it, block) }
  val phi = ParallelCopyTemplate.createCopy(rewriteMap)
  phi.irLabelIndex = atIndex
  for (copiedValue in rewriteMap.values.filterIsInstance<Variable>()) {
    graph.parallelCopies.getOrPut(block.id, ::mutableListOf) += copiedValue
  }

  // Splice the copy in the instructions
  block.add(atIndex, phi)
  // Also rewrite the constrained instr too
  block[atIndex + 1] = block[atIndex + 1].rewriteBy(rewriteMap)

  for ((value, rewritten) in rewriteMap) {
    when (value) {
      is VirtualRegister -> {
        // The constrained instr was rewritten, and the value cannot have been used there, so it dies when it is copied
        graph.virtualDeaths[value] = InstrLabel(block.id, atIndex)
      }
      is Variable -> {
        // The use got pushed by adding the copy, remove that
        graph.defUseChains.getOrPut(value, ::mutableSetOf) -= InstrLabel(block.id, atIndex + 1)
        // Add back the variable use from atIndex: the parallel copy itself does indeed use the variable
        graph.defUseChains.getOrPut(value, ::mutableSetOf) += InstrLabel(block.id, atIndex)
        // We also need to setup the copy's use at the rewritten instruction:
        graph.defUseChains.getOrPut(rewritten, ::mutableSetOf) += InstrLabel(block.id, atIndex + 1)
      }
    }
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
private fun TargetFunGenerator.prepareForColoring() {
  for (blockId in graph.domTreePreorder) {
    val block = graph[blockId]
    // Track which variables are alive at any point in this block
    // Initialize with the block live-ins, and with φ vars
    val alive: MutableSet<AllocatableValue> = graph.liveInsOf(blockId).toMutableSet()
    // The φ-uses are not alive at index 0; they die at the φ itself
    alive -= block.phiUses

    var index = 0

    fun updateAlive(index: Int) {
      val mi = block[index]
      val dyingHere = graph.dyingAt(InstrLabel(blockId, index), mi)
      alive += mi.defs.filterIsInstance<AllocatableValue>().filter { graph.isUsed(it) }
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
      graph.ssaReconstruction(alive.filterIsInstance<Variable>().toSet())
      // The parallel copy needs to have updateAlive run on it, before skipping it
      updateAlive(index)
      index++

      val startMi = block[index]
      val startIndex = index
      for ((value, target) in startMi.constrainedArgs) {
        // Result constrained to same register as live-though constrained variable: copy must be made
        if (graph.livesThrough(value, InstrLabel(blockId, index)) &&
            target in startMi.constrainedRes.map { it.target }) {
          insertSingleCopy(block, index, value, startMi)
          index++
        }
      }
      graph.ssaReconstruction(alive.filterIsInstance<Variable>().toSet())
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

private class RegisterAllocationContext(val generator: TargetFunGenerator) {
  /** The allocation itself. */
  val coloring = mutableMapOf<IRValue, MachineRegister>()

  /** The value of [assigned] at the end of each [allocBlock]. */
  val registerUseMap = mutableMapOf<InstrBlock, List<MachineRegister>>()

  /** The registers in use at a moment in time. Should be only used internally by the allocator. */
  val assigned = mutableSetOf<MachineRegister>()

  /** Store the copy sequences to replace each label with a parallel copy. */
  val parallelCopies = mutableMapOf<InstrLabel, List<MachineInstruction>>()

  val target get() = generator.target
  val graph get() = generator.graph

  /** Begin a DFS from the starting block. */
  fun doAllocation() {
    for (blockId in graph.domTreePreorder) {
      allocBlock(graph[blockId])
    }
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
  val t = arg.filter { graph.livesThrough(it, label) }.toMutableSet()
  val a = (arg - t).toMutableSet()
  val d = mi.defs.filterIsInstance<AllocatableValue>().toMutableSet()
  val cA = mutableSetOf<MachineRegister>()
  val cD = mutableSetOf<MachineRegister>()

  for ((value, target) in mi.constrainedArgs) {
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
    // We differ here a bit, because cD - cA might have a register, but of the wrong class
    val reg = target.selectRegisterWhitelist(cD - cA, x)
    if (reg != null) {
      coloring[x] = reg
    } else {
      coloring[x] = target.selectRegisterBlacklist(assigned + cA, x)
    }
  }
  for (x in d) {
    val reg = target.selectRegisterWhitelist(cA - cD, x)
    if (reg != null) {
      coloring[x] = reg
    } else {
      coloring[x] = target.selectRegisterBlacklist(assigned + cD, x)
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

  val (block, index) = label
  require(index > 0) { "No parallel copy found for constrained instruction" }

  val phiIdx = graph[block].take(index).indexOfLast { it.template is ParallelCopyTemplate }
  require(phiIdx >= 0) { "No parallel copy preceding constrained" }
  val phi = graph[block][phiIdx]

  val phiMap = (phi.template as ParallelCopyTemplate).values

  // There should be exclusively copies defined in this interval
  val extraCopies = graph[block].subList(phiIdx + 1, index).flatMap { it.defs }.filterIsInstance<AllocatableValue>()

  constrainedColoring(label, mi)
  unassignDyingAt(label, mi)
  // Correctly color copies inserted by the live range split (and the other copies from 4.6.1)
  // See last paragraph of section 4.6.4 for why we have to do this
  val copies = phiMap.values + extraCopies
  val usedAtL = mi.uses.filterIsInstance<AllocatableValue>()
  val definedAtL = mi.defs.filterIsInstance<AllocatableValue>()
  val colorsAtL = (definedAtL + usedAtL).mapTo(mutableSetOf()) { coloring.getValue(it) }
  val toRecolor = copies - usedAtL
  // First, un-assign all the old colors
  assigned -= toRecolor.map { checkNotNull(coloring[it]) }
  // And only then allocate them again
  // If an old assignment would have been in rax, and the recoloring puts a value in rax before the old assignment is
  // processed, rax would have gotten removed from assigned, even though the recoloring assigned something to there
  for (copy in toRecolor) {
    val color = target.selectRegisterBlacklist(assigned + colorsAtL, copy)
    coloring[copy] = color
    assigned += color
  }
  // assigned is a set because each reg can only be assigned to one value at a time, making it safe to add/remove the
  // entry in assigned when the value is gen'd/killed
  // The loop above then assumes it can remove old colors from assigned, since they were reallocated, but that's not
  // necessarily true for values in usedAtL, because they might be live-through the constrained label
  for (inUse in usedAtL.filterNot { it.isUndefined }) {
    assigned += coloring.getValue(inUse)
  }

  // Create the copy sequence to replace the parallel copy
  parallelCopies[InstrLabel(block, phiIdx)] = generator.replaceParallel(phi, coloring, assigned + colorsAtL)

  for (def in definedAtL.filter { graph.isUsed(it) && !it.isUndefined }) {
    val color = checkNotNull(coloring[def]) { "Value defined at constrained label not colored: $def" }
    assigned += color
  }
}

/**
 * Register allocation for one [block]. Recursive DFS case.
 */
private fun RegisterAllocationContext.allocBlock(block: InstrBlock) {
  for (x in graph.liveInsOf(block.id) - block.phiDefs - block.phiUses) {
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
  for ((variable, _) in block.phi) {
    val color = target.selectRegisterBlacklist(assigned, variable)
    coloring[variable] = color
    assigned += color
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
      if (graph.isUsed(definition)) assigned += color
    }
  }
  registerUseMap[block] = assigned.toList()
  assigned.clear()
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
 * @param debugNoPostColoring only for debugging: if true, post-coloring transforms will not be applied
 * @param debugNoCheckAlloc only for debugging: if true, do not run [walkGraphAllocs]
 * @see runSpiller
 * @see insertSpillReloadCode
 * @see prepareForColoring
 * @see replaceParallelInstructions
 * @see removePhi
 */
fun TargetFunGenerator.regAlloc(
    debugNoPostColoring: Boolean = false,
    debugNoCheckAlloc: Boolean = false
): AllocationResult {
  val spillResult = runSpiller()
  val spillMap = insertSpillReloadCode(spillResult)
  prepareForColoring()
  // FIXME: coalescing
  val ctx = RegisterAllocationContext(this)
  for ((_, stackValue) in spillMap) {
    ctx.coloring[stackValue] = SpillSlot(stackValue, stackSlotIds(), target.machineTargetData)
  }
  ctx.doAllocation()
  val allocations = ctx.coloring.filterKeys { it !is ConstantValue }
  val result = AllocationResult(graph, allocations, ctx.registerUseMap, spillResult)

  if (!debugNoCheckAlloc) {
    val failedCheck = result.walkGraphAllocs { register, (blockId, index), type ->
      if (type == ViolationType.SOFT) return@walkGraphAllocs false
      logger.error("Hard violation of allocation for $register at (block: $blockId, index $index)")
      return@walkGraphAllocs true
    }
    check(!failedCheck) { "Hard violation. See above errors." }
  }
  if (debugNoPostColoring) return result

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
private fun TargetFunGenerator.removePhi(allocationResult: AllocationResult): AllocationResult {
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
 * To avoid the "lost copies" problem, critical edges must be split, and the copies inserted there.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Section 2.2.3
 */
private fun TargetFunGenerator.splitCriticalForCopies(src: InstrBlock, dest: InstrBlock): InstrBlock {
  // If the edge isn't critical, don't split it
  if (graph.successors(src).size <= 1 || graph.predecessors(dest).size <= 1) {
    return src
  }
  return graph.splitEdge(src, dest, this::createJump)
}

/**
 * Creates the register transfer graph for one φ instruction (that means all of [BasicBlock.phi]),
 * for a given predecessor, and generates the correct copy sequence to replace the φ with.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Section 4.4, pp 55-58
 * @param block the l in the paper
 * @param pred the l' in the paper
 * @return the copy instruction sequence
 */
private fun TargetFunGenerator.removeOnePhi(
    allocationResult: AllocationResult,
    block: InstrBlock,
    pred: InstrBlock
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
    adjacency.getValue(coloring.getValue(predIncoming)) += coloring.getValue(variable)
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
private fun TargetFunGenerator.replaceParallel(
    parallelCopy: MachineInstruction,
    coloring: AllocationMap,
    assigned: Set<MachineRegister>
): List<MachineInstruction> {
  val phiMap = (parallelCopy.template as ParallelCopyTemplate).values
  // Step 1: the set of undefined registers U is excluded from the graph
  val vals = (phiMap.keys + phiMap.values).filterNot { it.isUndefined }
  // regs is the set R of registers in the register transfer graph T(R, T_E)
  val regs = vals.map { coloring.getValue(it) }
  // Adjacency lists for T
  val adjacency = mutableMapOf<MachineRegister, MutableSet<MachineRegister>>()
  for (it in regs) adjacency[it] = mutableSetOf()
  for (key in phiMap.keys) {
    val target = coloring.getValue(phiMap.getValue(key))
    adjacency.getValue(coloring.getValue(key)) += target
  }
  // Step 2: the unused register set F
  val free = (target.registers - target.forbidden - assigned).toMutableList()
  return solveTransferGraph(adjacency, free)
}

/**
 * Common steps for register transfer graph solving.
 *
 * @see removeOnePhi
 * @see replaceParallel
 */
private fun TargetFunGenerator.solveTransferGraph(
    adjacency: MutableMap<MachineRegister, MutableSet<MachineRegister>>,
    free: MutableList<MachineRegister>
): List<MachineInstruction> {
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
        var rLast: MachineRegister = freeTemp
        var nextInCycle = r1
        do {
          if (adjacency.getValue(nextInCycle).size > 1) TODO("deal with multiple cycles")
          copies += createRegisterCopy(rLast, nextInCycle)
          rLast = nextInCycle
          val next = adjacency.getValue(nextInCycle).single()
          adjacency.getValue(nextInCycle).clear()
          nextInCycle = next
        } while (nextInCycle != r1)
        copies += createRegisterCopy(rLast, freeTemp)
      } else {
        // Step 4c: F is empty
        TODO("implement this")
      }
    }
    // While T is not empty
  } while (adjacency.values.any { it.isNotEmpty() })
  return copies
}
