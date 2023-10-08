package slak.ckompiler.backend.x64

import io.github.oshai.kotlinlogging.KotlinLogging
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*
import slak.ckompiler.throwICE

private val logger = KotlinLogging.logger {}

private fun compatibleWith(operand: X64OperandTemplate, ref: IRValue): Boolean {
  if (operand is NamedDisplacement && ref is NamedConstant) return true
  if (operand is JumpTarget && ref is JumpTargetConstant) return true

  val refSize = MachineTargetData.x64.sizeOf(ref.type.unqualify().normalize())
  val sizeMatches = refSize == operand.size

  return when (ref) {
    is MemoryLocation, is DerefStackValue, is StrConstant, is FltConstant -> {
      (sizeMatches || operand == ModRM.M)
          && operand is ModRM
          && (operand.type == OperandType.REG_OR_MEM || operand.type == OperandType.MEMORY)
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
