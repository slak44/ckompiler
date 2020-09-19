package slak.ckompiler.backend

import org.apache.logging.log4j.LogManager
import slak.ckompiler.AtomicId
import slak.ckompiler.analysis.BasicBlock
import slak.ckompiler.analysis.Variable
import slak.ckompiler.throwICE

private val logger = LogManager.getLogger()

typealias InstrPhi = MutableMap<Variable, MutableMap<AtomicId, Variable>>

class InstrBlock(
    val id: AtomicId,
    val seqId: Int,
    val domTreeHeight: Int,
    private val graph: InstructionGraph,
    private val instructions: MutableList<MachineInstruction>,
    val phi: InstrPhi
) : MutableList<MachineInstruction> by instructions {
  override fun remove(element: MachineInstruction): Boolean {
    return removeAll(listOf(element))
  }

  override fun removeAll(elements: Collection<MachineInstruction>): Boolean {
    logger.throwICE("Do not use this function; it is complicated to update the graph's deaths with it")
  }

  override fun removeAt(index: Int): MachineInstruction {
    return instructions.removeAt(index).also {
      graph.updateIndices(id, index, -1)
    }
  }

  override fun add(index: Int, element: MachineInstruction) {
    instructions.add(index, element)
    graph.updateIndices(id, index, 1)
  }

  override fun addAll(index: Int, elements: Collection<MachineInstruction>): Boolean {
    return instructions.addAll(index, elements).also {
      graph.updateIndices(id, index, elements.size)
    }
  }

  override fun hashCode() = id
  override fun equals(other: Any?) = id == (other as? InstrBlock)?.id

  override fun toString(): String {
    return "InstrBlock(id = $id, seq = $seqId, h = $domTreeHeight, succ = ${graph.successors(id).map { it.id }})"
  }

  companion object {
    fun fromBasic(graph: InstructionGraph, bb: BasicBlock, instrs: List<MachineInstruction>): InstrBlock {
      require(bb.height != -1 && bb.postOrderId != -1)
      val transformedPhi =
          bb.phi.associate { it.variable to it.incoming.mapKeys { (key) -> key.nodeId }.toMutableMap() }.toMutableMap()
      return InstrBlock(bb.nodeId, bb.postOrderId, bb.height, graph, instrs.toMutableList(), transformedPhi)
    }
  }
}
