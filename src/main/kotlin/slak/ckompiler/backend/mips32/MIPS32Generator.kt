package slak.ckompiler.backend.mips32

import io.github.oshai.kotlinlogging.KotlinLogging
import slak.ckompiler.IdCounter
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

class MIPS32Generator private constructor(
    cfg: CFG,
    override val target: MIPS32Target,
    funAsm: MIPS32FunAssembler
) : TargetFunGenerator<MIPS32Instruction>,
    FunctionAssembler<MIPS32Instruction> by funAsm,
    FunctionCallGenerator by MIPS32CallGenerator(target, cfg.registerIds) {
  constructor(cfg: CFG, target: MIPS32Target) : this(cfg, target, MIPS32FunAssembler(cfg, target, IdCounter()))

  override val graph: InstructionGraph = InstructionGraph.partiallyInitialize(cfg)

  override val stackSlotIds = funAsm.stackSlotIds

  private val sp = target.ptrRegisterByName("\$sp")
  private val zero = target.ptrRegisterByName("\$zero")

  init {
    graph.copyStructureFrom(cfg, this::selectBlockInstrs)
  }

  override fun createIRCopy(dest: IRValue, src: IRValue): MachineInstruction {
    return matchTypedCopy(dest, src)
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
    return matchTypedCopy(destValue, srcValue)
  }

  override fun createLocalPush(src: MachineRegister): List<MachineInstruction> {
    require(src is MIPS32Register) { "Must be a mips32 register" }
    return when (src.valueClass) {
      MIPS32RegisterClass.INTEGER -> {
        val rspValue = PhysicalRegister(src, PointerType(target.machineTargetData.ptrDiffType, emptyList()))
        val topOfStack = MemoryLocation(rspValue)
        val srcPhysReg = PhysicalRegister(src, target.machineTargetData.ptrDiffType)

        listOf(
            addi.match(sp, sp, -wordSizeConstant),
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
    return b.match(JumpTargetConstant(target.id))
  }

  override fun insertPhiCopies(block: InstrBlock, copies: List<MachineInstruction>) {
    val jmpInstrs = block.indexOfLast {
      it.irLabelIndex != block.last().irLabelIndex || it.template !in b
    }
    // +1 because we want to insert _after_ the index of the non-jump
    val indexToInsert = (jmpInstrs + 1).coerceAtLeast(0)
    // This is clearly post-allocation, so it's ok
    block.unsafelyGetInstructions().addAll(indexToInsert, copies)
  }

  private fun selectBlockInstrs(block: BasicBlock): List<MachineInstruction> {
    val selected = mutableListOf<MachineInstruction>()
    for ((index, irInstr) in block.ir.withIndex()) {
      selected += expandMacroWithLoads(irInstr).onEach { it.irLabelIndex = index }
    }

    when (val term = block.terminator) {
      MissingJump -> logger.throwICE("Incomplete BasicBlock")
      is CondJump -> {
        selected += selectCondJump(term, block.ir.size)
      }
      is SelectJump -> TODO("deal with switches later")
      is UncondJump -> {
        selected += b.match(JumpTargetConstant(term.target)).also {
          it.irLabelIndex = block.ir.size + 1
        }
      }
      is ConstantJump -> {
        selected += b.match(JumpTargetConstant(term.target)).also {
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
      selected += expandMacroWithLoads(irInstr).onEach { it.irLabelIndex = idxOffset + index }
    }

    val actualJump = when (val l = condJump.cond.last()) {
      is IntCmp -> matchIntCompareAndBranch(l, JumpTargetConstant(condJump.target)) + listOf(
          b.match(JumpTargetConstant(condJump.other))
      )
      is FltCmp -> TODO("mips float compare")
      else -> TODO("no idea what happens here")
    }
    selected += actualJump.onEach { it.irLabelIndex = idxOffset + condJump.cond.size - 1 }
    return selected
  }

  private fun matchIntCompareAndBranch(i: IntCmp, jumpIfTrue: JumpTargetConstant): List<MachineInstruction> {
    if (i.cmp == Comparisons.EQUAL || i.cmp == Comparisons.NOT_EQUAL) {
      val (lhs, lhsOps) = convertIfImm(i.lhs)
      val (rhs, rhsOps) = convertIfImm(i.rhs)

      val compareAndBranch = if (i.cmp == Comparisons.EQUAL) beq else bne

      return lhsOps + rhsOps + listOf(compareAndBranch.match(lhs, rhs, jumpIfTrue))
    }

    val compareResult = VirtualRegister(graph.registerIds(), i.lhs.type)
    val subCompare = matchSub(compareResult, i.lhs, i.rhs)

    val branch = when (i.cmp) {
      Comparisons.LESS_THAN -> bltz.match(compareResult, jumpIfTrue)
      Comparisons.GREATER_THAN -> bgtz.match(compareResult, jumpIfTrue)
      Comparisons.LESS_EQUAL -> blez.match(compareResult, jumpIfTrue)
      Comparisons.GREATER_EQUAL -> bgez.match(compareResult, jumpIfTrue)
      Comparisons.NOT_EQUAL, Comparisons.EQUAL -> logger.throwICE("Unreachable, checked above")
    }

    return subCompare + branch
  }

  private fun selectImpossible(ret: ImpossibleJump, idxOffset: Int): List<MachineInstruction> {
    val selected = mutableListOf<MachineInstruction>()
    val endIdx = idxOffset + (ret.returned?.size ?: 0) - 1
    if (ret.returned != null) {
      require(ret.returned.isNotEmpty())
      for ((index, irInstr) in ret.returned.withIndex()) {
        selected += expandMacroWithLoads(irInstr).onEach { it.irLabelIndex = idxOffset + index }
      }
      val retVal = ret.returned.last().result
      selected += createReturn(retVal).onEach { it.irLabelIndex = endIdx }
    }
    selected += b.match(JumpTargetConstant(graph.returnBlock.id)).also { it.irLabelIndex = endIdx }
    return selected
  }

  private fun removeParamsFromValue(value: IRValue): IRValue {
    return if (value is ParameterReference) {
      parameterMap[value] ?: logger.throwICE("Function parameter not mapped to register/memory")
    } else {
      value
    }
  }

  private fun MutableList<MachineInstruction>.convertToLoadMemory(value: IRValue): IRValue {
    if (value !is MemoryLocation) {
      return value
    }

    val target = VirtualRegister(graph.registerIds(), value.type)
    this += matchTypedCopy(target, value)
    return target
  }

  private fun expandMacroWithLoads(i: IRInstruction): List<MachineInstruction> = buildList {
    val iWithLoads = when (i) {
      is StoreMemory, is LoadMemory, is MoveInstr -> i
      is FltBinary -> i.copy(lhs = convertToLoadMemory(i.lhs), rhs = convertToLoadMemory(i.rhs))
      is FltCmp -> i.copy(lhs = convertToLoadMemory(i.lhs), rhs = convertToLoadMemory(i.rhs))
      is FltNeg -> i.copy(operand = convertToLoadMemory(i.operand))
      is IndirectCall -> i.copy(args = i.args.map { convertToLoadMemory(it) })
      is NamedCall -> i.copy(args = i.args.map { convertToLoadMemory(it) })
      is IntBinary -> i.copy(lhs = convertToLoadMemory(i.lhs), rhs = convertToLoadMemory(i.rhs))
      is IntCmp -> i.copy(lhs = convertToLoadMemory(i.lhs), rhs = convertToLoadMemory(i.rhs))
      is IntInvert -> i.copy(operand = convertToLoadMemory(i.operand))
      is IntNeg -> i.copy(operand = convertToLoadMemory(i.operand))
      is ReinterpretCast -> i.copy(operand = convertToLoadMemory(i.operand))
      is StructuralCast -> i.copy(operand = convertToLoadMemory(i.operand))
    }
    this += expandMacroFor(iWithLoads)
  }

  private fun expandMacroFor(i: IRInstruction): List<MachineInstruction> = when (i) {
    is MoveInstr -> listOf(matchTypedCopy(i.result, removeParamsFromValue(i.value)))
    is LoadMemory -> {
      val loadable = if (i.loadFrom is MemoryLocation) i.loadFrom else MemoryLocation(i.loadFrom)
      listOf(matchTypedCopy(i.result, loadable))
    }
    is StoreMemory -> {
      val valueNoParam = removeParamsFromValue(i.value)
      val (value, ops) = convertIfImm(valueNoParam)
      val storable =
          if (i.storeTo is StackVariable || i.storeTo is MemoryLocation) i.storeTo
          else MemoryLocation(i.storeTo)
      if (value.type is PointerType && MemoryLocation(value) == storable) {
        emptyList()
      } else {
        ops + matchTypedCopy(storable, value)
      }
    }
    is ReinterpretCast -> if (i.operand == i.result) emptyList() else listOf(matchTypedCopy(i.result, i.operand))
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
    is StructuralCast -> {
      // FIXME: this does nothing
      listOf(matchTypedCopy(i.result, i.operand))
    }
    is FltBinary -> when (i.op) {
      FloatingBinaryOps.ADD -> matchFloatBinary(i, add_s, add_d)
      FloatingBinaryOps.MUL -> matchFloatBinary(i, mul_s, mul_d)
      FloatingBinaryOps.SUB -> matchFloatBinary(i, sub_s, sub_d)
      FloatingBinaryOps.DIV -> matchFloatBinary(i, div_s, div_d)
    }
    is FltCmp -> TODO()
    is FltNeg -> matchFloatUnary(i, neg_s, neg_d)
    is IndirectCall -> createCall(i.result, i.callable, i.args)
    is NamedCall -> createCall(i.result, i.name, i.args)
  }

  private fun matchFloatBinary(
      i: BinaryInstruction,
      operationFloat: List<MIPS32InstructionTemplate>,
      operationDouble: List<MIPS32InstructionTemplate>
  ): List<MachineInstruction> {
    val ftype = i.result.type.unqualify()
    val operation = when (ftype) {
      FloatType -> operationFloat
      DoubleType, LongDoubleType -> operationDouble
      else -> TODO("unknown float type")
    }

    val (lhs, lhsOps) = convertIfImm(i.lhs)
    val (rhs, rhsOps) = convertIfImm(i.rhs)

    return lhsOps + rhsOps + operation.match(i.result, lhs, rhs)
  }

  private fun matchFloatUnary(
      i: FltNeg,
      operationFloat: List<MIPS32InstructionTemplate>,
      operationDouble: List<MIPS32InstructionTemplate>,
  ): List<MachineInstruction> {
    val ftype = i.result.type.unqualify()
    val operation = when (ftype) {
      FloatType -> operationFloat
      DoubleType, LongDoubleType -> operationDouble
      else -> TODO("unknown float type")
    }

    val (operand, operandOps) = convertIfImm(i.operand)

    return operandOps + operation.match(i.result, operand)
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
    return matchSub(i.result, i.lhs, i.rhs)
  }

  private fun matchSub(result: IRValue, subLhs: IRValue, subRhs: IRValue): List<MachineInstruction> {
    val (lhs, ops) = convertIfImm(subLhs)
    if (subRhs is ConstantValue) {
      val rhs = when (subRhs) {
        is IntConstant -> -subRhs
        is FltConstant -> -subRhs
        else -> logger.throwICE("Subtracting non int/float constant")
      }
      return ops + matchAdd(result, lhs, rhs)
    }

    val isUnsigned = result.type.unqualify() !is SignedIntegralType
    val sub = if (isUnsigned) subu else sub
    return listOf(sub.match(result, lhs, subRhs))
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
    val div = if (isUnsigned) divu else div
    val resultCopy = if (isRem) mfhi else mflo
    return ops + listOf(
        div.match(nonImm, convertedImm),
        resultCopy.match(i.result)
    )
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
          sltiu.match(i.result, i.result, IntConstant(1, i.result.type)),
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
    return regCopy to listOf(matchTypedCopy(regCopy, imm))
  }

  companion object {
    val wordSizeConstant = IntConstant(MIPS32Target.WORD, UnsignedIntType)

    private val logger = KotlinLogging.logger {}
  }
}
