package slak.ckompiler.backend.x64

import slak.ckompiler.analysis.*
import slak.ckompiler.backend.AsmEmitter
import slak.ckompiler.backend.AsmInstruction
import slak.ckompiler.backend.EmissileFunction

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
    for (i in s) emit(i)
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
    override val functions: List<EmissileFunction>,
    override val mainCfg: EmissileFunction?
) : AsmEmitter {
  private val prelude = mutableListOf<String>()
  private val text = mutableListOf<String>()
  private val data = mutableListOf<String>()

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

  val BasicBlock.label get() = ".block_${hashCode()}"

  private fun generateFunction(function: EmissileFunction) {
    prelude += "global ${function.first.cfg.f.name}"
    val (asmGenerator, allocationResult) = function
    val asmMap = asmGenerator.applyAllocation(allocationResult)
    val returnBlock = asmMap.keys.single { it.postOrderId == -1 }
    text += instrGen {
      label(asmGenerator.cfg.f.name)
      emit(genAsm(asmGenerator.genFunctionPrologue(allocationResult)))
      for ((block, asm) in asmMap - returnBlock) {
        label(block.label)
        emit(genAsm(asm))
      }
      label(returnBlock.label)
      emit(genAsm(asmGenerator.genFunctionEpilogue(allocationResult)))
    }
  }

  private fun genAsm(blockAsm: List<AsmInstruction>) = instrGen {
    for (i in blockAsm) {
      require(i is X64Instruction)
      emit("${i.template.name} ${i.operands.joinToString(", ") { operandToString(it) }}")
    }
  }

  // FIXME: handle float and string operands, they're stored separately
  private fun operandToString(operand: X64Value): String = when (operand) {
    is ImmediateValue -> when (val const = operand.value) {
      is IntConstant -> const.toString()
      is FltConstant -> TODO()
      is StrConstant -> TODO()
      is JumpTargetConstant -> const.target.label
    }
    is RegisterValue -> operand.toString()
    is StackValue -> operand.toString()
  }
}
