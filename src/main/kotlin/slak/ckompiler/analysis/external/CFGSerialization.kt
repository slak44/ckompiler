package slak.ckompiler.analysis.external

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import slak.ckompiler.AtomicId
import slak.ckompiler.IdCounter
import slak.ckompiler.ThreadLocal
import slak.ckompiler.analysis.*
import slak.ckompiler.parser.ErrorExpression
import slak.ckompiler.parser.ExprConstantNode
import slak.ckompiler.parser.TypedIdentifier
import slak.ckompiler.threadLocalWithInitial

@Serializable
@SerialName("slak.ckompiler.analysis.BasicBlock")
private data class BasicBlockSurrogate(
    val isRoot: Boolean,
    val phi: Set<PhiInstruction>,
    val ir: List<IRInstruction>,
    val postOrderId: Int,
    val height: Int,
    val nodeId: AtomicId,
    val predecessors: List<AtomicId>,
    val dominanceFrontier: List<AtomicId>,
    val terminator: Jump,
)

class BasicBlockSerializer : KSerializer<BasicBlock> {
  override val descriptor: SerialDescriptor = BasicBlockSurrogate.serializer().descriptor

  override fun deserialize(decoder: Decoder): BasicBlock {
    val surrogate = decoder.decodeSerializableValue(BasicBlockSurrogate.serializer())

    val nodeMap = CFGSerializer.allNodesReference.get()

    val node = nodeMap.getValue(surrogate.nodeId)
    node.terminator = surrogate.terminator
    node.phi += surrogate.phi
    node.ir += surrogate.ir
    node.postOrderId = surrogate.postOrderId
    node.height = surrogate.height
    node.preds.clear()
    node.preds += surrogate.predecessors.map { nodeMap.getValue(it) }
    node.dominanceFrontier += surrogate.dominanceFrontier.map { nodeMap.getValue(it) }

    return node
  }

  override fun serialize(encoder: Encoder, value: BasicBlock) {
    val surrogate = BasicBlockSurrogate(
        value.isRoot,
        value.phi,
        value.ir,
        value.postOrderId,
        value.height,
        value.nodeId,
        value.preds.map { it.nodeId },
        value.dominanceFrontier.map { it.nodeId },
        value.terminator
    )
    encoder.encodeSerializableValue(BasicBlockSurrogate.serializer(), surrogate)
  }
}

@Serializable
@SerialName("PhiInstruction")
private data class PhiInstructionSurrogate(
    val variable: Variable,
    val incoming: Map<AtomicId, Variable>,
)

object PhiInstructionSerializer : KSerializer<PhiInstruction> {
  override val descriptor: SerialDescriptor = PhiInstructionSurrogate.serializer().descriptor

  override fun deserialize(decoder: Decoder): PhiInstruction {
    val surrogate = decoder.decodeSerializableValue(PhiInstructionSurrogate.serializer())
    val nodeMap = CFGSerializer.allNodesReference.get()
    return PhiInstruction(surrogate.variable, surrogate.incoming.mapKeys { (key) -> nodeMap.getValue(key) })
  }

  override fun serialize(encoder: Encoder, value: PhiInstruction) {
    val surrogate = PhiInstructionSurrogate(value.variable, value.incoming.mapKeys { it.key.nodeId })
    encoder.encodeSerializableValue(PhiInstructionSurrogate.serializer(), surrogate)
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
    val surrogate = decoder.decodeSerializableValue(CondJumpSurrogate.serializer())
    val nodeMap = CFGSerializer.allNodesReference.get()
    return CondJump(surrogate.cond, ErrorExpression(), nodeMap.getValue(surrogate.target), nodeMap.getValue(surrogate.other))
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
    val options: Map<ExprConstantNode, AtomicId>,
    val default: AtomicId,
)

object SelectJumpSerializer : KSerializer<SelectJump> {
  override val descriptor: SerialDescriptor = SelectJumpSurrogate.serializer().descriptor

  override fun deserialize(decoder: Decoder): SelectJump {
    val surrogate = decoder.decodeSerializableValue(SelectJumpSurrogate.serializer())
    val nodeMap = CFGSerializer.allNodesReference.get()
    return SelectJump(
        surrogate.cond,
        ErrorExpression(),
        surrogate.options.mapValues { nodeMap.getValue(it.value) },
        nodeMap.getValue(surrogate.default)
    )
  }

  override fun serialize(encoder: Encoder, value: SelectJump) {
    encoder.encodeSerializableValue(
        SelectJumpSurrogate.serializer(),
        SelectJumpSurrogate(value.cond, value.options.mapValues { it.value.nodeId }, value.default.nodeId)
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
    val surrogate = decoder.decodeSerializableValue(UncondJumpSurrogate.serializer())
    val nodeMap = CFGSerializer.allNodesReference.get()
    return UncondJump(nodeMap.getValue(surrogate.target))
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
    val surrogate = decoder.decodeSerializableValue(ImpossibleJumpSurrogate.serializer())
    val nodeMap = CFGSerializer.allNodesReference.get()
    return ImpossibleJump(nodeMap.getValue(surrogate.target), surrogate.returned, ErrorExpression())
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
    val surrogate = decoder.decodeSerializableValue(ConstantJumpSurrogate.serializer())
    val nodeMap = CFGSerializer.allNodesReference.get()
    return ConstantJump(nodeMap.getValue(surrogate.target), nodeMap.getValue(surrogate.impossible))
  }

  override fun serialize(encoder: Encoder, value: ConstantJump) {
    encoder.encodeSerializableValue(
        ConstantJumpSurrogate.serializer(),
        ConstantJumpSurrogate(value.target.nodeId, value.impossible.nodeId)
    )
  }
}

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
    val definitions: Map<Variable, Label>,
    val defUseChains: Map<Variable, List<Label>>,
    val latestVersions: Map<AtomicId, Int>,
    val registerIds: IdCounter,
)

class CFGSerializer : KSerializer<CFG> {
  private val idsSerializer = SetSerializer(AtomicId.serializer())
  private val typedIdentifiersSerializer = ListSerializer(TypedIdentifier.serializer())

  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CFGSerializer") {
    element("startBlockId", Int.serializer().descriptor)
    element("allNodeIds", idsSerializer.descriptor)
    element("typedIdentifiers", typedIdentifiersSerializer.descriptor)
    element("cfg", CFGSurrogate.serializer().descriptor)
  }

  override fun deserialize(decoder: Decoder): CFG {
    val startId = decoder.decodeInt()

    val allNodeIds = decoder.decodeSerializableValue(idsSerializer)
    val nodeMap = allNodesReference.get()
    nodeMap.clear()
    for (nodeId in allNodeIds) {
      nodeMap[nodeId] = BasicBlock(isRoot = nodeId == startId, nodeId = nodeId)
    }

    val valueFactory = IRValueFactory()
    for (tid in decoder.decodeSerializableValue(typedIdentifiersSerializer)) {
      valueFactory.createTypedIdentifier(tid)
    }
    irValueFactory.set(valueFactory)

    val surrogate = decoder.decodeSerializableValue(CFGSurrogate.serializer())

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
        definitions = surrogate.definitions,
        defUseChains = surrogate.defUseChains,
        latestVersions = surrogate.latestVersions,
        registerIds = surrogate.registerIds,
    )
  }

  override fun serialize(encoder: Encoder, value: CFG) {
    encoder.encodeInt(value.startBlock.nodeId)

    val allNodeIds = value.allNodes.mapTo(mutableSetOf()) { it.nodeId }
    encoder.encodeSerializableValue(idsSerializer, allNodeIds)

    val tids = value.exprDefinitions.keys.map { it.tid }.distinctBy { it.id }
    encoder.encodeSerializableValue(typedIdentifiersSerializer, tids)

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
        value.definitions,
        value.defUseChains,
        value.latestVersions,
        value.registerIds
    )
    encoder.encodeSerializableValue(CFGSurrogate.serializer(), surrogate)
  }

  companion object {
    /**
     * Assuming there will only ever be one deserialization per-thread, this stores the deserialized [CFGSurrogate.allNodes].
     *
     * Serializers don't store the entire [BasicBlock], only the [BasicBlock.nodeId].
     * As a result, deserialization of nested objects (eg [PhiInstructionSerializer]) will make use of these to add the correct
     * [BasicBlock] references.
     */
    val allNodesReference: ThreadLocal<MutableMap<AtomicId, BasicBlock>> = threadLocalWithInitial { mutableMapOf() }

    val irValueFactory: ThreadLocal<IRValueFactory> = threadLocalWithInitial { IRValueFactory() }
  }
}
