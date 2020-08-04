package slak.ckompiler.backend.x64

import org.apache.logging.log4j.LogManager
import slak.ckompiler.AtomicId
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE
import kotlin.math.absoluteValue
import kotlin.math.sign

class X64Generator private constructor(
    private val cfg: CFG,
    override val target: X64Target,
    private val funAsm: X64FunAssembler
) : TargetFunGenerator,
    FunctionAssembler by funAsm,
    FunctionCallGenerator by X64CallGenerator(target, cfg.registerIds) {
  constructor(cfg: CFG, target: X64Target) : this(cfg, target, X64FunAssembler(target, cfg))

  override val graph: InstructionGraph = InstructionGraph.partiallyInitialize(cfg)

  init {
    graph.copyStructureFrom(cfg, this::selectBlockInstrs)
  }

  override fun createIRCopy(dest: IRValue, src: IRValue): MachineInstruction {
    return matchTypedMov(dest, src)
  }

  override fun createRegisterCopy(dest: MachineRegister, src: MachineRegister): MachineInstruction {
    val dc = dest.valueClass
    require(dc is X64RegisterClass && dc == src.valueClass) {
      "Register value classes do not match"
    }
    val type = when (dc) {
      X64RegisterClass.INTEGER -> UnsignedLongType
      X64RegisterClass.SSE -> when (src.sizeBytes) {
        4 -> FloatType
        8 -> DoubleType
        else -> TODO("type?")
      }
      X64RegisterClass.X87 -> LongDoubleType
    }
    return matchTypedMov(PhysicalRegister(dest, type), PhysicalRegister(src, type))
  }

  override fun createJump(target: InstrBlock): MachineInstruction {
    return jmp.match(JumpTargetConstant(target.id))
  }

  override fun insertPhiCopies(block: InstrBlock, copies: List<MachineInstruction>) {
    val jmpInstrs = block.indexOfLast {
      it.irLabelIndex != block.last().irLabelIndex || !(it.template in jmp || it.template in jcc.values.flatten())
    }
    block.addAll(jmpInstrs, copies)
  }

  override fun rewriteSpill(block: InstrBlock, spilled: Set<AtomicId>) {
    val bb = cfg.nodes.first { it.nodeId == block.id }
    val it = block.listIterator()
    while (it.hasNext()) {
      val mi = it.next()
      val shouldChange = mi.operands.any { it is Variable && it.id in spilled }
      if (!shouldChange) continue

      // Remove all MIs generated from this IR, both behind and forward
      while (it.hasPrevious() && it.previous().irLabelIndex == mi.irLabelIndex) it.remove()
      while (it.hasNext() && it.next().irLabelIndex == mi.irLabelIndex) it.remove()

      // Find original IR from basic block
      val actualIR = if (mi.irLabelIndex > bb.ir.size) {
        val idxInTerm = mi.irLabelIndex - bb.ir.size
        when (val term = bb.terminator) {
          is CondJump -> term.cond[idxInTerm]
          is SelectJump -> term.cond[idxInTerm]
          is ImpossibleJump -> term.returned!![idxInTerm]
          else -> logger.throwICE("irLabelIndex points to terminator without IR")
        }
      } else {
        bb.ir[mi.irLabelIndex]
      }

      // Replace spills
      val rewrittenIR = spilled.replaceSpilled(actualIR)

      // Match and insert new MIs
      val newMIs = expandMacroFor(rewrittenIR).onEach { it.irLabelIndex = mi.irLabelIndex }
      for (newMI in newMIs) {
        it.add(newMI)
      }
    }
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
    val actualJump = when (val l = condJump.cond.last()) {
      is IntCmp -> listOf(
          matchTypedCmp(l.lhs, l.rhs),
          selectJmp(l, l.cmp, JumpTargetConstant(condJump.target)),
          jmp.match(JumpTargetConstant(condJump.other))
      )
      is FltCmp -> listOf(
          matchTypedCmp(l.lhs, l.rhs),
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
      jumpTrue: JumpTargetConstant
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
      IntegralBinaryOps.ADD -> matchCommutative(i, add)
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
        else -> TODO("unimplemented structural cast type from integral")
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
   * Handle non-commutative 2-address code operation.
   *
   * Register Allocation for Programs in SSA Form, Sebastian Hack: Section 5.1.2.2
   */
  private fun matchNonCommutative(
      i: BinaryInstruction,
      op: List<X64InstrTemplate>
  ) = when (i.result) {
    // result = result OP rhs
    i.lhs -> listOf(op.match(i.lhs, i.rhs))
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
      listOfNotNull(
          matchTypedMov(i.result, i.lhs),
          op.match(i.result, i.rhs),
          if (i.rhs !is ConstantValue) dummyUse.match(i.rhs) else null
      )
    }
  }

  private fun matchIMul(i: IntBinary): List<MachineInstruction> {
    if (i.result == i.lhs) {
      return listOf(imul.match(i.result, i.rhs))
    }
    if (i.result == i.rhs) {
      return listOf(imul.match(i.result, i.lhs))
    }
    val (nonImm, maybeImm) = findImmInBinary(i.lhs, i.rhs)
    if (maybeImm is ConstantValue) {
      return listOf(imul.match(i.result, nonImm, maybeImm))
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
    val dummy = VirtualRegister(graph.registerIds(), target.machineTargetData.ptrDiffType, isUndefined = true)
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
      op: List<X64InstrTemplate>
  ): List<MachineInstruction> = when (i.result) {
    // result = result OP rhs
    i.lhs -> listOf(op.match(i.lhs, i.rhs))
    // result = lhs OP result
    i.rhs -> listOf(op.match(i.rhs, i.lhs))
    else -> {
      val (nonImm, maybeImm) = findImmInBinary(i.lhs, i.rhs)
      listOf(
          matchTypedMov(i.result, nonImm),
          op.match(i.result, maybeImm)
      )
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
    return listOf(
        matchTypedCmp(nonImm, maybeImm),
        setcc.getValue(setValue).match(setccTarget),
        movzx.match(i.result, setccTarget)
    )
  }

  /**
   * Create a generic compare instruction that sets flags. Picks from `cmp`, `comiss`, `comisd`.
   */
  private fun matchTypedCmp(lhs: IRValue, rhs: IRValue) = when (validateClasses(lhs, rhs)) {
    X64RegisterClass.INTEGER -> cmp.match(lhs, rhs)
    X64RegisterClass.SSE -> when (target.machineTargetData.sizeOf(rhs.type)) {
      4 -> comiss.match(lhs, rhs)
      8 -> comisd.match(lhs, rhs)
      else -> logger.throwICE("Float size not 4 or 8 bytes")
    }
    X64RegisterClass.X87 -> TODO("x87 cmps")
  }

  private fun findImmInBinary(lhs: IRValue, rhs: IRValue): Pair<IRValue, IRValue> {
    // Can't have result = imm OP imm
    require(lhs !is ConstantValue || rhs !is ConstantValue)
    val nonImm = if (lhs is ConstantValue) rhs else lhs
    val maybeImm = if (lhs === nonImm) rhs else lhs
    return nonImm to maybeImm
  }

  companion object {
    private val logger = LogManager.getLogger()

    private fun Int.toHex() = "${if (sign == -1) "-" else ""}0x${absoluteValue.toString(16)}"
  }
}
