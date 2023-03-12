package slak.ckompiler.backend.mips32

import mu.KotlinLogging
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

private fun isMemoryForCopy(value: IRValue): Boolean = value is MemoryLocation || value is DerefStackValue

fun matchTypedCopy(dest: IRValue, src: IRValue): MachineInstruction {
  if (isMemoryForCopy(dest)) {
    require(!isMemoryForCopy(src))
    return sw.match(src, dest)
  }

  if (isMemoryForCopy(src)) {
    require(!isMemoryForCopy(dest))
    return lw.match(dest, src)
  }

  if (src is StrConstant || src is FltConstant) {
    return la.match(dest, src)
  }

  if (src is IntConstant) {
    return li.match(dest, src)
  }

  return move.match(dest, src)
}
