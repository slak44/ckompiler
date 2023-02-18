package slak.ckompiler.backend.x64

import mu.KotlinLogging
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*
import slak.ckompiler.backend.x64.Imm.*
import slak.ckompiler.backend.x64.ModRM.*
import slak.ckompiler.throwICE
import kotlin.jvm.JvmName
import kotlin.math.absoluteValue

private val logger = KotlinLogging.logger {}

enum class OperandType {
  REGISTER, MEMORY, REG_OR_MEM
}

interface X64Operand : Operand {
  val size: Int
}

enum class ModRM(val type: OperandType, override val size: Int) : X64Operand {
  RM8(OperandType.REG_OR_MEM, 1),
  RM16(OperandType.REG_OR_MEM, 2),
  RM32(OperandType.REG_OR_MEM, 4),
  RM64(OperandType.REG_OR_MEM, 8),

  R8(OperandType.REGISTER, 1),
  R16(OperandType.REGISTER, 2),
  R32(OperandType.REGISTER, 4),
  R64(OperandType.REGISTER, 8),

  M16(OperandType.MEMORY, 2),
  M32(OperandType.MEMORY, 4),
  M64(OperandType.MEMORY, 8),
  M(OperandType.MEMORY, 0), // Any memory

  XMM_SS(OperandType.REGISTER, 4),
  XMM_SD(OperandType.REGISTER, 8)
}

enum class Imm(override val size: Int) : X64Operand {
  IMM8(1), IMM16(2), IMM32(4), IMM64(8)
}

data class Register(val register: MachineRegister) : X64Operand {
  override val size = register.sizeBytes
}

object JumpTarget : X64Operand {
  override val size = 0
}

object NamedDisplacement : X64Operand {
  override val size = 0
}

data class X64InstrTemplate(
    override val name: String,
    override val operandType: List<X64Operand>,
    override val operandUse: List<VariableUse>,
) : InstructionTemplate<X64Operand>()

sealed class X64Value

data class ImmediateValue(val value: ConstantValue) : X64Value() {
  override fun toString() = value.toString()
}

data class RegisterValue(val register: MachineRegister, val size: Int) : X64Value() {
  constructor(phys: PhysicalRegister) : this(phys.reg, MachineTargetData.x64.sizeOf(phys.type))

  init {
    require(register.valueClass != Memory) {
      "RegisterValue cannot refer to memory: $register"
    }
  }

  override fun toString(): String {
    if (register.sizeBytes == size) return register.regName
    return register.aliases.first { it.second == size }.first
  }
}

/**
 * Intel 64 Basic Architecture (volume 1): 3.7.5.1
 *
 * Intel 64 ISA Reference (volume 2): 2.1.5
 */
data class MemoryValue(
    val sizeInMem: Int,
    val base: RegisterValue,
    val index: RegisterValue? = null,
    val scale: Int? = null,
    val displacement: Int? = null,
) : X64Value() {
  init {
    require(scale == null || index != null) {
      "Cannot use scale with no index"
    }
    require(scale == null || scale in arrayOf(1, 2, 4, 8)) {
      "Scale can only be 1, 2, 4, or 8"
    }
  }

  override fun toString(): String {
    val indexText = when {
      index == null -> ""
      scale != null -> " + $scale * $index"
      else -> " + $index"
    }
    val dispText = when {
      displacement == null || displacement == 0 -> ""
      displacement < 0 -> " - ${displacement.absoluteValue}"
      else -> " + $displacement"
    }
    return "[$base$indexText$dispText]"
  }

  companion object {
    private val rbp = RegisterValue(X64Target().registerByName("rbp"), 8)

    fun frameAbs(stackSlot: StackSlot, frameOffset: Int): MemoryValue {
      return MemoryValue(stackSlot.sizeBytes, base = rbp, displacement = frameOffset)
    }

    fun inFrame(stackSlot: StackSlot, frameOffset: Int): MemoryValue {
      return MemoryValue(stackSlot.sizeBytes, base = rbp, displacement = -(frameOffset + stackSlot.sizeBytes))
    }
  }
}

data class X64Instruction(
    override val template: X64InstrTemplate,
    val operands: List<X64Value>,
) : AsmInstruction {
  override fun toString() = "${template.name} ${operands.joinToString(", ")}"
}

private infix fun X64Operand.compatibleWith(ref: IRValue): Boolean {
  if (this is NamedDisplacement && ref is NamedConstant) return true
  if (this is JumpTarget && ref is JumpTargetConstant) return true

  val refSize = MachineTargetData.x64.sizeOf(ref.type.unqualify().normalize())
  val sizeMatches = refSize == size

  return when (ref) {
    is MemoryLocation, is DerefStackValue, is StrConstant, is FltConstant -> {
      (sizeMatches || this == M) && this is ModRM && (type == OperandType.REG_OR_MEM || type == OperandType.MEMORY)
    }
    is VirtualRegister, is Variable, is StackVariable, is StackValue -> {
      sizeMatches && (this is Register || (this is ModRM && (type == OperandType.REG_OR_MEM || type == OperandType.REGISTER)))
    }
    is PhysicalRegister -> {
      val isCorrectKind = (this is Register && register == ref.reg) ||
          (this is ModRM && (type == OperandType.REG_OR_MEM || type == OperandType.REGISTER))
      val isCorrectSize = size == ref.reg.sizeBytes || size in ref.reg.aliases.map { it.second }
      isCorrectKind && isCorrectSize
    }
    is IntConstant -> when {
      this !is Imm -> false
      sizeMatches -> true
      else -> {
        val highestBitSet = Long.SIZE_BITS - ref.value.countLeadingZeroBits()
        val immSizeBits = size * 8
        highestBitSet < immSizeBits
      }
    }
    is NamedConstant, is JumpTargetConstant -> false // was checked above
    is ParameterReference -> logger.throwICE("Parameter references were removed")
  }
}

fun List<X64InstrTemplate>.tryMatch(vararg operands: IRValue): MachineInstruction? {
  val instr = firstOrNull {
    it.operandType.size == operands.size &&
        operands.zip(it.operandType).all { (ref, targetOperand) -> targetOperand compatibleWith ref }
  } ?: return null
  return MachineInstruction(instr, operands.toList())
}

fun List<X64InstrTemplate>.match(vararg operands: IRValue): MachineInstruction {
  val res = tryMatch(*operands)
  return checkNotNull(res) {
    val likely = filter { it.operandType.size == operands.size }.sortedByDescending {
      operands.zip(it.operandType).count { (ref, targetOperand) -> targetOperand compatibleWith ref }
    }
    "Instruction selection failure: match ${operands.toList()} in $this\n" +
        "Likely candidate: ${likely.firstOrNull()}"
  }
}

fun List<X64InstrTemplate>.tryMatchAsm(vararg operands: X64Value): X64Instruction? {
  val instr = firstOrNull {
    it.operandType.size == operands.size && operands.zip(it.operandType).all { (value, targetOperand) ->
      when (targetOperand) {
        is Register -> value is RegisterValue && value.register == targetOperand.register
        is Imm -> value is ImmediateValue && MachineTargetData.x64.sizeOf(value.value.type) == targetOperand.size
        is ModRM -> {
          val matchReg = value is RegisterValue && targetOperand.size == value.size
          val matchMem = value is MemoryValue && targetOperand.size == value.sizeInMem
          when (targetOperand.type) {
            OperandType.REGISTER -> matchReg
            OperandType.MEMORY -> matchMem
            OperandType.REG_OR_MEM -> matchReg || matchMem
          }
        }
        else -> false
      }
    }
  }
  return if (instr == null) null else X64Instruction(instr, operands.toList())
}

fun List<X64InstrTemplate>.matchAsm(vararg operands: X64Value): X64Instruction {
  val instr = tryMatchAsm(*operands)
  return checkNotNull(instr) { "Failed to create instruction from the given operands: ${operands.toList()} in $this" }
}

fun X64Target.matchAsmMov(dest: X64Value, src: X64Value): X64Instruction {
  require(dest !is ImmediateValue)
  val destClass = if (dest is RegisterValue) dest.register.valueClass else Memory
  val srcClass = when (src) {
    is ImmediateValue -> registerClassOf(src.value.type)
    is RegisterValue -> src.register.valueClass
    is MemoryValue -> Memory
  }
  val srcSize = when (src) {
    is ImmediateValue -> machineTargetData.sizeOf(src.value.type)
    is RegisterValue -> src.size
    is MemoryValue -> src.sizeInMem
  }
  return when (validateClasses(destClass, srcClass)) {
    X64RegisterClass.INTEGER -> mov.matchAsm(dest, src)
    X64RegisterClass.SSE -> when (srcSize) {
      4 -> movss.tryMatchAsm(dest, src) ?: movq.matchAsm(dest, src)
      8 -> movsd.tryMatchAsm(dest, src) ?: movq.matchAsm(dest, src)
      else -> logger.throwICE("Float size not 4 or 8 bytes")
    }
    X64RegisterClass.X87 -> TODO("x87 movs")
  }
}

/**
 * Create a generic copy instruction. Figures out register class and picks the correct kind of move.
 */
fun matchTypedMov(dest: IRValue, src: IRValue): MachineInstruction {
  return if (src is StackVariable) {
    lea.match(dest, MemoryLocation(src))
  } else {
    matchRegTypedMov(dest, src)
  }
}

private fun matchRegTypedMov(dest: IRValue, src: IRValue) = when (validateClasses(dest, src)) {
  X64RegisterClass.INTEGER -> mov.match(dest, src)
  X64RegisterClass.SSE -> when (MachineTargetData.x64.sizeOf(src.type)) {
    4 -> movss.tryMatch(dest, src) ?: movq.match(dest, src)
    8 -> movsd.tryMatch(dest, src) ?: movq.match(dest, src)
    else -> logger.throwICE("Float size not 4 or 8 bytes")
  }
  X64RegisterClass.X87 -> TODO("x87 movs")
}

fun validateClasses(dest: IRValue, src: IRValue): X64RegisterClass {
  val destRegClass = X64Target().registerClassOf(dest.type)
  val srcRegClass = X64Target().registerClassOf(src.type)
  return validateClasses(destRegClass, srcRegClass)
}

/**
 * Gets the common non-memory register class of the operands.
 */
fun validateClasses(destRegClass: MachineRegisterClass, srcRegClass: MachineRegisterClass): X64RegisterClass {
  require(destRegClass != Memory || srcRegClass != Memory) { "No memory-to-memory move exists" }
  val nonMemoryClass = if (destRegClass != Memory && srcRegClass != Memory) {
    require(destRegClass == srcRegClass) { "Move between register classes without cast" }
    destRegClass
  } else {
    if (destRegClass == Memory) srcRegClass else destRegClass
  }
  require(nonMemoryClass is X64RegisterClass)
  return nonMemoryClass
}

fun x64InstructionClass(
    name: String,
    defaultUse: List<VariableUse>? = null,
    block: ICBuilder<X64Operand, X64InstrTemplate>.() -> Unit,
)= instructionClass(name, ::X64InstrTemplate, defaultUse, block)

typealias X64ICBuilderBlock = ICBuilder<X64Operand, X64InstrTemplate>.() -> Unit

private val nullary: X64ICBuilderBlock = { instr() }

val dummyUse = dummyInstructionClass("USE", ::X64InstrTemplate, listOf(VariableUse.USE)) {
  instr(R8)
  instr(R16)
  instr(R32)
  instr(R64)
}

val imul = x64InstructionClass("imul", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  // 3 operand RMI form
  instr(R16, RM16, IMM8)
  instr(R32, RM32, IMM8)
  instr(R64, RM64, IMM8)
  instr(R16, RM16, IMM16)
  instr(R32, RM32, IMM32)
  instr(R64, RM64, IMM32)
  // 2 operand RM form
  val twoOp = listOf(VariableUse.DEF_USE, VariableUse.USE)
  twoOp.instr(R16, RM16)
  twoOp.instr(R32, RM32)
  twoOp.instr(R64, RM64)
  // 1 operand M form
  val oneOp = listOf(VariableUse.DEF_USE)
  oneOp.instr(RM8)
  oneOp.instr(RM16)
  oneOp.instr(RM32)
  oneOp.instr(RM64)
}

private val division: X64ICBuilderBlock = {
  instr(RM8)
  instr(RM16)
  instr(RM32)
  instr(RM64)
}

val div = x64InstructionClass("div", listOf(VariableUse.USE), division)
val idiv = x64InstructionClass("idiv", listOf(VariableUse.USE), division)

val cwd = x64InstructionClass("cwd", emptyList(), nullary)
val cdq = x64InstructionClass("cdq", emptyList(), nullary)
val cqo = x64InstructionClass("cqo", emptyList(), nullary)

private val arithmetic: X64ICBuilderBlock = {
  instr(RM8, IMM8)
  instr(RM16, IMM16)
  instr(RM32, IMM32)
  instr(RM64, IMM32)

  instr(RM16, IMM8)
  instr(RM32, IMM8)
  instr(RM64, IMM8)

  instr(RM8, R8)
  instr(RM16, R16)
  instr(RM32, R32)
  instr(RM64, R64)

  instr(R8, RM8)
  instr(R16, RM16)
  instr(R32, RM32)
  instr(R64, RM64)
}

val add = x64InstructionClass("add", listOf(VariableUse.DEF_USE, VariableUse.USE), arithmetic)
val sub = x64InstructionClass("sub", listOf(VariableUse.DEF_USE, VariableUse.USE), arithmetic)
val cmp = x64InstructionClass("cmp", listOf(VariableUse.USE, VariableUse.USE), arithmetic)
val and = x64InstructionClass("and", listOf(VariableUse.DEF_USE, VariableUse.USE), arithmetic)
val or = x64InstructionClass("or", listOf(VariableUse.DEF_USE, VariableUse.USE), arithmetic)
val xor = x64InstructionClass("xor", listOf(VariableUse.DEF_USE, VariableUse.USE), arithmetic)

private val unaryRM: X64ICBuilderBlock = {
  instr(RM8)
  instr(RM16)
  instr(RM32)
  instr(RM64)
}

val neg = x64InstructionClass("neg", listOf(VariableUse.DEF_USE), unaryRM)
val not = x64InstructionClass("not", listOf(VariableUse.DEF_USE), unaryRM)

val lea = x64InstructionClass("lea", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(R16, M)
  instr(R32, M)
  instr(R64, M)
}

val mov = x64InstructionClass("mov", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RM8, R8)
  instr(RM16, R16)
  instr(RM32, R32)
  instr(RM64, R64)

  instr(R8, RM8)
  instr(R16, RM16)
  instr(R32, RM32)
  instr(R64, RM64)

  instr(R8, IMM8)
  instr(R16, IMM16)
  instr(R32, IMM32)
  instr(R64, IMM64)

  instr(RM8, IMM8)
  instr(RM16, IMM16)
  instr(RM32, IMM32)
  instr(RM64, IMM32)
}

private val movAndExtend: X64ICBuilderBlock = {
  instr(R16, RM8)
  instr(R32, RM8)
  instr(R64, RM8)

  instr(R32, RM16)
  instr(R64, RM16)
}

val movzx = x64InstructionClass("movzx", listOf(VariableUse.DEF, VariableUse.USE), movAndExtend)
val movsx = x64InstructionClass("movsx", listOf(VariableUse.DEF, VariableUse.USE), movAndExtend)
val movsxd = x64InstructionClass("movsxd", listOf(VariableUse.DEF, VariableUse.USE)) { instr(R64, RM32) }

val movq = x64InstructionClass("movq", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(XMM_SS, XMM_SS)
  instr(XMM_SD, XMM_SD)
  instr(XMM_SS, M64)
  instr(XMM_SD, M64)

  instr(M64, XMM_SS)
  instr(M64, XMM_SD)
}

val setcc = listOf(
    "seta", "setae", "setb", "setbe", "setc", "sete", "setg", "setge", "setl",
    "setle", "setna", "setnae", "setnb", "setnbe", "setnc", "setne", "setng", "setnge", "setnl",
    "setnle", "setno", "setnp", "setns", "setnz", "seto", "setp", "setpe", "setpo", "sets", "setz"
).associateWith {
  x64InstructionClass(it, listOf(VariableUse.DEF)) { instr(RM8) }
}

val jcc = listOf(
    "ja", "jae", "jb", "jbe", "jc", "jcxz", "jecxz", "jrcxz", "je", "jg", "jge", "jl", "jle", "jna",
    "jnae", "jnb", "jnbe", "jnc", "jne", "jng", "jnge", "jnl", "jnle", "jno", "jnp", "jns", "jnz",
    "jo", "jp", "jpe", "jpo", "js", "jz"
).associateWith {
  x64InstructionClass(it, listOf(VariableUse.USE)) { instr(JumpTarget) }
}

val jmp = x64InstructionClass("jmp", listOf(VariableUse.USE)) { instr(JumpTarget) }

val call = x64InstructionClass("call", listOf(VariableUse.USE)) {
  instr(NamedDisplacement)
  instr(RM16)
  instr(RM32)
  instr(RM64)
}

val leave = x64InstructionClass("leave", emptyList(), nullary)
val ret = x64InstructionClass("ret", emptyList(), nullary)

val push = x64InstructionClass("push", listOf(VariableUse.USE)) {
  instr(RM16)
  instr(RM32)
  instr(RM64)

  instr(R16)
  instr(R32)
  instr(R64)

  instr(IMM8)
  instr(IMM16)
  instr(IMM32)
}

val pop = x64InstructionClass("pop", listOf(VariableUse.DEF)) {
  instr(RM16)
  instr(RM32)
  instr(RM64)

  instr(R16)
  instr(R32)
  instr(R64)
}

val movss = x64InstructionClass("movss", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(M32, XMM_SS)
  instr(XMM_SS, XMM_SS)
  instr(XMM_SS, M32)
}

val movsd = x64InstructionClass("movsd", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(M64, XMM_SD)
  instr(XMM_SD, XMM_SD)
  instr(XMM_SD, M64)
}

val cvtsi2ss = x64InstructionClass("cvtsi2ss", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(XMM_SS, RM32)
  instr(XMM_SS, RM64)
}

val cvtsi2sd = x64InstructionClass("cvtsi2sd", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(XMM_SD, RM32)
  instr(XMM_SD, RM64)
}

val cvtss2sd = x64InstructionClass("cvtss2sd", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(XMM_SD, XMM_SS)
  instr(XMM_SD, M32)
}

val cvtsd2ss = x64InstructionClass("cvtsd2ss", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(XMM_SS, XMM_SD)
  instr(XMM_SS, M64)
}

val cvttss2si = x64InstructionClass("cvttss2si", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(R32, XMM_SS)
  instr(R32, M32)
  instr(R64, XMM_SS)
  instr(R64, M32)
}

val cvttsd2si = x64InstructionClass("cvttsd2si", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(R32, XMM_SD)
  instr(R32, M64)
  instr(R64, XMM_SD)
  instr(R64, M64)
}

private val ssArithmetic: X64ICBuilderBlock = {
  instr(XMM_SS, XMM_SS)
  instr(XMM_SS, M32)
}

val addss = x64InstructionClass("addss", listOf(VariableUse.DEF_USE, VariableUse.USE), ssArithmetic)
val subss = x64InstructionClass("subss", listOf(VariableUse.DEF_USE, VariableUse.USE), ssArithmetic)
val mulss = x64InstructionClass("mulss", listOf(VariableUse.DEF_USE, VariableUse.USE), ssArithmetic)
val divss = x64InstructionClass("divss", listOf(VariableUse.DEF_USE, VariableUse.USE), ssArithmetic)
val xorps = x64InstructionClass("xorps", listOf(VariableUse.DEF_USE, VariableUse.USE), ssArithmetic)
val comiss = x64InstructionClass("comiss", listOf(VariableUse.USE, VariableUse.USE), ssArithmetic)

private val sdArithmetic: X64ICBuilderBlock = {
  instr(XMM_SD, XMM_SD)
  instr(XMM_SD, M64)
}

val addsd = x64InstructionClass("addsd", listOf(VariableUse.DEF_USE, VariableUse.USE), sdArithmetic)
val subsd = x64InstructionClass("subsd", listOf(VariableUse.DEF_USE, VariableUse.USE), sdArithmetic)
val mulsd = x64InstructionClass("mulsd", listOf(VariableUse.DEF_USE, VariableUse.USE), sdArithmetic)
val divsd = x64InstructionClass("divsd", listOf(VariableUse.DEF_USE, VariableUse.USE), sdArithmetic)
val xorpd = x64InstructionClass("xorpd", listOf(VariableUse.DEF_USE, VariableUse.USE), sdArithmetic)
val comisd = x64InstructionClass("comisd", listOf(VariableUse.USE, VariableUse.USE), sdArithmetic)
