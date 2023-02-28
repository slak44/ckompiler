package slak.ckompiler.backend

import slak.ckompiler.analysis.ConstantValue
import slak.ckompiler.analysis.IRValue

fun <O : OperandTemplate, T : InstructionTemplate<O>> List<T>.tryMatch(
    compatibleWith: (operand: O, ref: IRValue) -> Boolean,
    vararg operands: IRValue
): MachineInstruction? {
  val instr = firstOrNull {
    it.operandType.size == operands.size &&
        operands.zip(it.operandType).all { (ref, targetOperand) -> compatibleWith(targetOperand, ref) }
  } ?: return null
  return MachineInstruction(instr, operands.toList())
}

fun <O : OperandTemplate, T : InstructionTemplate<O>> List<T>.match(
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

/**
 * Finds the constant, if any, in a binary operation.
 * Useful for ISAs which can't do operations with two immediates (which should be most of them).
 */
fun findImmInBinary(lhs: IRValue, rhs: IRValue): Pair<IRValue, IRValue> {
  // Can't have result = imm OP imm
  require(lhs !is ConstantValue || rhs !is ConstantValue)
  val nonImm = if (lhs is ConstantValue) rhs else lhs
  val maybeImm = if (lhs === nonImm) rhs else lhs
  return nonImm to maybeImm
}
