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

enum class Replacements(val value: Int) {
  NONE(0b00),
  DEF(0b01),
  USE(0b10),
  DEF_USE(0b11);

  operator fun plus(other: Replacements): Replacements {
    return values().first { it.value == (this.value or other.value) }
  }

  fun hasUse(): Boolean {
    return (value and USE.value) != 0
  }

  fun hasDef(): Boolean {
    return (value and DEF.value) != 0
  }
}

@JsExport
@Suppress("NON_EXPORTABLE_TYPE")
data class RenameReplacements(
    val phiRenameReplacements: Map<AtomicId, List<Triple<Int, Int, AtomicId>>>,
    val renameReplacements: Map<Pair<AtomicId, Int>, List<Pair<Int, Replacements>>>,
    val serializedRenameSteps: String,
) {
  fun getFor(nodeId: AtomicId, index: Int, stepIndex: Int): Replacements {
    val replacementList = (renameReplacements[nodeId to index] ?: emptyList()).filter { stepIndex >= it.first }

    return replacementList.map { it.second }.reduceOrNull { acc, r -> acc + r } ?: Replacements.NONE
  }
}

@JsExport
fun generateRenameSteps(cfg: CFG, targetVariable: Variable): RenameReplacements {
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

    val bbPhi = bb.phi.toList()

    for ((variable, instrIndex, isDef) in orderedLocations) {
      val reachingDef = cfg.definitions.getValue(variable)

      if (instrIndex == DEFINED_IN_PHI && !isDef) {
        val phiIndex = bbPhi.indexOfFirst { it.variable.identityId == variable.identityId }

        check(phiIndex != -1) { "Phi for $variable not found in node ${bb.nodeId}" }

        val matchingPred = bbPhi[phiIndex].incoming.entries
            .first { (_, incomingVersion) -> incomingVersion == variable }
            .key

        phiUseStates.getOrPut(matchingPred, ::mutableListOf) += RenamingStepState(
            RenamingStep.SUCC_PHI_REPLACE_USE,
            bb = matchingPred.nodeId,
            i = phiIndex,
            newVersion = variable.version,
            reachingDefBlock = reachingDef.first.nodeId,
            reachingDefIdx = reachingDef.second,
            succBB = bb.nodeId,
        )
      } else {
        val idx = if (instrIndex == DEFINED_IN_PHI) {
          bbPhi.indexOfFirst { it.variable.identityId == variable.identityId }
        } else {
          bbPhi.size + instrIndex
        }

        if (isDef) {
          blockStates.getOrPut(bb, ::mutableListOf) += RenamingStepState(
              RenamingStep.CHECK_DEFINED,
              bb = bb.nodeId,
              i = idx,
          )

          blockStates.getOrPut(bb, ::mutableListOf) += RenamingStepState(
              RenamingStep.INSTR_REPLACE_DEF,
              bb = bb.nodeId,
              i = idx,
              newVersion = variable.version,
          )
        } else {
          blockStates.getOrPut(bb, ::mutableListOf) += RenamingStepState(
              RenamingStep.CHECK_USED,
              bb = bb.nodeId,
              i = idx,
          )

          blockStates.getOrPut(bb, ::mutableListOf) += RenamingStepState(
              RenamingStep.INSTR_REPLACE_USE,
              bb = bb.nodeId,
              i = idx,
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
        bbSuccPhiStates.sortBy { step -> step.succBB }
        val succPhiSteps = bbSuccPhiStates.flatMap { step -> listOf(RenamingStepState(RenamingStep.EACH_SUCC_PHI, it.nodeId), step) }

        val forState = RenamingStepState(RenamingStep.EACH_BB_PREORDER, it.nodeId)

        listOf(forState) + bbStates + succPhiSteps
      }
      .flatten()

  val done = RenamingStepState(RenamingStep.DONE)

  val phiRenameReplacements = mutableMapOf<AtomicId, MutableList<Triple<Int, Int, AtomicId>>>()
  val renameReplacements = mutableMapOf<Pair<AtomicId, Int>, MutableList<Pair<Int, Replacements>>>()

  for ((idx, state) in states.withIndex()) {
    if (idx == 0 || state.bb == null || state.i == null) {
      continue
    }
    val label = state.bb to state.i
    when (state.step) {
      RenamingStep.SUCC_PHI_REPLACE_USE -> {
        phiRenameReplacements.getOrPut(state.succBB!!, ::mutableListOf) += Triple(idx, state.newVersion!!, state.bb)
      }
      RenamingStep.INSTR_REPLACE_DEF -> {
        renameReplacements.getOrPut(label, ::mutableListOf) += idx to Replacements.DEF
      }
      RenamingStep.INSTR_REPLACE_USE -> {
        renameReplacements.getOrPut(label, ::mutableListOf) += idx to Replacements.USE
      }
      else -> {
        // Ignore
      }
    }
  }

  val serialized = json.encodeToString(states + done)

  return RenameReplacements(phiRenameReplacements, renameReplacements, serialized)
}

@JsExport
fun getPhiRenameText(
    phi: PhiInstruction,
    bb: BasicBlock,
    index: Int,
    variable: Variable,
    stepIndex: Int,
    replacements: RenameReplacements,
): String {
  if (variable.identityId != phi.variable.identityId) {
    return phi.toString()
  }

  val phiReplacementList = (replacements.phiRenameReplacements[bb.nodeId] ?: emptyList()).filter { stepIndex >= it.first }

  val incomingStr = phi.incoming.keys.joinToString(", ") { pred ->
    val version = phiReplacementList.firstOrNull { it.third == pred.nodeId }?.second
    val versionStr = if (version == null) "" else " v$version"

    "BB${pred.nodeId}$versionStr"
  }

  val toReplace = replacements.getFor(bb.nodeId, index, stepIndex)

  val target = if (toReplace.hasDef()) phi.variable.toString() else phi.variable.tid.toString()

  return "store $target = φ($incomingStr)"
}

@JsExport
fun getRenameText(
    ir: IRInstruction,
    bb: BasicBlock,
    index: Int,
    variable: Variable,
    stepIndex: Int,
    replacements: RenameReplacements,
): String {
  val toReplace = replacements.getFor(bb.nodeId, index, stepIndex)

  fun replaceUse(value: IRValue): String {
    return if (value is Variable && value.identityId == variable.identityId && !toReplace.hasUse()) {
      value.tid.toString()
    } else {
      value.toString()
    }
  }

  val result = when {
    ir !is StoreMemory && ir.result is Variable && (ir.result as Variable).identityId == variable.identityId && !toReplace.hasDef() -> {
      (ir.result as Variable).tid.toString()
    }
    else -> ir.result.toString()
  }

  val irText = when (ir) {
    is FltBinary -> "$result = flt op ${replaceUse(ir.lhs)} ${ir.op} ${replaceUse(ir.rhs)}"
    is FltCmp -> "$result = flt cmp ${replaceUse(ir.lhs)} ${ir.cmp} ${replaceUse(ir.rhs)}"
    is FltNeg -> "$result = flt negate ${replaceUse(ir.operand)}"
    is IndirectCall -> "$result = call *(${ir.callable}) with args ${ir.args.joinToString(", ") { replaceUse(it) }}"
    is NamedCall -> "$result = call ${ir.name} with args ${ir.args.joinToString(", ") { replaceUse(it) }}"
    is IntBinary -> "$result = int op ${replaceUse(ir.lhs)} ${ir.op} ${replaceUse(ir.rhs)}"
    is IntCmp -> "$result = int cmp ${replaceUse(ir.lhs)} ${ir.cmp} ${replaceUse(ir.rhs)}"
    is IntInvert -> "$result = invert ${replaceUse(ir.operand)}"
    is IntNeg -> "$result = int negate ${replaceUse(ir.operand)}"
    is LoadMemory -> "load $result = *(${replaceUse(ir.loadFrom)})"
    is StoreMemory -> "store *(${replaceUse(ir.storeTo)}) = ${replaceUse(ir.value)}"
    is MoveInstr -> "move $result = ${replaceUse(ir.value)}"
    is ReinterpretCast -> "$result = reinterpret ${ir.operand}"
    is StructuralCast -> "$result = cast ${ir.operand} to ${ir.result.type}"
  }

  return if (ir === bb.instructions.asSequence().last()) {
    when (bb.terminator) {
      is CondJump, is SelectJump -> "$irText ?"
      is ImpossibleJump -> "return $irText"
      else -> irText
    }
  } else {
    irText
  }
}
