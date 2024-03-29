package slak.ckompiler.analysis.external

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import slak.ckompiler.AtomicId
import slak.ckompiler.analysis.BasicBlock
import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.Variable
import kotlin.js.JsExport

@Suppress("SERIALIZER_TYPE_INCOMPATIBLE")
@Serializable(with = EnumOrdinalSerializer::class)
enum class PhiInsertionStep {
  PREPARE,
  WHILE_LOOP,
  PICK_X_FROM_W,
  ITERATE_DF,
  CHECK_PROCESSED,
  INSERT_PHI,
  CHECK_DEFS,
  ADD_TO_W,
  DONE
}

typealias BBPath = List<AtomicId>
typealias PhiIncoming = List<BBPath>

@Serializable
data class PhiInsertionStepState(
    val step: PhiInsertionStep,
    val blockX: AtomicId? = null,
    val blockY: AtomicId? = null,
    val highlightedPhiPaths: PhiIncoming? = null,
    val f: Set<AtomicId>,
    val w: Set<AtomicId>,
)

fun buildDefPath(defsV: Set<BasicBlock>, f: Set<BasicBlock>, start: BasicBlock, pred: BasicBlock): BBPath {
  val path = mutableListOf(start)

  var block: BasicBlock? = pred
  while (block != null) {
    path += block
    if (block == start) {
      // This is a cycle
      break
    }
    if (block in defsV || block in f) {
      break
    }
    block = block.preds.firstOrNull()
  }

  return path.map { it.nodeId }
}

fun cloneSet(set: Set<BasicBlock>): Set<AtomicId> {
  return set.mapTo(mutableSetOf()) { it.nodeId }
}

@JsExport
fun generatePhiSteps(cfg: CFG, variable: Variable): String {
  val states = mutableListOf<PhiInsertionStepState>()

  val defsV = requireNotNull(cfg.exprDefinitions[variable])

  val f = mutableSetOf<BasicBlock>()
  val w = mutableSetOf(*defsV.toTypedArray())

  states += PhiInsertionStepState(PhiInsertionStep.PREPARE, f = cloneSet(f), w = cloneSet(w))

  while (w.isNotEmpty()) {
    states += PhiInsertionStepState(PhiInsertionStep.WHILE_LOOP, f = cloneSet(f), w = cloneSet(w))

    val x = w.first()
    w -= x

    states += PhiInsertionStepState(PhiInsertionStep.PICK_X_FROM_W, blockX = x.nodeId, f = cloneSet(f), w = cloneSet(w))

    for (y in x.dominanceFrontier) {
      states += PhiInsertionStepState(PhiInsertionStep.ITERATE_DF, blockX = x.nodeId, blockY = y.nodeId, f = cloneSet(f), w = cloneSet(w))

      states += PhiInsertionStepState(
          PhiInsertionStep.CHECK_PROCESSED,
          blockX = x.nodeId,
          blockY = y.nodeId,
          f = cloneSet(f),
          w = cloneSet(w)
      )
      if (y !in f) {
        val insertedPhi = y.preds.map { buildDefPath(defsV, f, y, it) }
        f += y

        states += PhiInsertionStepState(
            PhiInsertionStep.INSERT_PHI,
            blockX = x.nodeId,
            blockY = y.nodeId,
            highlightedPhiPaths = insertedPhi,
            f = cloneSet(f),
            w = cloneSet(w)
        )

        states += PhiInsertionStepState(
            PhiInsertionStep.CHECK_DEFS,
            blockX = x.nodeId,
            blockY = y.nodeId,
            f = cloneSet(f),
            w = cloneSet(w)
        )
        if (y !in defsV) {
          w += y

          states += PhiInsertionStepState(PhiInsertionStep.ADD_TO_W, blockX = x.nodeId, blockY = y.nodeId, f = cloneSet(f), w = cloneSet(w))
        }
      }
    }
  }

  states += PhiInsertionStepState(PhiInsertionStep.DONE, f = cloneSet(f), w = cloneSet(w))

  return json.encodeToString(states)
}
