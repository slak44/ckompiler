package slak.ckompiler.backend.x64

import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.ConstantValue
import slak.ckompiler.analysis.PhysicalRegister
import slak.ckompiler.backend.*
import kotlin.math.absoluteValue

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
