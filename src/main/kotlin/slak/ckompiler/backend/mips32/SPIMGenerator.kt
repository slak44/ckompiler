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
    for (function in functions) generateFunction(function)
    mainCfg?.let { function ->
      text += genStartRoutine()
      generateFunction(function)
    }

    val code = prelude + ".data" + data + ".text" + text
    return code.joinToString("\n") + '\n'
  }

  private fun genStartRoutine() = instrGen {
    // TODO: make our own start routine sometime
  }

  val InstrBlock.label get() = id.label
  val AtomicId.label get() = ".block_$this"

  private fun generateFunction(function: TargetFunGenerator<MIPS32Instruction>) {
    val allocationResult = function.regAlloc()
    text += instrGen {
      label(function.graph.f.name)
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
