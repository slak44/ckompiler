package slak.ckompiler.backend.nasm_x86_64

import org.apache.logging.log4j.LogManager
import slak.ckompiler.analysis.*
import slak.ckompiler.lexer.FloatingSuffix
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE
import java.util.*

private val logger = LogManager.getLogger("CodeGenerator")

typealias Instructions = List<String>

private class InstructionBuilder {
  private var indentLevel = 0
  private val instr = mutableListOf<String>()

  private val currentIndent get() = "  ".repeat(indentLevel)

  fun label(s: String) {
    instr += "$currentIndent$s:"
    indentLevel++
  }

  fun emit(s: String) {
    instr += "$currentIndent$s"
  }

  fun emit(s: Instructions) {
    for (i in s) emit(i)
  }

  fun toInstructions(): Instructions = instr
}

private fun instrGen(block: InstructionBuilder.() -> Unit): Instructions {
  val builder = InstructionBuilder()
  builder.block()
  return builder.toInstructions()
}

/**
 * Data used while generating a function from a [cfg].
 */
private data class FunctionGenContext(val variableRefs: MutableMap<ComputeReference, Int>,
                                      val wasBlockGenerated: BitSet,
                                      val cfg: CFG) {
  val retLabel = ".return_${cfg.f.name}"
  val BasicBlock.label get() = ".block_${cfg.f.name}_$nodeId"
  val ComputeReference.pos get() = "[rbp${variableRefs[copy()]}]"
}

/** Generate x86_64 NASM code. */
class NasmGenerator(externals: List<String>, functions: List<CFG>, mainCfg: CFG?) {
  private val prelude = mutableListOf<String>()
  private val text = mutableListOf<String>()
  private val data = mutableListOf<String>()

  val nasm: String

  /**
   * This maps literals to a label in .data with their value. It also enables deduplication, because
   * it is undefined behaviour to modify string literals.
   *
   * C standard: 6.4.5.0.7
   */
  private val stringRefs = mutableMapOf<StringLiteralNode, String>()
  private val stringRefIds = IdCounter()

  /**
   * Maps a float to a label with the value.
   * @see stringRefs
   */
  private val floatRefs = mutableMapOf<FloatingConstantNode, String>()
  private val floatRefIds = IdCounter()

  /**
   * System V ABI: 3.2.3, page 20
   */
  private val intArgRegisters = listOf("rdi", "rsi", "rdx", "rcx", "r8", "r9")

  /**
   * System V ABI: 3.2.3, page 20
   */
  private val fltArgRegisters =
      listOf("xmm0", "xmm1", "xmm2", "xmm3", "xmm4", "xmm5", "xmm6", "xmm7")

  init {
    for (external in externals) prelude += "extern $external"
    for (function in functions) generateFunctionFromCFG(function)
    mainCfg?.let {
      text += genStartRoutine()
      generateFunctionFromCFG(it)
    }

    val code = prelude + "section .data" + data + "section .text" + text
    nasm = code.joinToString("\n") + '\n'
  }

  private fun generateFunctionFromCFG(cfg: CFG) {
    val ctx = FunctionGenContext(mutableMapOf(), BitSet(cfg.nodes.size), cfg)
    prelude += "extern exit"
    prelude += "global ${cfg.f.name}"
    text += ctx.genFun(cfg)
  }

  /**
   * System V ABI: 3.4.1, page 28, figure 3.9
   */
  private fun genStartRoutine() = instrGen {
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

  /**
   * C standard: 5.1.2.2.3
   * System V ABI: 3.2.1, figure 3.3
   */
  private fun FunctionGenContext.genFun(cfg: CFG) = instrGen {
    label(cfg.f.name)
    // Callee-saved registers
    // FIXME: if the registers are not used in the function, saving them wastes 2 instructions
    // FIXME: using rbp as frame pointer wastes a general-purpose register
    emit("push rbp")
    emit("push rbx")
    emit("push r8")
    emit("push r9")
    // FIXME: r12-r15 are also callee-saved
    // New stack frame
    emit("mov rbp, rsp")
    // Local variables
    // FIXME: local variable size is not always 8 bytes
    var rbpOffset = -8
    for ((ref) in cfg.definitions) {
      emit("; ${ref.tid.name}")
      // FIXME: they're not all required to go on the stack
      // FIXME: initial value shouldn't always be 0
      emit("push 0")
      variableRefs[ref] = rbpOffset
      rbpOffset -= 8
    }
    // Regular function arguments
    var intArgCounter = 0
    var fltArgCounter = 0
    for (arg in cfg.f.parameters) {
      if (arg.type.isABIIntegerType()) {
        emit("mov ${ComputeReference(arg).pos}, ${intArgRegisters[intArgCounter]}")
        intArgCounter++
        if (intArgCounter >= intArgRegisters.size) {
          TODO("too many int parameters, not implemented yet")
        }
      } else if (arg.type.isSSEType()) {
        emit("movaps ${ComputeReference(arg).pos}, ${fltArgRegisters[fltArgCounter]}")
        fltArgCounter++
        if (fltArgCounter >= fltArgRegisters.size) {
          TODO("too many float parameters, not implemented yet")
        }
      } else {
        TODO("other types")
      }
    }
    // Start actual codegen
    emit(genBlock(cfg.startBlock))
    // Generate leftover blocks not touched by travelling through the code
    for (block in cfg.nodes) {
      if (!wasBlockGenerated[block.nodeId]) {
        emit(genBlock(block))
      }
    }
    // Epilogue
    label(retLabel)
    emit("mov rsp, rbp")
    emit("pop r9")
    emit("pop r8")
    emit("pop rbx")
    emit("pop rbp")
    emit("ret")
  }

  private fun FunctionGenContext.genBlock(b: BasicBlock) = instrGen {
    if (wasBlockGenerated[b.nodeId]) return@instrGen
    wasBlockGenerated[b.nodeId] = true
    label(b.label)
    emit(genExpressions(b.irContext))
    emit(genJump(b.terminator))
  }

  private fun FunctionGenContext.genJump(jmp: Jump): Instructions = when (jmp) {
    is CondJump -> genCondJump(jmp)
    is UncondJump -> genUncondJump(jmp.target)
    is ImpossibleJump -> genReturn(jmp.returned)
    is ConstantJump -> genUncondJump(jmp.target)
    // FIXME: handle the case where the function is main, and the final block is allowed to be this;
    //   alternatively, fix this in the parser by handling main separately
    MissingJump -> logger.throwICE("Incomplete BasicBlock")
  }

  private fun FunctionGenContext.genZeroJump(target: BasicBlock) = instrGen {
    emit("cmp rax, 0") // FIXME: random use of rax
    emit("jnz ${target.label}")
  }

  private fun FunctionGenContext.genCondJump(jmp: CondJump) = instrGen {
    emit(genExpressions(jmp.cond))
    // FIXME: missed optimization, we might be able to generate better code for stuff like
    //   `a < 1 && b > 2` if we look further than the last IR expression
    val condExpr = jmp.cond.ir.last()
    if (condExpr is Store && condExpr.data is BinaryComputation) when (condExpr.data.op) {
      BinaryComputations.LESS_THAN -> emit("jl ${jmp.target.label}")
      BinaryComputations.GREATER_THAN -> emit("jg ${jmp.target.label}")
      BinaryComputations.LESS_EQUAL_THAN -> emit("jle ${jmp.target.label}")
      BinaryComputations.GREATER_EQUAL_THAN -> emit("jge ${jmp.target.label}")
      BinaryComputations.EQUAL -> emit("je ${jmp.target.label}")
      BinaryComputations.NOT_EQUAL -> emit("jne ${jmp.target.label}")
      else -> emit(genZeroJump(jmp.target))
    } else {
      emit(genZeroJump(jmp.target))
    }
    // Try to generate the "else" block right after the cmp, so that if the cond is false, we just
    // keep executing without having to do another jump
    if (!wasBlockGenerated[jmp.other.nodeId]) {
      emit(genBlock(jmp.other))
    } else {
      emit("jmp ${jmp.other.label}")
    }
  }

  private fun FunctionGenContext.genUncondJump(target: BasicBlock) = instrGen {
    emit("jmp ${target.label}")
    emit(genBlock(target))
  }

  /**
   * We classify return types using the same conventions as the ABI.
   *
   * System V ABI: 3.2.3, page 22
   */
  private fun FunctionGenContext.genReturn(retExpr: IRLoweringContext?) = instrGen {
    if (retExpr == null) {
      // Nothing to return
      return@instrGen
    }
    emit(genExpressions(retExpr))
    when (retExpr.src.last().type) {
      ErrorType -> logger.throwICE("ErrorType cannot propagate to codegen stage") { retExpr }
      is FunctionType -> logger.throwICE("FunctionType is an illegal return type") { retExpr }
      // INTEGER classification
      is PointerType, is IntegralType -> {
        // FIXME: random use of rax
        // FIXME: we currently put integer expression results in rax anyway
        emit("mov rax, rax")
      }
      VoidType -> {
        // This happens when function returns void, and the return statement returns a void expr,
        // something like "return f();", when f also returns void
        // Do nothing; as far as we're concerned, the expression can do whatever it wants, but the
        // function still returns nothing
      }
      // SSE classification
      FloatType, DoubleType -> {
        // FIXME: random use of xmm8
        // FIXME: there are two SSE return registers, xmm0 and xmm1
        emit("movaps xmm0, xmm8")
      }
      is ArrayType -> TODO()
      is BitfieldType -> TODO()
      is StructureType -> TODO()
      is UnionType -> TODO()
      LongDoubleType -> TODO("this type has a complicated ABI")
    }
    emit("jmp $retLabel")
  }

  private fun FunctionGenContext.genExpressions(ctx: IRLoweringContext) = instrGen {
    for (e in ctx.ir) emit(genExpr(e))
  }

  private fun FunctionGenContext.genExpr(e: IRExpression) = when (e) {
    is Store -> genStore(e)
    is ComputeReference -> genRefUse(e) // FIXME: this makes sense?
    is Call -> genCall(e)
    else -> logger.throwICE("Illegal IRExpression implementor")
  }

  /**
   * System V ABI: 3.2.3, page 20
   */
  private fun FunctionGenContext.genCall(call: Call) = instrGen {
    var intArgCounter = 0
    var fltArgCounter = 0
    for (arg in call.args) {
      emit(genComputeConstant(arg))
      if (arg is ComputeFloat) {
        // FIXME: random use of xmm8
        if (fltArgCounter < fltArgRegisters.size) {
          emit("movaps ${fltArgRegisters[fltArgCounter]}, xmm8")
          fltArgCounter++
        } else {
          emit("push xmm8")
        }
        continue
      }
      // FIXME: random use of rax
      if (intArgCounter < intArgRegisters.size) {
        emit("mov ${intArgRegisters[intArgCounter]}, rax")
        intArgCounter++
      } else {
        emit("push rax")
      }
    }
    if (call.functionPointer is ComputeReference) {
      if (call.functionPointer.tid.type.asCallable()!!.variadic) {
        // The ABI says al must contain the number of vector arguments
        emit("mov al, $fltArgCounter")
      }
      emit("call ${call.functionPointer.tid.name}")
    } else {
      // This is the case where we call some random function pointer
      // We expect the address in rax (expr result will be there)
      // FIXME: this probably doesn't work
      emit(genComputeConstant(call.functionPointer))
      // FIXME: random use of rax
      emit("call rax")
    }
  }

  private fun FunctionGenContext.genStore(store: Store) = instrGen {
    // If the right hand side of the assignment isn't a synthetic reference (version 0), then gen
    // code for it
    if (!(store.data is ComputeReference && store.data.version == 0)) {
      emit(genComputeExpr(store.data))
    }
    if (store.isSynthetic) return@instrGen
    when (store.data.kind) {
      OperationTarget.INTEGER -> {
        // FIXME: random use of rax
        emit("mov ${store.target.pos}, rax")
      }
      OperationTarget.SSE -> {
        // FIXME: random use of xmm8
        emit("movaps ${store.target.pos}, xmm8")
      }
    }
  }

  /**
   * Assume returns in rax/xmm8.
   *
   * FIXME: random use of rax/xmm8
   */
  private fun FunctionGenContext.genComputeExpr(compute: ComputeExpression) = when (compute) {
    is BinaryComputation -> genBinary(compute)
    is UnaryComputation -> TODO()
    is Call -> genCall(compute)
    is ComputeConstant -> genComputeConstant(compute)
  }

  private fun FunctionGenContext.genBinary(bin: BinaryComputation) = when (bin.kind) {
    OperationTarget.INTEGER -> genIntBinary(bin)
    OperationTarget.SSE -> genFltBinary(bin)
  }

  private fun FunctionGenContext.genIntBinary(bin: BinaryComputation) = instrGen {
    emit(genComputeConstant(bin.rhs))
    // FIXME: random use of rax/rbx
    emit("mov rbx, rax")
    emit(genComputeConstant(bin.lhs))
    emit(genIntBinaryOperation(bin.op))
  }

  /**
   * Assume operands are rax/rbx.
   *
   * FIXME: random use of rax
   * Assume returns in rax.
   */
  private fun genIntBinaryOperation(op: BinaryComputations) = instrGen {
    when (op) {
      BinaryComputations.ADD -> emit("add rax, rbx")
      BinaryComputations.SUBSTRACT -> emit("sub rax, rbx")
      BinaryComputations.MULTIPLY -> emit("mul rax, rbx")
      // FIXME: this is signed division
      // FIXME: idiv is slooooow
      // It so happens idiv takes the dividend from rax
      // idiv clobbers rdx with the remainder
      BinaryComputations.DIVIDE -> emit("idiv rbx")
      BinaryComputations.REMAINDER -> {
        emit("idiv rbx")
        emit("mov rax, rdx")
      }
      BinaryComputations.LEFT_SHIFT -> emit("shl rax, rbx")
      BinaryComputations.RIGHT_SHIFT -> emit("shr rax, rbx")
      // FIXME: this doesn't really work for anything other than jumps
      BinaryComputations.LESS_THAN, BinaryComputations.GREATER_THAN,
      BinaryComputations.LESS_EQUAL_THAN, BinaryComputations.GREATER_EQUAL_THAN,
      BinaryComputations.EQUAL, BinaryComputations.NOT_EQUAL -> emit("cmp rax, rbx")
      BinaryComputations.BITWISE_AND, BinaryComputations.LOGICAL_AND -> emit("and rax, rbx")
      BinaryComputations.BITWISE_OR, BinaryComputations.LOGICAL_OR -> emit("or rax, rbx")
      BinaryComputations.BITWISE_XOR -> emit("xor rax, rbx")
    }
  }

  private fun FunctionGenContext.genFltBinary(bin: BinaryComputation) = instrGen {
    emit(genComputeConstant(bin.rhs))
    // FIXME: random use of xmm8/xmm9
    emit("movaps xmm9, xmm8")
    emit(genComputeConstant(bin.lhs))
    emit(genFltBinaryOperation(bin.op))
  }

  /**
   * Assume operands are xmm8/xmm9.
   *
   * FIXME: random use of xmm8/xmm9
   * Assume returns in xmm8.
   */
  private fun genFltBinaryOperation(op: BinaryComputations) = instrGen {
    when (op) {
      BinaryComputations.ADD -> emit("addss xmm8, xmm9")
      BinaryComputations.SUBSTRACT -> emit("subss xmm8, xmm9")
      BinaryComputations.MULTIPLY -> emit("mulss xmm8, xmm9")
      BinaryComputations.DIVIDE -> {
        emit("divss xmm8, xmm9")
        emit("movaps xmm8, xmm9")
      }
      BinaryComputations.LESS_THAN, BinaryComputations.GREATER_THAN,
      BinaryComputations.LESS_EQUAL_THAN, BinaryComputations.GREATER_EQUAL_THAN,
      BinaryComputations.EQUAL, BinaryComputations.NOT_EQUAL -> TODO("???")
      BinaryComputations.LOGICAL_AND -> TODO("???")
      BinaryComputations.LOGICAL_OR -> TODO("???")
      BinaryComputations.BITWISE_AND, BinaryComputations.BITWISE_OR, BinaryComputations.BITWISE_XOR,
      BinaryComputations.REMAINDER, BinaryComputations.LEFT_SHIFT,
      BinaryComputations.RIGHT_SHIFT -> {
        logger.throwICE("Illegal operation between floats made it to codegen") { op }
      }
    }
  }

  private fun FunctionGenContext.genComputeConstant(ct: ComputeConstant) = when (ct) {
    is ComputeInteger -> genInt(ct.int)
    is ComputeFloat -> genFloat(ct.float)
    is ComputeChar -> TODO()
    is ComputeString -> genString(ct.str)
    is ComputeReference -> genRefUse(ct)
  }

  private fun genString(str: StringLiteralNode) = instrGen {
    // Make sure the entry in stringRefs exists
    if (str !in stringRefs) {
      stringRefs[str] = "str_${stringRefIds()}_${str.string.filter(Char::isLetterOrDigit).take(10)}"
      // FIXME: handle different encodings
      val asBytes = str.string.toByteArray().joinToString(", ")
      data += "${stringRefs[str]}: db $asBytes, 0"
    }
    // FIXME: random use of rax
    emit("mov rax, ${stringRefs[str]}")
  }

  private fun genFloat(flt: FloatingConstantNode) = instrGen {
    if (flt.suffix == FloatingSuffix.LONG_DOUBLE) TODO("x87 stuff")
    // Make sure the entry in floatRefs exists
    if (flt !in floatRefs) {
      val prettyName = flt.value.toString().filter(Char::isLetterOrDigit).take(10)
      floatRefs[flt] = "flt_${floatRefIds()}_$prettyName"
      val kind = if (flt.suffix == FloatingSuffix.FLOAT) "dd" else "dq"
      data += "${floatRefs[flt]}: $kind ${flt.value}"
    }
    // FIXME: random use of xmm8
    emit("movaps xmm8, [${floatRefs[flt]}]")
  }

  private fun genInt(int: IntegerConstantNode) = instrGen {
    // FIXME: random use of rax
    emit("mov rax, ${int.value}")
  }

  private fun FunctionGenContext.genRefUse(ref: ComputeReference) = instrGen {
    when (ref.kind) {
      OperationTarget.INTEGER -> {
        // FIXME: random use of rax
        emit("mov rax, ${ref.pos}")
      }
      OperationTarget.SSE -> {
        // FIXME: random use of xmm8
        emit("movaps xmm8, ${ref.pos}")
      }
    }
  }
}
