package slak.ckompiler.backend

import org.apache.logging.log4j.LogManager
import slak.ckompiler.AtomicId
import slak.ckompiler.analysis.DEFINED_IN_PHI
import slak.ckompiler.analysis.Variable
import slak.ckompiler.throwICE

private val logger = LogManager.getLogger()

/**
 * Rebuild SSA for some affected variables. See reference for some notations.
 *
 * Register Allocation for Programs in SSA Form, Sebastian Hack: 4.2.1.2, Algorithm 4.1
 */
fun InstructionGraph.ssaReconstruction(reconstruct: Set<Variable>) {
  // vars is the set D in the algorithm
  val vars = reconstruct.toMutableSet()
  val ids = vars.mapTo(mutableSetOf()) { it.id }
  val blocks = vars.flatMap { variableDefs.filterKeys { defVar -> defVar.id == it.id }.values }.toMutableSet() +
      parallelCopies.filterValues { it.any { variable -> variable.id in ids } }.map { it.key }
  val f = iteratedDominanceFrontier(blocks, ids)

  // Track which blocks had their φ changed, so we can easily eliminate dead φ copies
  val hasAlteredPhi = mutableSetOf<Pair<AtomicId, Variable>>()

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
  fun findDef(label: InstrLabel, p: AtomicId?, variable: Variable): Variable {
    // p indicates the "path" to take at a phi
    val (blockId, index) = if (label.second == DEFINED_IN_PHI) InstrLabel(p!!, Int.MAX_VALUE) else label

    for (u in traverseDominatorTree(blockId)) {
      val uBlock = this[u]
      // Ignore things after our given label (including the label)
      for (mi in uBlock.take(if (u == blockId) index else Int.MAX_VALUE).asReversed()) {
        val maybeDefined = mi.defs.filterIsInstance<Variable>().firstOrNull { it.id == variable.id }
        if (maybeDefined != null) {
          return maybeDefined
        }
      }
      val maybeDefinedPhi = uBlock.phi.entries.firstOrNull { it.key.id == variable.id }
      if (maybeDefinedPhi != null) {
        return maybeDefinedPhi.key
      }
      if (u in f) {
        // Insert new φ for variable
        val yPrime = createCopyOf(variable, uBlock) as Variable
        uBlock.phi[yPrime] = mutableMapOf()
        vars += yPrime
        val incoming = predecessors(uBlock).map { it.id }
            .associateWithTo(mutableMapOf()) { findDef(InstrLabel(u, DEFINED_IN_PHI), it, variable) }
        uBlock.phi[yPrime] = incoming
        hasAlteredPhi += uBlock.id to yPrime
        // Update def-use chains for the vars used in the φ
        for ((_, incVar) in incoming) {
          defUseChains.getOrPut(incVar, ::mutableSetOf) += InstrLabel(uBlock.id, DEFINED_IN_PHI)
        }
        return yPrime
      }
    }
    logger.throwICE("Unreachable: no definition for $variable was found up the tree from $label")
  }

  // Take all affected variables
  for (x in reconstruct) {
    // Make a copy, avoid concurrent modifications
    val uses = defUseChains.getValue(x).toMutableList()
    // And rewrite all of their uses
    for (label in uses) {
      val (blockId, index) = label
      val block = this[blockId]
      // Call findDef to get the latest definition for this use, and update the use with the version of the definition
      val newVersion = if (index == DEFINED_IN_PHI) {
        // If it's a φuse, then call findDef with the correct predecessor to search for defs in
        val phiEntry = block.phi.entries.first { it.key.id == x.id }
        val incoming = phiEntry.value
        val target = incoming.entries.first { it.value == x }.key
        val newVersion = findDef(label, target, x)
        // If already wired correctly, don't bother updating anything
        if (x == newVersion) continue
        hasAlteredPhi += blockId to phiEntry.key
        incoming[target] = newVersion
        newVersion
      } else {
        // Otherwise just go up the dominator tree looking for definitions
        val newVersion = findDef(label, null, x)
        // If already wired correctly, don't bother rewriting anything
        if (x == newVersion) continue
        // Rewrite use
        block[index] = block[index].rewriteBy(mapOf(x to newVersion))
        newVersion
      }
      if (x != newVersion) {
        // Update uses
        defUseChains.getOrPut(x, ::mutableSetOf) -= label
        defUseChains.getOrPut(newVersion, ::mutableSetOf) += label
      }
    }
  }
  eliminateDeadPhis(hasAlteredPhi)
  liveSets = computeLiveSetsByVar()
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
private fun InstructionGraph.eliminateDeadPhis(alteredPhis: Set<Pair<AtomicId, Variable>>) {
  if (alteredPhis.isEmpty()) return

  val variableRewrites = mutableMapOf<Variable, Variable>()
  for ((blockId, variable) in alteredPhis) {
    val versions = this[blockId].phi.getValue(variable).map { it.value.version }
    // All versions are identical, φ is useless for this variable
    if (versions.distinct().size == 1) {
      val rewritten = variable.copy(version = versions[0])
      // If rewritten itself is marked for rewrite, go directly to the re-rewritten version
      val realRewritten = if (rewritten in variableRewrites) variableRewrites.getValue(rewritten) else rewritten
      // Mark it for rewrite
      variableRewrites += variable to realRewritten
      // And get rid of the φ
      this[blockId].phi -= variable
      defUseChains.getOrPut(rewritten, ::mutableSetOf) -= InstrLabel(blockId, DEFINED_IN_PHI)
      variableDefs -= variable
    }
  }
  val recursiveAlterations = mutableSetOf<Pair<AtomicId, Variable>>()
  for ((originalVariable, rewritten) in variableRewrites) {
    // Rewrite all uses of the original variable
    for ((blockId, index) in defUseChains.getValue(originalVariable)) {
      if (index == DEFINED_IN_PHI) {
        val phiEntry = this[blockId].phi.entries.first { it.key.id == originalVariable.id }
        val incoming = phiEntry.value
        val target = incoming.entries.first { it.value == originalVariable }.key
        incoming[target] = rewritten
        recursiveAlterations += blockId to phiEntry.key
      } else {
        this[blockId][index] = this[blockId][index].rewriteBy(mapOf(originalVariable to rewritten))
      }
    }
    // Move all the uses over to the rewritten var
    defUseChains.getOrPut(rewritten, ::mutableSetOf) += defUseChains.getValue(originalVariable)
    defUseChains.getValue(originalVariable).clear()
  }
  eliminateDeadPhis(recursiveAlterations)
}
