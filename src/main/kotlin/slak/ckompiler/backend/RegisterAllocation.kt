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
 * @see rewriteBlockUses
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
 * Rebuild SSA for some affected variables. See reference for some notations.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: 4.2.1.2, Algorithm 4.1
 */
fun CFG.ssaReconstruction(
    toRewrite: Set<AllocatableValue>,
    instrMap: InstructionMap,
    miDefUseChains: DefUseChains
): InstructionMap {
  val newInstrMap = instrMap.toMutableMap()
  val varsInitial = toRewrite.filterIsInstance<Variable>()
  val vars = varsInitial.toMutableSet()
  val ids = vars.mapTo(mutableSetOf()) { it.id }
  val blocks = vars.map { definitions.getValue(it).first }.toMutableSet()
  val f = blocks.iteratedDominanceFrontier(ids)

  fun findDef(label: Label, variable: Variable): Variable {
    val (block, index) = label

    for (u in traverseDominatorTree(doms, block)) {
      // If this is a phi, we need to skip the first block
      if (u == block && index == DEFINED_IN_PHI) continue
      // Ignore things after our given label (including the label)
      for (mi in newInstrMap.getValue(u).take(if (u == block) index else Int.MAX_VALUE).asReversed()) {
        val maybeDefined = mi.defs.filterIsInstance<Variable>().firstOrNull { it.id == variable.id }
        if (maybeDefined != null) {
          return maybeDefined
        }
      }
      val maybeDefinedPhi = u.phi.firstOrNull { it.variable.id == variable.id }
      if (maybeDefinedPhi != null) {
        return maybeDefinedPhi.variable
      }
      if (u in f) {
        u.phi += PhiInstruction(variable, u.preds.associateWith { findDef(Label(it, Int.MAX_VALUE), variable) })
      }
    }
    logger.throwICE("Unreachable: no definition for $variable was found up the tree from $label")
  }

  for (x in varsInitial) {
    for (label in miDefUseChains.getValue(x)) {
      val (block, index) = label
      val newVersion = findDef(label, x)
      // Already wired correctly
      if (x == newVersion) continue
      if (index == DEFINED_IN_PHI) {
        val phiInstr = block.phi.first { it.variable.id == x.id }
        block.phi -= phiInstr
        val (pred, _) = phiInstr.incoming.entries.first { it.value == x }
        pred.phi += phiInstr.copy(incoming = phiInstr.incoming + (pred to newVersion))
      } else {
        val instrs = newInstrMap.getValue(block)
        val newMI = instrs[index].rewriteBy(mapOf(x to newVersion))
        newInstrMap[block] = instrs.take(index) + newMI + instrs.drop(index + 1)
      }
    }
  }
  return newInstrMap
}

/**
 * This function rewrites a block to accommodate a new version of some variables. The copies are assumed to already be
 * in place or will be inserted later.
 *
 * [VirtualRegister]s are easy to deal with: they cannot escape the block, so they can't introduce new φs.
 * That means we can just rewire the uses, and still be in SSA form, so no SSA reconstruction needed.
 *
 * [Variable]s are more problematic, since a new version of a variable might create a new φ, which is problematic.
 * Consider a diamond [CFG]:
 * ```
 *     A
 *   /  \
 *  B    C
 *   \  /
 *    D
 * ```
 * With the variable defined at the top in A (version 1, any key of [rewriteMap]), and used in all other 3 blocks.
 * Now if we need to rewrite its uses in say, block C, we'll replace its uses with version 2 (associated [rewriteMap]
 * value). But now we have a problem: version 1 is live-out in block B, and version 2 is live-out in block C, so block D
 * needs a φ instruction, one which was not there previously (there was only one version).
 *
 * If the φ was already there, it would have been ok: just update the incoming value from this block with the new
 * version. When we insert a new φ, we still know the incoming from our block (it's the new version), but we don't
 * know the incoming from the other blocks... and those other blocks might not know which version is live-out in them.
 * That means we have to rebuild the SSA (which is not exactly a cheap operation). This function does not rebuild it,
 * see [ssaReconstruction] for that.
 *
 * @return the instructions from [instructions], after the index [atIdx], rewritten
 *
 * @see rewriteBy
 * @see prepareForColoring
 */
private fun rewriteBlockUses(
    atIdx: LabelIndex,
    rewriteMap: Map<AllocatableValue, AllocatableValue>,
    instructions: List<MachineInstruction>
): List<MachineInstruction> {
  return instructions.drop(atIdx + 1).map { it.rewriteBy(rewriteMap) }
}

/**
 * This function incrementally updates a "last uses" map, by updating all the last use indices that were pushed due to a
 * copy (or more) being inserted.
 *
 * @see prepareForColoring
 */
private fun rewriteLastUses(
    newLastUses: MutableMap<IRValue, Label>,
    block: BasicBlock,
    copyIndex: LabelIndex,
    copiesInserted: Int = 1
) {
  for ((modifiedValue, oldDeath) in newLastUses.filterValues { it.first == block && it.second >= copyIndex }) {
    val nextIdx = if (oldDeath.second == Int.MAX_VALUE) Int.MAX_VALUE else oldDeath.second + copiesInserted
    newLastUses[modifiedValue] = Label(block, nextIdx)
  }
}

/**
 * Creates a new virtual register, or a new version of a variable.
 */
private fun CFG.makeCopiedValue(value: AllocatableValue): AllocatableValue {
  if (value.isUndefined) return value
  return if (value is Variable) {
    val oldVersion = latestVersions.getValue(value.id)
    latestVersions[value.id] = oldVersion + 1
    value.copy(oldVersion + 1)
  } else {
    VirtualRegister(registerIds(), value.type)
  }
}

/**
 * Insert a copy.
 *
 * Updates the [lastUses], the instructions and the [CFG]'s internal state to account for that copy.
 *
 * [value] is a constrained argument of [constrainedInstr].
 */
private fun TargetFunGenerator.rewriteValue(
    lastUses: MutableMap<IRValue, Label>,
    value: AllocatableValue,
    block: BasicBlock,
    constrainedInstr: MachineInstruction,
    copyIndex: LabelIndex,
    instructions: List<MachineInstruction>
): List<MachineInstruction> {
  // No point in inserting a copy for something that's never used
  if (value !in lastUses) return instructions
  val copiedValue = cfg.makeCopiedValue(value)
  if (copiedValue is Variable) {
    cfg.definitions[copiedValue] = Label(block, copyIndex)
  }
  val copyInstr = createIRCopy(copiedValue, value)
  copyInstr.irLabelIndex = constrainedInstr.irLabelIndex
  val newInstrAfter = rewriteBlockUses(copyIndex, mapOf(value to copiedValue), instructions)
  val newInstr = instructions.take(copyIndex) + copyInstr + constrainedInstr + newInstrAfter
  // The copy will die at the index where the original died
  lastUses[copiedValue] = lastUses.getValue(value)
  rewriteLastUses(lastUses, block, copyIndex)
  // This is the case where the value is an undefined dummy
  // We want to keep the original and correct last use, instead of destroying it here
  if (value === copiedValue) return newInstr
  // The value is a constrained argument: it is still used at the constrained MI, after the copy
  // We know that is the last use, because all the ones afterwards were just rewritten
  lastUses[value] = Label(block, copyIndex + 1)
  return newInstr
}

/**
 * Splits the live-range of each value in [splitFor], by creating copies and inserting them [atIndex].
 *
 * @see prepareForColoring
 */
private fun TargetFunGenerator.splitLiveRanges(
    splitFor: Set<AllocatableValue>,
    atIndex: LabelIndex,
    block: BasicBlock,
    instructions: List<MachineInstruction>,
    lastUses: MutableMap<IRValue, Label>
): List<MachineInstruction> {
  // No point in making a copy for something that's never used
  val actualValues = splitFor.filter { it in lastUses }
  val rewriteMap = actualValues.associateWith(cfg::makeCopiedValue)
  val phi = ParallelCopyTemplate.createCopy(rewriteMap)
  phi.irLabelIndex = atIndex
  for (newVar in rewriteMap.values.filterIsInstance<Variable>()) {
    cfg.definitions[newVar] = Label(block, atIndex)
  }

  val rewrittenAfter = rewriteBlockUses(atIndex, rewriteMap, instructions)

  // Splice the copy in the instructions, and rewrite the constrained instr too
  val withCopy = instructions.take(atIndex) + phi + instructions[atIndex].rewriteBy(rewriteMap) + rewrittenAfter

  // Update last uses
  for ((toRewrite, rewritten) in rewriteMap) {
    // Each replacement will die at the index where the original died
    lastUses[rewritten] = lastUses.getValue(toRewrite)
  }
  rewriteLastUses(lastUses, block, atIndex)
  for (value in actualValues) {
    // The constrained instr was rewritten, and the value cannot have been used there, so it dies when it is copied
    lastUses[value] = Label(block, atIndex)
  }

  return withCopy
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
private fun TargetFunGenerator.prepareForColoring(
    instrMap: InstructionMap,
    lastUses: Map<IRValue, Label>
): Pair<InstructionMap, Map<IRValue, Label>> {
  var newMap = instrMap.toMutableMap()
  val newLastUses = lastUses.toMutableMap()
  for (block in cfg.domTreePreorder) {
    // Track which variables are alive at any point in this block
    // Initialize with the block live-ins, and with φ vars
    val alive: MutableSet<AllocatableValue> = cfg.liveIns.getValue(block).toMutableSet()
    alive += block.phi.map { it.variable }

    fun updateAlive(mi: MachineInstruction, index: LabelIndex) {
      val dyingHere = mi.uses.filter { newLastUses[it] == Label(block, index) }.filterIsInstance<AllocatableValue>()
      alive += mi.defs.filter { it in newLastUses }.filterIsInstance<AllocatableValue>()
      alive -= dyingHere
    }

    var index = 0
    while (index < newMap.getValue(block).size) {
      val mi = newMap.getValue(block)[index]
      if (!mi.isConstrained) {
        updateAlive(mi, index)
        index++
        continue
      }

      for ((value, target) in mi.constrainedArgs) {
        // If it doesn't die after this instruction, it's not live-through, so leave it alone
        if (newLastUses.getValue(value).second <= index) continue
        // Result constrained to same register as live-though constrained variable: copy must be made
        if (target in mi.constrainedRes.map { it.target }) {
          newMap[block] = rewriteValue(newLastUses, value, block, mi, index, newMap.getValue(block))
          newMap = cfg.ssaReconstruction(alive, newMap, findDefUseChains(cfg, newMap)).toMutableMap()
          updateAlive(newMap.getValue(block)[index], index)
          index++
        }
      }
      check(newMap.getValue(block)[index] == mi) {
        "Sanity check failed: the constrained arg copy insertion above did not correctly update the current index"
      }

      newMap[block] = splitLiveRanges(alive, index, block, newMap.getValue(block), newLastUses)
      newMap = cfg.ssaReconstruction(alive, newMap, findDefUseChains(cfg, newMap)).toMutableMap()
      // The parallel copy needs to have updateAlive run on it, before skipping it
      updateAlive(newMap.getValue(block)[index], index)
      index++
      // Same with the original constrained label
      updateAlive(newMap.getValue(block)[index], index)
      index++
    }
  }
  return Pair(newMap, newLastUses)
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
      for (it in mi.uses.filterIsInstance<AllocatableValue>()) {
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

private fun findDefUseChains(cfg: CFG, instrMap: InstructionMap): DefUseChains {
  val allUses = mutableMapOf<Variable, MutableList<Label>>()
  fun newUse(variable: Variable, label: Label) {
    allUses.putIfAbsent(variable, mutableListOf())
    allUses.getValue(variable) += label
  }

  for (block in cfg.domTreePreorder) {
    for (phi in block.phi) {
      for (variable in phi.incoming.values) {
        newUse(variable, Label(block, DEFINED_IN_PHI))
      }
    }
    for ((index, mi) in instrMap.getValue(block).withIndex()) {
      for (variable in mi.filterOperands { _, use -> use == VariableUse.USE }.filterIsInstance<Variable>()) {
        newUse(variable, Label(block, index))
      }
    }
  }
  return allUses
}

private data class RegisterAllocationContext(
    val generator: TargetFunGenerator,
    val instrMap: InstructionMap,
    val lastUses: Map<IRValue, Label>
) {
  /** List of visited nodes for DFS. */
  val visited = mutableSetOf<BasicBlock>()

  /** The allocation itself. */
  val coloring = mutableMapOf<IRValue, MachineRegister>()

  /** The value of [assigned] at the end of each [allocBlock]. */
  val registerUseMap = mutableMapOf<BasicBlock, List<MachineRegister>>()

  /** The registers in use at a moment in time. Should be only used internally by the allocator. */
  val assigned = mutableSetOf<MachineRegister>()

  /** Store the copy sequences to replace each label with a parallel copy. */
  val parallelCopies = mutableMapOf<Label, List<MachineInstruction>>()

  val target get() = generator.target

  /** Begin a DFS from the starting block. */
  fun doAllocation() {
    require(visited.isEmpty() && coloring.isEmpty())
    allocBlock(generator.cfg.startBlock)
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
private fun RegisterAllocationContext.constrainedColoring(mi: MachineInstruction, label: Label) {
  val arg = mi.uses.filterIsInstance<AllocatableValue>()
  val t = arg
      .filter { lastUses[it]?.first == label.first && lastUses.getValue(it).second > label.second }
      .toMutableSet()
  val a = (arg - t).toMutableSet()
  val d = mi.defs.filterIsInstance<AllocatableValue>().toMutableSet()
  val cA = mutableSetOf<MachineRegister>()
  val cD = mutableSetOf<MachineRegister>()

  for ((value, target) in mi.constrainedArgs) {
    cA += target
    a -= value
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
private fun RegisterAllocationContext.unassignDyingAt(label: Label, mi: MachineInstruction) {
  val dyingHere = mi.uses.filter { lastUses[it] == label }
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
private fun RegisterAllocationContext.allocConstrainedMI(label: Label, mi: MachineInstruction) {
  require(mi.isConstrained)

  val (block, index) = label
  require(index > 0) { "No parallel copy found for constrained instruction" }
  val phi = instrMap.getValue(block)[index - 1]

  val phiTemplate = phi.template
  require(phiTemplate is ParallelCopyTemplate) { "Instruction preceding constrained is not a parallel copy" }
  val phiMap = phiTemplate.values

  constrainedColoring(mi, label)
  unassignDyingAt(label, mi)
  // Correctly color copies inserted by the live range split
  // See last paragraph of section 4.6.4
  val copies = phiMap.values
  val usedAtL = mi.uses.filterIsInstance<AllocatableValue>()
  val definedAtL = mi.defs.filterIsInstance<AllocatableValue>()
  val colorsAtL = (definedAtL + usedAtL).mapTo(mutableSetOf()) { coloring.getValue(it) }
  for (copy in copies - usedAtL) {
    val oldColor = checkNotNull(coloring[copy])
    assigned -= oldColor
    val color = target.selectRegisterBlacklist(assigned + colorsAtL, copy)
    coloring[copy] = color
    assigned += color
  }
  // Create the copy sequence to replace the parallel copy
  parallelCopies[Label(block, index - 1)] = generator.replaceParallel(phi, coloring, assigned)

  for (def in definedAtL.filter { it in lastUses }) {
    val color = checkNotNull(coloring[def]) { "Value defined at constrained label not colored: $def" }
    assigned += color
  }
}

/**
 * Register allocation for one [block]. Recursive DFS case.
 */
private fun RegisterAllocationContext.allocBlock(block: BasicBlock) {
  if (block in visited) return
  visited += block

  for (x in generator.cfg.liveIns.getValue(block)) {
    // If the live-in wasn't colored, and is null, that's a bug
    assigned += requireNotNull(coloring[x]) { "Live-in not colored: $x in $block" }
  }
  // Add the parameter register constraints to assigned
  if (block == generator.cfg.startBlock) {
    assigned += generator.parameterMap.values.mapNotNull { (it as? PhysicalRegister)?.reg }
  }
  // Remove incoming φ-uses from assigned
  for (used in block.phi.flatMap { it.incoming.values }) {
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
  for ((index, mi) in instrMap.getValue(block).withIndex()) {
    // Also add stack variables to coloring
    coloring += mi.operands
        .filter { it is StackVariable || it is MemoryLocation }
        .mapNotNull { if (it is MemoryLocation) it.ptr as? StackVariable else it }
        .associateWith { StackSlot(it as StackVariable, target.machineTargetData) }
    if (mi.isConstrained) {
      allocConstrainedMI(Label(block, index), mi)
      continue
    }
    // Handle unconstrained instructions
    unassignDyingAt(Label(block, index), mi)
    val defined = mi.defs
        .asSequence()
        .filter { it !in colored }
        .filterIsInstance<AllocatableValue>()
    // Allocate registers for values defined at this label
    for (definition in defined) {
      val color = target.selectRegisterBlacklist(assigned, definition)
      if (coloring[definition] != null) {
        logger.throwICE("Coloring the same definition twice") {
          "def: $definition, old color: ${coloring[definition]}, new color: $color"
        }
      }
      coloring[definition] = color
      colored += definition
      // Don't mark the register as assigned unless this value has at least one use, that is, it exists in lastUses
      if (definition in lastUses) assigned += color
    }
  }
  registerUseMap[block] = assigned.toList()
  assigned.clear()
  // Recursive DFS on dominator tree
  for (c in generator.cfg.nodes.filter { generator.cfg.doms[it] == block }.sortedBy { it.height }) {
    allocBlock(c)
  }
}

/**
 * Return a modified [InstructionMap] with all [ParallelCopyTemplate] MIs removed.
 */
private fun RegisterAllocationContext.replaceParallelInstructions(): Pair<InstructionMap, Map<IRValue, Label>> {
  val newInstrs = instrMap.toMutableMap()
  val newLastUses = lastUses.toMutableMap()
  for ((block, blockPositions) in parallelCopies.entries.groupBy { it.key.first }) {
    var intraBlockOffset = 0
    for ((label, copySequence) in blockPositions.sortedBy { it.key.second }) {
      val index = label.second + intraBlockOffset
      val existing = newInstrs.getValue(block)
      // +1 to also drop the parallel copy itself
      newInstrs[block] = existing.take(index) + copySequence + existing.drop(index + 1)
      rewriteLastUses(newLastUses, block, index, copySequence.size)
      // Added N copy MIs, removed the parallel MI, so the indices should offset by N - 1
      intraBlockOffset += copySequence.size - 1
    }
  }
  return newInstrs to newLastUses
}

/**
 * Performs target-independent spilling and register allocation.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Algorithm 4.2
 * @param debugNoReplaceParallel only for debugging: if true, [replaceParallelInstructions] will not be called
 * @see spiller
 */
fun TargetFunGenerator.regAlloc(instrMap: InstructionMap, debugNoReplaceParallel: Boolean = false): AllocationResult {
  val initialLastUses = findLastUses(cfg, instrMap)
  val spilledInstrs = spiller(instrMap, initialLastUses)
  val spillLastUses = findLastUses(cfg, spilledInstrs)
  val (finalInstrMap, lastUses) = prepareForColoring(spilledInstrs, spillLastUses)
  // FIXME: coalescing
  val ctx = RegisterAllocationContext(this, finalInstrMap, lastUses)
  ctx.doAllocation()
  val (withoutParallelInstr, withoutParallelUses) = if (debugNoReplaceParallel) {
    finalInstrMap to lastUses
  } else {
    ctx.replaceParallelInstructions()
  }
  val allocations = ctx.coloring.filterKeys { it !is ConstantValue }
  val intermediate = AllocationResult(withoutParallelInstr, allocations, ctx.registerUseMap, withoutParallelUses)
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
  val newRegUseMap = allocationResult.registerUseMap.toMutableMap()
  for (block in cfg.domTreePreorder.filter { it.phi.isNotEmpty() }) {
    // Make an explicit copy here to avoid ConcurrentModificationException, since splitting edges changes the preds
    for (pred in block.preds.toList()) {
      val copies = removeOnePhi(allocationResult.copy(registerUseMap = newRegUseMap), block, pred)
      if (copies.isEmpty()) continue
      val insertHere = splitCriticalForCopies(newInstructionMap, pred, block)
      newRegUseMap[insertHere] = newRegUseMap.getValue(block)
      val instructions = newInstructionMap.getValue(insertHere)
      newInstructionMap[insertHere] = insertPhiCopies(instructions, copies)
    }
  }
  return allocationResult.copy(partial = newInstructionMap, registerUseMap = newRegUseMap)
}

/**
 * To avoid the "lost copies" problem, critical edges must be split, and the copies inserted
 * there.
 *
 * This split messes with the internal state of the CFG.
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
  val insertedBlock = cfg.newBlock()
  // Rewire terminator jumps
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
  // Update preds
  succ.preds -= block
  succ.preds += insertedBlock
  // Update successor's φs, for which the old block was incoming (now insertedBlock should be in incoming)
  val rewritePhis = succ.phi.filter { block in it.incoming.keys }
  val rewritten = rewritePhis.map {
    val value = it.incoming.getValue(block)
    it.copy(incoming = it.incoming - block + (insertedBlock to value))
  }
  succ.phi -= rewritePhis
  succ.phi += rewritten
  // Create the MIs for the inserted block (just an unconditional jump)
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
