package slak.ckompiler.backend

import slak.ckompiler.analysis.AllocatableValue
import slak.ckompiler.analysis.PhysicalRegister

/**
 * @see walkGraphAllocs
 */
enum class ViolationType {
  /** A hard violation is a definite error in the allocation. */
  HARD,
  /** A soft violation might have been caused by two-address code, and might still be a correct allocation. */
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
    violationHandler: (MachineRegister, InstrLabel, ViolationType) -> Boolean
): Boolean {
  var hasFailed = false
  val allocated = allocations.keys.filterIsInstance<AllocatableValue>()
  for (blockId in graph.blocks) {
    val alive = mutableListOf<MachineRegister>()
    alive += graph.liveInsOf(blockId).map { allocations.getValue(it) }
    val usedByPhi = graph[blockId].phiUses.filter { !it.isUndefined }
    alive -= usedByPhi.map { allocations.getValue(it) }
    for ((index, mi) in graph[blockId].withIndex()) {
      val regsDefinedHere = mi.defs.intersect(allocated)
          .filter { graph.isUsed(it as AllocatableValue) && !it.isUndefined }
          .map { allocations.getValue(it) } +
          mi.defs.filterIsInstance<PhysicalRegister>().map { it.reg }
      val regsDyingHere = mi.uses.intersect(allocated)
          .filter { graph.isDeadAfter(it as AllocatableValue, InstrLabel(blockId, index)) }
          .map { allocations.getValue(it) } +
          mi.uses.filterIsInstance<PhysicalRegister>().map { it.reg }
      val useDefRegs = mi
          .filterOperands { value, variableUse -> variableUse == VariableUse.DEF_USE && value is AllocatableValue }
          .map { allocations.getValue(it) }
      alive -= regsDyingHere
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
