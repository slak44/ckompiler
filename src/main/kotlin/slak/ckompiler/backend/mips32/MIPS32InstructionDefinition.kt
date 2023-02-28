package slak.ckompiler.backend.mips32

import slak.ckompiler.backend.ICBuilder
import slak.ckompiler.backend.VariableUse
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

val sw = mips32InstructionClass("sw", listOf(VariableUse.USE, VariableUse.DEF)) {
  instr(RegisterOperand, MemoryOperand)
}

val move = mips32InstructionClass("move", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(RegisterOperand, RegisterOperand)
}

val bc = mips32InstructionClass("bc", listOf(VariableUse.USE)) {
  instr(Label)
}

val jal = mips32InstructionClass("jal", listOf(VariableUse.USE)) {
  instr(Label)
}

val jr = mips32InstructionClass("jr", listOf(VariableUse.USE)) {
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
