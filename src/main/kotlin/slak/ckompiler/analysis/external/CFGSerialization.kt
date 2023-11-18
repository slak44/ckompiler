package slak.ckompiler.analysis.external

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import slak.ckompiler.AtomicId
import slak.ckompiler.IdCounter
import slak.ckompiler.analysis.*
import slak.ckompiler.parser.TypeName
import slak.ckompiler.parser.TypedIdentifier

@Serializable
@SerialName("slak.ckompiler.analysis.BasicBlock")
private data class BasicBlockSurrogate(
    val isRoot: Boolean,
    val phi: Set<PhiInstruction>,
    val ir: List<IRInstruction>,
    val postOrderId: Int,
    val nodeId: AtomicId,
    val predecessors: List<AtomicId>,
    val successors: List<AtomicId>,
    val terminator: Jump,
)

object BasicBlockSerializer : KSerializer<BasicBlock> {
  override val descriptor: SerialDescriptor = BasicBlockSurrogate.serializer().descriptor

  override fun deserialize(decoder: Decoder): BasicBlock {
    throw UnsupportedOperationException("We only care about serialization")
  }

  override fun serialize(encoder: Encoder, value: BasicBlock) {
    val surrogate = BasicBlockSurrogate(
        value.isRoot,
        value.phi,
        value.ir,
        value.postOrderId,
        value.nodeId,
        value.preds.map { it.nodeId },
        value.successors.map { it.nodeId },
        value.terminator
    )
    encoder.encodeSerializableValue(BasicBlockSurrogate.serializer(), surrogate)
  }
}

@Serializable
@SerialName("slak.ckompiler.analysis.PhiInstruction")
private data class PhiInstructionSurrogate(
    val variable: Variable,
    val incoming: Map<AtomicId, Variable>,
)

object PhiInstructionSerializer : KSerializer<PhiInstruction> {
  override val descriptor: SerialDescriptor = PhiInstructionSurrogate.serializer().descriptor

  override fun deserialize(decoder: Decoder): PhiInstruction {
    throw UnsupportedOperationException("We only care about serialization")
  }

  override fun serialize(encoder: Encoder, value: PhiInstruction) {
    val surrogate = PhiInstructionSurrogate(value.variable, value.incoming.mapKeys { it.key.nodeId })
    encoder.encodeSerializableValue(PhiInstructionSurrogate.serializer(), surrogate)
  }
}

@Serializable
@SerialName("slak.ckompiler.analysis.Variable")
private data class VariableSurrogate(
    val type: TypeName,
    val name: String,
    val identityId: AtomicId,
    val version: Int,
)

object VariableSerializer : KSerializer<Variable> {
  override val descriptor: SerialDescriptor = VariableSurrogate.serializer().descriptor

  override fun deserialize(decoder: Decoder): Variable {
    throw UnsupportedOperationException("We only care about serialization")
  }

  override fun serialize(encoder: Encoder, value: Variable) {
    val surrogate = VariableSurrogate(value.type, value.name, value.identityId, value.version)
    encoder.encodeSerializableValue(VariableSurrogate.serializer(), surrogate)
  }
}

object TypeNameSerializer : KSerializer<TypeName> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TypeName", PrimitiveKind.STRING)

  override fun deserialize(decoder: Decoder): TypeName {
    throw UnsupportedOperationException("We only care about serialization")
  }

  override fun serialize(encoder: Encoder, value: TypeName) {
    encoder.encodeString(value.toString())
  }
}

@Serializable
@SerialName("slak.ckompiler.analysis.CondJump")
private data class CondJumpSurrogate(
    val cond: List<IRInstruction>,
    val target: AtomicId,
    val other: AtomicId,
)

object CondJumpSerializer : KSerializer<CondJump> {
  override val descriptor: SerialDescriptor = CondJumpSurrogate.serializer().descriptor

  override fun deserialize(decoder: Decoder): CondJump {
    throw UnsupportedOperationException("We only care about serialization")
  }

  override fun serialize(encoder: Encoder, value: CondJump) {
    encoder.encodeSerializableValue(
        CondJumpSurrogate.serializer(),
        CondJumpSurrogate(value.cond, value.target.nodeId, value.other.nodeId)
    )
  }
}

@Serializable
@SerialName("slak.ckompiler.analysis.SelectJump")
private data class SelectJumpSurrogate(
    val cond: List<IRInstruction>,
    val options: List<AtomicId>,
    val default: AtomicId,
)

object SelectJumpSerializer : KSerializer<SelectJump> {
  override val descriptor: SerialDescriptor = SelectJumpSurrogate.serializer().descriptor

  override fun deserialize(decoder: Decoder): SelectJump {
    throw UnsupportedOperationException("We only care about serialization")
  }

  override fun serialize(encoder: Encoder, value: SelectJump) {
    encoder.encodeSerializableValue(
        SelectJumpSurrogate.serializer(),
        SelectJumpSurrogate(value.cond, value.options.values.map { it.nodeId }, value.default.nodeId)
    )
  }
}

@Serializable
@SerialName("slak.ckompiler.analysis.UncondJump")
private data class UncondJumpSurrogate(
    val target: AtomicId,
)

object UncondJumpSerializer : KSerializer<UncondJump> {
  override val descriptor: SerialDescriptor = UncondJumpSurrogate.serializer().descriptor

  override fun deserialize(decoder: Decoder): UncondJump {
    throw UnsupportedOperationException("We only care about serialization")
  }

  override fun serialize(encoder: Encoder, value: UncondJump) {
    encoder.encodeSerializableValue(
        UncondJumpSurrogate.serializer(),
        UncondJumpSurrogate(value.target.nodeId)
    )
  }
}

@Serializable
@SerialName("slak.ckompiler.analysis.ImpossibleJump")
private data class ImpossibleJumpSurrogate(
    val target: AtomicId,
    val returned: List<IRInstruction>?,
)

object ImpossibleJumpSerializer : KSerializer<ImpossibleJump> {
  override val descriptor: SerialDescriptor = ImpossibleJumpSurrogate.serializer().descriptor

  override fun deserialize(decoder: Decoder): ImpossibleJump {
    throw UnsupportedOperationException("We only care about serialization")
  }

  override fun serialize(encoder: Encoder, value: ImpossibleJump) {
    encoder.encodeSerializableValue(
        ImpossibleJumpSurrogate.serializer(),
        ImpossibleJumpSurrogate(value.target.nodeId, value.returned)
    )
  }
}

@Serializable
@SerialName("slak.ckompiler.analysis.ConstantJump")
private data class ConstantJumpSurrogate(
    val target: AtomicId,
    val impossible: AtomicId,
)

object ConstantJumpSerializer : KSerializer<ConstantJump> {
  override val descriptor: SerialDescriptor = ConstantJumpSurrogate.serializer().descriptor

  override fun deserialize(decoder: Decoder): ConstantJump {
    throw UnsupportedOperationException("We only care about serialization")
  }

  override fun serialize(encoder: Encoder, value: ConstantJump) {
    encoder.encodeSerializableValue(
        ConstantJumpSurrogate.serializer(),
        ConstantJumpSurrogate(value.target.nodeId, value.impossible.nodeId)
    )
  }
}

private typealias LabelSurrogate = Pair<AtomicId, LabelIndex>

@Serializable
@SerialName("slak.ckompiler.analysis.CFG")
private data class CFGSurrogate(
    val functionIdentifier: TypedIdentifier,
    val functionParameters: List<TypedIdentifier>,
    val allNodes: Set<BasicBlock>,
    val startBlock: AtomicId,
    val nodes: Set<AtomicId>,
    val domTreePreorder: List<AtomicId>,
    val doms: List<AtomicId>,
    val exprDefinitions: Map<Variable, List<AtomicId>>,
    val stackVariableIds: Set<AtomicId>,
    val definitions: Map<Variable, LabelSurrogate>,
    val defUseChains: Map<Variable, List<LabelSurrogate>>,
    val latestVersions: Map<AtomicId, Int>,
    val registerIds: IdCounter,
)

object CFGSerializer : KSerializer<CFG> {
  override val descriptor: SerialDescriptor = CFGSurrogate.serializer().descriptor

  override fun deserialize(decoder: Decoder): CFG {
    val surrogate = decoder.decodeSerializableValue(CFGSurrogate.serializer())

    val nodeMap = surrogate.allNodes.associateBy { it.nodeId }

    return CFG(
        functionIdentifier = surrogate.functionIdentifier,
        functionParameters = surrogate.functionParameters,
        startBlock = nodeMap.getValue(surrogate.startBlock),
        allNodes = surrogate.allNodes,
        nodes = surrogate.nodes.mapTo(mutableSetOf()) { nodeMap.getValue(it) },
        domTreePreorder = surrogate.domTreePreorder.map { nodeMap.getValue(it) },
        doms = DominatorList(surrogate.doms.map { nodeMap.getValue(it) }),
        exprDefinitions = surrogate.exprDefinitions.mapValues { (_, value) -> value.mapTo(mutableSetOf()) { nodeMap.getValue(it) } },
        stackVariableIds = surrogate.stackVariableIds,
        definitions = surrogate.definitions.mapValues { (_, value) -> nodeMap.getValue(value.first) to value.second },
        defUseChains = surrogate.defUseChains.mapValues { (_, value) -> value.map { nodeMap.getValue(it.first) to it.second } },
        latestVersions = surrogate.latestVersions,
        registerIds = surrogate.registerIds,
    )
  }

  override fun serialize(encoder: Encoder, value: CFG) {
    val surrogate = CFGSurrogate(
        value.functionIdentifier,
        value.functionParameters,
        value.allNodes,
        value.startBlock.nodeId,
        value.nodes.mapTo(mutableSetOf()) { it.nodeId },
        value.domTreePreorder.map { it.nodeId },
        value.doms.toList().map { it?.nodeId ?: Int.MAX_VALUE },
        value.exprDefinitions.mapValues { (_, value) -> value.map { it.nodeId } },
        value.stackVariableIds,
        value.definitions.mapValues { (_, value) -> value.first.nodeId to value.second },
        value.defUseChains.mapValues { (_, value) -> value.map { (block, index) -> block.nodeId to index } },
        value.latestVersions,
        value.registerIds
    )
    encoder.encodeSerializableValue(CFGSurrogate.serializer(), surrogate)
  }
}
