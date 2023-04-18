package slak.ckompiler.backend.mips32

import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.ConstantValue
import slak.ckompiler.analysis.PhysicalRegister
import slak.ckompiler.backend.*

sealed interface MIPS32OperandTemplate : OperandTemplate

object Immediate : MIPS32OperandTemplate

object RegisterOperand : MIPS32OperandTemplate

object MemoryOperand : MIPS32OperandTemplate

object Label : MIPS32OperandTemplate

data class MIPS32InstructionTemplate(
    override val name: String,
    override val operandType: List<MIPS32OperandTemplate>,
    override val operandUse: List<VariableUse>,
    override val isDummy: Boolean
) : InstructionTemplate<MIPS32OperandTemplate>()

sealed class MIPS32Value

data class MIPS32ImmediateValue(val value: ConstantValue) : MIPS32Value() {
  override fun toString() = value.toString()
}

data class MIPS32RegisterValue(val register: MachineRegister, val size: Int) : MIPS32Value() {
  constructor(phys: PhysicalRegister) : this(phys.reg, MachineTargetData.mips32.sizeOf(phys.type))

  init {
    require(register.valueClass != Memory) {
      "RegisterValue cannot refer to memory: $register"
    }
  }

  override fun toString(): String {
    return register.regName
  }
}

data class MIPS32MemoryValue(val sizeInMem: Int, val base: MIPS32RegisterValue, val displacement: Int) : MIPS32Value() {
  override fun toString(): String {
    return "$displacement($base)"
  }

  companion object {
    fun inFrame(fp: MIPS32RegisterValue, stackSlot: StackSlot, frameOffset: Int): MIPS32MemoryValue {
      return MIPS32MemoryValue(stackSlot.sizeBytes, base = fp, displacement = -(frameOffset + stackSlot.sizeBytes))
    }
  }
}

data class MIPS32Instruction(
    override val template: MIPS32InstructionTemplate,
    val operands: List<MIPS32Value>,
) : AsmInstruction {
  override fun toString() = "${template.name} ${operands.joinToString(", ")}"
}
