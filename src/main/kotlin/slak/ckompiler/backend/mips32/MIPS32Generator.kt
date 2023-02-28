package slak.ckompiler.backend.mips32

import mu.KotlinLogging
import slak.ckompiler.IdCounter
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*
import slak.ckompiler.parser.PointerType
import slak.ckompiler.parser.SignedIntType
import slak.ckompiler.parser.SignedIntegralType
import slak.ckompiler.parser.UnsignedIntType
import slak.ckompiler.throwICE

class MIPS32Generator(
    cfg: CFG,
    override val target: MIPS32Target,
) : TargetFunGenerator<MIPS32Instruction>,
    FunctionAssembler<MIPS32Instruction> by MIPS32FunAssembler(target),
    FunctionCallGenerator by MIPS32CallGenerator() {
  override val graph: InstructionGraph = InstructionGraph.partiallyInitialize(cfg)

  override val stackSlotIds: IdCounter
    get() = TODO("not implemented")

  init {
    graph.copyStructureFrom(cfg, this::selectBlockInstrs)
  }

  private val sp = target.ptrRegisterByName("\$sp")
  private val zero = target.ptrRegisterByName("\$zero")

  override fun createIRCopy(dest: IRValue, src: IRValue): MachineInstruction {
    return target.matchTypedCopy(dest, src)
  }

  private fun transformMachineReg(reg: MachineRegister): IRValue {
    when (val vc = reg.valueClass) {
      is MIPS32RegisterClass -> {
        val type = when (vc) {
          MIPS32RegisterClass.INTEGER -> UnsignedIntType
          MIPS32RegisterClass.FLOAT -> TODO()
        }
        return PhysicalRegister(reg, type)
      }
      Memory -> {
        require(reg is SpillSlot)
        return DerefStackValue(reg.value)
      }
      else -> logger.throwICE("Invalid value class")
    }
  }

  override fun createRegisterCopy(dest: MachineRegister, src: MachineRegister): MachineInstruction {
    val destValue = transformMachineReg(dest)
    val srcValue = transformMachineReg(src)
    if (dest.valueClass is MIPS32RegisterClass && src.valueClass is MIPS32RegisterClass && dest.valueClass != src.valueClass) {
      logger.throwICE("Register value classes do not match")
    }
    return target.matchTypedCopy(destValue, srcValue)
  }

  override fun createLocalPush(src: MachineRegister): List<MachineInstruction> {
    require(src is MIPS32Register) { "Must be a mips32 register" }
    return when (src.valueClass) {
      MIPS32RegisterClass.INTEGER -> {
        val rspValue = PhysicalRegister(src, PointerType(target.machineTargetData.ptrDiffType, emptyList()))
        val topOfStack = MemoryLocation(rspValue)
        val srcPhysReg = PhysicalRegister(src, target.machineTargetData.ptrDiffType)

        listOf(
            addiu.match(sp, sp, -wordSizeConstant),
            sw.match(srcPhysReg, topOfStack),
        )
      }
      MIPS32RegisterClass.FLOAT -> TODO()
    }
  }

  override fun createLocalPop(dest: MachineRegister): List<MachineInstruction> {
    require(dest is MIPS32Register) { "Must be a mips32 register" }
    return when (dest.valueClass) {
      MIPS32RegisterClass.INTEGER -> {
        val rspValue = PhysicalRegister(dest, PointerType(target.machineTargetData.ptrDiffType, emptyList()))
        val topOfStack = MemoryLocation(rspValue)
        val destPhysReg = PhysicalRegister(dest, target.machineTargetData.ptrDiffType)

        listOf(
            lw.match(destPhysReg, topOfStack),
            addiu.match(sp, sp, wordSizeConstant),
        )
      }
      MIPS32RegisterClass.FLOAT -> TODO()
    }
  }

  override fun createJump(target: InstrBlock): MachineInstruction {
    return bc.match(JumpTargetConstant(target.id))
  }

  override fun insertPhiCopies(block: InstrBlock, copies: List<MachineInstruction>) {
    val jmpInstrs = block.indexOfLast {
      it.irLabelIndex != block.last().irLabelIndex || it.template !in bc
    }
    // +1 because we want to insert _after_ the index of the non-jump
    val indexToInsert = (jmpInstrs + 1).coerceAtLeast(0)
    // This is clearly post-allocation, so it's ok
    block.unsafelyGetInstructions().addAll(indexToInsert, copies)
  }

  private fun selectBlockInstrs(block: BasicBlock): List<MachineInstruction> {
    val selected = mutableListOf<MachineInstruction>()
    for ((index, irInstr) in block.ir.withIndex()) {
      selected += expandMacroFor(irInstr).onEach { it.irLabelIndex = index }
    }

    when (val term = block.terminator) {
      MissingJump -> logger.throwICE("Incomplete BasicBlock")
      is CondJump -> {
        selected += selectCondJump(term, block.ir.size)
      }
      is SelectJump -> TODO("deal with switches later")
      is UncondJump -> {
        selected += bc.match(JumpTargetConstant(term.target)).also {
          it.irLabelIndex = block.ir.size + 1
        }
      }
      is ConstantJump -> {
        selected += bc.match(JumpTargetConstant(term.target)).also {
          it.irLabelIndex = block.ir.size + 1
        }
      }
      is ImpossibleJump -> {
        selected += selectImpossible(term, block.ir.size)
      }
    }

    return selected
  }

  private fun selectCondJump(condJump: CondJump, idxOffset: Int): List<MachineInstruction> {
    val selected = mutableListOf<MachineInstruction>()
    for ((index, irInstr) in condJump.cond.dropLast(1).withIndex()) {
      selected += expandMacroFor(irInstr).onEach { it.irLabelIndex = idxOffset + index }
    }
    TODO()
  }

  private fun selectImpossible(ret: ImpossibleJump, idxOffset: Int): List<MachineInstruction> {
    val selected = mutableListOf<MachineInstruction>()
    val endIdx = idxOffset + (ret.returned?.size ?: 0) - 1
    if (ret.returned != null) {
      require(ret.returned.isNotEmpty())
      for ((index, irInstr) in ret.returned.withIndex()) {
        selected += expandMacroFor(irInstr).onEach { it.irLabelIndex = idxOffset + index }
      }
      val retVal = ret.returned.last().result
      selected += createReturn(retVal).onEach { it.irLabelIndex = endIdx }
    }
    selected += bc.match(JumpTargetConstant(graph.returnBlock.id)).also { it.irLabelIndex = endIdx }
    return selected
  }

  private fun expandMacroFor(i: IRInstruction): List<MachineInstruction> = when (i) {
    is MoveInstr -> listOf(target.matchTypedCopy(i.result, i.value))
    is LoadMemory -> listOf(target.matchTypedCopy(i.result, i.loadFrom))
    is StoreMemory -> listOf(target.matchTypedCopy(i.storeTo, i.value))
    is ReinterpretCast -> if (i.operand == i.result) emptyList() else listOf(target.matchTypedCopy(i.result, i.operand))
    is IntBinary -> when (i.op) {
      IntegralBinaryOps.ADD -> matchAdd(i)
      IntegralBinaryOps.SUB -> matchSub(i)
      IntegralBinaryOps.MUL -> matchMul(i)
      IntegralBinaryOps.DIV -> matchDivRem(i, isRem = false)
      IntegralBinaryOps.REM -> matchDivRem(i, isRem = true)
      IntegralBinaryOps.LSH -> TODO()
      IntegralBinaryOps.RSH -> TODO()
      IntegralBinaryOps.AND, IntegralBinaryOps.OR, IntegralBinaryOps.XOR -> matchLogical(i, i.op)
    }
    is IntCmp -> matchCmp(i)
    is IntInvert -> listOf(nor.match(i.result, i.operand, i.operand))
    is IntNeg -> listOf(subu.match(i.result, zero, i.operand))
    is StructuralCast -> TODO()
    is FltBinary -> TODO()
    is FltCmp -> TODO()
    is FltNeg -> listOf(neg.match(i.result, i.operand))
    is IndirectCall -> createCall(i.result, i.callable, i.args)
    is NamedCall -> createCall(i.result, i.name, i.args)
  }

  private fun matchAdd(i: BinaryInstruction): List<MachineInstruction> {
    val (nonImm, maybeImm) = findImmInBinary(i.lhs, i.rhs)
    return matchAdd(i.result, nonImm, maybeImm)
  }

  private fun matchAdd(result: IRValue, nonImmLhs: IRValue, maybeImmRhs: IRValue): List<MachineInstruction> {
    val isUnsigned = result.type.unqualify() !is SignedIntegralType
    val add = when (maybeImmRhs) {
      is ConstantValue -> if (isUnsigned) addiu else addi
      else -> if (isUnsigned) addu else add
    }
    return listOf(add.match(result, nonImmLhs, maybeImmRhs))
  }

  private fun matchSub(i: BinaryInstruction): List<MachineInstruction> {
    val (lhs, ops) = convertIfImm(i.lhs)
    if (i.rhs is ConstantValue) {
      val rhs = when (val iRhs = i.rhs) {
        is IntConstant -> -iRhs
        is FltConstant -> -iRhs
        else -> logger.throwICE("Subtracting non int/float constant")
      }
      return ops + matchAdd(i.result, lhs, rhs)
    }

    val isUnsigned = i.result.type.unqualify() !is SignedIntegralType
    val sub = if (isUnsigned) subu else sub
    return listOf(sub.match(i.result, lhs, i.rhs))
  }

  private fun matchMul(i: BinaryInstruction): List<MachineInstruction> {
    val (nonImm, maybeImm) = findImmInBinary(i.lhs, i.rhs)
    val (convertedImm, ops) = convertIfImm(maybeImm)
    val isUnsigned = i.result.type.unqualify() !is SignedIntegralType
    val mul = if (isUnsigned) mulu else mul
    return ops + listOf(mul.match(i.result, nonImm, convertedImm))
  }

  private fun matchDivRem(i: BinaryInstruction, isRem: Boolean): List<MachineInstruction> {
    val (nonImm, maybeImm) = findImmInBinary(i.lhs, i.rhs)
    val (convertedImm, ops) = convertIfImm(maybeImm)
    val isUnsigned = i.result.type.unqualify() !is SignedIntegralType
    val div = when (isRem) {
      true -> if (isUnsigned) modu else mod
      false -> if (isUnsigned) divu else div
    }
    return ops + listOf(div.match(i.result, nonImm, convertedImm))
  }

  private fun matchLogical(i: BinaryInstruction, intOp: IntegralBinaryOps): List<MachineInstruction> {
    val (nonImm, maybeImm) = findImmInBinary(i.lhs, i.rhs)
    val logical = when (maybeImm) {
      is ConstantValue -> when (intOp) {
        IntegralBinaryOps.AND -> andi
        IntegralBinaryOps.OR -> ori
        IntegralBinaryOps.XOR -> xori
        else -> logger.throwICE("Illegal argument")
      }
      else -> when (intOp) {
        IntegralBinaryOps.AND -> and
        IntegralBinaryOps.OR -> or
        IntegralBinaryOps.XOR -> xor
        else -> logger.throwICE("Illegal argument")
      }
    }
    return listOf(logical.match(i.result, nonImm, maybeImm))
  }

  private fun matchCmp(i: IntCmp): List<MachineInstruction> {
    val isSigned = i.result.type.unqualify() is SignedIntType

    // TODO: there are a lot more cases where we can skip on the load immediate and directly use SLTI or XORI
    if (i.rhs is ConstantValue && i.cmp == Comparisons.LESS_THAN) {
      val slti = if (isSigned) slti else sltiu
      return listOf(slti.match(i.result, i.lhs, i.rhs))
    }
    if (i.lhs is ConstantValue && i.cmp == Comparisons.GREATER_EQUAL) {
      val slti = if (isSigned) slti else sltiu
      return listOf(slti.match(i.result, i.rhs, i.lhs))
    }

    val (lhs, opsLhs) = convertIfImm(i.lhs)
    val (rhs, opsRhs) = convertIfImm(i.rhs)

    val slt = if (isSigned) slt else sltu

    val opsCompare = when (i.cmp) {
      Comparisons.LESS_THAN -> listOf(
          slt.match(i.result, lhs, rhs),
      )
      Comparisons.GREATER_THAN -> listOf(
          slt.match(i.result, lhs, rhs),
          xori.match(i.result, i.result, IntConstant(1, i.result.type)),
      )
      Comparisons.GREATER_EQUAL -> listOf(
          slt.match(i.result, rhs, lhs),
      )
      Comparisons.LESS_EQUAL -> listOf(
          slt.match(i.result, rhs, lhs),
          xori.match(i.result, i.result, IntConstant(1, i.result.type)),
      )
      Comparisons.EQUAL -> listOf(
          xor.match(i.result, lhs, rhs),
          sltu.match(i.result, i.result, IntConstant(1, i.result.type)),
      )
      Comparisons.NOT_EQUAL -> listOf(
          xor.match(i.result, lhs, rhs),
          sltu.match(i.result, zero, i.result),
      )
    }

    return opsLhs + opsRhs + opsCompare
  }

  private fun convertIfImm(irValue: IRValue): Pair<IRValue, List<MachineInstruction>> {
    return if (irValue is ConstantValue) convertImmForOp(irValue) else irValue to emptyList()
  }

  private fun convertImmForOp(imm: ConstantValue): Pair<IRValue, List<MachineInstruction>> {
    val regCopy = VirtualRegister(graph.registerIds(), imm.type)
    return regCopy to listOf(target.matchTypedCopy(regCopy, imm))
  }

  companion object {
    const val WORD = 4

    val wordSizeConstant = IntConstant(WORD, UnsignedIntType)

    private val logger = KotlinLogging.logger {}
  }
}
