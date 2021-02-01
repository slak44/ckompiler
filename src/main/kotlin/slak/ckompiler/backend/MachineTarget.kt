package slak.ckompiler.backend

import slak.ckompiler.AtomicId
import slak.ckompiler.IdCounter
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

/**
 * Target-independent register representation.
 */
interface MachineRegister {
  /**
   * Unique per instance.
   */
  val id: AtomicId

  val regName: String
  val sizeBytes: Int
  val valueClass: MachineRegisterClass

  /**
   * For overlapping registers files (like x64), other name+size aliases of this same register.
   */
  val aliases: List<RegisterAlias>
}

/**
 * Fake register that's actually a stack slot in the function's frame.
 */
interface StackSlot : MachineRegister

/**
 * A transient [StackSlot]. Like [VirtualRegister] for stack slots.
 */
class SpillSlot(value: StackValue, override val id: AtomicId, mtd: MachineTargetData) : StackSlot {
  override val regName = value.name
  override val sizeBytes = mtd.sizeOf(value.type.referencedType)
  override val valueClass = Memory
  override val aliases = emptyList<RegisterAlias>()

  override fun toString() = "stack slot $id (spill)"
}

/**
 * A [StackSlot] that will be exclusively used by the linked [StackVariable].
 */
class FullVariableSlot(val value: StackVariable, override val id: AtomicId, mtd: MachineTargetData) : StackSlot {
  override val regName = value.name
  override val sizeBytes = mtd.sizeOf(value.type.referencedType)
  override val valueClass = Memory
  override val aliases = emptyList<RegisterAlias>()

  override fun toString() = "stack slot $id (full)"
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

  fun filterOperands(test: (IRValue, VariableUse) -> Boolean): List<IRValue> {
    require(operands.size == template.operandUse.size)
    return operands
        .zip(template.operandUse)
        .asSequence()
        .filter { test(it.first, it.second) }
        .map { it.first }
        .filter { it !is ConstantValue && it !is ParameterReference }
        .toList()
  }

  /**
   * List of things used at this label.
   */
  val uses: List<IRValue> by lazy {
    require(operands.size == template.operandUse.size)
    return@lazy filterOperands { _, use -> use == VariableUse.USE || use == VariableUse.DEF_USE } +
        constrainedArgs.map { it.value }
  }

  /**
   * List of things defined at this label.
   */
  val defs: List<IRValue> by lazy {
    require(operands.size == template.operandUse.size)
    return@lazy filterOperands { _, use -> use == VariableUse.DEF || use == VariableUse.DEF_USE } +
        constrainedRes.map { it.value }
  }

  override fun toString(): String {
    if (template is ParallelCopyTemplate) return template.toString()
    val linkPrefix = "├─ "
    fun String.trimIfEmpty() = if (this.replace(linkPrefix, "").isBlank()) "" else this
    val linkedBefore = links
        .filter { it.pos == LinkPosition.BEFORE }
        .joinToString("\n$linkPrefix", prefix = linkPrefix, postfix = "\n") { it.mi.toString() }
        .trimIfEmpty()
        .replaceFirst(linkPrefix, "┌─ ")
    val initial = template.name + operands.joinToString(", ", prefix = " ")
    val (constrainedArgList, argUndefined) = constrainedArgs.partition { !it.value.isUndefined }
    val constrainedArgsText = constrainedArgList.joinToString("\n$linkPrefix", prefix = "\n$linkPrefix") {
      "[constrains ${it.value} to ${it.target}]"
    }.trimIfEmpty()
    val (constrainedResList, resUndefined) = constrainedRes.partition { !it.value.isUndefined }
    val constrainedResText = constrainedResList.joinToString("\n$linkPrefix", prefix = "\n$linkPrefix") {
      "[result ${it.value} constrained to ${it.target}]"
    }.trimIfEmpty()
    val truncateTo = 10
    val extraTextArgs = if (argUndefined.size > truncateTo) " + ${argUndefined.size - truncateTo} others" else ""
    val extraTextRes = if (resUndefined.size > truncateTo) " + ${resUndefined.size - truncateTo} others" else ""
    val dummyArgs = argUndefined.take(truncateTo).joinToString(", ") { it.target.regName }
    val dummyRes = resUndefined.take(truncateTo).joinToString(", ") { it.target.regName }
    val dummyLine = if (argUndefined.isNotEmpty() && resUndefined.isNotEmpty()) {
      "\n$linkPrefix[dummy args: $dummyArgs$extraTextArgs | dummy res: $dummyRes$extraTextRes]"
    } else {
      ""
    }
    val linkedAfter = links
        .filter { it.pos == LinkPosition.AFTER }
        .joinToString("\n$linkPrefix", prefix = "\n$linkPrefix") { it.mi.toString() }
        .trimIfEmpty()
    val allLinesAfter = (constrainedArgsText + constrainedResText + dummyLine + linkedAfter)
        .reversed()
        .replaceFirst(linkPrefix.reversed(), "└─ ".reversed())
        .reversed()

    return linkedBefore + initial + allLinesAfter
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

  override fun toString(): String {
    val old = values.keys.map { it.toString() }
    val oldLength = old.map { it.length }.maxOrNull() ?: 0

    val new = values.values.map { it.toString() }
    val newLength = new.map { it.length }.maxOrNull() ?: 0

    if (old.isEmpty()) {
      return "parallel copy [empty]"
    }

    val copyStr = new.zip(old).withIndex().joinToString("\n") { (idx, pair) ->
      val (newStr, oldStr) = pair
      val part1 = "  $newStr${" ".repeat((newLength - newStr.length).coerceAtLeast(0))}  "
      val part2 = "  $oldStr${" ".repeat((oldLength - oldStr.length).coerceAtLeast(0))}  "

      when (idx) {
        0 -> "[${part1.drop(1)}   [${part2.drop(1)}"
        old.size / 2 -> "$part1 ← $part2"
        old.size - 1 -> "${part1.dropLast(1)}]   ${part2.dropLast(1)}]"
        else -> "$part1   $part2"
      }
    }
    return "parallel copy\n$copyStr"
  }

  companion object {
    fun createCopy(values: Map<AllocatableValue, AllocatableValue>): MachineInstruction {
      return MachineInstruction(ParallelCopyTemplate(values), values.keys.toList() + values.values)
    }
  }
}

interface AsmInstruction {
  val template: InstructionTemplate
}

/**
 * Generates calls and returns. These operations tend to be (and very much are on x64) long and complicated, which is
 * why this separate interface exists.
 */
interface FunctionCallGenerator {
  fun createCall(
      result: LoadableValue,
      callable: IRValue,
      args: List<IRValue>
  ): List<MachineInstruction>

  fun createReturn(retVal: LoadableValue): List<MachineInstruction>
}

/**
 * Provides function-related facilities: setting up/tearing down the stack frame, handling incoming function
 * parameters, assembling the function instructions into a list.
 */
interface FunctionAssembler {
  /**
   * Constraints for current function parameters. The given [IRValue]s are marked as live-in for the start block.
   */
  val parameterMap: Map<ParameterReference, IRValue>

  /**
   * Generates the asm for the "bulk" of the function, the stuff between the prologue and the epilogue.
   */
  fun applyAllocation(alloc: AllocationResult): Map<AtomicId, List<AsmInstruction>>

  fun genFunctionPrologue(alloc: AllocationResult): List<AsmInstruction>

  /**
   * This function must always be called after [genFunctionPrologue], because it can depend on some results from it.
   */
  fun genFunctionEpilogue(alloc: AllocationResult): List<AsmInstruction>
}

/**
 * Generates [AsmInstruction]s for a single function.
 */
interface TargetFunGenerator : FunctionAssembler, FunctionCallGenerator {
  /**
   * Reference to source [MachineTarget].
   */
  val target: MachineTarget

  /**
   * Resulting graph to generate code for. The output of this generator.
   */
  val graph: InstructionGraph

  /**
   * The function's pool of [StackValue] ids.
   */
  val stackValueIds: IdCounter

  /**
   * The function's pool of [StackSlot] ids.
   */
  val stackSlotIds: IdCounter

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
  fun createJump(target: InstrBlock): MachineInstruction

  /**
   * Handle copy insertion while implementing φs.
   *
   * The copies must be placed before the jumps at the end of the block, but before the
   * compare that might make use of the pre-copy values. This is a target-dependent issue, which is
   * why this function is here.
   */
  fun insertPhiCopies(block: InstrBlock, copies: List<MachineInstruction>)
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

  /**
   * Get the maximum register pressure in each [MachineRegisterClass] for this target.
   */
  val maxPressure: Map<MachineRegisterClass, Int> get() =
      (registers - forbidden).groupBy { it.valueClass }.mapValues { it.value.size }
}

fun MachineTarget.registerByName(name: String): MachineRegister {
  return registers.first { reg ->
    reg.regName == name || name in reg.aliases.map { it.first }
  }
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
