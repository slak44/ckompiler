package slak.ckompiler.backend

import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.parser.TypeName

interface MachineRegisterClass {
  val id: Int
  val displayName: String
}

/**
 * A pseudo-register class used for values that have been spilled by the register allocator.
 */
object Memory : MachineRegisterClass {
  override val id = -2
  override val displayName = "<stack slot>"
}

/**
 * Alias name + alias size.
 */
typealias RegisterAlias = Pair<String, Int>

fun alias(name: String, size: Int): RegisterAlias = name to size

interface MachineRegister {
  val id: Int
  val regName: String
  val sizeBytes: Int
  val valueClass: MachineRegisterClass
  val aliases: List<RegisterAlias>
}

/**
 * Fake register that's actually a stack slot in the function's frame.
 */
class StackSlot(value: MemoryReference, mtd: MachineTargetData) : MachineRegister {
  override val id = value.id
  override val regName = value.name
  override val sizeBytes = mtd.sizeOf(value.type)
  override val valueClass = Memory
  override val aliases = emptyList<RegisterAlias>()

  override fun toString() = "stack slot $id"
}

class RegisterBuilder<T : MachineRegister>(
    val regs: MutableList<T>,
    val valueClass: MachineRegisterClass
)

inline fun <T : MachineRegister> registers(block: MutableList<T>.() -> Unit): List<T> {
  val regs = mutableListOf<T>()
  regs.block()
  return regs
}

inline fun <T : MachineRegister> MutableList<T>.ofClass(
    valueClass: MachineRegisterClass,
    block: RegisterBuilder<T>.() -> Unit
) {
  val builder = RegisterBuilder(this, valueClass)
  builder.block()
}

enum class VariableUse {
  DEF, USE, DEF_USE
}

interface InstructionTemplate {
  val name: String
  val operandUse: List<VariableUse>
}

data class MachineInstruction(val template: InstructionTemplate, val operands: List<IRValue>)

fun List<MachineInstruction>.stringify(): String {
  return joinToString(separator = "\n") {
    "${it.template.name} " + it.operands.joinToString(", ")
  }
}

interface MachineTarget {
  val machineTargetData: MachineTargetData
  val targetName: String
  val registers: List<MachineRegister>

  /**
   * Do not consider these when allocating function locals.
   */
  val forbidden: List<MachineRegister>

  fun registerClassOf(type: TypeName): MachineRegisterClass

  /**
   * Instruction selection for one [IRInstruction].
   */
  fun expandMacroFor(i: IRInstruction): List<MachineInstruction>

  /**
   * Runs a pass over a [BasicBlock], and possibly mutates the IR in it.
   *
   * Useful for stuff like SSA destruction.
   *
   * Is executed before instruction selection, that is, before [expandMacroFor] is called.
   */
  fun localIRTransform(bb: BasicBlock)

  fun genFunctionPrologue(lists: ISelMap): List<MachineInstruction>
  fun genFunctionEpilogue(lists: ISelMap): List<MachineInstruction>
}

fun MachineTarget.registerByName(name: String): MachineRegister {
  return registers.first { reg ->
    reg.regName == name || name in reg.aliases.map { it.first }
  }
}

/**
 * [BasicBlock]s and the [MachineInstruction]s created from the [BasicBlock.instructions].
 *
 * @see MachineTarget.instructionSelection
 */
typealias ISelMap = Map<BasicBlock, List<MachineInstruction>>

fun MachineTarget.instructionSelection(cfg: CFG): ISelMap {
  return cfg.nodes
      .onEach(::localIRTransform)
      .associateWith { block ->
        block.instructions
            .asSequence()
            .filter { it !is PhiInstr }
            .flatMap { expandMacroFor(it).asSequence() }
            .toList()
      }
}

fun MachineTarget.instructionScheduling(lists: ISelMap): ISelMap {
  // FIXME: deal with this sometime
  return lists
}
