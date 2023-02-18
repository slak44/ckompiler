package slak.ckompiler.backend.x64

import slak.ckompiler.backend.ICBuilder
import slak.ckompiler.backend.VariableUse
import slak.ckompiler.backend.dummyInstructionClass
import slak.ckompiler.backend.instructionClass

fun x64InstructionClass(
    name: String,
    defaultUse: List<VariableUse>? = null,
    block: ICBuilder<X64Operand, X64InstrTemplate>.() -> Unit,
)= instructionClass(name, ::X64InstrTemplate, defaultUse, block)

typealias X64ICBuilderBlock = ICBuilder<X64Operand, X64InstrTemplate>.() -> Unit

private val nullary: X64ICBuilderBlock = { instr() }

val dummyUse = dummyInstructionClass("USE", ::X64InstrTemplate, listOf(VariableUse.USE)) {
  instr(ModRM.R8)
  instr(ModRM.R16)
  instr(ModRM.R32)
  instr(ModRM.R64)
}

val imul = x64InstructionClass("imul", listOf(VariableUse.DEF, VariableUse.USE, VariableUse.USE)) {
  // 3 operand RMI form
  instr(ModRM.R16, ModRM.RM16, Imm.IMM8)
  instr(ModRM.R32, ModRM.RM32, Imm.IMM8)
  instr(ModRM.R64, ModRM.RM64, Imm.IMM8)
  instr(ModRM.R16, ModRM.RM16, Imm.IMM16)
  instr(ModRM.R32, ModRM.RM32, Imm.IMM32)
  instr(ModRM.R64, ModRM.RM64, Imm.IMM32)
  // 2 operand RM form
  val twoOp = listOf(VariableUse.DEF_USE, VariableUse.USE)
  twoOp.instr(ModRM.R16, ModRM.RM16)
  twoOp.instr(ModRM.R32, ModRM.RM32)
  twoOp.instr(ModRM.R64, ModRM.RM64)
  // 1 operand M form
  val oneOp = listOf(VariableUse.DEF_USE)
  oneOp.instr(ModRM.RM8)
  oneOp.instr(ModRM.RM16)
  oneOp.instr(ModRM.RM32)
  oneOp.instr(ModRM.RM64)
}

private val division: X64ICBuilderBlock = {
  instr(ModRM.RM8)
  instr(ModRM.RM16)
  instr(ModRM.RM32)
  instr(ModRM.RM64)
}

val div = x64InstructionClass("div", listOf(VariableUse.USE), division)
val idiv = x64InstructionClass("idiv", listOf(VariableUse.USE), division)

val cwd = x64InstructionClass("cwd", emptyList(), nullary)
val cdq = x64InstructionClass("cdq", emptyList(), nullary)
val cqo = x64InstructionClass("cqo", emptyList(), nullary)

private val arithmetic: X64ICBuilderBlock = {
  instr(ModRM.RM8, Imm.IMM8)
  instr(ModRM.RM16, Imm.IMM16)
  instr(ModRM.RM32, Imm.IMM32)
  instr(ModRM.RM64, Imm.IMM32)

  instr(ModRM.RM16, Imm.IMM8)
  instr(ModRM.RM32, Imm.IMM8)
  instr(ModRM.RM64, Imm.IMM8)

  instr(ModRM.RM8, ModRM.R8)
  instr(ModRM.RM16, ModRM.R16)
  instr(ModRM.RM32, ModRM.R32)
  instr(ModRM.RM64, ModRM.R64)

  instr(ModRM.R8, ModRM.RM8)
  instr(ModRM.R16, ModRM.RM16)
  instr(ModRM.R32, ModRM.RM32)
  instr(ModRM.R64, ModRM.RM64)
}

val add = x64InstructionClass("add", listOf(VariableUse.DEF_USE, VariableUse.USE), arithmetic)
val sub = x64InstructionClass("sub", listOf(VariableUse.DEF_USE, VariableUse.USE), arithmetic)
val cmp = x64InstructionClass("cmp", listOf(VariableUse.USE, VariableUse.USE), arithmetic)
val and = x64InstructionClass("and", listOf(VariableUse.DEF_USE, VariableUse.USE), arithmetic)
val or = x64InstructionClass("or", listOf(VariableUse.DEF_USE, VariableUse.USE), arithmetic)
val xor = x64InstructionClass("xor", listOf(VariableUse.DEF_USE, VariableUse.USE), arithmetic)

private val unaryRM: X64ICBuilderBlock = {
  instr(ModRM.RM8)
  instr(ModRM.RM16)
  instr(ModRM.RM32)
  instr(ModRM.RM64)
}

val neg = x64InstructionClass("neg", listOf(VariableUse.DEF_USE), unaryRM)
val not = x64InstructionClass("not", listOf(VariableUse.DEF_USE), unaryRM)

val lea = x64InstructionClass("lea", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(ModRM.R16, ModRM.M)
  instr(ModRM.R32, ModRM.M)
  instr(ModRM.R64, ModRM.M)
}

val mov = x64InstructionClass("mov", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(ModRM.RM8, ModRM.R8)
  instr(ModRM.RM16, ModRM.R16)
  instr(ModRM.RM32, ModRM.R32)
  instr(ModRM.RM64, ModRM.R64)

  instr(ModRM.R8, ModRM.RM8)
  instr(ModRM.R16, ModRM.RM16)
  instr(ModRM.R32, ModRM.RM32)
  instr(ModRM.R64, ModRM.RM64)

  instr(ModRM.R8, Imm.IMM8)
  instr(ModRM.R16, Imm.IMM16)
  instr(ModRM.R32, Imm.IMM32)
  instr(ModRM.R64, Imm.IMM64)

  instr(ModRM.RM8, Imm.IMM8)
  instr(ModRM.RM16, Imm.IMM16)
  instr(ModRM.RM32, Imm.IMM32)
  instr(ModRM.RM64, Imm.IMM32)
}

private val movAndExtend: X64ICBuilderBlock = {
  instr(ModRM.R16, ModRM.RM8)
  instr(ModRM.R32, ModRM.RM8)
  instr(ModRM.R64, ModRM.RM8)

  instr(ModRM.R32, ModRM.RM16)
  instr(ModRM.R64, ModRM.RM16)
}

val movzx = x64InstructionClass("movzx", listOf(VariableUse.DEF, VariableUse.USE), movAndExtend)
val movsx = x64InstructionClass("movsx", listOf(VariableUse.DEF, VariableUse.USE), movAndExtend)
val movsxd = x64InstructionClass("movsxd", listOf(VariableUse.DEF, VariableUse.USE)) { instr(ModRM.R64, ModRM.RM32) }

val movq = x64InstructionClass("movq", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(ModRM.XMM_SS, ModRM.XMM_SS)
  instr(ModRM.XMM_SD, ModRM.XMM_SD)
  instr(ModRM.XMM_SS, ModRM.M64)
  instr(ModRM.XMM_SD, ModRM.M64)

  instr(ModRM.M64, ModRM.XMM_SS)
  instr(ModRM.M64, ModRM.XMM_SD)
}

val setcc = listOf(
    "seta", "setae", "setb", "setbe", "setc", "sete", "setg", "setge", "setl",
    "setle", "setna", "setnae", "setnb", "setnbe", "setnc", "setne", "setng", "setnge", "setnl",
    "setnle", "setno", "setnp", "setns", "setnz", "seto", "setp", "setpe", "setpo", "sets", "setz"
).associateWith {
  x64InstructionClass(it, listOf(VariableUse.DEF)) { instr(ModRM.RM8) }
}

val jcc = listOf(
    "ja", "jae", "jb", "jbe", "jc", "jcxz", "jecxz", "jrcxz", "je", "jg", "jge", "jl", "jle", "jna",
    "jnae", "jnb", "jnbe", "jnc", "jne", "jng", "jnge", "jnl", "jnle", "jno", "jnp", "jns", "jnz",
    "jo", "jp", "jpe", "jpo", "js", "jz"
).associateWith {
  x64InstructionClass(it, listOf(VariableUse.USE)) { instr(JumpTarget) }
}

val jmp = x64InstructionClass("jmp", listOf(VariableUse.USE)) { instr(JumpTarget) }

val call = x64InstructionClass("call", listOf(VariableUse.USE)) {
  instr(NamedDisplacement)
  instr(ModRM.RM16)
  instr(ModRM.RM32)
  instr(ModRM.RM64)
}

val leave = x64InstructionClass("leave", emptyList(), nullary)
val ret = x64InstructionClass("ret", emptyList(), nullary)

val push = x64InstructionClass("push", listOf(VariableUse.USE)) {
  instr(ModRM.RM16)
  instr(ModRM.RM32)
  instr(ModRM.RM64)

  instr(ModRM.R16)
  instr(ModRM.R32)
  instr(ModRM.R64)

  instr(Imm.IMM8)
  instr(Imm.IMM16)
  instr(Imm.IMM32)
}

val pop = x64InstructionClass("pop", listOf(VariableUse.DEF)) {
  instr(ModRM.RM16)
  instr(ModRM.RM32)
  instr(ModRM.RM64)

  instr(ModRM.R16)
  instr(ModRM.R32)
  instr(ModRM.R64)
}

val movss = x64InstructionClass("movss", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(ModRM.M32, ModRM.XMM_SS)
  instr(ModRM.XMM_SS, ModRM.XMM_SS)
  instr(ModRM.XMM_SS, ModRM.M32)
}

val movsd = x64InstructionClass("movsd", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(ModRM.M64, ModRM.XMM_SD)
  instr(ModRM.XMM_SD, ModRM.XMM_SD)
  instr(ModRM.XMM_SD, ModRM.M64)
}

val cvtsi2ss = x64InstructionClass("cvtsi2ss", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(ModRM.XMM_SS, ModRM.RM32)
  instr(ModRM.XMM_SS, ModRM.RM64)
}

val cvtsi2sd = x64InstructionClass("cvtsi2sd", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(ModRM.XMM_SD, ModRM.RM32)
  instr(ModRM.XMM_SD, ModRM.RM64)
}

val cvtss2sd = x64InstructionClass("cvtss2sd", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(ModRM.XMM_SD, ModRM.XMM_SS)
  instr(ModRM.XMM_SD, ModRM.M32)
}

val cvtsd2ss = x64InstructionClass("cvtsd2ss", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(ModRM.XMM_SS, ModRM.XMM_SD)
  instr(ModRM.XMM_SS, ModRM.M64)
}

val cvttss2si = x64InstructionClass("cvttss2si", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(ModRM.R32, ModRM.XMM_SS)
  instr(ModRM.R32, ModRM.M32)
  instr(ModRM.R64, ModRM.XMM_SS)
  instr(ModRM.R64, ModRM.M32)
}

val cvttsd2si = x64InstructionClass("cvttsd2si", listOf(VariableUse.DEF, VariableUse.USE)) {
  instr(ModRM.R32, ModRM.XMM_SD)
  instr(ModRM.R32, ModRM.M64)
  instr(ModRM.R64, ModRM.XMM_SD)
  instr(ModRM.R64, ModRM.M64)
}

private val ssArithmetic: X64ICBuilderBlock = {
  instr(ModRM.XMM_SS, ModRM.XMM_SS)
  instr(ModRM.XMM_SS, ModRM.M32)
}

val addss = x64InstructionClass("addss", listOf(VariableUse.DEF_USE, VariableUse.USE), ssArithmetic)
val subss = x64InstructionClass("subss", listOf(VariableUse.DEF_USE, VariableUse.USE), ssArithmetic)
val mulss = x64InstructionClass("mulss", listOf(VariableUse.DEF_USE, VariableUse.USE), ssArithmetic)
val divss = x64InstructionClass("divss", listOf(VariableUse.DEF_USE, VariableUse.USE), ssArithmetic)
val xorps = x64InstructionClass("xorps", listOf(VariableUse.DEF_USE, VariableUse.USE), ssArithmetic)
val comiss = x64InstructionClass("comiss", listOf(VariableUse.USE, VariableUse.USE), ssArithmetic)

private val sdArithmetic: X64ICBuilderBlock = {
  instr(ModRM.XMM_SD, ModRM.XMM_SD)
  instr(ModRM.XMM_SD, ModRM.M64)
}

val addsd = x64InstructionClass("addsd", listOf(VariableUse.DEF_USE, VariableUse.USE), sdArithmetic)
val subsd = x64InstructionClass("subsd", listOf(VariableUse.DEF_USE, VariableUse.USE), sdArithmetic)
val mulsd = x64InstructionClass("mulsd", listOf(VariableUse.DEF_USE, VariableUse.USE), sdArithmetic)
val divsd = x64InstructionClass("divsd", listOf(VariableUse.DEF_USE, VariableUse.USE), sdArithmetic)
val xorpd = x64InstructionClass("xorpd", listOf(VariableUse.DEF_USE, VariableUse.USE), sdArithmetic)
val comisd = x64InstructionClass("comisd", listOf(VariableUse.USE, VariableUse.USE), sdArithmetic)
