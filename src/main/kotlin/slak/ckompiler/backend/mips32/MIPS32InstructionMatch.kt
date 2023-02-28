package slak.ckompiler.backend.mips32

import mu.KotlinLogging
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.MachineInstruction
import slak.ckompiler.backend.Memory
import slak.ckompiler.backend.match
import slak.ckompiler.backend.tryMatch
import slak.ckompiler.throwICE

private val logger = KotlinLogging.logger {}

private fun compatibleWith(operand: MIPS32OperandTemplate, ref: IRValue): Boolean {
  return when (ref) {
    is NamedConstant, is JumpTargetConstant -> operand is Label
    is IntConstant, is FltConstant -> operand is Immediate
    is DerefStackValue, is MemoryLocation, is StrConstant -> operand is MemoryOperand
    is Variable, is VirtualRegister, is StackValue, is StackVariable -> operand is RegisterOperand
    is PhysicalRegister -> operand is RegisterOperand
    is ParameterReference -> logger.throwICE("Parameter references were removed")
  }
}

fun List<MIPS32InstructionTemplate>.tryMatch(vararg operands: IRValue) = tryMatch(::compatibleWith, *operands)

fun List<MIPS32InstructionTemplate>.match(vararg operands: IRValue) = match(::compatibleWith, *operands)

fun MIPS32Target.matchTypedCopy(dest: IRValue, src: IRValue): MachineInstruction {
  val destType = registerClassOf(dest.type)
  val srcType = registerClassOf(src.type)

  if (destType is Memory) {
    require(srcType !is Memory)
    return sw.match(src, dest)
  }

  if (srcType is Memory) {
    require(destType !is Memory)
    return lw.match(dest, src)
  }

  if (destType != srcType) {
    TODO()
  }

  if (src is ConstantValue) {
    return li.match(dest, src)
  }

  return move.match(dest, src)
}
