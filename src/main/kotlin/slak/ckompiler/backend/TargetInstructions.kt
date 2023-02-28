package slak.ckompiler.backend

import kotlin.jvm.JvmName

private typealias CreateInstructionTemplateProvider<O, T> =
    (name: String, operandType: List<O>, operandUse: List<VariableUse>, isDummy: Boolean) -> T

class ICBuilder<O : OperandTemplate, T : InstructionTemplate<O>>(
    val instructions: MutableList<T>,
    val name: String,
    val isDummy: Boolean,
    val defaultUse: List<VariableUse>? = null,
    val createTemplate: CreateInstructionTemplateProvider<O, T>
) {
  fun instr(vararg operands: O) = instr(defaultUse!!, *operands)

  fun instr(operandUse: List<VariableUse>, vararg operands: O) {
    instructions += createTemplate(name, operands.toList(), operandUse, isDummy)
  }

  @JvmName("l_instr")
  fun List<VariableUse>.instr(vararg operands: O): Unit = instr(this, *operands)
}

fun <O : OperandTemplate, T : InstructionTemplate<O>> instructionClass(
    name: String,
    createTemplate: CreateInstructionTemplateProvider<O, T>,
    defaultUse: List<VariableUse>? = null,
    block: ICBuilder<O, T>.() -> Unit,
): List<T> {
  val builder = ICBuilder(mutableListOf(), name, false, defaultUse, createTemplate)
  builder.block()
  return builder.instructions
}

fun <O : OperandTemplate, T : InstructionTemplate<O>> dummyInstructionClass(
    name: String,
    createTemplate: CreateInstructionTemplateProvider<O, T>,
    defaultUse: List<VariableUse>? = null,
    block: ICBuilder<O, T>.() -> Unit,
): List<T> {
  val builder = ICBuilder(mutableListOf(), "DUMMY $name DO NOT EMIT", true, defaultUse, createTemplate)
  builder.block()
  return builder.instructions
}
