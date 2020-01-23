package slak.ckompiler.backend.x64

import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*
import slak.ckompiler.backend.x64.Imm.*
import slak.ckompiler.backend.x64.ModRM.*

enum class OperandType {
  REGISTER, MEMORY, REG_OR_MEM
}

/**
 * Generic operand to an instruction (register reference, memory location, immediate).
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
  R64(OperandType.REGISTER, 8)
}

enum class Imm(override val size: Int) : Operand {
  IMM8(1), IMM16(2), IMM32(4), IMM64(8)
}

data class Register(val register: MachineRegister) : Operand {
  override val size = register.sizeBytes
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
    val defaultUse: List<VariableUse>
)

private fun instructionClass(
    name: String,
    defaultUse: List<VariableUse>,
    block: ICBuilder.() -> Unit
): List<X64InstrTemplate> {
  val builder = ICBuilder(mutableListOf(), name, defaultUse)
  builder.block()
  return builder.instructions
}

private fun ICBuilder.instr(vararg operands: Operand) = instr(defaultUse, *operands)

private fun ICBuilder.instr(operandUse: List<VariableUse>, vararg operands: Operand) {
  instructions += X64InstrTemplate(name, operands.toList(), operandUse)
}

private infix fun Operand.compatibleWith(ref: IRValue): Boolean {
  if (MachineTargetData.x64.sizeOf(ref.type) != size) return false
  return when (ref) {
    is MemoryReference -> {
      this is ModRM && (type == OperandType.REG_OR_MEM || type == OperandType.MEMORY)
    }
    is VirtualRegister, is Variable -> {
      this is Register ||
          (this is ModRM && (type == OperandType.REG_OR_MEM || type == OperandType.REGISTER))
    }
    is ConstantValue -> {
      this is Imm
    }
  }
}

fun List<X64InstrTemplate>.match(vararg operands: IRValue): MachineInstruction {
  val instr = firstOrNull {
    it.operands.size == operands.size &&
        operands.zip(it.operands).all { (ref, targetOperand) -> targetOperand compatibleWith ref }
  }
  checkNotNull(instr) { "Instruction selection failure: match $operands in $this" }
  return MachineInstruction(instr, operands.toList())
}

val add = instructionClass("add", listOf(VariableUse.DEF_USE, VariableUse.USE)) {
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
