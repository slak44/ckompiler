package slak.ckompiler.backend.x64

import mu.KotlinLogging
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*
import slak.ckompiler.backend.x64.Imm.*
import slak.ckompiler.backend.x64.ModRM.*
import slak.ckompiler.parser.Bool
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

private fun compatibleWith(operand: X64Operand, ref: IRValue): Boolean {
  if (operand is NamedDisplacement && ref is NamedConstant) return true
  if (operand is JumpTarget && ref is JumpTargetConstant) return true

  val refSize = MachineTargetData.x64.sizeOf(ref.type.unqualify().normalize())
  val sizeMatches = refSize == operand.size

  return when (ref) {
    is MemoryLocation, is DerefStackValue, is StrConstant, is FltConstant -> {
      (sizeMatches || operand == M) && operand is ModRM && (operand.type == OperandType.REG_OR_MEM || operand.type == OperandType.MEMORY)
    }
    is VirtualRegister, is Variable, is StackVariable, is StackValue -> {
      sizeMatches &&
          (operand is Register || (operand is ModRM && (operand.type == OperandType.REG_OR_MEM || operand.type == OperandType.REGISTER)))
    }
    is PhysicalRegister -> {
      val isCorrectKind = (operand is Register && operand.register == ref.reg) ||
          (operand is ModRM && (operand.type == OperandType.REG_OR_MEM || operand.type == OperandType.REGISTER))
      val isCorrectSize = operand.size == ref.reg.sizeBytes || operand.size in ref.reg.aliases.map { it.second }
      isCorrectKind && isCorrectSize
    }
    is IntConstant -> when {
      operand !is Imm -> false
      sizeMatches -> true
      else -> {
        val highestBitSet = Long.SIZE_BITS - ref.value.countLeadingZeroBits()
        val immSizeBits = operand.size * 8
        highestBitSet < immSizeBits
      }
    }
    is NamedConstant, is JumpTargetConstant -> false // was checked above
    is ParameterReference -> logger.throwICE("Parameter references were removed")
  }
}

fun List<X64InstrTemplate>.tryMatch(vararg operands: IRValue) = tryMatch(::compatibleWith, *operands)

fun List<X64InstrTemplate>.match(vararg operands: IRValue) = match(::compatibleWith, *operands)

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
