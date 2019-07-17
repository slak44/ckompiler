package slak.ckompiler.backend.nasm_x86_64

import org.apache.logging.log4j.LogManager
import slak.ckompiler.analysis.*
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
                                      val cfg: CFG,
                                      val isMain: Boolean) {
  val retLabel = "return_${cfg.f.name}"
  val BasicBlock.label get() = "block_${cfg.f.name}_$nodeId"
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
   * System V ABI: 3.2.3, page 20
   */
  private val intArgRegisters = listOf("rdi", "rsi", "rdx", "rcx", "r8", "r9")

  init {
    for (external in externals) prelude += "extern $external"
    for (function in functions) generateFunctionFromCFG(function, isMain = false)
    mainCfg?.let { generateFunctionFromCFG(it, isMain = true) }

    val code = prelude + "section .data" + data + "section .text" + text
    nasm = code.joinToString("\n") + '\n'
  }

  private fun generateFunctionFromCFG(cfg: CFG, isMain: Boolean) {
    val ctx = FunctionGenContext(mutableMapOf(), BitSet(cfg.nodes.size), cfg, isMain)
    prelude += "extern exit"
    prelude += "global ${cfg.f.name}"
    text += ctx.genFun(cfg, isMain)
  }

  /**
   * C standard: 5.1.2.2.3
   * System V ABI: 3.2.1, figure 3.3
   */
  private fun FunctionGenContext.genFun(cfg: CFG, isMain: Boolean) = instrGen {
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
    var rbpOffset = -4
    for ((ref) in cfg.definitions) {
      emit("; ${ref.tid.name}")
      // FIXME: they're not all required to go on the stack
      // FIXME: initial value shouldn't always be 0
      // FIXME: this only handles integral types
      emit("push 0")
      variableRefs[ref] = rbpOffset
      rbpOffset -= 4
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
    if (isMain) {
      // FIXME: random use of rax
      emit("mov rdi, rax")
      emit("call exit")
    } else {
      emit("ret")
    }
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
    // FIXME: handle the case where the function is main, and the final block is allowed to be this
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
      FloatType, DoubleType -> TODO("SSE class")
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
   * System V ABI: 3.2.3
   */
  private fun FunctionGenContext.genCall(call: Call) = instrGen {
    // FIXME: pretends only integral arguments exist
    for ((idx, arg) in call.args.withIndex()) {
      emit(genComputeConstant(arg))
      // FIXME: random use of rax
      emit("mov ${intArgRegisters[idx]}, rax")
      if (idx >= intArgRegisters.size) {
        emit("push rax")
        break
      }
    }
    if (call.functionPointer is ComputeReference) {
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
    emit(genComputeExpr(store.data))
    if (store.isSynthetic) {
      return@instrGen
    } else {
      emit("mov ${store.target.pos}, rax")
    }
  }

  /**
   * Assume returns in rax.
   *
   * FIXME: random use of rax
   */
  private fun FunctionGenContext.genComputeExpr(compute: ComputeExpression) = when (compute) {
    is BinaryComputation -> genBinary(compute)
    is UnaryComputation -> TODO()
    is Call -> TODO()
    is ComputeConstant -> genComputeConstant(compute)
  }

  private fun FunctionGenContext.genBinary(bin: BinaryComputation) = instrGen {
    // FIXME: random use of rax/rbx
    emit(genComputeConstant(bin.rhs))
    emit("mov rbx, rax")
    emit(genComputeConstant(bin.lhs))
    emit(genBinaryOperation(bin.op))
  }

  /**
   * Assume operands are rax/rbx.
   *
   * FIXME: random use of rax
   * Assume returns in rax.
   */
  private fun genBinaryOperation(op: BinaryComputations) = instrGen {
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

  private fun FunctionGenContext.genComputeConstant(ct: ComputeConstant) = when (ct) {
    is ComputeInteger -> genInt(ct.int)
    is ComputeFloat -> TODO()
    is ComputeChar -> TODO()
    is ComputeString -> genString(ct.str)
    is ComputeReference -> genRefUse(ct)
  }

  private fun genString(str: StringLiteralNode) = instrGen {
    // Make sure the entry in stringRefs exists
    if (str !in stringRefs) {
      stringRefs[str] = "str_${stringRefIds()}_${str.string.filter(Char::isLetterOrDigit).take(5)}"
      // FIXME: handle different encodings
      // FIXME: is this escaping correct?
      val escapedStr = str.string.replace("'", "\\'")
      data += "${stringRefs[str]}: db '$escapedStr', 0"
    }
    // FIXME: random use of rax
    emit("mov rax, ${stringRefs[str]}")
  }

  private fun genInt(int: IntegerConstantNode) = instrGen {
    // FIXME: random use of rax
    emit("mov rax, ${int.value}")
  }

  private fun FunctionGenContext.genRefUse(ref: ComputeReference) = instrGen {
    // FIXME: random use of rax
    emit("mov rax, ${ref.pos}")
  }
}
