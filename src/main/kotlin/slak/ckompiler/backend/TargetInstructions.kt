package slak.ckompiler.backend

import kotlin.jvm.JvmName

class ICBuilder<O : Operand, T : InstructionTemplate<O>>(
    val instructions: MutableList<T>,
    val name: String,
    val defaultUse: List<VariableUse>? = null,
    val createTemplate: (name: String, operandType: List<O>, operandUse: List<VariableUse>) -> T
) {
  fun instr(vararg operands: O) = instr(defaultUse!!, *operands)

  fun instr(operandUse: List<VariableUse>, vararg operands: O) {
    instructions += createTemplate(name, operands.toList(), operandUse)
  }

  @JvmName("l_instr")
  fun List<VariableUse>.instr(vararg operands: O): Unit = instr(this, *operands)
}

fun <O : Operand, T : InstructionTemplate<O>> instructionClass(
    name: String,
    createTemplate: (name: String, operandType: List<O>, operandUse: List<VariableUse>) -> T,
    defaultUse: List<VariableUse>? = null,
    block: ICBuilder<O, T>.() -> Unit,
): List<T> {
  val builder = ICBuilder(mutableListOf(), name, defaultUse, createTemplate)
  builder.block()
  return builder.instructions
}

fun <O : Operand, T : InstructionTemplate<O>> dummyInstructionClass(
    name: String,
    createTemplate: (name: String, operandType: List<O>, operandUse: List<VariableUse>) -> T,
    defaultUse: List<VariableUse>? = null,
    block: ICBuilder<O, T>.() -> Unit,
): List<T> = instructionClass("DUMMY $name DO NOT EMIT", createTemplate, defaultUse, block)
