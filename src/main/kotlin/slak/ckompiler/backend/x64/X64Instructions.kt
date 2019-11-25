package slak.ckompiler.backend.x64

import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.InstructionTemplate
import slak.ckompiler.backend.MachineInstruction
import slak.ckompiler.backend.MachineRegister
import slak.ckompiler.backend.x64.Imm.*
import slak.ckompiler.backend.x64.ModRM.*

enum class OperandType {
  REGISTER, MEMORY, REG_OR_MEM
}

enum class AccessType {
  READ, WRITE, READ_WRITE
}

interface Operand {
  val size: Int
}

enum class ModRM(val type: OperandType, override val size: Int) : Operand {
  RM8(OperandType.REG_OR_MEM, 8),
  RM16(OperandType.REG_OR_MEM, 16),
  RM32(OperandType.REG_OR_MEM, 32),
  RM64(OperandType.REG_OR_MEM, 64),

  R8(OperandType.REGISTER, 8),
  R16(OperandType.REGISTER, 16),
  R32(OperandType.REGISTER, 32),
  R64(OperandType.REGISTER, 64)
}

enum class Imm(override val size: Int) : Operand {
  IMM8(8), IMM16(16), IMM32(32), IMM64(64)
}

data class Register(val register: MachineRegister) : Operand {
  override val size = register.sizeBytes
}

data class X64InstrTemplate(
    val name: String,
    val operands: List<Operand>,
    val encoding: List<AccessType>,
    val implicitOperands: List<MachineRegister> = emptyList(),
    val implicitResults: List<MachineRegister> = emptyList()
) : InstructionTemplate

private class ICBuilder(
    val instructions: MutableList<X64InstrTemplate>,
    val name: String,
    val defaultEncoding: List<AccessType>
)

private fun instructionClass(
    name: String,
    defaultEncoding: List<AccessType>,
    block: ICBuilder.() -> Unit
): List<X64InstrTemplate> {
  val builder = ICBuilder(mutableListOf(), name, defaultEncoding)
  builder.block()
  return builder.instructions
}

private fun ICBuilder.instr(vararg operands: Operand) = instr(defaultEncoding, *operands)

private fun ICBuilder.instr(encoding: List<AccessType>, vararg operands: Operand) {
  instructions += X64InstrTemplate(name, operands.toList(), encoding)
}

private infix fun Operand.compatibleWith(ref: DataReference): Boolean {
  if (MachineTargetData.x64.sizeOf(ref.type) != size) return false
  return when (ref) {
    is Variable -> {
      this is ModRM && (type == OperandType.REG_OR_MEM || type == OperandType.MEMORY)
    }
    is VirtualRegister -> {
      this is Register ||
          (this is ModRM && (type == OperandType.REG_OR_MEM || type == OperandType.REGISTER))
    }
    is ConstantValue -> {
      this is Imm
    }
  }
}

fun List<X64InstrTemplate>.match(vararg operands: DataReference): MachineInstruction {
  val instr = firstOrNull {
    it.operands.size == operands.size &&
        operands.zip(it.operands).all { (ref, targetOperand) -> targetOperand compatibleWith ref }
  }
  checkNotNull(instr) { "Instruction selection failure: match $operands in $this" }
  return MachineInstruction(instr, operands.toList())
}

val add = instructionClass("add", listOf(AccessType.READ_WRITE, AccessType.READ)) {
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

val mov = instructionClass("mov", listOf(AccessType.WRITE, AccessType.READ)) {
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
