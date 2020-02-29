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
    val registerUseMap: RegisterUseMap
) {
  val stackSlots get() = allocations.values.filterIsInstance<StackSlot>()
  operator fun component4() = stackSlots
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
 * Performs target-independent spilling and register allocation.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Algorithm 4.2
 * @see spiller
 */
fun TargetFunGenerator.regAlloc(instrMap: InstructionMap): AllocationResult {
  val lastUses = mutableMapOf<IRValue, Label>()
  for (block in cfg.domTreePreorder) {
    for (mi in instrMap.getValue(block)) {
      // Initialize lastUses for other LoadableValues
      for (it in mi.uses.filter { it is VirtualRegister || it is PhysicalRegister }) {
        lastUses[it] = block to mi.irLabelIndex
      }
    }
  }
  // Initialize lastUses for Variables
  lastUses.putAll(cfg.defUseChains.mapValues { it.value.last() })
  val spilledInstrs = spiller(instrMap, lastUses)
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
    for (mi in spilledInstrs.getValue(block)) {
      // Also add stack variables to coloring
      coloring += mi.operands
          .filter { it is StackVariable || it is MemoryLocation }
          .mapNotNull { if (it is MemoryLocation) it.ptr as? StackVariable else it }
          .associateWith { StackSlot(it as StackVariable, target.machineTargetData) }
      val dyingHere = mi.uses.filter { lastUses[it] == (block to mi.irLabelIndex) }
      // Deallocate registers of values that die at this label
      for (value in dyingHere) {
        val color = coloring[value] ?: continue
        assigned -= color
      }
      val defined = mi.defs
          .asSequence()
          .filter { it !in colored }
          .filter { it !is PhysicalRegister || coloring[it] == null }
          .filter { it !is StackVariable && it !is MemoryLocation }
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
  val intermediate = AllocationResult(spilledInstrs, allocations, registerUseMap)
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
