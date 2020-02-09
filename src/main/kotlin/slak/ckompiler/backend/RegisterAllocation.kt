package slak.ckompiler.backend

import org.apache.logging.log4j.LogManager
import slak.ckompiler.analysis.*
import slak.ckompiler.throwICE

private val logger = LogManager.getLogger()

typealias ValueIndex = Int
typealias AdjLists = List<List<ValueIndex>>
typealias AllocationMap = Map<IRValue, MachineRegister>

data class AllocationResult(
    val partial: InstructionMap,
    val allocations: AllocationMap,
    val stackSlots: List<StackSlot>
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
  // FIXME: fit SSA destruction somewhere in here
  val visited = mutableSetOf<BasicBlock>()
  val coloring = mutableMapOf<IRValue, MachineRegister?>()
  fun allocBlock(block: BasicBlock) {
    if (block in visited) return
    visited += block
    val assigned = mutableListOf<MachineRegister>()
    for (x in cfg.liveInFor(block)) {
      // If the live-in wasn't colored, and is null, that's a bug
      assigned += coloring[x]!!
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
    // Recursive DFS on dominator tree
    for (c in cfg.nodes.filter { cfg.doms[it] == block }.sortedBy { it.height }) {
      allocBlock(c)
    }
  }
  allocBlock(cfg.startBlock)
  val allocations = coloring
      .filterKeys { it !is MemoryReference && it !is ConstantValue }
      .mapValues { it.value!! }
  return AllocationResult(instrMap, allocations, stackSlots)
}
