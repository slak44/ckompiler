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

  private val retLabel = "return_${cfg.f.name}"
  private val BasicBlock.fnLabel get() = "block_${cfg.f.name}_$nodeId"

  /**
   * C standard: 5.1.2.2.3
   */
  init {
    prelude += "extern exit"
    prelude += "global ${cfg.f.name}"
    text += instrGen {
      label(cfg.f.name)
      emit("push rbp")
      for (node in cfg.nodes) emit(genBlock(node))
      label(retLabel)
      emit("pop rbp")
      if (isMain) {
        emit("mov rdi, rax")
        emit("call exit")
      } else {
        emit("ret")
      }
    }
  }

  private fun genBlock(b: BasicBlock) = instrGen {
    label(b.fnLabel)
//    for (e in b.data) emit(genExpr(e))
    emit(genJump(b.terminator))
  }

  private fun genJump(jmp: Jump) = when (jmp) {
    is CondJump -> genCondJump(jmp)
    is UncondJump -> genUncondJump(jmp.target)
    is ImpossibleJump -> TODO("genReturn(jmp.returned)")
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
  private fun genReturn(retExpr: Expression?) = instrGen {
    if (retExpr == null) {
      // Nothing to return
      return@instrGen
    }
    emit(genExpr(retExpr))
    when (retExpr.type) {
      ErrorType -> logger.throwICE("ErrorType cannot propagate to codegen stage") { retExpr }
      is FunctionType -> logger.throwICE("FunctionType is an illegal return type") { retExpr }
      // INTEGER classification
      is PointerType, is IntegralType -> {
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

  /**
   * Returns in rax. FIXME: temporary fix, we need a register allocator
   */
  private fun genExpr(e: Expression) = when (e) {
    is ErrorExpression -> logger.throwICE("ErrorExpression was removed")
    is TypedIdentifier -> TODO()
    is FunctionCall -> TODO()
    is UnaryExpression -> TODO()
    is SizeofTypeName -> TODO()
    is SizeofExpression -> TODO()
    is PrefixIncrement -> TODO()
    is PrefixDecrement -> TODO()
    is PostfixIncrement -> TODO()
    is PostfixDecrement -> TODO()
    is BinaryExpression -> genBinExpr(e)
    is IntegerConstantNode -> genInt(e)
    is FloatingConstantNode -> TODO()
    is CharacterConstantNode -> TODO()
    is StringLiteralNode -> TODO()
  }

  private fun genInt(int: IntegerConstantNode) = instrGen {
    emit("mov rax, ${int.value}")
  }

  private fun genAssignment(target: Expression, value: Expression) = instrGen {
    // FIXME: implement
  }

  private fun genBinExpr(e: BinaryExpression) = instrGen {
    if (e.op in assignmentOps) {
      emit(genAssignment(e.lhs, e.rhs))
    }
    // FIXME: implement
  }

  fun getNasm(): String {
    val nasm = prelude + "section .data" + data + "section .text" + text
    return nasm.joinToString("\n") + '\n'
  }

  companion object {
    private val logger = LogManager.getLogger("CodeGenerator")
  }
}
