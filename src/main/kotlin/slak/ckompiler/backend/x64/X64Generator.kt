package slak.ckompiler.backend.x64

import org.apache.logging.log4j.LogManager
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*
import slak.ckompiler.parser.SignedIntType
import slak.ckompiler.throwICE

class X64Generator(override val cfg: CFG) : TargetFunGenerator {
  private val logger = LogManager.getLogger()

  /**
   * All returns actually jump to this synthetic block, which then really returns from the function.
   */
  private val returnBlock = BasicBlock(isRoot = false)

  override fun instructionSelection(): InstructionMap {
    return cfg.nodes.associateWith(this::selectBlockInstrs)
  }

  private fun selectBlockInstrs(block: BasicBlock): List<MachineInstruction> {
    val selected = mutableListOf<MachineInstruction>()
    // FIXME: deal with φ
    for (irInstr in block.ir) {
      selected += expandMacroFor(irInstr)
    }
    when (val term = block.terminator) {
      MissingJump -> logger.throwICE("Incomplete BasicBlock")
      is CondJump -> {
        for (irInstr in term.cond.dropLast(1)) {
          selected += expandMacroFor(irInstr)
        }
        when (val l = term.cond.last()) {
          is IntCmp -> {
            selected += selectCondJmp(l, JumpTargetConstant(term.target))
            selected += jmp.match(JumpTargetConstant(term.other))
          }
          is FltCmp -> TODO("floats")
          else -> TODO("no idea what happens here")
        }
      }
      is SelectJump -> TODO("deal with switches later")
      is UncondJump -> {
        selected += jmp.match(JumpTargetConstant(term.target))
      }
      is ConstantJump -> {
        selected += jmp.match(JumpTargetConstant(term.target))
      }
      is ImpossibleJump -> {
        selected += jmp.match(JumpTargetConstant(returnBlock))
      }
    }
    return selected
  }

  private fun selectCondJmp(i: IntCmp, jumpTrue: JumpTargetConstant): MachineInstruction {
    val isSigned = i.lhs.type.unqualify() is SignedIntType
    // FIXME: deal with common cases like `!(a > b)` -> jnge/jnae
    val jmpName = when (i.cmp) {
      Comparisons.EQUAL -> "je"
      Comparisons.NOT_EQUAL -> "jne"
      Comparisons.LESS_THAN -> if (isSigned) "jl" else "jb"
      Comparisons.GREATER_THAN -> if (isSigned) "jg" else "ja"
      Comparisons.LESS_EQUAL -> if (isSigned) "jle" else "jbe"
      Comparisons.GREATER_EQUAL -> if (isSigned) "jge" else "jae"
    }
    return jcc.getValue(jmpName).match(jumpTrue)
  }

  private fun expandMacroFor(i: IRInstruction): List<MachineInstruction> = when (i) {
    is LoadInstr -> listOf(mov.match(i.result, i.target))
    is StoreInstr -> listOf(mov.match(i.target, i.value))
    is ConstantRegisterInstr -> listOf(mov.match(i.result, i.const))
    is StructuralCast -> TODO()
    is ReinterpretCast -> TODO()
    is NamedCall -> TODO()
    is IndirectCall -> TODO()
    is IntBinary -> when (i.op) {
      IntegralBinaryOps.ADD -> matchAdd(i)
      IntegralBinaryOps.SUB -> TODO()
      IntegralBinaryOps.MUL -> TODO()
      IntegralBinaryOps.DIV -> TODO()
      IntegralBinaryOps.REM -> TODO()
      IntegralBinaryOps.LSH -> TODO()
      IntegralBinaryOps.RSH -> TODO()
      IntegralBinaryOps.AND -> TODO()
      IntegralBinaryOps.OR -> TODO()
      IntegralBinaryOps.XOR -> TODO()
    }
    is IntCmp -> matchCmp(i)
    is IntInvert -> TODO()
    is IntNeg -> TODO()
    is FltBinary -> TODO()
    is FltCmp -> TODO()
    is FltNeg -> TODO()
    is PhiInstr -> TODO()
  }

  private fun matchAdd(i: IntBinary) = when (i.result) {
    // result = result OP rhs
    i.lhs -> listOf(add.match(i.lhs, i.rhs))
    // result = lhs OP result
    i.rhs -> listOf(add.match(i.rhs, i.lhs))
    else -> {
      // Can't have result = imm OP imm
      require(i.lhs !is ConstantValue || i.rhs !is ConstantValue)
      val nonImm = if (i.lhs is ConstantValue) i.rhs else i.lhs
      val maybeImm = if (i.lhs === nonImm) i.rhs else i.lhs
      listOf(
          mov.match(i.result, nonImm),
          add.match(i.result, maybeImm)
      )
    }
  }

  private fun matchCmp(i: IntCmp): List<MachineInstruction> {
    val isSigned = i.lhs.type.unqualify() is SignedIntType
    val setValue = when (i.cmp) {
      Comparisons.EQUAL -> "sete"
      Comparisons.NOT_EQUAL -> "setne"
      Comparisons.LESS_THAN -> if (isSigned) "setl" else "setb"
      Comparisons.GREATER_THAN -> if (isSigned) "setg" else "seta"
      Comparisons.LESS_EQUAL -> if (isSigned) "setle" else "setbe"
      Comparisons.GREATER_EQUAL -> if (isSigned) "setge" else "setae"
    }
    return listOf(
        cmp.match(i.lhs, i.rhs),
        setcc.getValue(setValue).match(i.result)
    )
  }

  override fun applyAllocation(alloc: AllocationResult): List<X64Instruction> {
    val (instrs, allocated, stackSlots) = alloc
    var currentStackOffset = 0
    val stackOffsets = stackSlots.associateWith {
      val offset = currentStackOffset
      currentStackOffset += it.sizeBytes
      offset
    }
    val result = mutableListOf<X64Instruction>()
    for (block in cfg.postOrderNodes) {
      for ((template, operands) in instrs.getValue(block)) {
        require(template is X64InstrTemplate)
        val ops = operands.map {
          if (it is ConstantValue) return@map ImmediateValue(it)
          val machineRegister = allocated.getValue(it)
          if (machineRegister is StackSlot) {
            return@map StackValue(machineRegister, stackOffsets.getValue(machineRegister))
          } else {
            return@map RegisterValue(machineRegister, X64Target.machineTargetData.sizeOf(it.type))
          }
        }
        result += X64Instruction(template, ops)
      }
    }
    return result
  }

  override fun genFunctionPrologue(alloc: AllocationResult): List<AsmInstruction> {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  override fun genFunctionEpilogue(alloc: AllocationResult): List<AsmInstruction> {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }
}
