package slak.ckompiler.backend.nasm_x86_64

import org.apache.logging.log4j.LogManager
import slak.ckompiler.analysis.*
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE
import java.util.*

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
  private val wasBlockGenerated: BitSet

  val nasm: String

  private val retLabel = "return_${cfg.f.name}"
  private val BasicBlock.label get() = "block_${cfg.f.name}_$nodeId"
  private val ComputeReference.pos get() = "[rbp${variableRefs[copy()]}]"

  /**
   * C standard: 5.1.2.2.3
   */
  init {
    variableRefs = mutableMapOf()
    wasBlockGenerated = BitSet(cfg.nodes.size)
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
    if (wasBlockGenerated[b.nodeId]) return@instrGen
    wasBlockGenerated[b.nodeId] = true
    label(b.label)
    emit(genExpressions(b.irContext))
    emit(genJump(b.terminator))
  }

  private fun genJump(jmp: Jump): Instructions = when (jmp) {
    is CondJump -> genCondJump(jmp)
    is UncondJump -> genUncondJump(jmp.target)
    is ImpossibleJump -> genReturn(jmp.returned)
    is ConstantJump -> genUncondJump(jmp.target)
    MissingJump -> logger.throwICE("Incomplete BasicBlock")
  }

  private fun genZeroJump(target: BasicBlock) = instrGen {
    emit("cmp rax, 0") // FIXME: random use of rax
    emit("jnz ${target.label}")
  }

  private fun genCondJump(jmp: CondJump) = instrGen {
    emit(genExpressions(jmp.cond))
    // FIXME: missed optimization, we might be able to generate better code for stuff like
    //   `a < 1 && b > 2` if we look further than the last IR expression
    val condExpr = jmp.cond.ir.last()
    if (condExpr is Call) TODO("implement function calls")
    else if (condExpr is Store && condExpr.data is BinaryComputation) when (condExpr.data.op) {
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

  private fun genUncondJump(target: BasicBlock) = instrGen {
    emit("jmp ${target.label}")
    emit(genBlock(target))
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
