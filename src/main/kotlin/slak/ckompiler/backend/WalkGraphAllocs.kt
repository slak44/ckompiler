package slak.ckompiler.backend

import slak.ckompiler.analysis.AllocatableValue
import slak.ckompiler.analysis.PhysicalRegister

/**
 * @see walkGraphAllocs
 */
enum class ViolationType {
  /** A hard violation is a definite error in the allocation. */
  HARD,

  /**
   * A soft violation is an instruction that both uses and defines the same register.
   * This is common in two-address code, but it happens in three-address code when the result is equal to an operand.
   * It is usually not an error for this to happen.
   */
  SOFT
}

/**
 * Find all wrongly colored registers. Notify the caller via the [violationHandler] callback for each violation.
 *
 * Call this function _before_ any destructive operations on the block instructions. That's before stuff like
 * [replaceParallelInstructions].
 *
 * @return true if any [violationHandler] call returned true
 * @see ViolationType
 */
inline fun AllocationResult.walkGraphAllocs(
    violationHandler: (MachineRegister, InstrLabel, ViolationType) -> Boolean,
): Boolean {
  var hasFailed = false
  val allocated = allocations.keys.filterIsInstance<AllocatableValue>()
  for (blockId in graph.blocks) {
    val alive = mutableListOf<MachineRegister>()
    alive += graph.liveness.liveInsOf(blockId).map { allocations.getValue(it) }
    val usedByPhi = graph[blockId].phiUses.filter { !it.isUndefined }
    alive -= usedByPhi.map { allocations.getValue(it) }.toSet()
    for ((index, mi) in graph[blockId].withIndex()) {
      val regsDefinedHere = mi.defs.intersect(allocated.toSet())
          .filter { graph.liveness.isUsed(it as AllocatableValue) && !it.isUndefined }
          .map { allocations.getValue(it) } +
          mi.defs.filterIsInstance<PhysicalRegister>().map { it.reg }
      val regsDyingHere = graph.dyingAt(InstrLabel(blockId, index), mi)
          .intersect(allocated.toSet())
          .filter { !it.isUndefined }
          .map { allocations.getValue(it) } +
          mi.uses.filterIsInstance<PhysicalRegister>().map { it.reg }
      val defUseValues = mi.filterOperands(listOf(VariableUse.DEF_USE)) + mi.defs.intersect(mi.uses.toSet())
      val useDefRegs = defUseValues
          .filterIsInstance<AllocatableValue>()
          .map { allocations.getValue(it) }
          .toSet()
      alive -= regsDyingHere.toSet()
      for (definedHere in regsDefinedHere) {
        if (definedHere in alive) {
          val violationType = if (definedHere !in useDefRegs) ViolationType.HARD else ViolationType.SOFT
          hasFailed = hasFailed || violationHandler(definedHere, InstrLabel(blockId, index), violationType)
        }
      }
      alive += regsDefinedHere
    }
  }
  return hasFailed
}
