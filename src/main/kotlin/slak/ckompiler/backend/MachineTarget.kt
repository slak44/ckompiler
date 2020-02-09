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

/**
 * A "template" for an instruction. Describes what is allowed as operands, and how they are used.
 */
interface InstructionTemplate {
  /**
   * Instruction name. "mov", "add", etc.
   */
  val name: String

  /**
   * What each operand represents: a definition, a use, or both.
   *
   * @see MachineInstruction.operands
   */
  val operandUse: List<VariableUse>
}

/**
 * Illegal [LabelIndex] value. Should only exist during construction.
 */
const val ILLEGAL_INDEX = Int.MIN_VALUE

data class MachineInstruction(
    val template: InstructionTemplate,
    val operands: List<IRValue>,
    var irLabelIndex: LabelIndex = ILLEGAL_INDEX
) {
  /**
   * List of [Variable]s, [VirtualRegister]s and [PhysicalRegister]s used at this label.
   */
  val uses: List<IRValue> by lazy {
    require(operands.size == template.operandUse.size)
    return@lazy operands
        .zip(template.operandUse)
        .asSequence()
        .filter { it.second == VariableUse.USE || it.second == VariableUse.DEF_USE }
        .map { it.first }
        .filter { it is Variable || it is VirtualRegister || it is PhysicalRegister }
        .toList()
  }

  /**
   * List of [Variable]s, [VirtualRegister]s and [PhysicalRegister]s defined at this label.
   */
  val defs: List<IRValue> by lazy {
    require(operands.size == template.operandUse.size)
    return@lazy operands
        .zip(template.operandUse)
        .asSequence()
        .filter { it.second == VariableUse.DEF || it.second == VariableUse.DEF_USE }
        .map { it.first }
        .filter { it is Variable || it is VirtualRegister || it is PhysicalRegister }
        .toList()
  }

  override fun toString() = "${template.name} " + operands.joinToString(", ")
}

/**
 * [BasicBlock]s and the [MachineInstruction]s created from the [BasicBlock.instructions].
 *
 * @see TargetFunGenerator.instructionSelection
 */
typealias InstructionMap = Map<BasicBlock, List<MachineInstruction>>

interface AsmInstruction {
  val template: InstructionTemplate
}

interface TargetFunGenerator {
  /**
   * Reference to source [MachineTarget].
   */
  val target: MachineTarget

  /**
   * Target function to generate code for.
   */
  val cfg: CFG

  /**
   * Constraints for current function parameters. The given [IRValue]s are marked as live-in for the
   * start block.
   */
  val parameterMap: Map<ParameterReference, IRValue>

  /**
   * Instruction selection for the function described by [cfg].
   */
  fun instructionSelection(): InstructionMap

  fun applyAllocation(alloc: AllocationResult): Map<BasicBlock, List<AsmInstruction>>

  fun genFunctionPrologue(alloc: AllocationResult): List<AsmInstruction>
  fun genFunctionEpilogue(alloc: AllocationResult): List<AsmInstruction>
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
}

fun MachineTarget.registerByName(name: String): MachineRegister {
  return registers.first { reg ->
    reg.regName == name || name in reg.aliases.map { it.first }
  }
}

fun MachineTarget.instructionScheduling(lists: InstructionMap): InstructionMap {
  // FIXME: deal with this sometime
  return lists
}

interface AsmEmitter {
  val externals: List<String>
  val functions: List<TargetFunGenerator>
  val mainCfg: TargetFunGenerator?

  fun emitAsm(): String
}
