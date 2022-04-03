package slak.ckompiler.analysis.external

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import slak.ckompiler.AtomicId
import slak.ckompiler.analysis.*
import kotlin.js.JsExport

@Suppress("SERIALIZER_TYPE_INCOMPATIBLE")
@Serializable(with = EnumOrdinalSerializer::class)
enum class RenamingStep {
  EACH_BB_PREORDER,
  EACH_INSTR,
  CHECK_DEFINED,
  INSTR_REPLACE_DEF,
  CHECK_USED,
  INSTR_REPLACE_USE,
  EACH_SUCC_PHI,
  SUCC_PHI_REPLACE_USE,
  DONE
}

@Serializable
data class RenamingStepState(
    val step: RenamingStep,
    val bb: AtomicId? = null,
    val i: Int? = null,
    val newVersion: Int? = null,
    val reachingDefBlock: AtomicId? = null,
    val reachingDefIdx: Int? = null,
    val succBB: AtomicId? = null,
)

@JsExport
fun generateRenameSteps(cfg: CFG, targetVariable: Variable): String {
  val defs = cfg.definitions.entries
      .filter { it.key.identityId == targetVariable.identityId }
      .groupBy({ it.value.first }, { it.key to it.value.second })

  val uses = cfg.defUseChains.entries
      .flatMap { (key, value) -> value.map { key to it } }
      .filter { it.first.identityId == targetVariable.identityId }
      .groupBy({ (_, label) -> label.first }, { (variable, label) -> variable to label.second })

  val blockStates = mutableMapOf<BasicBlock, MutableList<RenamingStepState>>()

  val phiUseStates = mutableMapOf<BasicBlock, MutableList<RenamingStepState>>()

  for (bb in cfg.domTreePreorder) {
    val bbDefs = defs[bb] ?: emptyList()
    val bbUses = uses[bb] ?: emptyList()

    val markedDefs = bbDefs.map { Triple(it.first, it.second, true) }
    val markedUses = bbUses.map { Triple(it.first, it.second, false) }

    val orderedLocations = (markedDefs + markedUses).sortedWith { (_, indexA, isDefA), (_, indexB, _) ->
      if (indexA == indexB) {
        if (isDefA) -1 else 1
      } else {
        indexA - indexB
      }
    }.distinct()

    for ((variable, instrIndex, isDef) in orderedLocations) {
      val reachingDef = cfg.definitions.getValue(variable)

      if (instrIndex == DEFINED_IN_PHI && !isDef) {
        val matchingPred = bb.phi
            .first { it.variable.identityId == variable.identityId }.incoming.entries
            .first { (_, incomingVersion) -> incomingVersion == variable }
            .key

        phiUseStates.getOrPut(matchingPred, ::mutableListOf) += RenamingStepState(
            RenamingStep.SUCC_PHI_REPLACE_USE,
            bb = matchingPred.nodeId,
            i = DEFINED_IN_PHI,
            newVersion = variable.version,
            reachingDefBlock = reachingDef.first.nodeId,
            reachingDefIdx = reachingDef.second,
            succBB = bb.nodeId,
        )
      } else {
        if (isDef) {
          blockStates.getOrPut(bb, ::mutableListOf) += RenamingStepState(
              RenamingStep.CHECK_DEFINED,
              bb = bb.nodeId,
              i = instrIndex,
          )

          blockStates.getOrPut(bb, ::mutableListOf) += RenamingStepState(
              RenamingStep.INSTR_REPLACE_DEF,
              bb = bb.nodeId,
              i = instrIndex,
              newVersion = variable.version,
          )
        } else {
          blockStates.getOrPut(bb, ::mutableListOf) += RenamingStepState(
              RenamingStep.CHECK_USED,
              bb = bb.nodeId,
              i = instrIndex,
          )

          blockStates.getOrPut(bb, ::mutableListOf) += RenamingStepState(
              RenamingStep.INSTR_REPLACE_USE,
              bb = bb.nodeId,
              i = instrIndex,
              newVersion = variable.version,
              reachingDefBlock = reachingDef.first.nodeId,
              reachingDefIdx = reachingDef.second,
          )
        }
      }
    }
  }

  val states = cfg.domTreePreorder
      .map {
        val bbStates = blockStates[it] ?: mutableListOf()
        if (bbStates.isNotEmpty()) {
          bbStates.add(0, RenamingStepState(RenamingStep.EACH_INSTR, it.nodeId))
        }

        val bbSuccPhiStates = phiUseStates[it] ?: mutableListOf()
        val succPhiSteps = bbSuccPhiStates.flatMap { step -> listOf(RenamingStepState(RenamingStep.EACH_SUCC_PHI, it.nodeId), step) }

        val forState = RenamingStepState(RenamingStep.EACH_BB_PREORDER, it.nodeId)

        listOf(forState) + bbStates + succPhiSteps
      }
      .flatten()

  val done = RenamingStepState(RenamingStep.DONE)

  return json.encodeToString(states + done)
}
