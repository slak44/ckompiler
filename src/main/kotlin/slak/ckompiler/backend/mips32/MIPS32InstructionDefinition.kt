package slak.ckompiler.backend.mips32

import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.LoadMemory
import slak.ckompiler.analysis.StackVariable
import slak.ckompiler.analysis.insertSpillCode
import slak.ckompiler.backend.ICBuilder
import slak.ckompiler.backend.VariableUse
import slak.ckompiler.backend.dummyInstructionClass
import slak.ckompiler.backend.instructionClass

private fun mips32InstructionClass(
    name: String,
    defaultUse: List<VariableUse>? = null,
    block: ICBuilder<MIPS32OperandTemplate, MIPS32InstructionTemplate>.() -> Unit,
) = instructionClass(name, ::MIPS32InstructionTemplate, defaultUse, block)

val la = mips32InstructionClass("la", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, Label)
}

val li = mips32InstructionClass("li", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, Immediate)
}

val lw = mips32InstructionClass("lw", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, MemoryOperand)
}

val lb = mips32InstructionClass("lb", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, MemoryOperand)
}

val sw = mips32InstructionClass("sw", listOf(VariableUse.USE, VariableUse.DEF)) {
  instr(RegisterOperand, MemoryOperand)
}

val sb = mips32InstructionClass("sb", listOf(VariableUse.USE, VariableUse.DEF)) {
  instr(RegisterOperand, MemoryOperand)
}

val move = mips32InstructionClass("move", listOf(VariableUse.DEF, VariableUse.USE)) {
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

val div = mips32InstructionClass("div", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val divu = mips32InstructionClass("divu", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val mod = mips32InstructionClass("mod", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
}

val modu = mips32InstructionClass("modu", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand, RegisterOperand)
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
