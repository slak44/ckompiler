package slak.ckompiler.backend

import slak.ckompiler.analysis.IRValue

fun <O : Operand, T : InstructionTemplate<O>> List<T>.tryMatch(
    compatibleWith: (operand: O, ref: IRValue) -> Boolean,
    vararg operands: IRValue
): MachineInstruction? {
  val instr = firstOrNull {
    it.operandType.size == operands.size &&
        operands.zip(it.operandType).all { (ref, targetOperand) -> compatibleWith(targetOperand, ref) }
  } ?: return null
  return MachineInstruction(instr, operands.toList())
}

fun <O : Operand, T : InstructionTemplate<O>> List<T>.match(
    compatibleWith: (operand: O, ref: IRValue) -> Boolean,
    vararg operands: IRValue,
): MachineInstruction {
  val res = tryMatch(compatibleWith, *operands)
  return checkNotNull(res) {
    val likely = filter { it.operandType.size == operands.size }.sortedByDescending {
      operands.zip(it.operandType).count { (ref, targetOperand) -> compatibleWith(targetOperand, ref) }
    }
    "Instruction selection failure: match ${operands.toList()} in $this\n" +
        "Likely candidate: ${likely.firstOrNull()}"
  }
}
