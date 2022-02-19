package slak.ckompiler

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import slak.ckompiler.analysis.BasicBlock
import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.Variable
import slak.ckompiler.analysis.json

@Serializable(with = PhiInsertionStepSerializer::class)
enum class PhiInsertionStep {
  PREPARE,
  WHILE_LOOP,
  PICK_X_FROM_W,
  ITERATE_DF,
  CHECK_PROCESSED,
  INSERT_PHI,
  MARK_PROCESSED,
  CHECK_DEFS,
  ADD_TO_W
}

object PhiInsertionStepSerializer : KSerializer<PhiInsertionStep> {
  override val descriptor: SerialDescriptor = Int.serializer().descriptor

  override fun deserialize(decoder: Decoder): PhiInsertionStep {
    throw UnsupportedOperationException("We only care about serialization")
  }

  override fun serialize(encoder: Encoder, value: PhiInsertionStep) {
    encoder.encodeSerializableValue(
        Int.serializer(),
        value.ordinal + 1
    )
  }
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

fun buildDefPath(defsV: Set<BasicBlock>, start: BasicBlock, pred: BasicBlock): BBPath {
  val path = mutableListOf(start)

  var block = pred
  while (block !in defsV) {
    path += block
    block = block.preds.first()
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

      if (y !in f) {
        states += PhiInsertionStepState(
            PhiInsertionStep.CHECK_PROCESSED,
            blockX = x.nodeId,
            blockY = y.nodeId,
            f = cloneSet(f),
            w = cloneSet(w)
        )

        val insertedPhi = y.preds.map { buildDefPath(defsV, y, it) }

        states += PhiInsertionStepState(
            PhiInsertionStep.INSERT_PHI,
            blockX = x.nodeId,
            blockY = y.nodeId,
            highlightedPhiPaths = insertedPhi,
            f = cloneSet(f),
            w = cloneSet(w)
        )

        f += y

        states += PhiInsertionStepState(
            PhiInsertionStep.MARK_PROCESSED,
            blockX = x.nodeId,
            blockY = y.nodeId,
            f = cloneSet(f),
            w = cloneSet(w)
        )

        if (y !in defsV) {
          states += PhiInsertionStepState(
              PhiInsertionStep.CHECK_DEFS,
              blockX = x.nodeId,
              blockY = y.nodeId,
              f = cloneSet(f),
              w = cloneSet(w)
          )

          w += y

          states += PhiInsertionStepState(PhiInsertionStep.ADD_TO_W, blockX = x.nodeId, blockY = y.nodeId, f = cloneSet(f), w = cloneSet(w))
        }
      }
    }
  }

  return json.encodeToString(states)
}
