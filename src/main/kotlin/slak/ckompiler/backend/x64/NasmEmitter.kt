package slak.ckompiler.backend.x64

import slak.ckompiler.AtomicId
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*

typealias Instructions = List<String>

private class InstructionBuilder {
  private val instr = mutableListOf<String>()

  fun label(s: String) {
    instr += "$s:"
  }

  fun emit(s: String) {
    instr += "  $s"
  }

  fun emit(s: Instructions) {
    for (i in s) instr += i
  }

  fun toInstructions(): Instructions = instr
}

private inline fun instrGen(block: InstructionBuilder.() -> Unit): Instructions {
  val builder = InstructionBuilder()
  builder.block()
  return builder.toInstructions()
}

class NasmEmitter(
    override val externals: List<String>,
    override val functions: List<TargetFunGenerator>,
    override val mainCfg: TargetFunGenerator?
) : AsmEmitter {
  private val peepholeOptimizer = X64PeepholeOpt()

  private val prelude = mutableListOf<String>()
  private val text = mutableListOf<String>()
  private val data = mutableListOf<String>()

  /**
   * This maps literals to a label in .data with their value. It also enables deduplication, because
   * it is undefined behaviour to modify string literals.
   *
   * C standard: 6.4.5.0.7
   */
  private val stringRefs = mutableMapOf<StrConstant, String>()

  /**
   * Maps a float to a label with the value.
   * @see stringRefs
   */
  private val floatRefs = mutableMapOf<FltConstant, String>()

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

  private fun generateFunction(function: TargetFunGenerator) {
    prelude += "global ${function.graph.f.name}"
    val allocationResult = function.regAlloc()
    val asmMap = function.applyAllocation(allocationResult)
    text += instrGen {
      label(function.graph.f.name)
      emit(genAsm(function.genFunctionPrologue(allocationResult)))
      for (block in function.graph.domTreePreorder) {
        label(block.label)
        @Suppress("UNCHECKED_CAST")
        emit(genAsm(peepholeOptimizer.optimize(function, asmMap.getValue(block) as List<X64Instruction>)))
      }
      label(function.returnBlock.label)
      emit(genAsm(function.genFunctionEpilogue(allocationResult)))
    }
  }

  private fun genAsm(blockAsm: List<AsmInstruction>) = instrGen {
    for (i in blockAsm) {
      require(i is X64Instruction)
      // lea is special
      val isLea = i.template in lea
      emit("${i.template.name} ${i.operands.joinToString(", ") {
        operandToString(it, forgetPrefix = isLea)
      }}")
    }
  }

  private fun operandToString(
      operand: X64Value,
      forgetPrefix: Boolean = false
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
    val labelName = const.value.toString()
        .replace('.', '_')
        .replace('+', 'p')
        .replace('-', 'm')
    floatRefs[const] = "f_${labelName}_${floatRefs.size}"
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
    val stringPeek = const.value.filter(Char::isLetterOrDigit).take(5)
    stringRefs[const] = "s_${stringPeek}_${stringRefs.size}"
    val bytes = const.value.toByteArray().joinToString(", ")
    data += "; ${const.value}"
    data += "${stringRefs[const]}: db $bytes, 0"
  }
}
