package slak.ckompiler.backend.x64

import org.apache.logging.log4j.LogManager
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*
import slak.ckompiler.parser.SignedCharType
import slak.ckompiler.parser.SignedIntType
import slak.ckompiler.parser.UnsignedLongType
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
        selected += selectCondJump(term)
      }
      is SelectJump -> TODO("deal with switches later")
      is UncondJump -> {
        selected += jmp.match(JumpTargetConstant(term.target))
      }
      is ConstantJump -> {
        selected += jmp.match(JumpTargetConstant(term.target))
      }
      is ImpossibleJump -> {
        selected += selectReturn(term)
      }
    }
    return selected
  }

  private fun selectCondJump(condJump: CondJump): List<MachineInstruction> {
    val selected = mutableListOf<MachineInstruction>()
    for (irInstr in condJump.cond.dropLast(1)) {
      selected += expandMacroFor(irInstr)
    }
    when (val l = condJump.cond.last()) {
      is IntCmp -> {
        selected += cmp.match(l.lhs, l.rhs)
        selected += selectIntJmp(l, JumpTargetConstant(condJump.target))
        selected += jmp.match(JumpTargetConstant(condJump.other))
      }
      is FltCmp -> TODO("floats")
      else -> TODO("no idea what happens here")
    }
    return selected
  }

  private fun selectIntJmp(i: IntCmp, jumpTrue: JumpTargetConstant): MachineInstruction {
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

  /**
   * System V ABI: 3.2.3, pages 24-25
   */
  private fun selectReturn(ret: ImpossibleJump): List<MachineInstruction> {
    val selected = mutableListOf<MachineInstruction>()
    if (ret.returned != null) {
      require(ret.returned.isNotEmpty())
      for (irInstr in ret.returned) {
        selected += expandMacroFor(irInstr)
      }
      val retVal = when (val r = ret.returned.last()) {
        is ResultInstruction -> r.result
        is SideEffectInstruction -> r.target
      }
      val retType = X64Target.registerClassOf(retVal.type)
      if (retType == Memory) {
        TODO("deal with this")
      } else {
        require(retType is X64RegisterClass)
        if (retType == X64RegisterClass.INTEGER) {
          val rax = PhysicalRegister(X64Target.registerByName("rax"), retVal.type)
          selected += mov.match(rax, retVal)
        } else {
          TODO("deal with this")
        }
      }
    }
    selected += jmp.match(JumpTargetConstant(returnBlock))
    return selected
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
    val setccTarget = VirtualRegister(cfg.registerIds(), SignedCharType)
    return listOf(
        cmp.match(i.lhs, i.rhs),
        setcc.getValue(setValue).match(setccTarget),
        movzx.match(i.result, setccTarget)
    )
  }

  override fun applyAllocation(alloc: AllocationResult): Map<BasicBlock, List<X64Instruction>> {
    val (instrs, allocated, stackSlots) = alloc
    var currentStackOffset = 0
    val stackOffsets = stackSlots.associateWith {
      val offset = currentStackOffset
      currentStackOffset += it.sizeBytes
      offset
    }
    val asm = mutableMapOf<BasicBlock, List<X64Instruction>>()
    for (block in cfg.postOrderNodes) {
      val result = mutableListOf<X64Instruction>()
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
      asm += block to result
    }
    asm += returnBlock to emptyList()
    return asm
  }

  /**
   * System V ABI: 3.2.1, figure 3.3
   */
  override fun genFunctionPrologue(alloc: AllocationResult): List<X64Instruction> {
    // FIXME: allocate stack space
    return listOf(
        push.match(rbp),
        mov.match(rbp, rsp)
    ).map {
      val ops = it.operands.map { op -> RegisterValue(op as PhysicalRegister) }
      X64Instruction(it.template as X64InstrTemplate, ops)
    }
  }

  /**
   * System V ABI: 3.2.1, figure 3.3
   */
  override fun genFunctionEpilogue(alloc: AllocationResult): List<X64Instruction> {
    // FIXME: deallocate stack space
    return listOf(
        X64Instruction(leave.first(), emptyList()),
        X64Instruction(ret.first(), emptyList())
    )
  }

  companion object {
    private val rbp = PhysicalRegister(X64Target.registerByName("rbp"), UnsignedLongType)
    private val rsp = PhysicalRegister(X64Target.registerByName("rsp"), UnsignedLongType)
  }
}
