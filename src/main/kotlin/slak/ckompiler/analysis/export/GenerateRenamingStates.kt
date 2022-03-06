package slak.ckompiler.analysis.export

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
    val bb: AtomicId,
    val i: Int? = null,
    val newVersion: Int? = null,
    val reachingDefBlock: AtomicId? = null,
    val reachingDefIdx: Int? = null,
    val succBB: AtomicId? = null,
)

@JsExport
fun generateRenameSteps(cfg: CFG, variable: Variable): String {
  val states = mutableListOf<RenamingStepState>()

  val defs = cfg.definitions.entries.groupBy({ it.value.first }, { it.key.version to it.value.second })

  val uses = cfg.defUseChains.entries
      .flatMap { (key, value) -> value.map { key to it } }
      .groupBy({ (_, label) -> label.first }, { (variable, label) -> variable.version to label.second })

  for (bb in cfg.domTreePreorder) {
    states += RenamingStepState(RenamingStep.EACH_BB_PREORDER, bb.nodeId)

    val bbDefs = defs[bb] ?: emptyList()
    val bbUses = uses[bb] ?: emptyList()

    val markedDefs = bbDefs.map { Triple(it.first, it.second, true) }
    val markedUses = bbUses.map { Triple(it.first, it.second, false) }

    val orderedLocations = (markedDefs + markedUses).sortedBy { it.second }

    for ((version, instrIndex, isDef) in orderedLocations) {
      TODO()
    }
  }

  return json.encodeToString(states)
}
