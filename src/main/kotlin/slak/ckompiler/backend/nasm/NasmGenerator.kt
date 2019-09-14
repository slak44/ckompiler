package slak.ckompiler.backend.nasm

import org.apache.logging.log4j.LogManager
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.lexer.FloatingSuffix
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE
import java.util.*
import kotlin.math.absoluteValue
import kotlin.math.sign

private val logger = LogManager.getLogger()

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

private inline fun instrGen(block: InstructionBuilder.() -> Unit): Instructions {
  val builder = InstructionBuilder()
  builder.block()
  return builder.toInstructions()
}

private fun Int.toHex() = "${if (sign == -1) "-" else ""}0x${absoluteValue.toString(16)}"

/**
 * Data used while generating a function from a [cfg].
 */
private data class FunctionGenContext(val variableRefs: MutableMap<TypedIdentifier, Int>,
                                      val wasBlockGenerated: BitSet,
                                      val cfg: CFG) {
  var stackAlignmentCounter = 0

  val retLabel = ".return_${cfg.f.name}"
  val BasicBlock.label get() = ".block_${cfg.f.name}_${hashCode()}"
  val ComputeReference.pos get() = "[rbp${variableRefs[tid]?.toHex()}]"

  // FIXME: register allocator.
  var synthsRemain = cfg.synthCount
  val tidToSynthRefs = mutableMapOf<String, Int>()
  val synthRefs = mutableMapOf<Int, String>()
}

/** Generate x86_64 NASM code. */
class NasmGenerator(
    externals: List<String>,
    functions: List<CFG>,
    mainCfg: CFG?,
    val target: MachineTargetData
) {
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
   * C standard: 5.1.2.2.3
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
   * System V ABI: 3.2.1, figure 3.3
   */
  private fun FunctionGenContext.genFun(cfg: CFG) = instrGen {
    label(cfg.f.name)
    // FIXME: using rbp as frame pointer wastes a general-purpose register
    // New stack frame
    emit("push rbp")
    emit("mov rbp, rsp")
    // Callee-saved registers
    // FIXME: if the registers are not used in the function, saving them wastes instructions
    emit("push rbx")
    emit("push r8")
    emit("push r9")
    emit("sub rsp, 8")
    // FIXME: r12-r15 are also callee-saved
    // Local variables
    // FIXME: number 8 is magic here
    // FIXME: use correct sizes for values, not just 8
    // FIXME: the *4 are the 4 pushes above
    var rbpOffset = -8 - 8 * 4
    for ((ref) in cfg.definitions) {
      emit("; ${ref.tid.name} at ${rbpOffset.toHex()}")
      // FIXME: they're not all required to go on the stack
      variableRefs[ref.tid] = rbpOffset
      rbpOffset -= 8
    }
    for (idx in 0 until cfg.synthCount) {
      emit("; $idx at ${rbpOffset.toHex()}")
      synthRefs[idx] = "[rbp${rbpOffset.toHex()}]"
      rbpOffset -= 8
    }
    val vars = cfg.definitions.size + cfg.synthCount
    val stackStuffCount = vars + vars % 2
    // FIXME: magic 8 again
    emit("sub rsp, ${stackStuffCount * 8}")
    stackAlignmentCounter += stackStuffCount
    // Regular function arguments
    var intArgCounter = 0
    var fltArgCounter = 0
    for (arg in cfg.f.parameters) {
      val refArg = ComputeReference(arg, isSynthetic = true)
      if (arg.type.isABIIntegerType()) {
        if (intArgCounter >= intArgRegisters.size) {
          val idxOnStack = (intArgCounter - intArgRegisters.size) +
              (fltArgCounter - fltArgRegisters.size).coerceAtLeast(0)
          // r11 is neither callee-saved nor caller-saved in System V
          // See ABI for magic here:
          emit("mov r11, [rbp+${idxOnStack * 8 + 16}]")
          emit("mov ${refArg.pos}, r11")
        } else {
          emit("mov ${refArg.pos}, ${intArgRegisters[intArgCounter]}")
        }
        intArgCounter++
      } else if (arg.type.isSSEType()) {
        emit("${irMov(refArg)} ${refArg.pos}, ${fltArgRegisters[fltArgCounter]}")
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
      if (!wasBlockGenerated[block.postOrderId]) {
        emit(genBlock(block))
      }
    }
    // Epilogue
    label(retLabel)
    // FIXME: magic 8 again
    if (stackAlignmentCounter != 0) emit("add rsp, ${(stackAlignmentCounter) * 8}")
    emit("add rsp, 8")
    emit("pop r9")
    emit("pop r8")
    emit("pop rbx")
    // Use equivalent leave here
    //  emit("mov rsp, rbp")
    //  emit("pop rbp")
    emit("leave")
    emit("ret")
  }

  private fun FunctionGenContext.genBlock(b: BasicBlock) = instrGen {
    if (wasBlockGenerated[b.postOrderId]) return@instrGen
    wasBlockGenerated[b.postOrderId] = true
    label(b.label)
    emit(genExpressions(b.irContext))
    emit(genJump(b.terminator))
  }

  private fun FunctionGenContext.genJump(jmp: Jump): Instructions = when (jmp) {
    is CondJump -> genCondJump(jmp)
    is UncondJump -> genUncondJump(jmp.target)
    is ImpossibleJump -> genReturn(jmp.returned)
    is ConstantJump -> genUncondJump(jmp.target)
    is SelectJump -> TODO()
    MissingJump -> logger.throwICE("Incomplete BasicBlock")
  }

  private fun FunctionGenContext.genZeroJump(block: BasicBlock, ce: ComputeExpression) = instrGen {
    when (ce.kind) {
      // FIXME: random use of rax, rbx, xmm8, xmm9
      OperationTarget.INTEGER -> emit("cmp rax, 0")
      OperationTarget.SSE -> {
        // FIXME: ultra ugly hack
        when (target.sizeOf(ce.resType)) {
          // FIXME: duplicated code
          // FIXME: yet another retarded when-expression on the size of floats
          4 -> {
            emit("${irSSEMov(ce.resType)} xmm9, xmm8")
            emit(genFloat(FloatingConstantNode(0.0, FloatingSuffix.FLOAT)))
            emit("comiss xmm9, xmm8")
          }
          else -> {
            emit("${irSSEMov(ce.resType)} xmm9, xmm8")
            emit(genFloat(FloatingConstantNode(0.0, FloatingSuffix.NONE)))
            emit("comisd xmm9, xmm8")
          }
        }
      }
    }
    emit("jnz ${block.label}")
  }

  private fun FunctionGenContext.genCondJump(jmp: CondJump) = instrGen {
    // FIXME: missed optimization, we might be able to generate better code for stuff like
    //   `a < 1 && b > 2` if we look further than the last IR expression
    val condExpr = jmp.cond.ir.last()
    emit(genExpressions(jmp.cond))
    val conds = listOf(BinaryComputations.LESS_THAN, BinaryComputations.GREATER_THAN,
        BinaryComputations.LESS_EQUAL_THAN, BinaryComputations.GREATER_EQUAL_THAN,
        BinaryComputations.EQUAL, BinaryComputations.NOT_EQUAL)
    if (condExpr is Store && condExpr.data is BinaryComputation && condExpr.data.op in conds) {
      when (condExpr.data.kind) {
        // FIXME: random use of rax, rbx, xmm8, xmm9
        OperationTarget.INTEGER -> emit("cmp rax, rbx")
        OperationTarget.SSE -> when (target.sizeOf(condExpr.data.resType)) {
          // FIXME: duplicated code
          // FIXME: yet another retarded when-expression on the size of floats
          4 -> emit("comiss xmm8, xmm9")
          8 -> emit("comisd xmm8, xmm9")
          else -> emit("comisd xmm8, xmm9")
        }
      }
    }
    val isSigned = when ((condExpr as? Store)?.data?.kind) {
      OperationTarget.INTEGER -> condExpr.target.resType is SignedIntegralType
      OperationTarget.SSE -> false
      null -> true
    }
    if (condExpr is Store && condExpr.data is BinaryComputation) when (condExpr.data.op) {
      BinaryComputations.LESS_THAN -> emit("${if (isSigned) "jl" else "jb"} ${jmp.target.label}")
      BinaryComputations.GREATER_THAN -> emit("${if (isSigned) "jg" else "ja"} ${jmp.target.label}")
      BinaryComputations.LESS_EQUAL_THAN ->
        emit("${if (isSigned) "jle" else "jbe"} ${jmp.target.label}")
      BinaryComputations.GREATER_EQUAL_THAN ->
        emit("${if (isSigned) "jge" else "jae"} ${jmp.target.label}")
      BinaryComputations.EQUAL -> emit("je ${jmp.target.label}")
      BinaryComputations.NOT_EQUAL -> emit("jne ${jmp.target.label}")
      else -> emit(genZeroJump(jmp.target, condExpr.data))
    } else if (condExpr is ComputeExpression) {
      emit(genZeroJump(jmp.target, condExpr))
    } else if (condExpr is Store) {
      emit(genZeroJump(jmp.target, condExpr.data))
    } else {
      logger.throwICE("Illegal IRExpression implementor")
    }
    // Try to generate the "else" block right after the cmp, so that if the cond is false, we just
    // keep executing without having to do another jump
    if (!wasBlockGenerated[jmp.other.postOrderId]) {
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
      emit("jmp $retLabel")
      return@instrGen
    }
    emit(genExpressions(retExpr))
    when (retExpr.src.last().type.unqualify()) {
      ErrorType -> logger.throwICE("ErrorType cannot propagate to codegen stage") { retExpr }
      is QualifiedType -> logger.throwICE("QualifiedType is removed just above")
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
      // FIXME: random use of xmm8
      // FIXME: there are two SSE return registers, xmm0 and xmm1
      FloatType, DoubleType -> {
        emit("${irSSEMov(retExpr.src.last().type)} xmm0, xmm8")
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
    for (e in ctx.ir) {
      emit("; ${e.toString().replace("\n", "\\n")}")
      emit(genExpr(e))
      // Return values bla bla
      if (e is Call) {
        // FIXME: random use of rax/rdi/xmm8
        emit(popXmm("xmm8"))
        emit("pop rdi")
        emit("pop rax")
        stackAlignmentCounter -= 4
      }
    }
  }

  private fun FunctionGenContext.genExpr(e: IRExpression) = when (e) {
    is Store -> genStore(e)
    is ComputeReference -> genRefUse(e) // FIXME: this makes sense?
    is Call -> genCall(e)
    else -> logger.throwICE("Illegal IRExpression implementor")
  }

  /**
   * System V ABI: 3.2.2, page 18; 3.2.3, page 20
   */
  private fun FunctionGenContext.genCall(call: Call) = instrGen {
    // Caller-saved registers
    // FIXME: random use of rax/rdi/xmm8
    emit("push rax")
    emit("push rdi")
    emit(pushXmm("xmm8"))
    stackAlignmentCounter += 4

    val intArgs = call.args.withIndex().filter { (_, it) -> it.kind == OperationTarget.INTEGER }
    val fltArgs = call.args.withIndex().filter { (_, it) -> it.kind == OperationTarget.SSE }
    val intRegArgs = intArgs.take(intArgRegisters.size)
    val fltRegArgs = fltArgs.take(fltArgRegisters.size)
    val intStackArgs = intArgs.drop(intArgRegisters.size)
    val fltStackArgs = fltArgs.drop(fltArgRegisters.size)
    // FIXME: ignores arg size
    val stackArgs = (intStackArgs + fltStackArgs).sortedBy { it.index }

    for (idx in intRegArgs.indices) {
      emit(genComputeConstant(intRegArgs[idx].value))
      // FIXME: random use of rax
      emit("mov ${intArgRegisters[idx]}, rax")
    }

    for (idx in fltRegArgs.indices) {
      val regArg = fltRegArgs[idx].value
      emit(genComputeConstant(regArg))
      // FIXME: yet another ugly hack:
      val mov = if (regArg is ComputeReference) irMov(regArg) else "movsd"
      // FIXME: random use of xmm8
      emit("$mov ${fltArgRegisters[idx]}, xmm8")
    }

    // FIXME: % 2 only by assuming arg size
    val argsAligned = stackArgs.size % 2 == 0
    // We have to do this before pushing the stack args (obviously, in retrospect)
    if (!argsAligned) emit("sub rsp, 8")

    for (idx in 0 until stackArgs.size) {
      emit(genComputeConstant(stackArgs.asReversed()[idx].value))
      // FIXME: only ints
      emit("push rax")
    }

    if (call.functionPointer is ComputeReference) {
      if (call.functionPointer.tid.type.asCallable()!!.variadic) {
        // The ABI says al must contain the number of vector arguments
        // FIXME: does that include floats passed on the stack?
        emit("mov al, ${fltArgs.size}")
      }
      emit("call ${call.functionPointer.tid.name}")
      val retType = call.functionPointer.tid.type.asCallable()!!.returnType
      if (retType is FloatingType) {
        emit("${irSSEMov(retType)} xmm8, xmm0")
      }
    } else {
      // FIXME: this _definitely_ doesn't work (call through expr)
      // This is the case where we call some random function pointer
      // We expect the address in rax (expr result will be there)
      emit(genComputeConstant(call.functionPointer))
      // FIXME: random use of rax
      emit("call rax")
    }
    if (!argsAligned) emit("add rsp, 8")
  }

  private fun FunctionGenContext.genStore(store: Store) = instrGen {
    // If the right hand side of the assignment isn't a synthetic reference (version 0), then gen
    // code for it
    if (store.data !is ComputeReference || !store.data.isSynthetic) {
      emit(genComputeExpr(store.data))
    }
    // FIXME: this is broken when the store isn't synthetic, but the store target *is*
    // FIXME: this is also broken when the store is synthetic but it shouldn't be
    if (store.isSynthetic) {
      if (!tidToSynthRefs.containsKey(store.target.tid.name)) {
        tidToSynthRefs[store.target.tid.name] = cfg.synthCount - synthsRemain
        synthsRemain--
      }
      // FIXME: horrifying
      val sr = synthRefs[tidToSynthRefs[store.target.tid.name]]
      emit("${irMov(store.target)} $sr, ${irTarget(store.target)}")
      return@instrGen
    }
    when (store.data.kind) {
      OperationTarget.INTEGER -> {
        // FIXME: random use of rax
        emit("mov ${store.target.pos}, rax")
      }
      OperationTarget.SSE -> {
        // FIXME: random use of xmm8
        emit("${irMov(store.target)} ${store.target.pos}, xmm8")
      }
    }
    // Do this shit here so they don't overwrite return values
    if (store.data is Call) {
      // FIXME: random use of rax/rdi/xmm8
      emit(popXmm("xmm8"))
      emit("pop rdi")
      emit("pop rax")
      stackAlignmentCounter -= 4
    }
  }

  // FIXME: random use of rax/xmm8
  private fun irTarget(e: ComputeReference): String = when (e.kind) {
    OperationTarget.INTEGER -> regFor(e.tid.type)
    OperationTarget.SSE -> "xmm8"
  }

  private fun irMov(e: ComputeExpression): String = when (e.kind) {
    OperationTarget.INTEGER -> "mov"
    OperationTarget.SSE -> irSSEMov(e.resType)
  }

  // FIXME: 4/8 bytes are not the only floating point types in the world
  private fun irSSEMov(t: TypeName): String = when (target.sizeOf(t)) {
    4 -> "movss"
    8 -> "movsd"
    else -> "movsd"
  }

  /**
   * Assume returns in rax/xmm8.
   *
   * FIXME: random use of rax/xmm8
   */
  private fun FunctionGenContext.genComputeExpr(compute: ComputeExpression) = when (compute) {
    is BinaryComputation -> genBinary(compute)
    is UnaryComputation -> genUnary(compute)
    is CastComputation -> genCast(compute)
    is Call -> genCall(compute)
    is ComputeConstant -> genComputeConstant(compute)
  }

  private fun FunctionGenContext.genCast(cast: CastComputation) = instrGen {
    emit(genComputeConstant(cast.operand))
    // FIXME: technically, there still is a cast here
    if (cast.operand.resType == cast.resType) return@instrGen
    // FIXME: random use of rax & xmm8
    val c = cast.operand.resType to cast.resType
    when {
      c.first is IntegralType && c.second is FloatType -> emit("cvtsi2ss xmm8, rax")
      c.first is IntegralType && c.second is DoubleType -> emit("cvtsi2sd xmm8, rax")
      c.first is FloatType && c.second is IntegralType -> emit("cvttss2si xmm8, rax")
      c.first is DoubleType && c.second is IntegralType -> emit("cvttsd2si xmm8, rax")
      c.first is FloatType && c.second is DoubleType -> emit("cvtss2sd xmm8, xmm8")
      c.first is DoubleType && c.second is FloatType -> emit("cvtsd2ss xmm8, xmm8")
      // FIXME: yet another ugly hack
      c.first is IntegralType && c.second is IntegralType -> return@instrGen
      else -> logger.throwICE("Logically impossible")
    }
  }

  private fun FunctionGenContext.genUnary(unary: UnaryComputation) = when (unary.kind) {
    OperationTarget.INTEGER -> getIntUnary(unary)
    OperationTarget.SSE -> getFltUnary(unary)
  }

  private fun FunctionGenContext.getIntUnary(unary: UnaryComputation) = instrGen {
    if (unary.op !in listOf(UnaryComputations.REF, UnaryComputations.DEREF)) {
      emit(genComputeConstant(unary.operand))
    }
    // FIXME: random use of rax
    when (unary.op) {
      UnaryComputations.REF -> {
        if (unary.operand !is ComputeReference) {
          logger.throwICE("Cannot take address of non-var") { unary }
        }
        emit("lea rax, ${unary.operand.pos}")
      }
      UnaryComputations.DEREF -> {
        if (unary.operand !is ComputeReference) {
          logger.throwICE("Cannot dereference non-pointer") { unary }
        }
        // FIXME: random use of rcx
        emit("mov rcx, ${unary.operand.pos}")
        emit("mov rax, [rcx]")
      }
      UnaryComputations.MINUS -> emit("neg rax")
      UnaryComputations.BIT_NOT -> emit("not rax")
      UnaryComputations.NOT -> {
        emit("test rax, rax")
        emit("sete al")
      }
    }
  }

  private fun FunctionGenContext.getFltUnary(unary: UnaryComputation) = instrGen {
    if (unary.op !in listOf(UnaryComputations.REF, UnaryComputations.DEREF)) {
      emit(genComputeConstant(unary.operand))
    }
    // FIXME: random use of rax
    when (unary.op) {
      UnaryComputations.REF -> {
        if (unary.operand !is ComputeReference) {
          logger.throwICE("Cannot take address of non-var") { unary }
        }
        emit("lea rax, ${unary.operand.pos}")
      }
      UnaryComputations.DEREF -> {
        if (unary.operand !is ComputeReference) {
          logger.throwICE("Cannot dereference non-pointer") { unary }
        }
        // FIXME: random use of xmm8/rcx
        emit("mov rcx, ${unary.operand.pos}")
        emit("${irMov(unary.operand)} xmm8, [rcx]")
      }
      UnaryComputations.MINUS -> {
        // FIXME: random use of xmm8/xmm9
        genFloat(FloatingConstantNode(-0.0, FloatingSuffix.NONE))
        emit("${irMov(unary.operand)} xmm9, xmm8")
        val xor = if (unary.operand.resType == FloatType) "xorss" else "xorpd"
        emit("$xor xmm8, xmm9")
      }
      UnaryComputations.BIT_NOT -> TODO()
      UnaryComputations.NOT -> TODO()
    }
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
   * FIXME: random use of rax/rbx
   * Assume operands are rax/rbx.
   * Assume returns in rax.
   */
  private fun genIntBinaryOperation(op: BinaryComputations) = instrGen {
    when (op) {
      BinaryComputations.ADD -> emit("add rax, rbx")
      BinaryComputations.SUBTRACT -> emit("sub rax, rbx")
      // FIXME: this is only signed mul
      BinaryComputations.MULTIPLY -> emit("imul rax, rbx")
      // FIXME: this is only signed division
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
      BinaryComputations.BITWISE_AND -> emit("and rax, rbx")
      BinaryComputations.BITWISE_OR -> emit("or rax, rbx")
      BinaryComputations.BITWISE_XOR -> emit("xor rax, rbx")
      BinaryComputations.SUBSCRIPT -> {
        // FIXME: random use of rcx
        emit("lea rcx, [rax + rbx]")
        emit("mov rax, [rcx]")
      }
    }
  }

  private fun pushXmm(reg: String) = instrGen {
    emit("sub rsp, 16")
    emit("movq [rsp], $reg")
  }

  private fun popXmm(reg: String) = instrGen {
    emit("movq $reg, [rsp]")
    emit("add rsp, 16")
  }

  private fun FunctionGenContext.genFltBinary(bin: BinaryComputation) = instrGen {
    emit(genComputeConstant(bin.rhs))
    // FIXME: random use of xmm8/xmm9
    emit("${irMov(bin)} xmm9, xmm8")
    emit(genComputeConstant(bin.lhs))
    emit(genFloatingBinaryOperations(bin))
  }

  /**
   * Assume operands are xmm8/xmm9.
   *
   * FIXME: random use of xmm8/xmm9
   * Assume returns in xmm8.
   */
  private fun genFloatingBinaryOperations(
      bin: BinaryComputation
  ) = when (target.sizeOf(bin.resType)) {
    // FIXME: ugly hack
    4 -> genFltBinaryOperation(bin.op)
    8 -> genDblBinaryOperation(bin.op)
    else -> genDblBinaryOperation(bin.op)
  }

  private fun genFltBinaryOperation(op: BinaryComputations) = instrGen {
    when (op) {
      BinaryComputations.ADD -> emit("addss xmm8, xmm9")
      BinaryComputations.SUBTRACT -> emit("subss xmm8, xmm9")
      BinaryComputations.MULTIPLY -> emit("mulss xmm8, xmm9")
      BinaryComputations.DIVIDE -> emit("divss xmm8, xmm9")
      BinaryComputations.LESS_THAN, BinaryComputations.GREATER_THAN,
      BinaryComputations.LESS_EQUAL_THAN, BinaryComputations.GREATER_EQUAL_THAN,
      BinaryComputations.EQUAL, BinaryComputations.NOT_EQUAL -> emit("comiss xmm8, xmm9")
      BinaryComputations.BITWISE_AND, BinaryComputations.BITWISE_OR, BinaryComputations.BITWISE_XOR,
      BinaryComputations.REMAINDER, BinaryComputations.LEFT_SHIFT,
      BinaryComputations.RIGHT_SHIFT -> {
        logger.throwICE("Illegal operation between floats made it to codegen") { op }
      }
      BinaryComputations.SUBSCRIPT -> TODO()
    }
  }

  private fun genDblBinaryOperation(op: BinaryComputations) = instrGen {
    when (op) {
      BinaryComputations.ADD -> emit("addsd xmm8, xmm9")
      BinaryComputations.SUBTRACT -> emit("subsd xmm8, xmm9")
      BinaryComputations.MULTIPLY -> emit("mulsd xmm8, xmm9")
      BinaryComputations.DIVIDE -> emit("divsd xmm8, xmm9")
      BinaryComputations.LESS_THAN, BinaryComputations.GREATER_THAN,
      BinaryComputations.LESS_EQUAL_THAN, BinaryComputations.GREATER_EQUAL_THAN,
      BinaryComputations.EQUAL, BinaryComputations.NOT_EQUAL -> emit("comisd xmm8, xmm9")
      BinaryComputations.BITWISE_AND, BinaryComputations.BITWISE_OR, BinaryComputations.BITWISE_XOR,
      BinaryComputations.REMAINDER, BinaryComputations.LEFT_SHIFT,
      BinaryComputations.RIGHT_SHIFT -> {
        logger.throwICE("Illegal operation between floats made it to codegen") { op }
      }
      BinaryComputations.SUBSCRIPT -> TODO()
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
      stringRefs[str] = "str_${stringRefIds()}_${str.string.filter(Char::isLetterOrDigit).take(5)}"
      // FIXME: handle different encodings
      val asBytes = str.string.toByteArray().joinToString(", ")
      data += "; ${str.string}"
      data += "${stringRefs[str]}: db $asBytes, 0"
    }
    // FIXME: random use of rax
    emit("mov rax, ${stringRefs[str]}")
  }

  private fun genFloat(flt: FloatingConstantNode) = instrGen {
    if (flt.suffix == FloatingSuffix.LONG_DOUBLE) TODO("x87 stuff")
    // Make sure the entry in floatRefs exists
    if (flt !in floatRefs) {
      val prettyName = flt.value.toString().filter(Char::isLetterOrDigit).take(5)
      floatRefs[flt] = "flt_${floatRefIds()}_$prettyName"
      val kind = if (flt.suffix == FloatingSuffix.FLOAT) "dd" else "dq"
      data += "; ${flt.value}"
      val fltNasm = when {
        flt.value.isNaN() -> "__QNaN__"
        flt.value == Double.POSITIVE_INFINITY -> "__Infinity__"
        flt.value == Double.NEGATIVE_INFINITY -> "-__Infinity__"
        else -> flt.value.toString()
      }
      data += "${floatRefs[flt]}: $kind $fltNasm"
    }
    val mov = if (flt.suffix == FloatingSuffix.FLOAT) "movss" else "movsd"
    // FIXME: random use of xmm8
    emit("$mov xmm8, [${floatRefs[flt]}]")
  }

  // FIXME: another big hack
  private fun regFor(typeName: TypeName) = when (target.sizeOf(typeName)) {
    // FIXME: random use of rax
    8 -> "rax"
    // FIXME: random use of eax
    4 -> "eax"
    else -> "rax"
  }

  private fun genInt(int: IntegerConstantNode) = instrGen {
    emit("mov ${regFor(int.type)}, ${int.value}")
  }

  private fun FunctionGenContext.genRefUse(ref: ComputeReference) = instrGen {
    // Same considerations as the ones in [genStore]
    if (ref.isSynthetic) {
      if (!tidToSynthRefs.containsKey(ref.tid.name)) {
        tidToSynthRefs[ref.tid.name] = cfg.synthCount - synthsRemain
        synthsRemain--
      }
      // FIXME: horrifying
      emit("${irMov(ref)} ${irTarget(ref)}, ${synthRefs[tidToSynthRefs[ref.tid.name]]}")
      return@instrGen
    }
    when (ref.kind) {
      OperationTarget.INTEGER -> {
        // FIXME: random use of rax
        emit("mov ${regFor(ref.tid.type)}, ${ref.pos}")
      }
      OperationTarget.SSE -> {
        // FIXME: random use of xmm8
        emit("${irMov(ref)} xmm8, ${ref.pos}")
      }
    }
  }
}
