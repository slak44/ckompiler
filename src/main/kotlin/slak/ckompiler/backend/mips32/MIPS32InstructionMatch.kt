package slak.ckompiler.backend.mips32

import io.github.oshai.kotlinlogging.KotlinLogging
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.MachineInstruction
import slak.ckompiler.backend.match
import slak.ckompiler.backend.tryMatch
import slak.ckompiler.parser.DoubleType
import slak.ckompiler.parser.FloatType
import slak.ckompiler.parser.FloatingType
import slak.ckompiler.parser.LongDoubleType
import slak.ckompiler.throwICE

private val logger = KotlinLogging.logger {}

private fun compatibleWith(operand: MIPS32OperandTemplate, ref: IRValue): Boolean {
  return when (ref) {
    is StrConstant, is FltConstant -> operand is Label || operand is MemoryOperand
    is NamedConstant, is JumpTargetConstant -> operand is Label
    is IntConstant -> operand is Immediate
    is DerefStackValue, is MemoryLocation -> operand is MemoryOperand
    is Variable, is VirtualRegister, is StackValue, is StackVariable -> operand is RegisterOperand
    is PhysicalRegister -> {
      check(ref.reg is MIPS32Register) { "Physical register must be a MIPS register" }

      if (ref.reg.isFPUControl) {
        operand is FPUControlRegisterOperand
      } else {
        operand is RegisterOperand
      }
    }
    is ParameterReference -> logger.throwICE("Parameter references were removed")
  }
}

fun List<MIPS32InstructionTemplate>.tryMatch(vararg operands: IRValue) = tryMatch(::compatibleWith, *operands)

fun List<MIPS32InstructionTemplate>.match(vararg operands: IRValue) = match(::compatibleWith, *operands)

fun List<MIPS32InstructionTemplate>.tryMatchAsm(vararg operands: MIPS32Value): MIPS32Instruction? {
  val instr = firstOrNull {
    it.operandType.size == operands.size && operands.zip(it.operandType).all { (value, targetOperand) ->
      when (targetOperand) {
        RegisterOperand, FPUControlRegisterOperand -> value is MIPS32RegisterValue
        MemoryOperand -> value is MIPS32MemoryValue
        Immediate -> value is MIPS32ImmediateValue
        Label -> false
      }
    }
  }
  return if (instr == null) null else MIPS32Instruction(instr, operands.toList())
}

fun List<MIPS32InstructionTemplate>.matchAsm(vararg operands: MIPS32Value): MIPS32Instruction {
  val instr = tryMatchAsm(*operands)
  return checkNotNull(instr) { "Failed to create instruction from the given operands: ${operands.toList()} in $this" }
}

private fun isMemoryForCopy(value: IRValue): Boolean = value is MemoryLocation || value is DerefStackValue

fun matchTypedCopy(dest: IRValue, src: IRValue): MachineInstruction {
  val srcType = src.type
  val destType = dest.type

  if (isMemoryForCopy(dest)) {
    require(!isMemoryForCopy(src)) { "Failed to copy to memory. src: $src, dest: $dest" }
    require(src !is StackVariable) { "Moving a StackVariable to a memory location requires 2 instructions (addi+sw). See stackAddrMove" }

    val destSize = MachineTargetData.mips32.sizeOf(destType)

    if (srcType is FloatingType) {
      check(destSize >= MachineTargetData.mips32.floatSizeBytes) { "Cannot move floating value to memory location of size $destSize" }
      val store = when (srcType) {
        FloatType -> s_s
        DoubleType, LongDoubleType -> s_d
      }

      return store.match(src, dest)
    }

    if (destSize == 1) {
      return sb.match(dest, src)
    }
    return sw.match(src, dest)
  }

  if (isMemoryForCopy(src)) {
    require(!isMemoryForCopy(dest)) { "Failed to copy from memory. src: $src, dest: $dest" }

    val srcSize = MachineTargetData.mips32.sizeOf(srcType)

    if (destType is FloatingType) {
      check(srcSize >= MachineTargetData.mips32.floatSizeBytes) { "Cannot load floating value from memory location of size $srcSize" }
      val load = when (destType) {
        FloatType -> l_s
        DoubleType, LongDoubleType -> l_d
      }

      return load.match(dest, src)
    }

    if (srcSize == 1) {
      return lb.match(dest, src)
    }
    return lw.match(dest, src)
  }

  if (src is FltConstant) {
    require(srcType is FloatingType) { "Floating constant must have floating type" }
    require(destType is FloatingType) { "Destination register for floating constant must have floating type" }

    val load = when (destType) {
      FloatType -> l_s
      DoubleType, LongDoubleType -> l_d
    }

    return load.match(dest, src)
  }

  if (src is StrConstant) {
    return la.match(dest, src)
  }

  if (src is IntConstant) {
    return li.match(dest, src)
  }

  if (src is StackVariable) {
    return stackAddrMove.match(dest, src)
  }

  val srcClass = MIPS32Target.registerClassOf(srcType)
  val destClass = MIPS32Target.registerClassOf(destType)

  check(srcClass is MIPS32RegisterClass && destClass is MIPS32RegisterClass) { "Register class for move must not be Memory" }
  check(srcClass == destClass) { "Register class for move source must match move destination" }

  return when (srcClass) {
    MIPS32RegisterClass.INTEGER -> move.match(dest, src)
    MIPS32RegisterClass.FLOAT -> when (srcType) {
      FloatType -> mov_s.match(dest, src)
      DoubleType, LongDoubleType -> mov_d.match(dest, src)
      else -> logger.throwICE("Unreachable; float register class not of floating type")
    }
  }
}
