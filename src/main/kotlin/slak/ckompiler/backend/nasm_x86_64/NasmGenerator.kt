package slak.ckompiler.backend.nasm_x86_64

import org.apache.logging.log4j.LogManager
import slak.ckompiler.analysis.*
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

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
 * Generate [NASM](https://www.nasm.us/) code, on x86_64.
 */
class NasmGenerator(private val cfg: CFG, isMain: Boolean) {
  private val prelude = mutableListOf<String>()
  private val text = mutableListOf<String>()
  private val data = mutableListOf<String>()

  private val variableRefs: Map<ComputeReference, Int>

  val nasm: String

  private val retLabel = "return_${cfg.f.name}"
  private val BasicBlock.fnLabel get() = "block_${cfg.f.name}_$nodeId"
  private val ComputeReference.pos get() = "[rbp${variableRefs[copy()]}]"

  /**
   * C standard: 5.1.2.2.3
   */
  init {
    variableRefs = mutableMapOf()
    prelude += "extern exit"
    prelude += "global ${cfg.f.name}"
    text += instrGen {
      label(cfg.f.name)
      // Callee-saved registers
      emit("push rbp")
      emit("push rbx")
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
      for (node in cfg.nodes) emit(genBlock(node))
      // Epilogue
      label(retLabel)
      emit("mov rsp, rbp")
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

    val code = prelude + "section .data" + data + "section .text" + text
    nasm = code.joinToString("\n") + '\n'
  }

  private fun genBlock(b: BasicBlock) = instrGen {
    label(b.fnLabel)
    emit(genExpressions(b.irContext))
    emit(genJump(b.terminator))
  }

  private fun genJump(jmp: Jump) = when (jmp) {
    is CondJump -> genCondJump(jmp)
    is UncondJump -> genUncondJump(jmp.target)
    is ImpossibleJump -> genReturn(jmp.returned)
    is ConstantJump -> genUncondJump(jmp.target)
    MissingJump -> logger.throwICE("Incomplete BasicBlock")
  }

  private fun genCondJump(jmp: CondJump) = instrGen {
    TODO()
  }

  private fun genUncondJump(target: BasicBlock) = instrGen {
    emit("jmp ${target.fnLabel}")
  }

  /**
   * We classify return types using the same conventions as the ABI.
   *
   * System V ABI: 3.2.3
   */
  private fun genReturn(retExpr: IRLoweringContext?) = instrGen {
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

  private fun genExpressions(ctx: IRLoweringContext) = instrGen {
    for (e in ctx.ir) emit(genExpr(e))
  }

  private fun genExpr(e: IRExpression) = when (e) {
    is Store -> genStore(e)
    is ComputeReference -> genRefUse(e) // FIXME: this makes sense?
    is Call -> TODO()
    else -> logger.throwICE("Illegal IRExpression implementor")
  }

  private fun genStore(store: Store) = instrGen {
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
  private fun genComputeExpr(compute: ComputeExpression) = when (compute) {
    is BinaryComputation -> genBinary(compute)
    is UnaryComputation -> TODO()
    is Call -> TODO()
    is ComputeConstant -> genComputeConstant(compute)
  }

  private fun genBinary(bin: BinaryComputation) = instrGen {
    emit(genComputeConstant(bin.lhs))
    // FIXME: random use of rax
    emit("mov rbx, rax")
    emit(genComputeConstant(bin.rhs))
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

  private fun genComputeConstant(ct: ComputeConstant) = when (ct) {
    is ComputeInteger -> genInt(ct.int)
    is ComputeFloat -> TODO()
    is ComputeChar -> TODO()
    is ComputeString -> TODO()
    is ComputeReference -> genRefUse(ct)
  }

  private fun genInt(int: IntegerConstantNode) = instrGen {
    // FIXME: random use of rax
    emit("mov rax, ${int.value}")
  }

  private fun genRefUse(ref: ComputeReference) = instrGen {
    // FIXME: random use of rax
    emit("mov rax, ${ref.pos}")
  }

  companion object {
    private val logger = LogManager.getLogger("CodeGenerator")
  }
}
