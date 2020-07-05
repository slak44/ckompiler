package slak.ckompiler.backend

import slak.ckompiler.AtomicId
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.parser.TypeName

interface MachineRegisterClass {
  val id: AtomicId
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
  val id: AtomicId
  val regName: String
  val sizeBytes: Int
  val valueClass: MachineRegisterClass
  val aliases: List<RegisterAlias>
}

/**
 * Fake register that's actually a stack slot in the function's frame.
 */
class StackSlot(value: StackVariable, mtd: MachineTargetData) : MachineRegister {
  override val id = value.id
  override val regName = value.name
  override val sizeBytes = mtd.sizeOf(value.type.referencedType)
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

data class Constraint(val value: AllocatableValue, val target: MachineRegister)

infix fun AllocatableValue.constrainedTo(target: MachineRegister) = Constraint(this, target)

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

  /**
   * Operand constraints. For instance, in x64 the div instruction expects an operand in rdx:rax.
   */
  val implicitOperands: List<MachineRegister>

  /**
   * Result constraints. For instance, in x64 the div instruction leaves the result in rdx:rax.
   */
  val implicitResults: List<MachineRegister>
}

/**
 * Illegal [LabelIndex] value. Should only exist during construction.
 */
const val ILLEGAL_INDEX = Int.MIN_VALUE

data class MachineInstruction(
    val template: InstructionTemplate,
    val operands: List<IRValue>,
    var irLabelIndex: LabelIndex = ILLEGAL_INDEX,
    val constrainedArgs: List<Constraint> = emptyList(),
    val constrainedRes: List<Constraint> = emptyList(),
    val isConstraintCopy: Boolean = false
) {
  val isConstrained = constrainedArgs.isNotEmpty() || constrainedRes.isNotEmpty()

  /**
   * List of things used at this label.
   */
  val uses: List<IRValue> by lazy {
    require(operands.size == template.operandUse.size)
    return@lazy operands
        .zip(template.operandUse)
        .asSequence()
        .filter { it.second == VariableUse.USE || it.second == VariableUse.DEF_USE }
        .map { it.first }
        .filter { it !is ConstantValue && it !is ParameterReference }
        .toList() + constrainedArgs.map { it.value }
  }

  /**
   * List of things defined at this label.
   */
  val defs: List<IRValue> by lazy {
    require(operands.size == template.operandUse.size)
    return@lazy operands
        .zip(template.operandUse)
        .asSequence()
        .filter { it.second == VariableUse.DEF || it.second == VariableUse.DEF_USE }
        .map { it.first }
        .filter { it !is ConstantValue && it !is ParameterReference }
        .toList() + constrainedRes.map { it.value }
  }

  fun withConstraints(constrainedArgs: List<Constraint>, constrainedRes: List<Constraint>): MachineInstruction {
    return copy(constrainedArgs = constrainedArgs, constrainedRes = constrainedRes)
  }

  override fun toString(): String {
    val initial = template.name + operands.joinToString(", ", prefix = " ")
    val constrainedArgs = constrainedArgs.joinToString(" ", prefix = " ") {
      "[constrains ${it.value} to ${it.target}]"
    }
    val constrainedRes = constrainedRes.joinToString(" ", prefix = " ") {
      "[result ${it.value} constrained to ${it.target}]"
    }
    return initial + constrainedArgs + constrainedRes
  }
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

/**
 * Provides function-related facilities: setting up/tearing down the stack frame, handling incoming
 * function parameters, calling other functions, generating moves for returns.
 */
interface FunctionAssembler {
  /**
   * Constraints for current function parameters. The given [IRValue]s are marked as live-in for the
   * start block.
   */
  val parameterMap: Map<ParameterReference, IRValue>

  /**
   * All returns actually jump to this synthetic block, which then really returns from the function.
   */
  val returnBlock: BasicBlock

  /**
   * Generates the asm for the "bulk" of the function, the stuff between the prologue and the
   * epilogue.
   */
  fun applyAllocation(alloc: AllocationResult): Map<BasicBlock, List<AsmInstruction>>

  fun genFunctionPrologue(alloc: AllocationResult): List<AsmInstruction>

  /**
   * This function must always be called after [genFunctionPrologue], because it can depend on some
   * results from it.
   */
  fun genFunctionEpilogue(alloc: AllocationResult): List<AsmInstruction>

  fun createCall(
      result: LoadableValue,
      callable: IRValue,
      args: List<IRValue>
  ): List<MachineInstruction>

  fun createReturn(retVal: LoadableValue): List<MachineInstruction>
}

/**
 * Generates [AsmInstruction]s for a single function.
 */
interface TargetFunGenerator : FunctionAssembler {
  /**
   * Reference to source [MachineTarget].
   */
  val target: MachineTarget

  /**
   * Target function to generate code for.
   */
  val cfg: CFG

  /**
   * Instruction selection for the function described by [cfg].
   */
  fun instructionSelection(): InstructionMap

  /**
   * Create a [MachineInstruction] to copy [src] to [dest]. The [InstructionTemplate] is target-specific, which is why
   * this is needed. This is for pre-coloring stages.
   *
   * You might be tempted to use this function for [MachineRegister]s (via [PhysicalRegister]). Don't; use
   * [createRegisterCopy] instead.
   *
   * @see createRegisterCopy post-coloring
   */
  fun createIRCopy(dest: IRValue, src: IRValue): MachineInstruction

  /**
   * Create a register to register copy instruction. Useful for post-coloring stages.
   *
   * @see createIRCopy pre-coloring
   */
  fun createRegisterCopy(dest: MachineRegister, src: MachineRegister): MachineInstruction

  /**
   * Create a jump instruction. Useful for post-coloring stages.
   */
  fun createJump(target: BasicBlock): MachineInstruction

  /**
   * Handle copy insertion while implementing φs.
   *
   * The copies must be placed before the jumps at the end of the block, but before the
   * compare that might make use of the pre-copy values. This is a target-dependent issue, which is
   * why this function is here.
   */
  fun insertPhiCopies(
      instructions: List<MachineInstruction>,
      copies: List<MachineInstruction>
  ): List<MachineInstruction>
}

interface MachineTarget {
  val machineTargetData: MachineTargetData
  val targetName: String
  val registerClasses: List<MachineRegisterClass>

  /**
   * Complete list of registers for this target.
   */
  val registers: List<MachineRegister>

  /**
   * Do not consider these when allocating function locals.
   */
  val forbidden: List<MachineRegister>

  fun isPreservedAcrossCalls(register: MachineRegister): Boolean

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
