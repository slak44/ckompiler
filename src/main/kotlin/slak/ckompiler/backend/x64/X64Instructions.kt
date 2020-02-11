package slak.ckompiler.backend.x64

import org.apache.logging.log4j.LogManager
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*
import slak.ckompiler.backend.x64.Imm.*
import slak.ckompiler.backend.x64.ModRM.*
import slak.ckompiler.throwICE

private val logger = LogManager.getLogger()

enum class OperandType {
  REGISTER, MEMORY, REG_OR_MEM
}

/**
 * A generic operand to an instruction (register reference, memory location, immediate, label).
 */
interface Operand {
  val size: Int
}

enum class ModRM(val type: OperandType, override val size: Int) : Operand {
  RM8(OperandType.REG_OR_MEM, 1),
  RM16(OperandType.REG_OR_MEM, 2),
  RM32(OperandType.REG_OR_MEM, 4),
  RM64(OperandType.REG_OR_MEM, 8),

  R8(OperandType.REGISTER, 1),
  R16(OperandType.REGISTER, 2),
  R32(OperandType.REGISTER, 4),
  R64(OperandType.REGISTER, 8),

  M32(OperandType.MEMORY, 4),
  M64(OperandType.MEMORY, 8),

  XMM_SS(OperandType.REGISTER, 4),
  XMM_SD(OperandType.REGISTER, 8)
}

enum class Imm(override val size: Int) : Operand {
  IMM8(1), IMM16(2), IMM32(4), IMM64(8)
}

data class Register(val register: MachineRegister) : Operand {
  override val size = register.sizeBytes
}

object JumpTarget : Operand {
  override val size = 0
}

object NamedDisplacement : Operand {
  override val size = 0
}

data class X64InstrTemplate(
    override val name: String,
    val operands: List<Operand>,
    override val operandUse: List<VariableUse>,
    val implicitOperands: List<MachineRegister> = emptyList(),
    val implicitResults: List<MachineRegister> = emptyList()
) : InstructionTemplate

sealed class X64Value

data class ImmediateValue(val value: ConstantValue) : X64Value() {
  override fun toString() = value.toString()
}

data class RegisterValue(val register: MachineRegister, val size: Int) : X64Value() {
  constructor(phys: PhysicalRegister) : this(phys.reg, MachineTargetData.x64.sizeOf(phys.type))

  override fun toString(): String {
    if (register.sizeBytes == size) return register.regName
    return register.aliases.first { it.second == size }.first
  }
}

data class StackValue(val stackSlot: StackSlot, val offset: Int) : X64Value() {
  override fun toString() = "[rbp - ${offset + stackSlot.sizeBytes}]"
}

data class X64Instruction(
    override val template: X64InstrTemplate,
    val operands: List<X64Value>
) : AsmInstruction {
  override fun toString() = "${template.name} ${operands.joinToString(", ")}"
}

private class ICBuilder(
    val instructions: MutableList<X64InstrTemplate>,
    val name: String,
    val defaultUse: List<VariableUse>? = null
) {
  fun instr(vararg operands: Operand) = instr(defaultUse!!, *operands)

  fun instr(operandUse: List<VariableUse>, vararg operands: Operand) {
    instructions += X64InstrTemplate(name, operands.toList(), operandUse)
  }

  @JvmName("l_instr")
  fun List<VariableUse>.instr(vararg operands: Operand): Unit = instr(this, *operands)
}

private fun instructionClass(
    name: String,
    defaultUse: List<VariableUse>? = null,
    block: ICBuilder.() -> Unit
): List<X64InstrTemplate> {
  val builder = ICBuilder(mutableListOf(), name, defaultUse)
  builder.block()
  return builder.instructions
}

private infix fun Operand.compatibleWith(ref: IRValue): Boolean {
  if (this is NamedDisplacement && ref is NamedConstant) return true
  if (this is JumpTarget && ref is JumpTargetConstant) return true
  if (ref !is PhysicalRegister && MachineTargetData.x64.sizeOf(ref.type.normalize()) != size) {
    return false
  }
  return when (ref) {
    is MemoryReference, is StrConstant, is FltConstant -> {
      this is ModRM && (type == OperandType.REG_OR_MEM || type == OperandType.MEMORY)
    }
    is VirtualRegister, is Variable -> {
      this is Register ||
          (this is ModRM && (type == OperandType.REG_OR_MEM || type == OperandType.REGISTER))
    }
    is PhysicalRegister -> {
      val isCorrectKind = (this is Register && register == ref.reg) ||
          (this is ModRM && (type == OperandType.REG_OR_MEM || type == OperandType.REGISTER))
      val isCorrectSize =
          size == ref.reg.sizeBytes || size in ref.reg.aliases.map { it.second }
      isCorrectKind && isCorrectSize
    }
    is IntConstant -> this is Imm
    is NamedConstant, is JumpTargetConstant -> false // was checked above
    is ParameterReference -> logger.throwICE("Parameter references were removed")
  }
}

fun List<X64InstrTemplate>.match(vararg operands: IRValue): MachineInstruction {
  val instr = firstOrNull {
    it.operands.size == operands.size &&
        operands.zip(it.operands).all { (ref, targetOperand) -> targetOperand compatibleWith ref }
  }
  checkNotNull(instr) { "Instruction selection failure: match ${operands.toList()} in $this" }
  return MachineInstruction(instr, operands.toList())
}

private val nullary: ICBuilder.() -> Unit = { instr() }

val dummyUse = instructionClass("DUMMY USE DO NOT EMIT", listOf(VariableUse.USE)) {
  instr(R8)
  instr(R16)
  instr(R32)
  instr(R64)
}

val dummyCallSave = instructionClass("DUMMY SAVE DO NOT EMIT", emptyList(), nullary)
val dummyCallRestore = instructionClass("DUMMY RESTORE DO NOT EMIT", emptyList(), nullary)

private val arithmetic: ICBuilder.() -> Unit = {
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

val add = instructionClass("add", listOf(VariableUse.DEF_USE, VariableUse.USE), arithmetic)
val sub = instructionClass("sub", listOf(VariableUse.DEF_USE, VariableUse.USE), arithmetic)
val cmp = instructionClass("cmp", listOf(VariableUse.USE, VariableUse.USE), arithmetic)
val and = instructionClass("and", listOf(VariableUse.DEF_USE, VariableUse.USE), arithmetic)
val or = instructionClass("or", listOf(VariableUse.DEF_USE, VariableUse.USE), arithmetic)
val xor = instructionClass("xor", listOf(VariableUse.DEF_USE, VariableUse.USE), arithmetic)

private val unaryRM: ICBuilder.() -> Unit = {
  instr(RM8)
  instr(RM16)
  instr(RM32)
  instr(RM64)
}

val neg = instructionClass("neg", listOf(VariableUse.DEF_USE), unaryRM)
val not = instructionClass("neg", listOf(VariableUse.DEF_USE), unaryRM)

val mov = instructionClass("mov", listOf(VariableUse.DEF, VariableUse.USE)) {
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

val movzx = instructionClass("movzx", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(R16, RM8)
  instr(R32, RM8)
  instr(R64, RM8)

  instr(R32, RM16)
  instr(R64, RM16)
}

val setcc = listOf(
    "seta", "setae", "setb", "setbe", "setc", "sete", "setg", "setge", "setl",
    "setle", "setna", "setnae", "setnb", "setnbe", "setnc", "setne", "setng", "setnge", "setnl",
    "setnle", "setno", "setnp", "setns", "setnz", "seto", "setp", "setpe", "setpo", "sets", "setz"
).associateWith {
  instructionClass(it, listOf(VariableUse.DEF)) { instr(RM8) }
}

val jcc = listOf(
    "ja", "jae", "jb", "jbe", "jc", "jcxz", "jecxz", "jrcxz", "je", "jg", "jge", "jl", "jle", "jna",
    "jnae", "jnb", "jnbe", "jnc", "jne", "jng", "jnge", "jnl", "jnle", "jno", "jnp", "jns", "jnz",
    "jo", "jp", "jpe", "jpo", "js", "jz"
).associateWith {
  instructionClass(it, listOf(VariableUse.USE)) { instr(JumpTarget) }
}

val jmp = instructionClass("jmp", listOf(VariableUse.USE)) { instr(JumpTarget) }

val call = instructionClass("call", listOf(VariableUse.USE)) {
  instr(NamedDisplacement)
  instr(RM16)
  instr(RM32)
  instr(RM64)
}

val leave = instructionClass("leave", emptyList(), nullary)
val ret = instructionClass("ret", emptyList(), nullary)

val push = instructionClass("push", listOf(VariableUse.USE)) {
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

val pop = instructionClass("pop", listOf(VariableUse.DEF)) {
  instr(RM16)
  instr(RM32)
  instr(RM64)

  instr(R16)
  instr(R32)
  instr(R64)
}

val movss = instructionClass("movss", listOf(VariableUse.DEF_USE, VariableUse.USE)) {
  instr(XMM_SS, XMM_SS)
  instr(XMM_SS, M32)
}

val movsd = instructionClass("movsd", listOf(VariableUse.DEF_USE, VariableUse.USE)) {
  instr(XMM_SD, XMM_SD)
  instr(XMM_SD, M64)
}

val cvtsi2ss = instructionClass("cvtsi2ss", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(XMM_SS, RM32)
  instr(XMM_SS, RM64)
}

val cvtsi2sd = instructionClass("cvtsi2sd", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(XMM_SD, RM32)
  instr(XMM_SD, RM64)
}

val cvtss2sd = instructionClass("cvtss2sd", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(XMM_SD, XMM_SS)
  instr(XMM_SD, M32)
}

val cvtsd2ss = instructionClass("cvtsd2ss", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(XMM_SS, XMM_SD)
  instr(XMM_SS, M64)
}

val cvttss2si = instructionClass("cvttss2si", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(R32, XMM_SS)
  instr(R32, M32)
  instr(R64, XMM_SS)
  instr(R64, M32)
}

val cvttsd2si = instructionClass("cvttsd2si", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(R32, XMM_SD)
  instr(R32, M64)
  instr(R64, XMM_SD)
  instr(R64, M64)
}
