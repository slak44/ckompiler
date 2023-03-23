package slak.ckompiler.backend.x64

import mu.KotlinLogging
import slak.ckompiler.IdCounter
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE
import kotlin.math.absoluteValue
import kotlin.math.sign

class X64Generator private constructor(
    cfg: CFG,
    override val target: X64Target,
    private val funAsm: X64FunAssembler,
) : TargetFunGenerator<X64Instruction>,
    FunctionAssembler<X64Instruction> by funAsm,
    FunctionCallGenerator by X64CallGenerator(target, cfg.registerIds) {
  constructor(cfg: CFG, target: X64Target) : this(cfg, target, X64FunAssembler(target, cfg, IdCounter()))

  override val stackSlotIds = funAsm.stackSlotIds

  override val graph: InstructionGraph = InstructionGraph.partiallyInitialize(cfg)

  private val rsp = target.registerByName("rsp")

  init {
    graph.copyStructureFrom(cfg, this::selectBlockInstrs)
  }

  override fun createIRCopy(dest: IRValue, src: IRValue): MachineInstruction {
    return matchTypedMov(dest, src)
  }

  private fun transformMachineReg(reg: MachineRegister, sizeHint: Int?): IRValue {
    when (val vc = reg.valueClass) {
      is X64RegisterClass -> {
        val type = when (vc) {
          X64RegisterClass.INTEGER -> when (sizeHint) {
            8, null -> UnsignedLongType
            4 -> UnsignedIntType
            else -> TODO("good luck")
          }
          X64RegisterClass.SSE -> when (reg.sizeBytes) {
            4 -> FloatType
            8 -> DoubleType
            else -> DoubleType // TODO: this is sort of a hack? doesn't work for regs as vectors, but unsure if they will ever get here
          }
          X64RegisterClass.X87 -> LongDoubleType
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

  // FIXME: we need to propagate size hints for these registers to here, because we really need the size
  override fun createRegisterCopy(dest: MachineRegister, src: MachineRegister): MachineInstruction {
    val sizeHint = when {
      dest.valueClass == Memory -> dest.sizeBytes
      src.valueClass == Memory -> src.sizeBytes
      else -> null
    }
    val destValue = transformMachineReg(dest, sizeHint)
    val srcValue = transformMachineReg(src, sizeHint)
    if (dest.valueClass is X64RegisterClass && src.valueClass is X64RegisterClass && dest.valueClass != src.valueClass) {
      logger.throwICE("Register value classes do not match")
    }
    return matchTypedMov(destValue, srcValue)
  }

  // FIXME: see above
  override fun createLocalPush(src: MachineRegister): List<MachineInstruction> {
    val valueClass = src.valueClass
    require(valueClass is X64RegisterClass) { "Must be an x64 register" }
    val physicalRegister = transformMachineReg(src, src.sizeBytes)
    val push = when (valueClass) {
      X64RegisterClass.INTEGER -> push.match(physicalRegister)
      X64RegisterClass.SSE -> {
        val rspValue = PhysicalRegister(rsp, PointerType(physicalRegister.type, emptyList()))
        val topOfStack = MemoryLocation(rspValue)
        matchTypedMov(topOfStack, physicalRegister)
      }
      X64RegisterClass.X87 -> TODO()
    }

    return listOf(push)
  }

  override fun createLocalPop(dest: MachineRegister): List<MachineInstruction> {
    val valueClass = dest.valueClass
    require(valueClass is X64RegisterClass) { "Must be an x64 register" }
    val physicalRegister = transformMachineReg(dest, dest.sizeBytes)
    val pop = when (valueClass) {
      X64RegisterClass.INTEGER -> pop.match(physicalRegister)
      X64RegisterClass.SSE -> {
        val rspValue = PhysicalRegister(rsp, PointerType(physicalRegister.type, emptyList()))
        val topOfStack = MemoryLocation(rspValue)
        matchTypedMov(physicalRegister, topOfStack)
      }
      X64RegisterClass.X87 -> TODO()
    }

    return listOf(pop)
  }

  override fun createJump(target: InstrBlock): MachineInstruction {
    return jmp.match(JumpTargetConstant(target.id))
  }

  override fun insertPhiCopies(block: InstrBlock, copies: List<MachineInstruction>) {
    val jmpInstrs = block.indexOfLast {
      it.irLabelIndex != block.last().irLabelIndex || !(it.template in jmp || it.template in jcc.values.flatten())
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
        selected += jmp.match(JumpTargetConstant(term.target)).also {
          it.irLabelIndex = block.ir.size + 1
        }
      }
      is ConstantJump -> {
        selected += jmp.match(JumpTargetConstant(term.target)).also {
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

    val l = condJump.cond.last()
    require(l is BinaryInstruction)

    val (lhs, ops) = if (l.lhs is IntConstant) {
      val regCopy = VirtualRegister(graph.registerIds(), l.lhs.type)
      regCopy to listOf(matchTypedMov(regCopy, l.lhs))
    } else {
      l.lhs to emptyList()
    }

    val actualJump = when (l) {
      is IntCmp -> ops + matchTypedCmp(lhs, l.rhs) + listOf(
          selectJmp(l, l.cmp, JumpTargetConstant(condJump.target)),
          jmp.match(JumpTargetConstant(condJump.other))
      )
      is FltCmp -> ops + matchTypedCmp(lhs, l.rhs) + listOf(
          selectJmp(l, l.cmp, JumpTargetConstant(condJump.target)),
          jmp.match(JumpTargetConstant(condJump.other))
      )
      else -> TODO("no idea what happens here")
    }
    selected += actualJump.onEach { it.irLabelIndex = idxOffset + condJump.cond.size - 1 }
    return selected
  }

  private fun selectJmp(
      i: BinaryInstruction,
      compare: Comparisons,
      jumpTrue: JumpTargetConstant,
  ): MachineInstruction {
    // See matchTypedCmp for why this works
    val isSigned = i.lhs.type.unqualify() is SignedIntType
    // FIXME: deal with common cases like `!(a > b)` -> jnge/jnae
    val jmpName = when (compare) {
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
    selected +=
        jmp.match(JumpTargetConstant(graph.returnBlock.id)).also { it.irLabelIndex = endIdx }
    return selected
  }

  private fun removeParamsFromValue(value: IRValue): IRValue {
    return if (value is ParameterReference) {
      parameterMap[value]
          ?: logger.throwICE("Function parameter not mapped to register/memory")
    } else {
      value
    }
  }

  private fun expandMacroFor(i: IRInstruction): List<MachineInstruction> = when (i) {
    is MoveInstr -> {
      val rhs = removeParamsFromValue(i.value)
      listOf(matchTypedMov(i.result, rhs))
    }
    is LoadMemory -> {
      val loadable =
          if (i.loadFrom is StackVariable || i.loadFrom is MemoryLocation) i.loadFrom
          else MemoryLocation(i.loadFrom)
      listOf(matchTypedMov(i.result, loadable))
    }
    is StoreMemory -> {
      val value = removeParamsFromValue(i.value)
      val storable =
          if (i.storeTo is StackVariable || i.storeTo is MemoryLocation) i.storeTo
          else MemoryLocation(i.storeTo)
      if (value.type is PointerType && MemoryLocation(value) == storable) {
        emptyList()
      } else {
        listOf(matchTypedMov(storable, value))
      }
    }
    is StructuralCast -> createStructuralCast(i)
    is ReinterpretCast -> {
      if (i.operand == i.result) emptyList() else listOf(matchTypedMov(i.result, i.operand))
    }
    is NamedCall -> {
      funAsm.isLeaf = false
      createCall(i.result, i.name, i.args)
    }
    is IndirectCall -> {
      funAsm.isLeaf = false
      createCall(i.result, i.callable, i.args)
    }
    is IntBinary -> when (i.op) {
      IntegralBinaryOps.ADD -> matchCommutativePtr(i, add)
      IntegralBinaryOps.SUB -> matchNonCommutative(i, sub)
      IntegralBinaryOps.MUL -> {
        if (i.lhs.type.unqualify() is SignedIntegralType) matchIMul(i) else TODO("mul")
      }
      IntegralBinaryOps.REM, IntegralBinaryOps.DIV -> matchDivRem(i)
      IntegralBinaryOps.LSH -> TODO()
      IntegralBinaryOps.RSH -> TODO()
      IntegralBinaryOps.AND -> matchCommutative(i, and)
      IntegralBinaryOps.OR -> matchCommutative(i, or)
      IntegralBinaryOps.XOR -> matchCommutative(i, xor)
    }
    is IntCmp -> matchCmp(i, i.cmp)
    is IntInvert -> if (i.operand == i.result) {
      listOf(not.match(i.operand))
    } else {
      listOf(
          mov.match(i.result, i.operand),
          not.match(i.result)
      )
    }
    is IntNeg -> if (i.operand == i.result) {
      listOf(neg.match(i.operand))
    } else {
      listOf(
          mov.match(i.result, i.operand),
          neg.match(i.result)
      )
    }
    is FltBinary -> when (i.op) {
      FloatingBinaryOps.ADD -> when (i.result.type.unqualify()) {
        FloatType -> matchCommutative(i, addss)
        DoubleType -> matchCommutative(i, addsd)
        else -> TODO("unknown")
      }
      FloatingBinaryOps.MUL -> when (i.result.type.unqualify()) {
        FloatType -> matchCommutative(i, mulss)
        DoubleType -> matchCommutative(i, mulsd)
        else -> TODO("unknown")
      }
      FloatingBinaryOps.SUB -> when (i.result.type.unqualify()) {
        FloatType -> matchNonCommutative(i, subss)
        DoubleType -> matchNonCommutative(i, subsd)
        else -> TODO("unknown")
      }
      FloatingBinaryOps.DIV -> when (i.result.type.unqualify()) {
        FloatType -> matchNonCommutative(i, divss)
        DoubleType -> matchNonCommutative(i, divsd)
        else -> TODO("unknown")
      }
    }
    is FltCmp -> matchCmp(i, i.cmp)
    is FltNeg -> when (i.result.type.unqualify()) {
      FloatType -> matchCommutative(object : BinaryInstruction {
        override val result = i.result
        override val lhs = i.operand
        override val rhs = FltConstant(-0.0, FloatType)
      }, xorps)
      DoubleType -> matchCommutative(object : BinaryInstruction {
        override val result = i.result
        override val lhs = i.operand
        override val rhs = FltConstant(-0.0, DoubleType)
      }, xorpd)
      else -> TODO("unknown")
    }
  }

  private fun createStructuralCast(cast: StructuralCast): List<MachineInstruction> {
    val src = cast.operand.type.normalize()
    val dest = cast.result.type.normalize()
    return when {
      src == dest -> emptyList()
      src is PointerType && dest is PointerType -> {
        // We really don't care about pointer-to-pointer casts
        listOf(matchTypedMov(cast.result, cast.operand))
      }
      src is IntegralType -> when (dest) {
        is PointerType -> listOf(matchTypedMov(cast.result, cast.operand))
        FloatType -> listOf(cvtsi2ss.match(cast.result, cast.operand))
        DoubleType -> listOf(cvtsi2sd.match(cast.result, cast.operand))
        is IntegralType -> listOf(matchIntegralCast(src, dest, cast))
        else -> TODO("unimplemented structural cast type from integral to $dest")
      }
      src is FloatType -> when (dest) {
        is IntegralType -> listOf(cvttss2si.match(cast.result, cast.operand))
        DoubleType -> listOf(cvtss2sd.match(cast.result, cast.operand))
        else -> TODO("unimplemented structural cast type from float")
      }
      src is DoubleType -> when (dest) {
        is IntegralType -> listOf(cvttsd2si.match(cast.result, cast.operand))
        FloatType -> listOf(cvtsd2ss.match(cast.result, cast.operand))
        else -> TODO("unimplemented structural cast type from double")
      }
      else -> TODO("unimplemented structural cast type")
    }
  }

  /**
   * FIXME: these cast rules are not so simple
   *
   * C standard: 6.3.1.3
   */
  private fun matchIntegralCast(src: IntegralType, dest: IntegralType, cast: StructuralCast): MachineInstruction {
    val destSize = target.machineTargetData.sizeOf(dest)
    val srcSize = target.machineTargetData.sizeOf(src)
    return when {
      destSize == srcSize -> matchTypedMov(cast.result, cast.operand)
      destSize > srcSize -> {
        // FIXME: edge cases for unsigned destination?
        when {
          src is SignedIntegralType && destSize == 8 && srcSize == 4 -> movsxd.match(cast.result, cast.operand)
          src is SignedIntegralType -> movsx.match(cast.result, cast.operand)
          src is UnsignedIntegralType && destSize == 8 && srcSize == 4 -> {
            // mov zero-extends for free on x64 if we change the dest size to the same as src
//            mov.match(target.machineTargetData.copyWithType(cast.result, src), cast.operand)
            // FIXME: it currently doesn't work, because the asm emitter emits `mov rcx, eax` which doesn't work, instead of `mov ecx, eax`
            //   this is a temporary hack which does technically work
            movsxd.match(cast.result, cast.operand)
          }
          else -> movzx.match(cast.result, cast.operand)
        }
      }
      destSize < srcSize -> {
        if (dest is SignedIntegralType) {
          mov.match(cast.result, target.machineTargetData.copyWithType(cast.operand, dest))
        } else {
          TODO("narrowing integral cast from $src to $dest")
        }
      }
      else -> logger.throwICE("Cast between same type cannot make it to codegen") { "$src to $dest" }
    }
  }

  /**
   * Handle non-commutative 2-address code operation.
   *
   * Register Allocation for Programs in SSA Form, Sebastian Hack: Section 5.1.2.2
   */
  private fun matchNonCommutative(
      i: BinaryInstruction,
      op: List<X64InstrTemplate>,
  ) = when (i.result) {
    // result = result OP rhs
    i.lhs -> {
      val (rhs, ops) = convertIfImm(i.rhs)
      ops + op.match(i.lhs, rhs)
    }
    // result = lhs OP result
    i.rhs -> {
      val opTarget = VirtualRegister(graph.registerIds(), i.lhs.type)
      listOf(
          matchTypedMov(opTarget, i.lhs),
          op.match(opTarget, i.result),
          matchTypedMov(i.result, opTarget)
      )
    }
    else -> {
      require(i.lhs !is ConstantValue || i.rhs !is ConstantValue)
      val (rhs, ops) = convertIfImm(i.rhs)
      ops + listOfNotNull(
          matchTypedMov(i.result, i.lhs),
          op.match(i.result, rhs),
          if (i.rhs !is ConstantValue) dummyUse.match(rhs) else null
      )
    }
  }

  private fun matchIMul(i: IntBinary): List<MachineInstruction> {
    if (i.result == i.lhs) {
      val (rhs, ops) = convertIfImm(i.rhs)
      return ops + imul.match(i.result, rhs)
    }
    if (i.result == i.rhs) {
      val (lhs, ops) = convertIfImm(i.lhs)
      return ops + imul.match(i.result, lhs)
    }
    val (nonImm, maybeImm) = findImmInBinary(i.lhs, i.rhs)
    if (maybeImm is ConstantValue) {
      return if (target.machineTargetData.sizeOf(maybeImm.type) < 8) {
        // 3-operand RMI only supports 32bit immediates
        listOf(imul.match(i.result, nonImm, maybeImm))
      } else {
        val (storedImm, ops) = convertImmForOp(maybeImm)
        ops + listOf(
            matchTypedMov(i.result, nonImm),
            imul.match(i.result, storedImm)
        )
      }
    }
    return listOf(
        mov.match(i.result, nonImm),
        imul.match(i.result, maybeImm)
    )
  }

  private fun matchDivRem(i: IntBinary): List<MachineInstruction> {
    val divType = i.lhs.type.unqualify()
    val divInstr = if (divType is SignedIntegralType) idiv else div
    val cdqInstr = when (target.machineTargetData.sizeOf(divType)) {
      2 -> cwd
      4 -> cdq
      8 -> cqo
      else -> TODO("what to do?")
    }
    val rax = target.registerByName("rax")
    val rdx = target.registerByName("rdx")
    val (resultReg, otherReg) = if (i.op == IntegralBinaryOps.REM) rdx to rax else rax to rdx
    val dummy = VirtualRegister(graph.registerIds(), target.machineTargetData.ptrDiffType, VRegType.CONSTRAINED)
    val actualDividend = if (i.lhs is AllocatableValue) i.lhs else VirtualRegister(graph.registerIds(), i.lhs.type)
    val actualDivisor = if (i.rhs is ConstantValue) VirtualRegister(graph.registerIds(), i.rhs.type) else i.rhs
    return listOfNotNull(
        if (i.lhs is AllocatableValue) null else matchTypedMov(actualDividend, i.lhs),
        if (i.rhs !is ConstantValue) null else matchTypedMov(actualDivisor, i.rhs),
        divInstr.match(actualDivisor).copy(
            constrainedArgs = listOf(actualDividend constrainedTo rax, dummy constrainedTo rdx),
            constrainedRes = listOf(i.result as AllocatableValue constrainedTo resultReg, dummy constrainedTo otherReg),
            links = listOf(LinkedInstruction(cdqInstr.match(), LinkPosition.BEFORE))
        )
    )
  }

  private fun matchCommutative(
      i: BinaryInstruction,
      op: List<X64InstrTemplate>,
  ): List<MachineInstruction> = when (i.result) {
    // result = result OP rhs
    i.lhs -> {
      val (rhs, ops) = convertIfImm(i.rhs)
      ops + op.match(i.lhs, rhs)
    }
    // result = lhs OP result
    i.rhs -> {
      val (lhs, ops) = convertIfImm(i.lhs)
      ops + op.match(i.rhs, lhs)
    }
    else -> {
      val (nonImm, maybeImm) = findImmInBinary(i.lhs, i.rhs)
      val (maybeConvImm, ops) = convertIfImm(maybeImm)
      listOf(matchTypedMov(i.result, nonImm)) + ops + op.match(i.result, maybeConvImm)
    }
  }

  private fun matchCommutativePtr(i: BinaryInstruction, op: List<X64InstrTemplate>): List<MachineInstruction> {
    val lhsType = i.lhs.type.normalize()
    val rhsType = i.rhs.type.normalize()
    if (lhsType !is PointerType && rhsType !is PointerType) {
      // No pointer arithmetic
      return matchCommutative(i, op)
    }

    if (lhsType !is IntegralType && rhsType !is IntegralType) {
      TODO("adding pointer to non-integral, maybe pointer plus pointer? is this valid?")
    }

    val (lhs, lhsOps) = convertToPtrDiff(lhsType, i.lhs)
    val (rhs, rhsOps) = convertToPtrDiff(rhsType, i.rhs)

    val binaryInstruction = object : BinaryInstruction {
      override val result = i.result
      override val lhs = lhs
      override val rhs = rhs
    }
    return lhsOps + rhsOps + matchCommutative(binaryInstruction, op)
  }

  private fun convertToPtrDiff(valueType: TypeName, value: IRValue): Pair<IRValue, List<MachineInstruction>> {
    return if (valueType is IntegralType) {
      val regCopy = VirtualRegister(graph.registerIds(), target.machineTargetData.ptrDiffType)
      val cast = StructuralCast(regCopy, value)
      regCopy to listOf(matchIntegralCast(valueType, target.machineTargetData.ptrDiffType, cast))
    } else {
      value to emptyList()
    }
  }

  private fun matchCmp(i: BinaryInstruction, compare: Comparisons): List<MachineInstruction> {
    // Floats use the unsigned flags, so isSigned will only be true for actual signed ints
    val isSigned = i.lhs.type.unqualify() is SignedIntType
    val setValue = when (compare) {
      Comparisons.EQUAL -> "sete"
      Comparisons.NOT_EQUAL -> "setne"
      Comparisons.LESS_THAN -> if (isSigned) "setl" else "setb"
      Comparisons.GREATER_THAN -> if (isSigned) "setg" else "seta"
      Comparisons.LESS_EQUAL -> if (isSigned) "setle" else "setbe"
      Comparisons.GREATER_EQUAL -> if (isSigned) "setge" else "setae"
    }
    val (nonImm, maybeImm) = findImmInBinary(i.lhs, i.rhs)
    val setccTarget = VirtualRegister(graph.registerIds(), SignedCharType)
    return matchTypedCmp(nonImm, maybeImm) + listOf(
        setcc.getValue(setValue).match(setccTarget),
        movzx.match(i.result, setccTarget)
    )
  }

  private fun matchFloatCmp(lhs: IRValue, rhs: IRValue): List<MachineInstruction> {
    val compare = when (target.machineTargetData.sizeOf(rhs.type)) {
      4 -> comiss
      8 -> comisd
      else -> logger.throwICE("Float size not 4 or 8 bytes")
    }

    val i = mutableListOf<MachineInstruction>()

    val newLhs = if (lhs is ConstantValue) {
      val regCopy = VirtualRegister(graph.registerIds(), lhs.type)
      i += matchTypedMov(regCopy, lhs)
      regCopy
    } else {
      lhs
    }

    val newRhs = if (rhs is ConstantValue) {
      val regCopy = VirtualRegister(graph.registerIds(), rhs.type)
      i += matchTypedMov(regCopy, rhs)
      regCopy
    } else {
      rhs
    }

    i += compare.match(newLhs, newRhs)

    return i
  }

  /**
   * Create a generic compare instruction that sets flags. Picks from `cmp`, `comiss`, `comisd`.
   */
  private fun matchTypedCmp(lhs: IRValue, rhs: IRValue) = when (validateClasses(lhs, rhs)) {
    X64RegisterClass.INTEGER -> listOf(cmp.match(lhs, rhs))
    X64RegisterClass.SSE -> matchFloatCmp(lhs, rhs)
    X64RegisterClass.X87 -> TODO("x87 cmps")
  }

  private fun convertIfImm(irValue: IRValue): Pair<IRValue, List<MachineInstruction>> {
    return if (irValue is ConstantValue) convertImmForOp(irValue) else irValue to emptyList()
  }

  private fun convertImmForOp(imm: ConstantValue): Pair<IRValue, List<MachineInstruction>> {
    if (imm !is IntConstant || target.machineTargetData.sizeOf(imm.type) < 8) return imm to emptyList()
    val regCopy = VirtualRegister(graph.registerIds(), imm.type)
    return regCopy to listOf(matchTypedMov(regCopy, imm))
  }

  companion object {
    private val logger = KotlinLogging.logger {}

    private fun Int.toHex() = "${if (sign == -1) "-" else ""}0x${absoluteValue.toString(16)}"
  }
}
