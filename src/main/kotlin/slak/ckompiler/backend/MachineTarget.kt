package slak.ckompiler.backend

import slak.ckompiler.AtomicId
import slak.ckompiler.IDebugHandler
import slak.ckompiler.IdCounter
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.mips32.MIPS32Generator
import slak.ckompiler.backend.mips32.MIPS32Target
import slak.ckompiler.backend.x64.*
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
 *
 * It also represents abstract memory locations, see [StackSlot]. Memory can be seen as an infinite array of registers.
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
sealed interface StackSlot : MachineRegister

/**
 * A transient [StackSlot]. Like [VirtualRegister] for stack slots.
 */
class SpillSlot(val value: StackValue, override val id: AtomicId, mtd: MachineTargetData) : StackSlot {
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
    val valueClass: MachineRegisterClass,
)

inline fun <T : MachineRegister> registers(block: MutableList<T>.() -> Unit): List<T> {
  val regs = mutableListOf<T>()
  regs.block()
  return regs
}

inline fun <T : MachineRegister> MutableList<T>.ofClass(
    valueClass: MachineRegisterClass,
    block: RegisterBuilder<T>.() -> Unit,
) {
  val builder = RegisterBuilder(this, valueClass)
  builder.block()
}

enum class VariableUse {
  DEF, USE, DEF_USE
}

/**
 * A generic operand template to an instruction (register reference, memory location, immediate, label).
 */
interface OperandTemplate

/**
 * A "template" for an instruction. Describes what is allowed as operands, and how they are used.
 */
abstract class InstructionTemplate<out T : OperandTemplate> {
  /**
   * Instruction name. "mov", "add", etc.
   */
  abstract val name: String

  /**
   * What kind of values this instruction accepts, and in what order.
   */
  abstract val operandType: List<T>

  /**
   * What each operand represents: a definition, a use, or both.
   *
   * @see MachineInstruction.operands
   */
  abstract val operandUse: List<VariableUse>

  /**
   * True if this instruction should be skipped from assembly emission.
   */
  abstract val isDummy: Boolean
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
    val template: InstructionTemplate<OperandTemplate>,
    val operands: List<IRValue>,
    var irLabelIndex: LabelIndex = ILLEGAL_INDEX,
    val constrainedArgs: List<Constraint> = emptyList(),
    val constrainedRes: List<Constraint> = emptyList(),
    val links: List<LinkedInstruction> = emptyList(),
) {
  val isConstrained = constrainedArgs.isNotEmpty() || constrainedRes.isNotEmpty()

  fun filterOperands(wantedUses: List<VariableUse>, takeIndirectUses: Boolean = false): Sequence<IRValue> {
    require(operands.size == template.operandUse.size)
    return operands
        .zip(template.operandUse)
        .asSequence()
        .filter { (value, use) ->
          use in wantedUses || (takeIndirectUses && value is MemoryLocation && value.ptr is AllocatableValue)
        }
        .flatMap { (value, use) ->
          if (takeIndirectUses && value is MemoryLocation && value.ptr is AllocatableValue) {
            return@flatMap if (use == VariableUse.USE) {
              listOf(value, value.ptr)
            } else {
              listOf(value.ptr)
            }
          }

          listOf(value)
        }
        .filter { it !is ConstantValue && it !is ParameterReference }
  }

  /**
   * List of things used at this label.
   */
  val uses: List<IRValue> by lazy {
    require(operands.size == template.operandUse.size)
    return@lazy (filterOperands(listOf(VariableUse.USE, VariableUse.DEF_USE), takeIndirectUses = true) +
        constrainedArgs.map { it.value }).toList()
  }

  /**
   * List of things defined at this label.
   */
  val defs: List<IRValue> by lazy {
    require(operands.size == template.operandUse.size)
    return@lazy (filterOperands(listOf(VariableUse.DEF, VariableUse.DEF_USE)) +
        constrainedRes.map { it.value }).toList()
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
data class ParallelCopyTemplate(val values: Map<AllocatableValue, AllocatableValue>) : InstructionTemplate<OperandTemplate>() {
  override val name get() = toString()
  override val operandUse get() = List(values.size) { VariableUse.USE } + List(values.size) { VariableUse.DEF }

  // Shouldn't be used
  override val operandType: List<OperandTemplate> = emptyList()

  // Should never reach emission anyway
  override val isDummy: Boolean = true

  override fun toString(): String {
    val old = values.keys.map { it.toString() }
    val oldLength = old.maxOfOrNull { it.length } ?: 0

    val new = values.values.map { it.toString() }
    val newLength = new.maxOfOrNull { it.length } ?: 0

    if (old.isEmpty()) {
      return "parallel copy [empty]"
    }

    if (old.size == 1) {
      return "parallel copy\n[ ${new.first()} ] ← [ ${old.first()} ]"
    }

    val copyStr = new.zip(old).withIndex().joinToString("\n") { (idx, pair) ->
      val (newStr, oldStr) = pair
      val part1 = "  $newStr${" ".repeat((newLength - newStr.length).coerceAtLeast(0))}  "
      val part2 = "  $oldStr${" ".repeat((oldLength - oldStr.length).coerceAtLeast(0))}  "

      if (old.size == 2 && idx == old.size - 1) {
        "${part1.dropLast(1)}] ← ${part2.dropLast(1)}]"
      } else when (idx) {
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

/**
 * A dummy no operation instruction.
 * Useful as a placeholder when removing instructions, so existing indices do not need to change.
 * These must be filtered before emission by [FunctionAssembler.applyAllocation].
 */
object PlaceholderTemplate : InstructionTemplate<OperandTemplate>() {
  override val name = "[empty placeholder]"
  override val operandUse = emptyList<VariableUse>()
  override val operandType = emptyList<OperandTemplate>()
  override val isDummy: Boolean = true

  fun createMI(): MachineInstruction {
    return MachineInstruction(PlaceholderTemplate, emptyList())
  }
}

interface AsmInstruction {
  val template: InstructionTemplate<OperandTemplate>
}

/**
 * Generates calls and returns. These operations tend to be (and very much are on x64) long and complicated, which is
 * why this separate interface exists.
 */
interface FunctionCallGenerator {
  fun createCall(result: LoadableValue, callable: IRValue, args: List<IRValue>): List<MachineInstruction>

  fun createReturn(retVal: LoadableValue): List<MachineInstruction>
}

/**
 * Provides function-related facilities: setting up/tearing down the stack frame, handling incoming function
 * parameters, assembling the function instructions into a list.
 */
interface FunctionAssembler<T : AsmInstruction> {
  /**
   * Constraints for current function parameters. The given [IRValue]s are marked as live-in for the start block.
   */
  val parameterMap: Map<ParameterReference, IRValue>

  /**
   * Generates the asm for the "bulk" of the function, the stuff between the prologue and the epilogue.
   */
  fun applyAllocation(alloc: AllocationResult): Map<AtomicId, List<T>>

  fun genFunctionPrologue(alloc: AllocationResult): List<T>

  /**
   * This function must always be called after [genFunctionPrologue], because it can depend on some results from it.
   */
  fun genFunctionEpilogue(alloc: AllocationResult): List<T>

  fun convertBlockInstructions(
      blockId: AtomicId,
      alloc: AllocationResult,
      convertMI: (mi: MachineInstruction) -> T,
  ): List<T> {
    val result = mutableListOf<T>()
    for (mi in alloc.graph[blockId]) {
      if (mi.template.isDummy) continue

      // Add linked before (eg cdq before div)
      for ((linkedMi) in mi.links.filter { it.pos == LinkPosition.BEFORE }) {
        result += convertMI(linkedMi)
      }
      // Add current instruction
      result += convertMI(mi)
      // Add linked after
      for ((linkedMi) in mi.links.filter { it.pos == LinkPosition.AFTER }) {
        result += convertMI(linkedMi)
      }
    }
    return result
  }
}

/**
 * Generates [AsmInstruction]s for a single function.
 */
interface TargetFunGenerator<T : AsmInstruction> : FunctionAssembler<T>, FunctionCallGenerator {
  /**
   * Reference to source [MachineTarget].
   */
  val target: MachineTarget

  /**
   * Resulting graph to generate code for. The output of this generator.
   */
  val graph: InstructionGraph

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
   * And by register, we mean [MachineRegister], which can actually be a stack slot in memory.
   *
   * @see createIRCopy pre-coloring
   */
  fun createRegisterCopy(dest: MachineRegister, src: MachineRegister): MachineInstruction

  /**
   * Create an instruction to push the contents of [src] to the stack. This is intended only for local operations, ones
   * which control all instructions between the push and the pop, and can guarantee there will be no other memory
   * accesses on the stack from them.
   *
   * @see createLocalPop
   */
  fun createLocalPush(src: MachineRegister): List<MachineInstruction>

  /**
   * Create an instruction to pop the contents from the top of the stack into [dest].
   *
   * @see createLocalPush
   */
  fun createLocalPop(dest: MachineRegister): List<MachineInstruction>

  /**
   * Create an (unconditional) jump instruction.
   */
  fun createJump(target: InstrBlock): MachineInstruction

  /**
   * Handle copy insertion while implementing φs.
   *
   * The copies must be placed before the jumps at the end of the block, but after the
   * compare that might make use of the pre-copy values. This is a target-dependent issue, which is
   * why this function is here.
   */
  fun insertPhiCopies(block: InstrBlock, copies: List<MachineInstruction>)
}

typealias AnyFunGenerator = TargetFunGenerator<out AsmInstruction>

fun createTargetFunGenerator(cfg: CFG, target: MachineTarget): AnyFunGenerator {
  return when (target.isaType) {
    ISAType.X64 -> X64Generator(cfg, target as X64Target)
    ISAType.MIPS32 -> MIPS32Generator(cfg, target as MIPS32Target)
  }
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
  val isaType: ISAType
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
  val forbidden: Set<MachineRegister>

  fun isPreservedAcrossCalls(register: MachineRegister): Boolean

  fun registerClassOf(type: TypeName): MachineRegisterClass

  /**
   * Get the maximum register pressure in each [MachineRegisterClass] for this target.
   */
  val maxPressure: Map<MachineRegisterClass, Int>
    get() = (registers - forbidden).groupBy { it.valueClass }.mapValues { it.value.size }
}

fun MachineTarget.registerByName(name: String): MachineRegister {
  return registers.first { reg ->
    reg.regName == name || name in reg.aliases.map { it.first }
  }
}

/**
 * Undefined behaviour, can do whatever; we pick the first register of the correct class.
 * This is useful, because all undefined things will be "in" the same register, which will create a bunch of
 * "mov rax, rax"-type instructions that are easy to remove.
 */
fun MachineTarget.getUndefinedRegisterFor(undefinedValue: LoadableValue): MachineRegister {
  check(undefinedValue.isUndefined)
  return (registers - forbidden)
      .first { reg -> reg.valueClass == registerClassOf(undefinedValue.type) }
}

fun IDebugHandler.createMachineTarget(isaType: ISAType, baseTargetOpts: TargetOptions, targetSpecific: List<String>): MachineTarget {
  return when (isaType) {
    ISAType.X64 -> X64Target(X64TargetOpts(baseTargetOpts, targetSpecific, this))
    ISAType.MIPS32 -> MIPS32Target(baseTargetOpts)
  }
}

interface PeepholeOptimizer<T : AsmInstruction> {
  fun optimize(targetFun: TargetFunGenerator<out T>, asm: List<T>): List<T>
}

fun createPeepholeOptimizer(isaType: ISAType): PeepholeOptimizer<AsmInstruction> {
  @Suppress("UNCHECKED_CAST")
  return when (isaType) {
    ISAType.X64 -> X64PeepholeOpt() as PeepholeOptimizer<AsmInstruction>
    ISAType.MIPS32 -> TODO("MIPS32")
  }
}

interface AsmEmitter<T : AsmInstruction> {
  val externals: List<String>
  val functions: List<TargetFunGenerator<T>>
  val mainCfg: TargetFunGenerator<T>?

  fun emitAsm(): String
}

fun createAsmEmitter(
    isaType: ISAType,
    externals: List<String>,
    functions: List<AnyFunGenerator>,
    mainCfg: AnyFunGenerator?,
): AsmEmitter<out AsmInstruction> {
  @Suppress("UNCHECKED_CAST")
  return when (isaType) {
    ISAType.X64 -> NasmEmitter(
        externals,
        functions as List<TargetFunGenerator<X64Instruction>>,
        mainCfg as TargetFunGenerator<X64Instruction>?
    )
    ISAType.MIPS32 -> TODO("MIPS")
  }
}
