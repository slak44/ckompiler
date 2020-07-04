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
typealias RegisterUseMap = Map<BasicBlock, List<MachineRegister>>

data class AllocationResult(
    val partial: InstructionMap,
    val allocations: AllocationMap,
    val registerUseMap: RegisterUseMap,
    val lastUses: Map<IRValue, Label>
) {
  val stackSlots get() = allocations.values.filterIsInstance<StackSlot>()
  operator fun component5() = stackSlots
}

private fun MachineTarget.matchValueToRegister(
    value: IRValue,
    forbiddenNeigh: List<MachineRegister>
): MachineRegister {
  val validClass = registerClassOf(value.type)
  val validSize = machineTargetData.sizeOf(value.type.unqualify().normalize())
  val register = (registers - forbidden - forbiddenNeigh).firstOrNull { candidate ->
    candidate.valueClass == validClass &&
        (candidate.sizeBytes == validSize || validSize in candidate.aliases.map { it.second })
  }
  return requireNotNull(register) {
    "Failed to find an empty register for the given value: $value"
  }
}

/**
 * This function rewrites a block to accommodate a new copy instruction. It also modifies the CFG to deal with rewritten
 * values, such that we remain in SSA form.
 *
 * [VirtualRegister]s are easy to deal with: they cannot escape the block, so they can't introduce new φs.
 * That means we can just rewire the uses, and still be in SSA form, so no SSA reconstruction needed.
 *
 * [Variable]s are more problematic, since a new version of a variable might create a new φ, which is problematic.
 * Say for a diamond [CFG]:
 *    A
 *   / \
 *  B   C
 *   \ /
 *    D
 * With the variable defined at the top in A (version 1, [toRewrite]), and used in all other 3 blocks.
 * Now if we need to rewrite its uses in say, block C, we'll replace its uses with version 2 ([rewritten]). But now we
 * have a problem: version 1 is live-out in block B, and version 2 is live-out in block C, so block D needs a φ
 * instruction, one which was not there previously (there was only one version).
 *
 * If the φ was already there, it would have been ok: just update the incoming value from this block with the new
 * version. When we insert a new φ, we still know the incoming from our [block] (it's the new version), but we don't
 * know the incoming from the other blocks... and those other blocks might not know which version is live-out in them.
 * That means we have to rebuild the SSA (which is not exactly a cheap operation).
 *
 * @see insertConstraintCopies
 * @see facilitatePrecoloring
 */
private fun rewriteBlockUses(
    block: BasicBlock,
    copyToInsert: MachineInstruction,
    atIdx: LabelIndex,
    toRewrite: IRValue,
    rewritten: IRValue,
    instructions: List<MachineInstruction>
): List<MachineInstruction> {
  val (before, after) = instructions.take(atIdx) to instructions.drop(atIdx + 1)
  val newAfter = mutableListOf<MachineInstruction>()
  for (mi in after) {
    val newOperands = mi.operands.zip(mi.template.operandUse).map {
      // Explicitly not care about DEF_USE
      if (it.first == toRewrite && it.second == VariableUse.USE) {
        rewritten
      } else {
        it.first
      }
    }
    val newConstrainedArgs = mi.constrainedArgs.map {
      if (it.value == toRewrite) {
        it.copy(value = rewritten)
      } else {
        it
      }
    }
    newAfter += mi.copy(operands = newOperands, constrainedArgs = newConstrainedArgs)
  }
  // FIXME: this is broken; see docs above about SSA
  // If we rewrote a variable, it might have been used in a future phi, so deal with that
  if (rewritten is Variable) {
    for (succ in block.successors) {
      val replacementPhis = mutableSetOf<PhiInstruction>()
      succ.phi.retainAll { phi ->
        if (phi.incoming.getValue(block) != toRewrite) return@retainAll true
        val newIncoming = phi.incoming.toMutableMap()
        newIncoming[block] = rewritten
        replacementPhis += phi.copy(incoming = newIncoming)
        return@retainAll false
      }
      succ.phi += replacementPhis
    }
  }
  return before + copyToInsert + instructions[atIdx] + newAfter
}

/**
 * This function incrementally updates a "last uses" map, by updating all the last use indices that were pushed due to a
 * copy being inserted.
 *
 * @see insertConstraintCopies
 * @see facilitatePrecoloring
 */
private fun rewriteLastUses(
    newLastUses: MutableMap<IRValue, Label>,
    block: BasicBlock,
    value: IRValue,
    copyIndex: LabelIndex
) {
  for ((modifiedValue, oldDeath) in newLastUses.filterValues { it.first == block && it.second > copyIndex }) {
    newLastUses[modifiedValue] = Label(block, oldDeath.second + 1)
  }
  newLastUses[value] = Label(block, copyIndex + 1)
}

/**
 * Creates a new virtual register, or a new version of a variable. Throws for other [IRValue]s.
 */
private fun CFG.makeCopiedValue(value: IRValue): IRValue {
  require(value is Variable || value is VirtualRegister)
  return if (value is Variable) {
    val oldVersion = latestVersions.getValue(value.id)
    latestVersions[value.id] = oldVersion + 1
    value.copy(oldVersion + 1)
  } else {
    VirtualRegister(registerIds(), value.type)
  }
}

private data class IntermediateConstraintsResult(
    val instrMap: InstructionMap,
    val lastUses: Map<IRValue, Label>,
    val constrainedLabels: List<Label>
)

/**
 * Inserts copies for constrained instructions, that respect the Simple Constraint Property (Definition 4.8).
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: 4.6.1
 */
private fun TargetFunGenerator.insertConstraintCopies(
    instrMap: InstructionMap,
    lastUses: Map<IRValue, Label>
): IntermediateConstraintsResult {
  val newMap = instrMap.toMutableMap()
  val newLastUses = lastUses.toMutableMap()
  val constrainedLabels = mutableListOf<Label>()
  for (block in cfg.domTreePreorder) {
    var newInstr = instrMap.getValue(block)
    // Track how many copies we inserted
    // We need to know to offset the index for rewriting, since the loop below uses only pre-insert indices
    var insertions = 0
    for ((index, mi) in instrMap.getValue(block).withIndex()) {
      if (mi.constrainedArgs.isEmpty() || mi.constrainedRes.isEmpty()) continue
      // Track constrained labels for later, so we don't traverse the graph again
      constrainedLabels += Label(block, index + insertions)
      for ((value, target) in mi.constrainedArgs) {
        // If it doesn't die after this instruction, it's not live-through, so leave it alone
        if (lastUses.getValue(value).second <= index) continue
        // Result constrained to same register as live-though constrained variable: copy must be made
        if (target in mi.constrainedRes.map { it.target }) {
          val copiedValue = cfg.makeCopiedValue(value)
          val copy = createIRCopy(copiedValue, value)
          copy.irLabelIndex = mi.irLabelIndex
          newInstr = rewriteBlockUses(block, copy, index + insertions, value, copiedValue, newInstr)
          rewriteLastUses(newLastUses, block, value, index + insertions)
          insertions++
        }
      }
    }
    newMap[block] = newInstr
  }
  return IntermediateConstraintsResult(newMap, newLastUses, constrainedLabels)
}

/**
 * Splits the live-range of each value in [splitFor], by creating copies and inserting them [atIndex].
 *
 * @see facilitatePrecoloring
 */
private fun TargetFunGenerator.splitLiveRanges(
    splitFor: List<IRValue>,
    atIndex: LabelIndex,
    block: BasicBlock,
    instructions: List<MachineInstruction>,
    lastUses: MutableMap<IRValue, Label>
): List<MachineInstruction> {
  require(splitFor.all { it is VirtualRegister || it is Variable }) {
    "Trying to split live range of non-var, non-vreg"
  }
  var newInstrs = instructions
  for ((insertedCurrently, value) in splitFor.withIndex()) {
    // The replacement will die at the index where the original died
    val rewrittenLastUse = lastUses.getValue(value)
    // Make the copy
    val rewritten = cfg.makeCopiedValue(value)
    val copy = createIRCopy(rewritten, value)
    copy.irLabelIndex = newInstrs[atIndex + insertedCurrently].irLabelIndex
    // And rewrite everything
    newInstrs = rewriteBlockUses(block, copy, atIndex + insertedCurrently, value, rewritten, newInstrs)
    rewriteLastUses(lastUses, block, value, atIndex + insertedCurrently)
    lastUses[rewritten] = rewrittenLastUse
  }
  return newInstrs
}

/**
 * Turns Precol-Ext (NP Complete for chordal graphs) into 1-Precol-Ext (P for chordal graphs). Basically, to solve
 * register targeting constraints, we precolor those nodes, and then extend the coloring to a complete k-coloring. The
 * general problem is hard, but if we split the live ranges of all the living variables at the constrained label, the
 * problem becomes solvable in polynomial time. See reference for more details and proofs.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: 4.6.3
 */
private fun TargetFunGenerator.facilitatePrecoloring(
    icr: IntermediateConstraintsResult
): IntermediateConstraintsResult {
  val (instrMap, lastUses, constrainedLabels) = icr
  val newInstrMap = instrMap.toMutableMap()
  val newLastUses = lastUses.toMutableMap()
  // Group constrained labels by the block they're in
  val blockConstraints = constrainedLabels.groupBy { it.first }
  for (block in blockConstraints.keys) {
    var newInstr = instrMap.getValue(block)
    // Get a list of indices to split live ranges at
    val constrainedIndices = blockConstraints.getValue(block).map { it.second }
    // Track which variables are alive at any point in this block
    // Initialize with the block live-ins
    val alive: MutableList<IRValue> = cfg.liveIns.getValue(block).toMutableList()
    var index = 0
    var inserted = 0
    while (index < newInstr.size) {
      val mi = newInstr[index]
      // The indices list has old values, before insertions
      if ((index - inserted) in constrainedIndices) {
        newInstr = splitLiveRanges(alive, index, block, newInstr, newLastUses)
        // FIXME: do we have to alter live-ins?
        // Each one alive is copied, each copy an instruction that is inserted
        inserted += alive.size
        index += alive.size
      }
      val dyingHere = mi.uses.filter { newLastUses[it] == Label(block, index) }
      alive -= dyingHere
      val definedHere = mi.defs.filter { it is VirtualRegister || it is Variable }
      alive += definedHere
      index++
    }
  }
  return IntermediateConstraintsResult(newInstrMap, newLastUses, constrainedLabels)
}

/**
 * Creates a map of each [IRValue] to the [Label] where it dies. Uses [MachineInstruction] indices.
 */
private fun findLastUses(cfg: CFG, instrMap: InstructionMap): Map<IRValue, Label> {
  val lastUses = mutableMapOf<IRValue, Label>()
  for (block in cfg.domTreePreorder) {
    for ((index, mi) in instrMap.getValue(block).withIndex()) {
      // For "variables", keep updating the map
      // Obviously, the last use will be the last update in the map
      for (it in mi.uses.filter { it is VirtualRegister || it is PhysicalRegister || it is Variable }) {
        lastUses[it] = Label(block, index)
      }
    }
    // We need to check successor phis: if something is used there, it means it is live-out in this block
    // If it's live-out in this block, its "last use" is not what was recorded above
    for (succ in block.successors) {
      for (phi in succ.phi) {
        val usedInSuccPhi = phi.incoming.getValue(block)
        lastUses[usedInSuccPhi] = Label(block, LabelIndex.MAX_VALUE)
      }
    }
  }
  return lastUses
}

/**
 * Performs target-independent spilling and register allocation.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Algorithm 4.2
 * @see spiller
 */
fun TargetFunGenerator.regAlloc(instrMap: InstructionMap): AllocationResult {
  val initialLastUses = findLastUses(cfg, instrMap)
  val spilledInstrs = spiller(instrMap, initialLastUses)
  val (finalInstrMap, lastUses) = facilitatePrecoloring(insertConstraintCopies(spilledInstrs, initialLastUses))
  // FIXME: coalescing
  // List of visited nodes for DFS
  val visited = mutableSetOf<BasicBlock>()
  val coloring = mutableMapOf<IRValue, MachineRegister>()
  val registerUseMap = mutableMapOf<BasicBlock, List<MachineRegister>>()
  fun allocBlock(block: BasicBlock) {
    if (block in visited) return
    visited += block
    val assigned = mutableListOf<MachineRegister>()
    for (x in cfg.liveIns.getValue(block)) {
      // If the live-in wasn't colored, and is null, that's a bug
      assigned += requireNotNull(coloring[x]) { "Live-in not colored: $x in $block" }
    }
    // Add the parameter register constraints to assigned
    if (block == cfg.startBlock) {
      assigned += parameterMap.values.mapNotNull { (it as? PhysicalRegister)?.reg }
    }
    // Remove incoming φ-uses from assigned
    for (used in block.phi.flatMap { it.incoming.values }) {
      val color = coloring[used] ?: continue
      assigned -= color
    }
    // Allocate φ-definitions
    for ((variable, _) in block.phi) {
      val color = target.matchValueToRegister(variable, assigned)
      coloring[variable] = color
      assigned += color
    }
    val colored = mutableSetOf<IRValue>()
    for ((index, mi) in finalInstrMap.getValue(block).withIndex()) {
      // Also add stack variables to coloring
      coloring += mi.operands
          .filter { it is StackVariable || it is MemoryLocation }
          .mapNotNull { if (it is MemoryLocation) it.ptr as? StackVariable else it }
          .associateWith { StackSlot(it as StackVariable, target.machineTargetData) }
      val dyingHere = mi.uses.filter { lastUses[it] == Label(block, index) }
      // Deallocate registers of values that die at this label
      for (value in dyingHere) {
        val color = coloring[value] ?: continue
        assigned -= color
      }
      val defined = mi.defs
          .asSequence()
          .filter { it !in colored }
          .filter { it !is PhysicalRegister && it !is StackVariable && it !is MemoryLocation }
      // Allocate registers for values defined at this label
      for (definition in defined) {
        val color = target.matchValueToRegister(definition, assigned)
        if (coloring[definition] != null) {
          logger.throwICE("Coloring the same definition twice")
        }
        coloring[definition] = color
        colored += definition
        // Don't mark the register as assigned unless this value has at least one use, that
        // is, it exists in lastUses
        if (definition in lastUses) assigned += color
      }
    }
    registerUseMap[block] = assigned
    // Recursive DFS on dominator tree
    for (c in cfg.nodes.filter { cfg.doms[it] == block }.sortedBy { it.height }) {
      allocBlock(c)
    }
  }
  allocBlock(cfg.startBlock)
  val allocations = coloring.filterKeys { it !is ConstantValue }
  val intermediate = AllocationResult(finalInstrMap, allocations, registerUseMap, lastUses)
  return removePhi(intermediate)
}

/**
 * Replace all φs.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Section 4.4
 * @return modified [AllocationResult]
 * @see removeOnePhi
 */
private fun TargetFunGenerator.removePhi(allocationResult: AllocationResult): AllocationResult {
  val newInstructionMap = allocationResult.partial.toMutableMap()
  for (block in cfg.domTreePreorder.filter { it.phi.isNotEmpty() }) {
    // Make an explicit copy here to avoid ConcurrentModificationException, since splitting edges
    // changes the preds
    for (pred in block.preds.toList()) {
      val copies = removeOnePhi(allocationResult, block, pred)
      if (copies.isEmpty()) continue
      val insertHere = splitCriticalForCopies(newInstructionMap, pred, block)
      val instructions = newInstructionMap.getValue(insertHere)
      newInstructionMap[insertHere] = insertPhiCopies(instructions, copies)
    }
  }
  return allocationResult.copy(partial = newInstructionMap)
}

/**
 * To avoid the "lost copies" problem, critical edges must be split, and the copies inserted
 * there.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Section 2.2.3
 */
private fun TargetFunGenerator.splitCriticalForCopies(
    instructionMap: MutableMap<BasicBlock, List<MachineInstruction>>,
    block: BasicBlock,
    succ: BasicBlock
): BasicBlock {
  // If the edge isn't critical, don't split it
  if (block.successors.size <= 1 || succ.preds.size <= 1) {
    return block
  }
  // FIXME: this split messes with the internal state of the CFG
  val insertedBlock = cfg.newBlock()
  insertedBlock.terminator = UncondJump(succ)
  val term = block.terminator
  block.terminator = when {
    term is SelectJump && term.options.isNotEmpty() -> {
      if (term.default == succ) term.copy(default = insertedBlock)
      else term.copy(options = term.options.mapValues {
        if (it.value == succ) insertedBlock else succ
      })
    }
    term is CondJump -> {
      if (term.target == succ) term.copy(target = insertedBlock)
      else term.copy(other = insertedBlock)
    }
    else -> logger.throwICE("Impossible; terminator has fewer successors than block.successors")
  }
  succ.preds -= block
  succ.preds += insertedBlock
  instructionMap[insertedBlock] = listOf(createJump(succ))
  return insertedBlock
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
    block: BasicBlock,
    pred: BasicBlock
): List<MachineInstruction> {
  val (_, coloring, registerUseMap) = allocationResult
  // Step 1: the set of undefined registers U is excluded from the graph
  val phis = block.phi.filterNot { it.incoming.getValue(pred).isUndefined }
  // regs is the set R of registers in the register transfer graph T(R, T_E)
  val regs = phis.flatMap {
    val betaY = it.incoming.getValue(pred)
    listOf(coloring.getValue(it.variable), coloring.getValue(betaY))
  }
  // Adjacency lists for T
  val adjacency = mutableMapOf<MachineRegister, MutableSet<MachineRegister>>()
  for (it in regs) adjacency[it] = mutableSetOf()
  // variable here is the y in the paper
  for ((variable, incoming) in phis) {
    // predIncoming is β(y) in the paper
    val predIncoming = incoming.getValue(pred)
    adjacency.getValue(coloring.getValue(predIncoming)) += coloring.getValue(variable)
  }
  // Step 2: the unused register set F
  val free =
      (target.registers - target.forbidden - registerUseMap.getValue(pred)).toMutableList()
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
