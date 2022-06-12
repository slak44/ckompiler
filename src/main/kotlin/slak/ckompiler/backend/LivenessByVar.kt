package slak.ckompiler.backend

import slak.ckompiler.AtomicId
import slak.ckompiler.analysis.DEFINED_IN_PHI
import slak.ckompiler.analysis.Variable

typealias LiveSet = MutableMap<AtomicId, MutableSet<Variable>>

/**
 * The [liveIn] set contains both block live-ins, and φ-defined variables in that block.
 *
 * The [liveOut] set contains block live-outs.
 */
data class LiveSets(val liveIn: LiveSet, val liveOut: LiveSet)

/**
 * Path exploration by variable to find live sets.
 *
 * Brandner, Florian, et al. Computing Liveness Sets for SSA-Form Programs. Diss. INRIA, 2011: Algorithm 6, Algorithm 7
 */
fun InstructionGraph.computeLiveSetsByVar(): LiveSets {
  val liveIn = mutableMapOf<AtomicId, MutableSet<Variable>>()
  val liveOut = mutableMapOf<AtomicId, MutableSet<Variable>>()

  fun InstrPhi.predsByVar(v: Variable): List<AtomicId>? {
    return entries.firstOrNull { it.key.identityId == v.identityId }?.value?.entries?.filter { it.value == v }?.map { it.key }
  }

  fun upAndMarkStack(B: AtomicId, v: Variable, fromPhi: Boolean = false) {
    // def(v) ∈ B (φ excluded)
    if (variableDefs.getValue(v) == B && v !in this[B].phiDefs) return
    if (liveIn.getOrPut(B, ::mutableSetOf).lastOrNull() == v) return
    liveIn[B]!! += v
    // defined in φ or spilled in this block, might still be live-in, so check that before returning
    if (v in this[B].phiDefs || spillBlocks[v]?.contains(B) == true) return
    val list = if (!fromPhi) {
      predecessors(B)
    } else {
      this[B].phi.predsByVar(v)!!.map { this[it] }
    }
    for (P in list) {
      if (spillBlocks[v]?.contains(P.id) == true) {
        return
      }
      if (liveOut.getOrPut(P.id, ::mutableSetOf).lastOrNull() != v) {
        liveOut[P.id]!! += v
      }
      upAndMarkStack(P.id, v)
    }
  }

  // FIXME: allow partial update for just some vars
  for (v in defUseChains.keys.filterIsInstance<Variable>().filter { !it.isUndefined }) {
    val uses = defUseChains.getValue(v)
    for ((B, index) in uses) {
      if (successors(B).any { succ -> B in succ.phi.predsByVar(v) ?: emptyList() }) {
        liveOut.getOrPut(B, ::mutableSetOf) += v
      }
      upAndMarkStack(B, v, index == DEFINED_IN_PHI)
    }
  }

  return LiveSets(liveIn, liveOut)
}
