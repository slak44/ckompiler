package slak.ckompiler.backend

import org.apache.logging.log4j.LogManager
import slak.ckompiler.analysis.*
import slak.ckompiler.throwICE

private val logger = LogManager.getLogger()

typealias ValueIndex = Int
typealias AdjLists = List<List<ValueIndex>>

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
    val stackSlots: List<StackSlot>,
    val registerUseMap: RegisterUseMap
)

private fun MachineTarget.matchValueToRegister(
    value: IRValue,
    forbiddenNeigh: List<MachineRegister>
): MachineRegister? {
  if (value is MemoryReference) return StackSlot(value, machineTargetData)
  val validClass = registerClassOf(value.type)
  val validSize = machineTargetData.sizeOf(value.type)
  return (registers - forbidden - forbiddenNeigh).firstOrNull { candidate ->
    candidate.valueClass == validClass &&
        (candidate.sizeBytes == validSize || validSize in candidate.aliases.map { it.second })
  }
}

/**
 * Performs target-independent spilling and register allocation.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Algorithm 4.2
 */
fun TargetFunGenerator.regAlloc(instrMap: InstructionMap): AllocationResult {
  val stackSlots = mutableListOf<StackSlot>()
  val lastUses = mutableMapOf<IRValue, Pair<BasicBlock, Int>>()
  lastUses.putAll(cfg.defUseChains.mapValues { it.value.last() })
  // FIXME: see if this can't be done in the variable renamer
  cfg.domTreePreorder.forEach { block ->
    for (mi in instrMap.getValue(block)) {
      mi.uses
          .filter { it is VirtualRegister || it is PhysicalRegister }
          .forEach { lastUses[it] = block to mi.irLabelIndex }
    }
  }
  // FIXME: spilling here
  // FIXME: coalescing
  // List of visited nodes for DFS
  val visited = mutableSetOf<BasicBlock>()
  val coloring = mutableMapOf<IRValue, MachineRegister>()
  val registerUseMap = mutableMapOf<BasicBlock, List<MachineRegister>>()
  fun allocBlock(block: BasicBlock) {
    if (block in visited) return
    visited += block
    val assigned = mutableListOf<MachineRegister>()
    for (x in cfg.liveInFor(block)) {
      // If the live-in wasn't colored, and is null, that's a bug
      assigned += requireNotNull(coloring[x]) { "Live-in not colored" }
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
      val color = target.matchValueToRegister(variable, assigned)!!
      coloring[variable] = color
      assigned += color
    }
    val colored = mutableSetOf<IRValue>()
    for (mi in instrMap.getValue(block)) {
      val dyingHere = mi.uses.filter { lastUses.getValue(it) == (block to mi.irLabelIndex) }
      // Deallocate registers of values that die at this label
      for (value in dyingHere) {
        val color = coloring[value] ?: continue
        assigned -= color
      }
      val defined = mi.defs
          .filter { it !in colored }
          .filter { it !is PhysicalRegister || coloring[it] == null }
      // Allocate registers for values defined at this label
      for (definition in defined) {
        val color = target.matchValueToRegister(definition, assigned)!!
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
  val allocations = coloring.filterKeys { it !is MemoryReference && it !is ConstantValue }
  val intermediate = AllocationResult(instrMap, allocations, stackSlots, registerUseMap)
  return removePhi(intermediate)
}

/**
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Section 4.4
 * @return modified [AllocationResult]
 */
private fun TargetFunGenerator.removePhi(allocationResult: AllocationResult): AllocationResult {
  val newInstructionMap = allocationResult.partial.toMutableMap()
  for (block in cfg.domTreePreorder.filter { it.phi.isNotEmpty() }) {
    for (pred in block.preds) {
      val copies = removeOnePhi(allocationResult, block, pred)
      newInstructionMap[pred] = allocationResult.partial.getValue(pred) + copies
    }
  }
  return allocationResult.copy(partial = newInstructionMap)
}

/**
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
  val (_, coloring, _, registerUseMap) = allocationResult
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
  // Step 4: T is either empty or it just contains cycles
  do {
    for (r1 in adjacency.keys) {
      // Step 4a: self-loops generate nothing (obviously)
      adjacency.getValue(r1) -= r1
      if (adjacency.getValue(r1).isEmpty()) continue
      if (free.isNotEmpty()) {
        // Step 4b: F is not empty
        // FIXME: deal with the case where there are free regs, but of the wrong class
        var rLast = free.first { it.valueClass == r1.valueClass }
        var nextInCycle: MachineRegister = r1
        do {
          if (adjacency.getValue(nextInCycle).size > 1) TODO("deal with multiple cycles")
          copies += createRegisterCopy(rLast, nextInCycle)
          rLast = nextInCycle
          nextInCycle = adjacency.getValue(nextInCycle).single()
          adjacency.getValue(nextInCycle).clear()
        } while (nextInCycle != r1)
      } else {
        // Step 4c: F is empty
        TODO("implement this")
      }
    }
    // While T is not empty
  } while (adjacency.values.any { it.isNotEmpty() })
  return copies
}
