package slak.ckompiler.backend.x64

import slak.ckompiler.AtomicId
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*

class NasmEmitter(
    externals: List<String>,
    functions: List<TargetFunGenerator<X64Instruction>>,
    mainCfg: TargetFunGenerator<X64Instruction>?,
) : AsmEmitter<X64Instruction>(externals, functions, mainCfg) {
  private val peepholeOptimizer = X64PeepholeOpt()

  private val prelude = mutableListOf<String>()
  private val text = mutableListOf<String>()

  override fun emitAsm(): String {
    for (external in externals) prelude += "extern $external"
    for (function in functions) generateFunction(function)
    mainCfg?.let { function ->
      text += genStartRoutine()
      generateFunction(function)
    }

    val code = prelude + "section .data" + data + "section .text" + text
    return code.joinToString("\n") + '\n'
  }

  /**
   * C standard: 5.1.2.2.3
   * System V ABI: 3.4.1, page 28, figure 3.9
   */
  private fun genStartRoutine() = instrGen {
    prelude += "extern exit"
    prelude += "global _start"
    label("_start")
    // argc:
    emit("mov rdi, [rsp]")
    // argv:
    emit("mov rsi, [rsp+8]")
    // envp:
    emit("lea rax, [8*rdi+rsp+16]")
    emit("mov rdx, [rax]")
    // Call main
    emit("call main")
    // Integral return value is in rax
    emit("mov rdi, rax")
    emit("call exit")
  }

  val InstrBlock.label get() = id.label
  val AtomicId.label get() = ".block_$this"

  private fun generateFunction(function: TargetFunGenerator<X64Instruction>) {
    prelude += "global ${function.graph.functionName}"
    val allocationResult = function.regAlloc()
    text += instrGen {
      label(function.graph.functionName)
      emit(genAsm(function.genFunctionPrologue(allocationResult)))
      val asmMap = function.applyAllocation(allocationResult)
      for (block in function.graph.blocks - function.graph.returnBlock.id) {
        label(block.label)
        emit(genAsm(peepholeOptimizer.optimize(function, asmMap.getValue(block))))
      }
      label(function.graph.returnBlock.label)
      emit(genAsm(function.genFunctionEpilogue(allocationResult)))
    }
  }

  private fun genAsm(blockAsm: List<X64Instruction>) = instrGen {
    for (i in blockAsm) {
      // lea is special
      val isLea = i.template in lea
      emit("${i.template.name} ${
        i.operands.joinToString(", ") {
          operandToString(it, forgetPrefix = isLea)
        }
      }")
    }
  }

  private fun operandToString(
      operand: X64Value,
      forgetPrefix: Boolean = false,
  ): String = when (operand) {
    is ImmediateValue -> when (val const = operand.value) {
      is IntConstant -> const.toString()
      is FltConstant -> {
        if (const !in floatRefs) createFloatConstant(const)
        // FIXME:
        "[${floatRefs.getValue(const)}]"
      }
      is StrConstant -> {
        if (const !in stringRefs) createStringConstant(const)
        stringRefs.getValue(const)
      }
      is JumpTargetConstant -> const.target.label
      is NamedConstant -> const.name
    }
    is RegisterValue -> regToStr(operand.register, operand.size)
    is MemoryValue -> "${if (forgetPrefix) "" else prefixFor(operand.sizeInMem)}${operand}"
  }

  private fun prefixFor(size: Int): String = when (size) {
    1 -> "byte"
    2 -> "word"
    4 -> "dword"
    8 -> "qword"
    else -> TODO()
  } + ' '

  private fun regToStr(register: MachineRegister, size: Int): String {
    return if (register.sizeBytes == size) register.regName
    else register.aliases.first { it.second == size }.first
  }

  private fun createFloatConstant(const: FltConstant) {
    floatRefs[const] = createFloatConstantText(const)
    val kind = when (MachineTargetData.x64.sizeOf(const.type)) {
      4 -> "dd"
      8 -> "dq"
      else -> TODO("handle larger floats")
    }
    val fltNasm = when {
      const.value.isNaN() -> "__QNaN__"
      const.value == Double.POSITIVE_INFINITY -> "__Infinity__"
      const.value == Double.NEGATIVE_INFINITY -> "-__Infinity__"
      else -> const.value.toString()
    }
    data += "${floatRefs[const]}: $kind $fltNasm"
  }

  private fun createStringConstant(const: StrConstant) {
    val bytes = createStringConstantText(const)
    data += "; ${const.value}"
    data += "${stringRefs[const]}: db $bytes, 0"
  }
}
