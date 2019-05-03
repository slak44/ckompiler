package slak.ckompiler.backend

import mu.KotlinLogging
import slak.ckompiler.analysis.*
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

typealias Instructions = List<String>

private class InstructionBuilder {
  private val instr = mutableListOf<String>()

  fun emit(s: String) {
    instr += s
  }

  fun emit(s: Instructions) {
    instr += s
  }

  fun toInstructions(): Instructions = instr
}

private fun instrGen(block: InstructionBuilder.() -> Unit): Instructions {
  val builder = InstructionBuilder()
  builder.block()
  return builder.toInstructions()
}

/**
 * Generate [NASM](https://www.nasm.us/) code.
 */
class CodeGenerator(private val cfg: CFG) {
  private val prelude = mutableListOf<String>()
  private val text = mutableListOf<String>()
  private val data = mutableListOf<String>()

  init {
    prelude += "global ${cfg.f.name}"
    val instr = instrGen {
      emit("push rbp")
      emit(genBlock(cfg.startBlock))
      emit("pop rbp")
    }
    text += "${cfg.f.name}:"
    text += instr
  }

  private fun genExpr(e: Expression) = when (e) {
    is ErrorExpression -> logger.throwICE("ErrorExpression was removed") {}
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
    is IntegerConstantNode -> TODO()
    is FloatingConstantNode -> TODO()
    is CharacterConstantNode -> TODO()
    is StringLiteralNode -> TODO()
  }

  private val BasicBlock.fnLabel get() = ".block_${cfg.f.name}_$nodeId"

  private fun genBlock(b: BasicBlock) = instrGen {
    emit(b.fnLabel)
    for (e in b.data) emit(genExpr(e))
    emit(genJump(b.terminator))
  }

  private fun genJump(jmp: Jump) = when (jmp) {
    is CondJump -> genCondJump(jmp)
    is UncondJump -> TODO()
    is ImpossibleJump -> genReturn(jmp.returned)
    is ConstantJump -> TODO()
    MissingJump -> logger.throwICE("Incomplete BasicBlock") {}
  }

  /**
   * We classify return types using the same conventions as the ABI.
   *
   * System V ABI: 3.2.3
   */
  private fun genReturn(retExpr: Expression?) = instrGen {
    if (retExpr == null) {
      emit("ret")
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
  }

  private fun genCondJump(jmp: CondJump) = instrGen {
    TODO()
  }

  private fun genBinExpr(e: BinaryExpression) = instrGen {
    emit("push eax")
    // FIXME: implement
    emit("pop eax")
  }

  fun getNasm(): String {
    val nasm = prelude + "section .data" + data + "section .text" + text
    return nasm.joinToString("\n")
  }

  companion object {
    private val logger = KotlinLogging.logger("CodeGenerator")
  }
}
