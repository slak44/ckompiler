package slak.ckompiler.backend.mips32

import slak.ckompiler.analysis.*
import slak.ckompiler.backend.ICBuilder
import slak.ckompiler.backend.VariableUse
import slak.ckompiler.backend.dummyInstructionClass
import slak.ckompiler.backend.instructionClass
import slak.ckompiler.format

private fun mips32InstructionClass(
    name: String,
    defaultUse: List<VariableUse>? = null,
    block: ICBuilder<MIPS32OperandTemplate, MIPS32InstructionTemplate>.() -> Unit,
) = instructionClass(name, ::MIPS32InstructionTemplate, defaultUse, block)

val nop = mips32InstructionClass("nop", emptyList()) {
  instr()
}

val la = mips32InstructionClass("la", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, Label)
}

val li = mips32InstructionClass("li", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, Immediate)
}

val lw = mips32InstructionClass("lw", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, MemoryOperand)
}

val l_s = mips32InstructionClass("l.s", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, MemoryOperand)
}

val l_d = mips32InstructionClass("l.d", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, MemoryOperand)
}

val lb = mips32InstructionClass("lb", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, MemoryOperand)
}

val sw = mips32InstructionClass("sw", listOf(VariableUse.USE, VariableUse.DEF)) {
  instr(RegisterOperand, MemoryOperand)
}

val s_s = mips32InstructionClass("s.s", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, MemoryOperand)
}

val s_d = mips32InstructionClass("s.d", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, MemoryOperand)
}

val sb = mips32InstructionClass("sb", listOf(VariableUse.USE, VariableUse.DEF)) {
  instr(RegisterOperand, MemoryOperand)
}

val move = mips32InstructionClass("move", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand)
}

val mov_s = mips32InstructionClass("mov.s", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand)
}

val mov_d = mips32InstructionClass("mov.d", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand)
}

val cfc1 = mips32InstructionClass("cfc1", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, FPUControlRegisterOperand)
}

val mfc1 = mips32InstructionClass("mfc1", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand)
}

val mtc1 = mips32InstructionClass("mtc1", listOf(VariableUse.USE, VariableUse.DEF)) {
  instr(RegisterOperand, RegisterOperand)
}

/**
 * [StackVariable]s are pointers to somewhere in the stack. They have displacements of the form -4($fp).
 * When used directly to load a value (so as [LoadMemory.loadFrom]), it's fine to leave them as is.
 * In every other context they are used as values, as pointers, and MIPS doesn't have either x64 lea, or complex addressing like mov.
 * So the address needs to be computed with arithmetic.
 *
 * Related to [CFG.insertSpillCode] and the insanity there.
 */
val stackAddrMove = dummyInstructionClass("move (stack special)", ::MIPS32InstructionTemplate, listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand)
}

val b = mips32InstructionClass("b", listOf(VariableUse.USE)) {
  instr(Label)
}

val beq = mips32InstructionClass("beq", listOf(VariableUse.USE, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, Label)
}

val bne = mips32InstructionClass("bne", listOf(VariableUse.USE, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, Label)
}

val bltz = mips32InstructionClass("bltz", listOf(VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, Label)
}

val blez = mips32InstructionClass("blez", listOf(VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, Label)
}

val bgtz = mips32InstructionClass("bgtz", listOf(VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, Label)
}

val bgez = mips32InstructionClass("bgez", listOf(VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, Label)
}

val bc1f = mips32InstructionClass("bc1f", listOf(VariableUse.USE)) {
  instr(Label)
}

val bc1t = mips32InstructionClass("bc1t", listOf(VariableUse.USE)) {
  instr(Label)
}

val jal = mips32InstructionClass("jal", listOf(VariableUse.USE)) {
  instr(Label)
}

val jr = mips32InstructionClass("jr", listOf(VariableUse.USE)) {
  instr(RegisterOperand)
}

val jalr = mips32InstructionClass("jalr", listOf(VariableUse.USE)) {
  instr(RegisterOperand)
}

val sub = mips32InstructionClass("sub", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val subu = mips32InstructionClass("subu", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val add = mips32InstructionClass("add", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val addu = mips32InstructionClass("addu", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val addi = mips32InstructionClass("addi", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, Immediate)
}

val addiu = mips32InstructionClass("addiu", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, Immediate)
}

val mul = mips32InstructionClass("mul", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val mulu = mips32InstructionClass("mulu", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val div = mips32InstructionClass("div", listOf(VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand)

  // FIXME: exclusive to MIPS32 Release 6, but would have been nice
  instr(listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE), RegisterOperand, RegisterOperand, RegisterOperand)
}

val divu = mips32InstructionClass("divu", listOf(VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand)

  // FIXME: exclusive to MIPS32 Release 6, but would have been nice
  instr(listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE), RegisterOperand, RegisterOperand, RegisterOperand)
}

// FIXME: exclusive to MIPS32 Release 6, but would have been nice
val mod = mips32InstructionClass("mod", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

// FIXME: exclusive to MIPS32 Release 6, but would have been nice
val modu = mips32InstructionClass("modu", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val mfhi = mips32InstructionClass("mfhi", listOf(VariableUse.DEF)) {
  instr(RegisterOperand)
}

val mflo = mips32InstructionClass("mflo", listOf(VariableUse.DEF)) {
  instr(RegisterOperand)
}

val and = mips32InstructionClass("and", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val andi = mips32InstructionClass("andi", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, Immediate)
}

val or = mips32InstructionClass("or", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val ori = mips32InstructionClass("ori", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, Immediate)
}

val xor = mips32InstructionClass("xor", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val xori = mips32InstructionClass("xori", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, Immediate)
}

val nor = mips32InstructionClass("nor", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val neg = mips32InstructionClass("neg", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand)
}

val slt = mips32InstructionClass("slt", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val slti = mips32InstructionClass("slti", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, Immediate)
}

val sltiu = mips32InstructionClass("sltiu", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, Immediate)
}

val sltu = mips32InstructionClass("sltu", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val add_s = mips32InstructionClass("add.s", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val sub_s = mips32InstructionClass("sub.s", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val mul_s = mips32InstructionClass("mul.s", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val div_s = mips32InstructionClass("div.s", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val neg_s = mips32InstructionClass("neg.s", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand)
}

val add_d = mips32InstructionClass("add.d", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val sub_d = mips32InstructionClass("sub.d", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val mul_d = mips32InstructionClass("mul.d", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val div_d = mips32InstructionClass("div.d", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val neg_d = mips32InstructionClass("neg.d", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand)
}

val cvt_d_s = mips32InstructionClass("cvt.d.s", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand)
}

val cvt_d_w = mips32InstructionClass("cvt.d.w", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand)
}

val cvt_s_d = mips32InstructionClass("cvt.s.d", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand)
}

val cvt_s_w = mips32InstructionClass("cvt.s.w", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand)
}

val cvt_w_d = mips32InstructionClass("cvt.w.d", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand)
}

val cvt_w_s = mips32InstructionClass("cvt.w.s", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand)
}

private fun mips32FmtInstrClass(
    nameTemplate: String,
    nameFmtVariants: List<String>,
    defaultUse: List<VariableUse>? = null,
    block: ICBuilder<MIPS32OperandTemplate, MIPS32InstructionTemplate>.() -> Unit,
): Map<String, List<MIPS32InstructionTemplate>> = buildMap {
  for (fmt in nameFmtVariants) {
    val name = nameTemplate.format(fmt)
    this[fmt] = instructionClass(name, ::MIPS32InstructionTemplate, defaultUse, block)
  }
}

private val floatCompareVariants = listOf(
    "f", "un", "eq", "ueq", "olt", "ult", "ole", "ule",
    "sf", "ngle", "seq", "ngl", "lt", "nge", "le", "ngt"
)

// FIXME: see CMP.condn.fmt for MIPS Release 6
val c_s = mips32FmtInstrClass("c.%s.s", floatCompareVariants, listOf(VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand)
}

val c_d = mips32FmtInstrClass("c.%s.d", floatCompareVariants, listOf(VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand)
}
