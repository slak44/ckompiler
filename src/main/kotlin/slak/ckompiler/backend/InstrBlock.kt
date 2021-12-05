package slak.ckompiler.backend

import mu.KotlinLogging
import slak.ckompiler.AtomicId
import slak.ckompiler.analysis.BasicBlock
import slak.ckompiler.analysis.VersionedValue
import slak.ckompiler.throwICE

private val logger = KotlinLogging.logger {}

typealias InstrPhi = MutableMap<VersionedValue, MutableMap<AtomicId, VersionedValue>>

class InstrBlock(
    val id: AtomicId,
    val seqId: Int,
    val domTreeHeight: Int,
    private val graph: InstructionGraph,
    private val instructions: MutableList<MachineInstruction>,
    val phi: InstrPhi
) : MutableList<MachineInstruction> by instructions {
  val phiDefs get() = phi.keys
  val phiUses get() = phi.values.flatMap { it.values }

  /**
   * Bypass the [InstructionGraph.updateIndices] calls in this object's [MutableList] implementation to get the
   * underlying object.
   *
   * This is useful for post-allocation phases, where liveness data is no longer useful, and the index updates are a
   * waste.
   *
   * Still, callers beware.
   */
  fun unsafelyGetInstructions() = instructions

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

  override fun listIterator(): MutableListIterator<MachineInstruction> {
    val it = instructions.listIterator()
    return object : MutableListIterator<MachineInstruction> by it {
      override fun remove() {
        it.remove()
        graph.updateIndices(id, it.nextIndex(), -1)
      }

      override fun add(element: MachineInstruction) {
        it.add(element)
        graph.updateIndices(id, it.previousIndex(), 1)
      }
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
      // The type of transformedPhi should be covariant with InstrPhi, but the compiler can't guarantee that
      @Suppress("UNCHECKED_CAST")
      transformedPhi as InstrPhi
      return InstrBlock(bb.nodeId, bb.postOrderId, bb.height, graph, instrs.toMutableList(), transformedPhi)
    }
  }
}
