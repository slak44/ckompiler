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

data class Constraint(val value: AllocatableValue, val target: MachineRegister)

infix fun AllocatableValue.constrainedTo(target: MachineRegister) = Constraint(this, target)

enum class LinkPosition { BEFORE, AFTER }

data class LinkedInstruction(val mi: MachineInstruction, val pos: LinkPosition)

data class MachineInstruction(
    val template: InstructionTemplate,
    val operands: List<IRValue>,
    var irLabelIndex: LabelIndex = ILLEGAL_INDEX,
    val constrainedArgs: List<Constraint> = emptyList(),
    val constrainedRes: List<Constraint> = emptyList(),
    val links: List<LinkedInstruction> = emptyList()
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

  override fun toString(): String {
    if (template is ParallelCopyTemplate) return template.toString()
    fun String.trimIfEmpty() = if (this.isBlank()) "" else this
    val linkedBefore = links
        .filter { it.pos == LinkPosition.BEFORE }
        .joinToString("\n\t", prefix = "\t", postfix = "\n") { it.mi.toString() }
        .trimIfEmpty()
    val initial = template.name + operands.joinToString(", ", prefix = " ")
    val (constrainedArgList, argUndefined) = constrainedArgs.partition { !it.value.isUndefined }
    val constrainedArgsText = constrainedArgList.joinToString("\n\t", prefix = "\n\t") {
      "[constrains ${it.value} to ${it.target}]"
    }.trimIfEmpty()
    val (constrainedResList, resUndefined) = constrainedRes.partition { !it.value.isUndefined }
    val constrainedResText = constrainedResList.joinToString("\n\t", prefix = "\n\t") {
      "[result ${it.value} constrained to ${it.target}]"
    }.trimIfEmpty()
    val dummyArgs = argUndefined.joinToString(", ") { it.target.regName }
    val dummyRes = resUndefined.joinToString(", ") { it.target.regName }
    val dummyLine = if (argUndefined.isNotEmpty() && resUndefined.isNotEmpty()) {
      "\n\t[dummy args: $dummyArgs | dummy res: $dummyRes]"
    } else {
      ""
    }
    val linkedAfter = links
        .filter { it.pos == LinkPosition.AFTER }
        .joinToString("\n\t", prefix = "\n\t") { it.mi.toString() }
        .trimIfEmpty()
    return linkedBefore + initial + constrainedArgsText + constrainedResText + dummyLine + linkedAfter
  }
}

/**
 * This is technically still a φ instruction, but this one is in the middle of a block, and essentially models a live
 * range split for the given variables (since it is in a block, "variables" actually includes [VirtualRegister]s too).
 *
 * It stores pairs of values: the old version, and the new version.
 *
 * Internally used by the register allocator, should probably not make it out of it.
 */
data class ParallelCopyTemplate(val values: Map<AllocatableValue, AllocatableValue>) : InstructionTemplate {
  override val name = toString()
  override val operandUse = List(values.size) { VariableUse.USE } + List(values.size) { VariableUse.DEF }
  override val implicitOperands: List<MachineRegister> = emptyList()
  override val implicitResults: List<MachineRegister> = emptyList()

  override fun toString(): String {
    val old = values.keys.joinToString(", ")
    val new = values.values.joinToString(", ")
    return "parallel copy [$new] ← [$old]"
  }

  companion object {
    fun createCopy(values: Map<AllocatableValue, AllocatableValue>): MachineInstruction {
      return MachineInstruction(ParallelCopyTemplate(values), values.keys.toList() + values.values)
    }
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

/**
 * Base options for a compilation target. Each target also has extra options beyond these.
 */
interface TargetOptions {
  val omitFramePointer: Boolean

  companion object {
    val defaults = object : TargetOptions {
      override val omitFramePointer = true
    }
  }
}

interface MachineTarget {
  val machineTargetData: MachineTargetData
  val targetName: String
  val registerClasses: List<MachineRegisterClass>
  val options: TargetOptions

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

interface PeepholeOptimizer<T : AsmInstruction> {
  fun optimize(targetFun: TargetFunGenerator, asm: List<T>): List<T>
}

interface AsmEmitter {
  val externals: List<String>
  val functions: List<TargetFunGenerator>
  val mainCfg: TargetFunGenerator?

  fun emitAsm(): String
}
