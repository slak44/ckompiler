package slak.ckompiler.backend

import io.github.oshai.kotlinlogging.KotlinLogging
import slak.ckompiler.AtomicId
import slak.ckompiler.analysis.*
import slak.ckompiler.throwICE

private val logger = KotlinLogging.logger {}

/**
 * Rebuild SSA for some affected variables. See reference for some notations.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: 4.2.1.2, Algorithm 4.1
 */
fun InstructionGraph.ssaReconstruction(reconstruct: Set<Variable>, target: MachineTarget<*>, spilled: SpillMap) {
  // vars is the set D in the algorithm
  val vars = reconstruct.toMutableSet<VersionedValue>()
  val ids = vars.mapTo(mutableSetOf()) { it.identityId }
  val blocks = vars.flatMap { liveness.variableDefs.filterKeys { defVar -> defVar.identityId == it.identityId }.values }.toMutableSet() +
      parallelCopies.filterValues { it.any { variable -> variable.identityId in ids } }.map { it.key }
  val f = iteratedDominanceFrontier(blocks, ids)

  // Track which blocks had their φ changed, so we can easily eliminate dead φ copies
  val hasAlteredPhi = mutableSetOf<Pair<AtomicId, VersionedValue>>()

  /**
   * This function looks for the closest definition of [variable].
   *
   * It starts at the [label], and checks each MI of the block in reverse (excluding the MI at the [label] itself).
   * After the MIs, the φs are also checked, since they too define new versions of variables.
   * If a definition is found, the function returns.
   * Otherwise, it repeats the process for the immediate dominator of the [label]'s block. For those blocks, we do not
   * start in the middle of the block (as we do for the initial [label]). All MIs are considered (index is set
   * to [Int.MAX_VALUE]).
   *
   * If the dominator tree root is reached without finding a definition, an error is thrown,
   * since it means the variable was used without being defined. Though this is technically possible in C, our
   * internal representation doesn't permit it, so it should never happen. C's use-before-define is turned into
   * variables with version 0, which are marked as undefined.
   *
   * The parameter [p] is mostly an implementation detail. When looking at a [label] that points to a φ, we need to
   * know which predecessor to go to. This information is available only to the caller of this function, which is why
   * we receive it as a parameter. It is null when the [label] does not refer to a φ.
   *
   * For certain variables in certain graphs, reaching the definition requires crossing that definition's dominance
   * frontier. This is problematic: at such nodes in the frontier, there is by definition at least another path that
   * can reach the node, beside the one containing our definition. Since we are in the business of ensuring the SSA
   * property, we can't just skip over these nodes. If the node already had a φ row for our variable, we can ignore
   * it, since it will be handled by the use-rewriting below. However, if there was no φ, we have to insert one. This
   * is the real problem, since we have no way of knowing the definitions for the other paths. Unless, of course, we
   * look for them, and it so happens that this function searches for definitions of variables.
   * We can break the problem in two using this observation: the original call that brought us to this frontier node,
   * and the finding definitions for all the predecessor paths to this block, so we can correctly create the φ.
   * The original call is solved; inserting the φ here creates a definition, which will become the closest definition
   * for the original caller.
   * For the second problem, we recursively call this function for all the predecessor paths: we will have a map of
   * predecessors to the versions live-out in them, which is exactly what we need to create the φ.
   *
   * There is also the issue of identifying these frontier blocks. We use the iterated dominance frontier of all the
   * variables we need to reconstruct (we apply iterated DF on the set of all definitions for these variables).
   *
   * An example for this case of inserting a φ is a diamond-looking graph:
   * ```
   *    A
   *   / \
   *  B   C
   *   \ /
   *    D
   * ```
   *
   * Consider a variable defined at the top in A (version 1), and used in all other 3 blocks (never redefined).
   * Assume something triggered [ssaReconstruction] for this variable in block C (a function call, for instance).
   * Blocks A and B do not require changes. Block C will contain the definition that triggered the reconstruction, and
   * will have all uses after the definition rewritten. Block D, though, has a problem: it's in the dominance frontier
   * of C. While a φ wasn't necessary before (there was only one version initially, never redefined), it is now;
   * Version 1 is live-out in block B, but the new definition in block C means version 2 is live-out in that block.
   * Block D now has to choose between these versions, which is exactly the purpose of φs.
   *
   * If the φ was already there in D, it would have been ok: just update the incoming value from C with the new
   * version. When we insert a new φ, we know the incoming from our block (it's the new version), but we don't
   * know the incoming from the other blocks. This is exactly why this complicated SSA reconstruction is required
   * rather than some simple copy-and-paste of the new version at the uses.
   */
  fun findDef(label: InstrLabel, p: AtomicId?, variable: VersionedValue): Pair<VersionedValue, InstrLabel> {
    // p indicates the "path" to take at a phi
    val (blockId, index) = if (label.second == DEFINED_IN_PHI) InstrLabel(p!!, Int.MAX_VALUE) else label

    for (u in traverseDominatorTree(blockId)) {
      val uBlock = this[u]
      // Ignore things after our given label (including the label)

      for ((currentIndex, mi) in uBlock.take(if (u == blockId) index else Int.MAX_VALUE).withIndex().reversed()) {
        val maybeDefined = mi.defs.filterIsInstance<VersionedValue>().firstOrNull { it.identityId == variable.identityId }
        if (maybeDefined != null) {
          return maybeDefined to InstrLabel(u, currentIndex)
        }
      }
      val maybeDefinedPhi = uBlock.phi.entries.firstOrNull { it.key.identityId == variable.identityId }
      if (maybeDefinedPhi != null) {
        return maybeDefinedPhi.key to InstrLabel(u, DEFINED_IN_PHI)
      }
      if (u in f) {
        val variableClass = target.registerClassOf(variable.type)
        val maxClassPressure = target.maxPressure.getValue(variableClass)
        val phiDefsClass = uBlock.phiDefs.filter { target.registerClassOf(it.type) == variableClass }

        // Insert new φ for variable, in memory if needed
        val yPrime = if (phiDefsClass.size >= maxClassPressure) {
          val stackValue = spilled.getOrPut(variable) { StackValue(variable) }
          DerefStackValue(stackValue)
        } else {
          liveness.createCopyOf(variable, uBlock) as VersionedValue
        }
        uBlock.phi[yPrime] = mutableMapOf()
        vars += yPrime

        val incoming = predecessors(uBlock).map { it.id }
            .associateWithTo(mutableMapOf()) { findDef(InstrLabel(u, DEFINED_IN_PHI), it, variable).first }
        uBlock.phi[yPrime] = incoming
        hasAlteredPhi += uBlock.id to yPrime
        // Update def-use chains for the vars used in the φ
        for ((_, incVar) in incoming) {
          liveness.addUse(incVar, uBlock.id, DEFINED_IN_PHI)
        }
        return yPrime to InstrLabel(u, DEFINED_IN_PHI)
      }
    }
    logger.throwICE("Unreachable: no definition for $variable was found up the tree from $label")
  }

  // Take all affected variables
  for (x in reconstruct) {
    // Make a copy, avoid concurrent modifications
    val uses = liveness.usesOf(x).toList()
    // And rewrite all of their uses
    for (label in uses) {
      val (blockId, index) = label
      val block = this[blockId]
      // Call findDef to get the latest definition for this use, and update the use with the version of the definition
      if (index == DEFINED_IN_PHI) {
        // If it's a φuse, then call findDef with the correct predecessor(s) to search for defs in
        val phiEntry = block.phi.entries.first { it.key.identityId == x.identityId }
        val incoming = phiEntry.value
        val stillUsed = mutableSetOf<VersionedValue>()
        // Our version of x might come from multiple preds, each must be replaced separately
        // For example: φ(n0 v1, n1 v2, n3 v2)
        for ((targetPred, _) in incoming.entries.filter { it.value == x }) {
          val newVersion = findDef(label, targetPred, x).first
          // If already wired correctly, don't bother updating anything
          if (x == newVersion) {
            stillUsed += x
            continue
          }
          hasAlteredPhi += blockId to phiEntry.key
          incoming[targetPred] = newVersion
          if (x != newVersion) {
            // Remove old use, add new use
            liveness.removeUse(x, label)
            liveness.addUse(newVersion, label)
          }
        }
        // If one predecessor version is updated, but another is not, the uses must be fixed
        // Consider the case where n1 v2 is not updated, but n3 v2 is updated to another version
        // For n3, the use set of v2 is updated to remove this label, but it is still used by the n1
        for (varUsed in stillUsed) {
          liveness.addUse(varUsed, label)
        }
      } else {
        // Otherwise just go up the dominator tree looking for definitions
        val (newVersion, location) = findDef(label, null, x)
        // If it's a constrained argument, and the new version we found is a spill, we need to look at the previous definition
        val actualNewVersion = if (block[index].constrainedArgs.any { it.value == x } && newVersion is DerefStackValue) {
          if (location.second == DEFINED_IN_PHI) {
            // TODO: this needs more logic here, this is certainly wrong
            newVersion
          } else {
            findDef(location, null, x).first
          }
        } else {
          newVersion
        }
        // If already wired correctly, don't bother rewriting anything
        if (x == actualNewVersion) continue
        // Rewrite use
        block[index] = block[index].rewriteBy(mapOf(x to actualNewVersion))
        if (x != actualNewVersion) {
          // Remove old use, add new use
          liveness.removeUse(x, label)
          liveness.addUse(actualNewVersion, label)
        }
      }
    }
  }
  eliminateDeadPhis(hasAlteredPhi)
  liveness.recomputeLiveSets()
}

/**
 * Erase instructions of the form `signed int i v6 ← φ(n6 v5, n7 v5)`, and rewrite all uses of the deleted value.
 *
 * If multiple versions need to be rewritten (like v5 -> v4, and v6 -> v5), we directly rewrite to the final values
 * (v5 -> v4, v6 -> v4).
 *
 * This function can also create further eliminations. Consider `signed int x v4 ← φ(n2 v2, n3 v3)` and
 * `signed int x v3 ← φ(n1 v2, n5 v2)`. The second φ is useless, and so we replace v3 <- v2. This makes the first φ
 * useless as well. This function also deals with this case, by calling itself recursively if a φ was rewritten.
 */
private fun InstructionGraph.eliminateDeadPhis(alteredPhis: Set<Pair<AtomicId, VersionedValue>>) {
  if (alteredPhis.isEmpty()) return

  val variableRewrites = mutableMapOf<VersionedValue, VersionedValue>()
  for ((blockId, variable) in alteredPhis) {
    if (variable !is Variable) {
      // FIXME: is this correct?
      continue
    }
    val versions = this[blockId].phi.getValue(variable).values
    // All versions are identical, φ is useless for this variable
    if (versions.distinct().size == 1) {
      val rewritten = when (val commonValue = versions.first()) {
        is DerefStackValue -> commonValue
        is Variable -> variable.copy(version = commonValue.version)
      }
      // If rewritten itself is marked for rewrite, go directly to the re-rewritten version
      val realRewritten = if (rewritten in variableRewrites) variableRewrites.getValue(rewritten) else rewritten
      // Mark it for rewrite
      variableRewrites += variable to realRewritten
      // And get rid of the φ
      this[blockId].phi -= variable
      liveness.removeUse(rewritten, blockId, DEFINED_IN_PHI)
      liveness.variableDefs -= variable
    }
  }
  val recursiveAlterations = mutableSetOf<Pair<AtomicId, VersionedValue>>()
  for ((originalVariable, rewritten) in variableRewrites) {
    // Rewrite all uses of the original variable
    for ((blockId, index) in liveness.usesOf(originalVariable)) {
      if (index == DEFINED_IN_PHI) {
        val phiEntry = this[blockId].phi.entries.first { it.key.identityId == originalVariable.identityId }
        val incoming = phiEntry.value
        val target = incoming.entries.first { it.value == originalVariable }.key
        incoming[target] = rewritten
        recursiveAlterations += blockId to phiEntry.key
      } else {
        this[blockId][index] = this[blockId][index].rewriteBy(mapOf(originalVariable to rewritten))
      }
    }
    // Move all the uses over to the rewritten var
    liveness.transferUsesToCopy(originalVariable, rewritten)
  }
  eliminateDeadPhis(recursiveAlterations)
}
