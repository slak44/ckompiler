package slak.ckompiler.backend.mips32

import mu.KotlinLogging
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.MachineInstruction
import slak.ckompiler.backend.match
import slak.ckompiler.backend.tryMatch
import slak.ckompiler.throwICE

private val logger = KotlinLogging.logger {}

private fun compatibleWith(operand: MIPS32OperandTemplate, ref: IRValue): Boolean {
  return when (ref) {
    is NamedConstant, is JumpTargetConstant, is StrConstant, is FltConstant -> operand is Label
    is IntConstant -> operand is Immediate
    is DerefStackValue, is MemoryLocation -> operand is MemoryOperand
    is Variable, is VirtualRegister, is StackValue, is StackVariable -> operand is RegisterOperand
    is PhysicalRegister -> operand is RegisterOperand
    is ParameterReference -> logger.throwICE("Parameter references were removed")
  }
}

fun List<MIPS32InstructionTemplate>.tryMatch(vararg operands: IRValue) = tryMatch(::compatibleWith, *operands)

fun List<MIPS32InstructionTemplate>.match(vararg operands: IRValue) = match(::compatibleWith, *operands)

fun List<MIPS32InstructionTemplate>.tryMatchAsm(vararg operands: MIPS32Value): MIPS32Instruction? {
  val instr = firstOrNull {
    it.operandType.size == operands.size && operands.zip(it.operandType).all { (value, targetOperand) ->
      when (targetOperand) {
        RegisterOperand -> value is MIPS32RegisterValue
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
  if (isMemoryForCopy(dest)) {
    require(!isMemoryForCopy(src))

    val destSize = MachineTargetData.mips32.sizeOf(dest.type)
    if (destSize == 1) {
      return sb.match(dest, src)
    }
    return sw.match(src, dest)
  }

  if (isMemoryForCopy(src)) {
    require(!isMemoryForCopy(dest))

    val srcSize = MachineTargetData.mips32.sizeOf(src.type)
    if (srcSize == 1) {
      return lb.match(dest, src)
    }
    return lw.match(dest, src)
  }

  if (src is StrConstant || src is FltConstant) {
    return la.match(dest, src)
  }

  if (src is IntConstant) {
    return li.match(dest, src)
  }

  if (src is StackVariable) {
    return stackAddrMove.match(dest, src)
  }

  return move.match(dest, src)
}
