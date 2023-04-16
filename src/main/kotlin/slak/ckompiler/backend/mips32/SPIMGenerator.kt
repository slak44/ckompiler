package slak.ckompiler.backend.mips32

import slak.ckompiler.AtomicId
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*

class SPIMGenerator(
    externals: List<String>,
    functions: List<TargetFunGenerator<MIPS32Instruction>>,
    mainCfg: TargetFunGenerator<MIPS32Instruction>?,
) : AsmEmitter<MIPS32Instruction>(externals, functions, mainCfg) {
  private val prelude = mutableListOf<String>()
  private val text = mutableListOf<String>()

  override fun emitAsm(): String {
    for (external in externals) prelude += ".globl $external"

    mainCfg?.let { function ->
      text += genStartRoutine()
      generateFunction(function, "_main")
    }

    for (function in functions) generateFunction(function)

    if ("printf" in externals) {
      text += generatePrintfSupport()
    }

    text += generateExitFunction()

    val code = prelude + ".data" + data + ".text" + text
    return code.joinToString("\n") + '\n'
  }

  /**
   * exit2, with return value.
   * https://www.doc.ic.ac.uk/lab/secondyear/spim/node8.html
   */
  private fun generateExitFunction() = instrGen {
    label("exit")
    emit("li \$v0, 17")
    emit("syscall")
  }

  private fun genStartRoutine() = instrGen {
    label("main")
    emit("jal _main")
    emit("move \$a0, \$v0")
    emit("jal exit")
  }

  private fun generatePrintfSupport() = instrGen {
    label("__builtin_print_char")
    emit("li \$v0, 11")
    emit("syscall")
    emit("jr \$ra")

    label("__builtin_print_int")
    emit("li \$v0, 1")
    emit("syscall")
    emit("jr \$ra")

    label("__builtin_print_string")
    emit("li \$v0, 4")
    emit("syscall")
    emit("jr \$ra")

    label("__builtin_print_float")
    emit("li \$v0, 3")
    emit("syscall")
    emit("jr \$ra")

    label("printf")
    emit("move \$a1, \$sp")
    emit("addiu \$sp, \$sp, -4")
    emit("sw \$ra, (\$sp)")
    emit("addiu \$a1, \$a1, 4")
    emit("jal __builtin_printf_no_va")
    emit("lw \$ra, (\$sp)")
    emit("addiu \$sp, \$sp, 4")
    emit("jr \$ra")
  }

  val InstrBlock.label get() = id.label
  val AtomicId.label get() = ".block_$this"

  private fun generateFunction(function: TargetFunGenerator<MIPS32Instruction>, overrideName: String? = null) {
    val allocationResult = function.regAlloc()
    text += instrGen {
      label(overrideName ?: function.graph.f.name)
      emit(genAsm(function.genFunctionPrologue(allocationResult)))
      val asmMap = function.applyAllocation(allocationResult)
      for (block in function.graph.blocks - function.graph.returnBlock.id) {
        label(block.label)
        emit(genAsm(asmMap.getValue(block)))
      }
      label(function.graph.returnBlock.label)
      emit(genAsm(function.genFunctionEpilogue(allocationResult)))
    }
  }

  private fun genAsm(instructions: List<MIPS32Instruction>) = instrGen {
    for (i in instructions) {
      emit("${i.template.name} ${i.operands.joinToString(", ") { operandToString(it) }}")
    }
  }

  private fun operandToString(operand: MIPS32Value): String = when (operand) {
    is MIPS32ImmediateValue -> when (val const = operand.value) {
      is FltConstant -> TODO()
      is IntConstant -> const.toString()
      is JumpTargetConstant -> const.target.label
      is NamedConstant -> const.name
      is StrConstant -> {
        if (const !in stringRefs) createStringConstant(const)
        stringRefs.getValue(const)
      }
    }
    is MIPS32MemoryValue -> "${operand.displacement}(${operand.base.register.regName})"
    is MIPS32RegisterValue -> operand.register.regName
  }

  private fun createStringConstant(const: StrConstant) {
    val bytes = createStringConstantText(const)
    data += "# ${const.value}"
    data += "${stringRefs[const]}: .byte $bytes, 0"
  }
}
