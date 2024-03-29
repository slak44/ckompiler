package slak.ckompiler.backend

import slak.ckompiler.AtomicId
import slak.ckompiler.analysis.*

typealias Location = Pair<AllocatableValue, InstrLabel>
typealias BlockLocations = List<Location>

data class MinResult(val spills: BlockLocations, val reloads: BlockLocations, val phiDefSpills: List<Variable>)

typealias WBlockMap = Map<MachineRegisterClass, MutableSet<AllocatableValue>>

private fun AnyFunGenerator.insertPhiSpill(
    phiDef: Variable,
    blockId: AtomicId,
    stackValue: StackValue?,
): StackValue {
  val targetStackValue = stackValue ?: StackValue(phiDef)
  val incoming = graph[blockId].phi.getValue(phiDef)
  graph[blockId].phi -= phiDef
  graph[blockId].phi[DerefStackValue(targetStackValue)] = incoming
  return targetStackValue
}

private fun AnyFunGenerator.insertSpill(
    value: AllocatableValue,
    location: InstrLabel,
    stackValue: StackValue?,
): StackValue {
  val (blockId, idx) = location
  val targetStackValue = stackValue ?: StackValue(value)
  val versionedValue = DerefStackValue(targetStackValue)
  val copy = createIRCopy(versionedValue, value)
  graph[blockId].add(idx, copy)
  if (value is VersionedValue) {
    graph.liveness.addUse(value, location)
  }
  return targetStackValue
}

private fun AnyFunGenerator.insertReload(
    original: AllocatableValue,
    toReload: StackValue,
    location: InstrLabel,
): AllocatableValue {
  val (blockId, idx) = location
  val copyTarget = graph.liveness.createCopyOf(original, graph[blockId])
  val derefValue = DerefStackValue(toReload)
  val copy = createIRCopy(copyTarget, derefValue)
  graph.liveness.addUse(derefValue, location)
  graph[blockId].add(idx, copy)
  return copyTarget
}

private fun AnyFunGenerator.nextUse(location: InstrLabel, v: AllocatableValue): Int {
  val (blockId, index) = location
  val nextUseIdx = graph[blockId].drop(index).indexOfFirst { mi -> v in mi.uses }
  if (nextUseIdx < 0) {
    return Int.MAX_VALUE
  }
  return nextUseIdx
}

/**
 * Replaces [spilledValues] from a parallel copy, because there is no reason to split their live range if they are spilled.
 * The function then replaces those variables from where they are used with the pre-parallel version, and removes this value from the
 * parallel copy. Also, update related liveness data.
 *
 * When the uses will be reached by the spiller, reloads will be inserted as appropriate.
 *
 * The rest of the spiller relies on the code being in SSA form, and accurate liveness data. Since this operation just reverts a live range
 * split, it maintains the SSA property.
 */
private fun AnyFunGenerator.removeSpillsFromParallel(
    parallelLocation: InstrLabel,
    parallel: MachineInstruction,
    spilledValues: Set<AllocatableValue>,
): MachineInstruction {
  require(parallel.template is ParallelCopyTemplate)

  val rewriteMap = spilledValues.associateBy { parallel.template.values.getValue(it) }

  // The extra copies between the parallel and the constrained instruction are not recorded in the def use chains, so search them manually
  val extraCopyLabelsToRewrite = mutableListOf<InstrLabel>()
  val (blockId, parallelIndex) = parallelLocation
  val constrainedOffset = graph[blockId].subList(parallelIndex + 1, graph[blockId].size).indexOfFirst { it.isConstrained }
  check(constrainedOffset != -1) { "Parallel copy must have attached constrained instruction" }
  for (extraCopiesIndex in parallelIndex + 1 until parallelIndex + 1 + constrainedOffset) {
    val mi = graph[blockId][extraCopiesIndex]
    val extraUseOfSpilled = mi.uses.intersect(rewriteMap.keys)
    extraCopyLabelsToRewrite += extraUseOfSpilled.map { blockId to extraCopiesIndex }
  }

  val labelsToRewrite = rewriteMap.keys
      .filterIsInstance<VersionedValue>()
      .flatMapTo(mutableSetOf()) { graph.liveness.usesOf(it) }
  for ((useBlock, useIndex) in labelsToRewrite + extraCopyLabelsToRewrite) {
    if (useIndex != DEFINED_IN_PHI) {
      graph[useBlock][useIndex] = graph[useBlock][useIndex].rewriteBy(rewriteMap)
    } else {
      for ((_, incoming) in graph[useBlock].phi) {
        for ((pred, versionFromPred) in incoming) {
          val rewritten = rewriteMap[versionFromPred]
          if (rewritten != null) {
            incoming[pred] = rewritten as VersionedValue
          }
        }
      }
    }
  }

  for ((toPurge, replacement) in rewriteMap) {
    when (toPurge) {
      is VersionedValue -> {
        graph.liveness.transferUsesToCopy(toPurge, replacement as VersionedValue)
        graph.liveness.removeUse(replacement, parallelLocation)
        if (toPurge is Variable) {
          graph.liveness.variableDefs -= toPurge
        }
      }
      is VirtualRegister -> {
        graph.liveness.virtualDeaths[replacement as VirtualRegister] = graph.liveness.virtualDeaths.getValue(toPurge)
        graph.liveness.virtualDeaths -= toPurge
      }
    }
  }

  val newCopyMap = parallel.template.values - spilledValues
  val newParallelCopyOperands = newCopyMap.keys.toList() + newCopyMap.values

  return parallel.copy(template = parallel.template.copy(values = newCopyMap), operands = newParallelCopyOperands)
}

/**
 * Apply the MIN algorithm on a block. While this implementation is based on the reference below, it does differ by
 * working on multiple register classes at the same time (ie int and float regs), and by the handling of constraints and φs.
 * It is also significantly less sophisticated (it doesn't consider loops separately).
 *
 * Register Spilling and Live-Range Splitting for SSA-Form Programs, Braun & Hack
 *
 * @param maxPressure max register pressure for each register class
 * @param allSpilled shared function-level set of spilled values, from all blocks
 */
private class BlockSpiller(
    val targetFunGenerator: AnyFunGenerator,
    val blockId: AtomicId,
    val maxPressure: Map<MachineRegisterClass, Int>,
    wBlockEntry: WBlockMap,
    sBlockEntry: Set<AllocatableValue>,
    val allSpilled: MutableSet<AllocatableValue>,
) {
  private val reloads = mutableListOf<Location>()
  private val spills = mutableListOf<Location>()

  private val phiDefSpills = mutableListOf<Variable>()

  val minResult get() = MinResult(spills, reloads, phiDefSpills)

  /**
   * The set of values that are in a register. Initially set to the values in a register at the start of the block.
   */
  private val w = wBlockEntry

  val wExit: Map<MachineRegisterClass, Set<AllocatableValue>> get() = w

  /**
   * The set of values that were already spilled once (and only once is enough because SSA).
   */
  private val s = sBlockEntry.toMutableSet()

  val sExit: Set<AllocatableValue> get() = s

  /**
   * Advance the MI iterator, updating [ParallelCopyTemplate]s if necessary.
   */
  private fun AnyFunGenerator.processNext(it: MutableListIterator<MachineInstruction>): MachineInstruction {
    val nextIndex = it.nextIndex()
    val next = it.next()

    if (next.template !is ParallelCopyTemplate) {
      return next
    }

    val toRemoveFromCopy = next.template.values.keys.intersect(allSpilled)
    if (toRemoveFromCopy.isEmpty()) {
      return next
    }

    val newParallel = removeSpillsFromParallel(blockId to nextIndex, next, toRemoveFromCopy)
    it.set(newParallel)

    // We need to do this here because we use the live sets in the spiller, and they need to be accurate
    graph.liveness.recomputeLiveSets()

    return newParallel
  }

  /**
   * If between the parallel copy and the constrained instruction, a constrained arg has both an extra copy, and a reload, we need to
   * insert yet another copy, because SSA reconstruction will overwrite the correct use at the constrained instruction with the result of
   * the extra copy, which defeats the point of the extra copy. This essentially tricks reconstruction and relies on coalescing to get
   * rid of the new copy.
   *
   * FIXME: insertSingleCopy duplication
   */
  private fun AnyFunGenerator.insertReloadCopies(
      it: MutableListIterator<MachineInstruction>,
      insn: MachineInstruction,
      insnIndex: Int,
      reloadedForExtraCopies: Set<AllocatableValue>,
  ): Boolean {
    require(insn.isConstrained)

    val block = graph[blockId]

    val constrainedArgsToCopy = insn.constrainedArgs.filter {
      !it.value.isUndefined && reloadedForExtraCopies.any { reload -> it.value.identityId == reload.identityId }
    }

    for ((arg, _) in constrainedArgsToCopy) {
      val copiedValue = graph.liveness.createCopyOf(arg, block)
      val copyInstr = createIRCopy(copiedValue, arg)
      copyInstr.irLabelIndex = block[insnIndex].irLabelIndex

      it.previous()
      it.add(copyInstr)

      // Rewrite constrained instruction
      block[insnIndex + 1] = block[insnIndex + 1].rewriteBy(mapOf(arg to copiedValue))
      // And vreg uses, if needed
      rewriteVregBlockUses(block, insnIndex + 2, mapOf(arg to copiedValue))

      when (arg) {
        is VersionedValue -> {
          // The value is a constrained argument: it is still used at the constrained MI, after the copy
          // We know that is the last use, because all the ones afterwards were just rewritten
          // The death is put at the current index, so the SSA reconstruction doesn't pick it up as alive
          // FIXME: do we need to correct this like in insertSingleCopy (probably yes)
          graph.liveness.addUse(arg, block.id, insnIndex)
        }
        is VirtualRegister -> graph.liveness.virtualDeaths[arg] = InstrLabel(block.id, insnIndex)
      }
    }

    return constrainedArgsToCopy.isNotEmpty()
  }

  /**
   * Register Spilling and Live-Range Splitting for SSA-Form Programs, Braun & Hack: Section 2, Algorithm 1
   */
  private fun AnyFunGenerator.limit(valueClass: MachineRegisterClass, insnIndex: Int, m: Int) {
    val actualM = m.coerceAtLeast(0)
    val label = InstrLabel(blockId, insnIndex)
    val wClass = w.getValue(valueClass).sortedBy { nextUse(label, it) }
    for (v in wClass.drop(actualM)) {
      if (v !in s && !graph.liveness.isDeadAfter(v, label)) {
        spills += Location(v, label)
        allSpilled += v
      }
      s -= v
    }
    val firstM = wClass.take(actualM)
    w.getValue(valueClass).clear()
    w.getValue(valueClass) += firstM
  }

  /**
   * Register Spilling and Live-Range Splitting for SSA-Form Programs, Braun & Hack: Section 2, Algorithm 1
   */
  private fun AnyFunGenerator.minAlgorithm(maxPressure: Map<MachineRegisterClass, Int>) {
    val block = graph[blockId]

    val phiDefs = block.phiDefs.groupBy { target.registerClassOf(it.type) }
    val phiUses = block.phiUses.groupBy { target.registerClassOf(it.type) }

    for ((valueClass, k) in maxPressure) {
      val wClass = w.getValue(valueClass)
      val phiDefsClass = phiDefs[valueClass] ?: emptyList()

      if (phiDefsClass.size >= k) {
        // wBlockEntry is already limited to k entries, so we are guaranteed to have at most k φ-uses in registers
        // This leaves us to deal with the excess φ-defs

        val existingPhiDefSpills = phiDefsClass
            .filterIsInstance<DerefStackValue>()
            .map { it.stackValue.referenceTo }
            .filterIsInstance<Variable>()
        allSpilled += existingPhiDefSpills

        val nonMemoryPhiDefs = phiDefsClass.filterIsInstance<Variable>()
        val defsToMoveToMemory = nonMemoryPhiDefs.size - k

        val sortedDefs = nonMemoryPhiDefs.sortedByDescending { nextUse(InstrLabel(blockId, 0), it) }

        val memoryDefs = sortedDefs.take(defsToMoveToMemory)
        val registerDefs = sortedDefs.drop(defsToMoveToMemory)

        phiDefSpills += memoryDefs
        allSpilled += memoryDefs
        wClass += registerDefs
      } else {
        // On a single edge, there are as many φ-defs as φ-uses, since only that predecessor's set of uses is live
        // As long as there are less φ-defs than k, the φ will fit in k registers, as the k uses will be replaced by k defs
        // So in this case we don't need to do anything in the spiller, simply update wClass with all the φ-defs which are in memory
        wClass += phiDefsClass
      }

      wClass -= (phiUses[valueClass] ?: emptyList()).toSet()
    }

    val it = block.listIterator()

    // FIXME: this looks insanely hacky. this should probably happen in processNext? or still at the constrained label?
    //    we should know all this data about extra copies beforehand
    //    this isInConstrainedArea should just be part of each MI, along with better api of extracting src/dest from extra copies
    var isInConstrainedArea = false
    val reloadedForExtraCopies = mutableSetOf<AllocatableValue>()

    while (it.hasNext()) {
      val insnIndex = it.nextIndex()
      val insn = processNext(it)

      if (insn.isConstrained) {
        val copiesInserted = insertReloadCopies(it, insn, insnIndex, reloadedForExtraCopies)

        reloadedForExtraCopies.clear()
        isInConstrainedArea = false
        if (copiesInserted) {
          continue
        }
      }

      val insnUses = insn.uses
          .filterIsInstance<AllocatableValue>()
          .filter { !it.isUndefined }
          .groupBy { target.registerClassOf(it.type) }
      val insnDefs = insn.defs
          .filterIsInstance<AllocatableValue>()
          .filter { !it.isUndefined }
          .groupBy { target.registerClassOf(it.type) }
      val constraintDummyCount = (insn.constrainedArgs + insn.constrainedRes)
          .filter { it.value is VirtualRegister && it.value.kind == VRegType.CONSTRAINED }
          .distinctBy { it.target }
          .groupBy { it.target.valueClass }
          .mapValues { it.value.size }
      for ((valueClass, k) in maxPressure) {
        val wClass = w.getValue(valueClass)
        val actualK = k - (constraintDummyCount[valueClass] ?: 0)
        // R is the set of reloaded variables at insn
        // Since they are reloads to registers, they are obviously in w
        val r = (insnUses[valueClass] ?: emptyList()) - wClass
        wClass += r
        s += r
        // Make room for insn's uses
        limit(valueClass, insnIndex, actualK)
        // Stuff that dies at this index should not count as "in a register"
        val dyingHere = insnUses[valueClass]?.filter { graph.liveness.isDeadAfter(it, InstrLabel(blockId, insnIndex)) }
        wClass -= dyingHere ?: emptyList()
        // Make room for insn's defs
        limit(valueClass, insnIndex, actualK - (insnDefs[valueClass]?.size ?: 0))
        // Since we made space for the defs, they are now in w
        wClass += insnDefs[valueClass] ?: emptyList()
        for (value in r) {
          if (isInConstrainedArea) {
            reloadedForExtraCopies += value
          }
          reloads += Location(value, InstrLabel(blockId, it.previousIndex()))
        }
      }
      if (insn.template is ParallelCopyTemplate) {
        reloadedForExtraCopies.clear()
        isInConstrainedArea = true
      }
    }
  }

  fun runSpill() {
    targetFunGenerator.minAlgorithm(maxPressure)
  }
}

/**
 * Register Spilling and Live-Range Splitting for SSA-Form Programs, Braun & Hack: Section 4.2, Algorithm 2
 */
private fun AnyFunGenerator.initUsual(
    maxPressure: Map<MachineRegisterClass, Int>,
    blockId: AtomicId,
    spillers: Map<AtomicId, BlockSpiller>,
): WBlockMap {
  val liveIns = graph.liveness.liveInsOf(blockId)

  val freq = mutableMapOf<MachineRegisterClass, MutableMap<AllocatableValue, Int>>()
  val take = mutableMapOf<MachineRegisterClass, MutableSet<AllocatableValue>>()
  val cand = mutableMapOf<MachineRegisterClass, MutableSet<AllocatableValue>>()

  val preds = graph.predecessors(blockId)
  for (pred in preds) {
    val spiller = spillers[pred.id] ?: continue
    for ((valueClass, wEnd) in spiller.wExit.entries) {
      for (variable in wEnd.filterIsInstance<Variable>()) {
        val map = freq.getOrPut(valueClass, ::mutableMapOf)
        if (variable in map) {
          val value = map.getValue(variable)
          map[variable] = value + 1
          if (value + 1 == preds.size) {
            cand.getOrPut(valueClass, ::mutableSetOf) -= variable
            take.getOrPut(valueClass, ::mutableSetOf) += variable
          }
        } else {
          map[variable] = 0
        }
        cand.getOrPut(valueClass, ::mutableSetOf) += variable
      }
    }
  }
  for ((valueClass, classCand) in cand) {
    val toTake = take.getOrPut(valueClass, ::mutableSetOf)
    val k = maxPressure.getValue(valueClass)
    if (toTake.size >= k) {
      // No need to sort, none in cand will be taken
      continue
    }
    // The candidates are part of a predecessor's wExit
    // But it is possible that value is used on another edge of the predecessor, not the one to blockId
    // So check if the value is liveIn in our block before taking it "in a register"
    toTake += classCand
        .filter { it in liveIns }
        .sortedByDescending { nextUse(InstrLabel(blockId, 0), it) }
        .take(k - toTake.size)
  }

  for (valueClass in target.registerClasses) {
    take.getOrPut(valueClass, ::mutableSetOf)
  }

  return take
}

private fun AnyFunGenerator.initSpilled(
    blockId: AtomicId,
    spillers: Map<AtomicId, BlockSpiller>,
    wBlockEntry: WBlockMap,
): Set<AllocatableValue> {
  val sJoin = graph.predecessors(blockId).flatMap { spillers[it.id]?.sExit ?: emptyList() }
  return sJoin.intersect(wBlockEntry.values.flatten())
}

/**
 * Find all variables that should be in a register in [blockId], but aren't in a register coming from [predId].
 * If there are any, split the edge and insert the required reloads there.
 *
 * Find all variables that are in a register in [predId], but shouldn't be in a register coming into [blockId].
 * If there are any, split the edge and insert the required spills there.
 *
 * Register Spilling and Live-Range Splitting for SSA-Form Programs, Braun & Hack: Section 4.3
 */
private fun AnyFunGenerator.findEdgeSpillsReloads(
    blockId: AtomicId,
    wEntryB: WBlockMap,
    sEntryB: Set<AllocatableValue>,
    predId: AtomicId,
    wExitP: Map<MachineRegisterClass, Set<AllocatableValue>>,
    sExitP: Set<AllocatableValue>,
): SpillResult {
  // Find which variables in phi are for other paths, so we can remove them
  // This is because we care only about variables from our specific predecessor
  val otherPathVersions = graph[blockId].phi.values.flatMap { incoming ->
    incoming.entries.filter { it.key != predId }.map { it.value }
  }

  var splitBlock: InstrBlock? = null

  val toSpill = (sEntryB - sExitP - otherPathVersions).intersect(wExitP.values.flatten()).filterIsInstance<Variable>()

  if (toSpill.isNotEmpty()) {
    splitBlock = graph.splitEdge(graph[predId], graph[blockId], this::createJump)
  }

  val edgeReloads = mutableListOf<Variable>()

  for ((valueClass, wExit) in wExitP) {
    // Make deep clone, because this is used by the spiller, and we don't actually want to modify it
    val forClass = wEntryB.getValue(valueClass).toMutableSet()
    forClass -= wExit
    forClass -= otherPathVersions
    // Only consider things that are actually used by our block
    val aliveAndNotInW = forClass.intersect(graph.liveness.liveInsOf(blockId))
    if (aliveAndNotInW.isNotEmpty()) {
      if (splitBlock == null) {
        splitBlock = graph.splitEdge(graph[predId], graph[blockId], this::createJump)
      }
      edgeReloads += aliveAndNotInW.filterIsInstance<Variable>()
    }
  }

  if (splitBlock == null) return emptyMap()

  val syntheticResult = MinResult(
      toSpill.map { it to InstrLabel(splitBlock.id, 0) },
      edgeReloads.map { it to InstrLabel(splitBlock.id, 0) },
      emptyList()
  )

  return mapOf(splitBlock.id to syntheticResult)
}

typealias SpillResult = Map<AtomicId, MinResult>

/**
 * Pre-allocation spilling. Reduces register pressure such that coloring will succeed.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: Section 3.1.5.2
 */
fun AnyFunGenerator.runSpiller(): SpillResult {
  val maxPressure = target.maxPressure

  val allSpilled = mutableSetOf<AllocatableValue>()

  val spillers = mutableMapOf<AtomicId, BlockSpiller>()

  val splitEdgeResults = mutableMapOf<AtomicId, MinResult>()

  data class EdgeProcessData(
      val predId: AtomicId,
      val blockId: AtomicId,
      val wBlockEntry: WBlockMap,
      val sBlockEntry: Set<AllocatableValue>,
  )

  val unprocessedEdges = mutableListOf<EdgeProcessData>()

  for (blockId in graph.postOrder().asReversed()) {
    val initialW = initUsual(maxPressure, blockId, spillers)
    val initialS = initSpilled(blockId, spillers, initialW)

    // Make copy, since we might split an edge and thus modify predecessors
    for (pred in graph.predecessors(blockId).toMutableList()) {
      val predSpiller = spillers[pred.id]
      if (predSpiller == null) {
        // This is mutable, and BlockSpiller actually mutates it
        val wBlockEntryCopy = initialW.toMutableMap().mapValues { it.value.toMutableSet() }
        unprocessedEdges += EdgeProcessData(pred.id, blockId, wBlockEntryCopy, initialS)
        continue
      }
      splitEdgeResults +=
          findEdgeSpillsReloads(blockId, initialW, initialS, pred.id, predSpiller.wExit, predSpiller.sExit)
    }

    val spiller = BlockSpiller(this, blockId, maxPressure, initialW, initialS, allSpilled)
    spillers[blockId] = spiller
    spiller.runSpill()
  }

  // Loop headers do not have all preds available on the first pass, for obvious reasons
  // Deal with the remaining edges here
  for ((predId, blockId, wBlockEntry, sBlockEntry) in unprocessedEdges) {
    val predSpiller = spillers.getValue(predId)
    splitEdgeResults +=
        findEdgeSpillsReloads(blockId, wBlockEntry, sBlockEntry, predId, predSpiller.wExit, predSpiller.sExit)
  }

  return spillers.mapValues { it.value.minResult } + splitEdgeResults
}

/** Maps a spilled value to a pointer to the stack where it was spilled. */
typealias SpillMap = MutableMap<AllocatableValue, StackValue>

/** Maps a spilled value to the [InstrBlock]s where it was spilled. */
typealias SpillBlocks = Map<AllocatableValue, List<AtomicId>>

/**
 * Inserts the spill and reload code at the locations found by [runSpiller].
 */
fun AnyFunGenerator.insertSpillReloadCode(result: SpillResult, spilled: SpillMap) {
  val spillBlocks = mutableMapOf<AllocatableValue, MutableList<AtomicId>>()

  for ((blockId, minResult) in result.entries) {
    // Before dealing with normal spills and reloads, handle the spilled φ-defs
    for (phiDef in minResult.phiDefSpills) {
      spilled[phiDef] = insertPhiSpill(phiDef, blockId, spilled[phiDef])
    }

    // All the labels in minResult contain indices from before inserting spill/reload instructions
    // We keep track of how many new instructions we inserted, so the original indices can be offset correctly
    var insnInserted = 0

    // The value, the index inside the current block, and whether it's a spill (true) or a reload (false)
    // This is ordered by the original index, so we can use insnInserted as an offset
    // We also want spills before reloads if their idx is the same. sortedBy is stable, and spills are added first.
    val modifications: List<Triple<AllocatableValue, Int, Boolean>> =
        (minResult.spills.map { (variable, label) -> Triple(variable, label.second, true) } +
            minResult.reloads.map { (variable, label) -> Triple(variable, label.second, false) })
            .sortedBy { it.second }

    for ((variable, originalIdx, isSpill) in modifications) {
      val offsetLabel = InstrLabel(blockId, originalIdx + insnInserted)

      if (isSpill) {
        spilled[variable] = insertSpill(variable, offsetLabel, spilled[variable])
        spillBlocks.getOrPut(variable, ::mutableListOf) += blockId
      } else {
        val toReload = spilled[variable] ?: continue
        insertReload(variable, toReload, offsetLabel)
      }

      insnInserted++
    }
  }

  graph.spillBlocks = spillBlocks

  graph.ssaReconstruction(spilled.map { it.key }.filterIsInstance<Variable>().toSet(), target, spilled)
}

/**
 * Find the register pressure for all the labels in the program, and for all register classes.
 */
fun AnyFunGenerator.findRegisterPressure(): Map<MachineRegisterClass, Map<InstrLabel, Int>> {
  val pressure = target.registerClasses.associateWith { mutableMapOf<InstrLabel, Int>() }
  val current = target.registerClasses.associateWithTo(mutableMapOf()) { 0 }
  for (blockId in graph.domTreePreorder) {
    val block = graph[blockId]
    for (liveIn in (graph.liveness.liveInsOf(blockId) - block.phiUses)) {
      val classOf = target.registerClassOf(liveIn.type)
      current[classOf] = current.getValue(classOf) + 1
    }
    for ((index, mi) in block.withIndex()) {
      val dyingHere = mi.uses
          .filterIsInstance<AllocatableValue>()
          .filter { graph.liveness.isDeadAfter(it, InstrLabel(blockId, index)) }
      // Reduce pressure for values that die at this label
      for (value in dyingHere) {
        val classOf = target.registerClassOf(value.type)
        current[classOf] = current.getValue(classOf) - 1
      }
      val defined = mi.defs.filterIsInstance<AllocatableValue>()
      // Increase pressure for values defined at this label
      // If never used, then it shouldn't increase pressure, nor should undefined
      for (definition in defined.filter { graph.liveness.isUsed(it) && !it.isUndefined }) {
        val classOf = target.registerClassOf(definition.type)
        current[classOf] = current.getValue(classOf) + 1
      }
      val constraintsMap = (mi.constrainedArgs + mi.constrainedRes)
          .toSet()
          .filter { it.value is VirtualRegister && it.value.kind == VRegType.CONSTRAINED }
          .groupBy { it.target.valueClass }
          .mapValues { it.value.size }
      for ((classOf, count) in constraintsMap) {
        current[classOf] = current.getValue(classOf) + count
      }

      for (mrc in target.registerClasses) {
        pressure.getValue(mrc)[InstrLabel(blockId, index)] = current.getValue(mrc)
      }
      for ((classOf, count) in constraintsMap) {
        current[classOf] = current.getValue(classOf) - count
      }
    }
    current.clear()
  }
  return pressure
}
