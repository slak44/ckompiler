package slak.ckompiler.backend.x64

import org.apache.logging.log4j.LogManager
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

class X64Generator(override val cfg: CFG) : TargetFunGenerator {
  /**
   * All returns actually jump to this synthetic block, which then really returns from the function.
   */
  private val returnBlock = BasicBlock(isRoot = false)

  /**
   * Maps each function parameter to a more concrete value: params passed in registers will be
   * mapped to [PhysicalRegister]s, and ones passed in memory to [MemoryReference]s. The memory refs
   * will always be 8 bytes per the ABI, regardless of their type.
   *
   * System V ABI: 3.2.1, figure 3.3
   */
  override val parameterMap = mutableMapOf<ParameterReference, IRValue>()

  override val target = X64Target

  init {
    mapFunctionParams()
  }

  override fun instructionSelection(): InstructionMap {
    return cfg.nodes.associateWith(this::selectBlockInstrs)
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

  override fun createJump(target: BasicBlock): MachineInstruction {
    return jmp.match(JumpTargetConstant(target))
  }

  override fun insertPhiCopies(
      instructions: List<MachineInstruction>,
      copies: List<MachineInstruction>
  ): List<MachineInstruction> {
    val jmpInstrs = instructions.takeLastWhile {
      it.irLabelIndex == instructions.last().irLabelIndex &&
          (it.template in jmp || it.template in jcc.values.flatten())
    }
    return instructions.dropLast(jmpInstrs.size) + copies + jmpInstrs
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
        selected += selectReturn(term, block.ir.size)
      }
    }
    return selected
  }

  /**
   * @see parameterMap
   */
  private fun mapFunctionParams() {
    val vars = cfg.f.parameters.map(::Variable).withIndex()
    val integral =
        vars.filter { X64Target.registerClassOf(it.value.type) == X64RegisterClass.INTEGER }
    val sse = vars.filter { X64Target.registerClassOf(it.value.type) == X64RegisterClass.SSE }
    for ((intVar, regName) in integral.zip(intArgRegNames)) {
      val targetRegister = PhysicalRegister(X64Target.registerByName(regName), intVar.value.type)
      parameterMap[ParameterReference(intVar.index, intVar.value.type)] = targetRegister
    }
    for ((sseVar, regName) in sse.zip(sseArgRegNames)) {
      val targetRegister = PhysicalRegister(X64Target.registerByName(regName), sseVar.value.type)
      parameterMap[ParameterReference(sseVar.index, sseVar.value.type)] = targetRegister
    }
    // FIXME: deal with X87 here
    for ((index, variable) in vars - integral - sse) {
      val memory = MemoryReference(cfg.memoryIds(), variable.type)
      parameterMap[ParameterReference(index, variable.type)] = memory
    }
  }

  private fun selectCondJump(condJump: CondJump, idxOffset: Int): List<MachineInstruction> {
    val selected = mutableListOf<MachineInstruction>()
    for ((index, irInstr) in condJump.cond.dropLast(1).withIndex()) {
      selected += expandMacroFor(irInstr).onEach { it.irLabelIndex = idxOffset + index }
    }
    val actualJump = when (val l = condJump.cond.last()) {
      is IntCmp -> listOf(
          cmp.match(l.lhs, l.rhs),
          selectIntJmp(l, JumpTargetConstant(condJump.target)),
          jmp.match(JumpTargetConstant(condJump.other))
      )
      is FltCmp -> TODO("floats")
      else -> TODO("no idea what happens here")
    }
    selected += actualJump.onEach { it.irLabelIndex = idxOffset + condJump.cond.size - 1 }
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
  private fun selectReturn(ret: ImpossibleJump, idxOffset: Int): List<MachineInstruction> {
    val selected = mutableListOf<MachineInstruction>()
    val endIdx = idxOffset + (ret.returned?.size ?: 0) - 1
    if (ret.returned != null) {
      require(ret.returned.isNotEmpty())
      for ((index, irInstr) in ret.returned.withIndex()) {
        selected += expandMacroFor(irInstr).onEach { it.irLabelIndex = idxOffset + index }
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
          selected += mov.match(rax, retVal).also { it.irLabelIndex = endIdx }
        } else {
          TODO("deal with this")
        }
      }
    }
    selected += jmp.match(JumpTargetConstant(returnBlock)).also { it.irLabelIndex = endIdx }
    return selected
  }

  private fun expandMacroFor(i: IRInstruction): List<MachineInstruction> = when (i) {
    is LoadInstr -> listOf(matchTypedMov(i.result, i.target))
    is StoreInstr -> {
      val rhs = if (i.value is ParameterReference) {
        parameterMap[i.value]
            ?: logger.throwICE("Function parameter not mapped to register/memory")
      } else {
        i.value
      }
      listOf(matchTypedMov(i.target, rhs))
    }
    is ConstantRegisterInstr -> listOf(mov.match(i.result, i.const))
    is StructuralCast -> createStructuralCast(i)
    is ReinterpretCast -> {
      if (i.operand == i.result) emptyList() else listOf(matchTypedMov(i.result, i.operand))
    }
    is NamedCall -> TODO()
    is IndirectCall -> TODO()
    is IntBinary -> when (i.op) {
      IntegralBinaryOps.ADD -> matchCommutativeBinary(i, add)
      IntegralBinaryOps.SUB -> matchSub(i)
      IntegralBinaryOps.MUL -> TODO()
      IntegralBinaryOps.DIV -> TODO()
      IntegralBinaryOps.REM -> TODO()
      IntegralBinaryOps.LSH -> TODO()
      IntegralBinaryOps.RSH -> TODO()
      IntegralBinaryOps.AND -> matchCommutativeBinary(i, and)
      IntegralBinaryOps.OR -> matchCommutativeBinary(i, or)
      IntegralBinaryOps.XOR -> matchCommutativeBinary(i, xor)
    }
    is IntCmp -> matchCmp(i)
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
    is FltBinary -> TODO()
    is FltCmp -> TODO()
    is FltNeg -> TODO()
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
   * Create a generic copy instruction. Figures out register class and picks the correct mov.
   */
  private fun matchTypedMov(dest: IRValue, src: IRValue): MachineInstruction {
    val destRegClass = target.registerClassOf(dest.type)
    val srcRegClass = target.registerClassOf(src.type)
    require(destRegClass != Memory || srcRegClass != Memory) { "No memory-to-memory move exists" }
    val nonMemoryClass = if (destRegClass != Memory && srcRegClass != Memory) {
      require(destRegClass == srcRegClass) { "Move between register classes without cast" }
      destRegClass
    } else {
      if (destRegClass == Memory) srcRegClass else destRegClass
    }
    require(nonMemoryClass is X64RegisterClass)
    return when (nonMemoryClass) {
      X64RegisterClass.INTEGER -> mov.match(dest, src)
      X64RegisterClass.SSE -> when (target.machineTargetData.sizeOf(src.type)) {
        4 -> movss.match(dest, src)
        8 -> movsd.match(dest, src)
        else -> logger.throwICE("Float size not 4 or 8 bytes")
      }
      X64RegisterClass.X87 -> TODO("x87 movs")
    }
  }

  /**
   * Handle non-commutative 2-address code operation.
   *
   * Register Allocation for Programs in SSA Form, Sebastian Hack: Section 5.1.2.2
   */
  private fun matchSub(i: IntBinary) = when (i.result) {
    // result = result OP rhs
    i.lhs -> listOf(sub.match(i.lhs, i.rhs))
    // result = lhs OP result
    i.rhs -> {
      val subTarget = VirtualRegister(cfg.registerIds(), i.lhs.type)
      listOf(
          mov.match(subTarget, i.lhs),
          sub.match(subTarget, i.result),
          mov.match(i.result, subTarget)
      )
    }
    else -> {
      require(i.lhs !is ConstantValue || i.rhs !is ConstantValue)
      listOfNotNull(
          mov.match(i.result, i.lhs),
          sub.match(i.result, i.rhs),
          if (i.rhs !is ConstantValue) dummyUse.match(i.rhs) else null
      )
    }
  }

  private fun matchCommutativeBinary(
      i: BinaryInstruction,
      op: List<X64InstrTemplate>
  ): List<MachineInstruction> = when (i.result) {
    // result = result OP rhs
    i.lhs -> listOf(op.match(i.lhs, i.rhs))
    // result = lhs OP result
    i.rhs -> listOf(op.match(i.rhs, i.lhs))
    else -> {
      val (nonImm, maybeImm) = findImmInBinary(i)
      listOf(
          mov.match(i.result, nonImm),
          op.match(i.result, maybeImm)
      )
    }
  }

  private fun findImmInBinary(i: BinaryInstruction): Pair<IRValue, IRValue> {
    // Can't have result = imm OP imm
    require(i.lhs !is ConstantValue || i.rhs !is ConstantValue)
    val nonImm = if (i.lhs is ConstantValue) i.rhs else i.lhs
    val maybeImm = if (i.lhs === nonImm) i.rhs else i.lhs
    return nonImm to maybeImm
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
          if (it is PhysicalRegister) {
            return@map RegisterValue(it.reg, X64Target.machineTargetData.sizeOf(it.type))
          }
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
    // FIXME: deal with MEMORY class function arguments (see paramSourceMap)
    // FIXME: allocate stack space for locals
    // FIXME: save callee-saved registers
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
    // FIXME: restore callee-saved registers
    return listOf(
        X64Instruction(leave.first(), emptyList()),
        X64Instruction(ret.first(), emptyList())
    )
  }

  companion object {
    private val logger = LogManager.getLogger()

    private val rbp = PhysicalRegister(X64Target.registerByName("rbp"), UnsignedLongType)
    private val rsp = PhysicalRegister(X64Target.registerByName("rsp"), UnsignedLongType)

    /**
     * System V ABI: 3.2.3, page 20
     */
    private val intArgRegNames = listOf("rdi", "rsi", "rdx", "rcx", "r8", "r9")

    /**
     * System V ABI: 3.2.3, page 20
     */
    private val sseArgRegNames =
        listOf("xmm0", "xmm1", "xmm2", "xmm3", "xmm4", "xmm5", "xmm6", "xmm7")
  }
}
