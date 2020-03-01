package slak.ckompiler.backend.x64

import org.apache.logging.log4j.LogManager
import slak.ckompiler.AtomicId
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE
import kotlin.properties.Delegates

class X64FunAssembler(val cfg: CFG) : FunctionAssembler {
  private val target = X64Target

  override val returnBlock = BasicBlock(isRoot = false)

  /**
   * If this is set to true, it means this function does not make any calls (ie is a leaf function).
   * This is useful for knowing whether to use the red zone or not.
   *
   * System V ABI: page 19
   */
  private var isLeaf = true

  /** @see genFunctionPrologue */
  private var finalStackSizeBytes by Delegates.notNull<Int>()

  /**
   * Maps each function parameter to a more concrete value: params passed in registers will be
   * mapped to [PhysicalRegister]s, and ones passed in memory to [StackVariable]s. The memory refs
   * will always be 8 bytes per the ABI, regardless of their type.
   *
   * System V ABI: 3.2.1, figure 3.3
   */
  override val parameterMap = mutableMapOf<ParameterReference, IRValue>()

  /**
   * Stores actual stack values for function parameters passed on the stack. That is, it maps the
   * id of the relevant [Variable]/[StackVariable]/[StackSlot] to its `[rbp + 0x123]` [StackValue].
   */
  private val stackParamOffsets = mutableMapOf<AtomicId, StackValue>()

  init {
    mapFunctionParams()
  }

  /** @see parameterMap */
  private fun mapFunctionParams() {
    val vars = cfg.f.parameters.map(::Variable).withIndex()
    val integral =
        vars.filter { X64Target.registerClassOf(it.value.type) == X64RegisterClass.INTEGER }
    val sse = vars.filter { X64Target.registerClassOf(it.value.type) == X64RegisterClass.SSE }
    for ((intVar, regName) in integral.zip(intArgRegNames)) {
      val type = intVar.value.type.unqualify().normalize()
      val targetRegister = PhysicalRegister(X64Target.registerByName(regName), type)
      parameterMap[ParameterReference(intVar.index, type)] = targetRegister
    }
    for ((sseVar, regName) in sse.zip(sseArgRegNames)) {
      val type = sseVar.value.type.unqualify().normalize()
      val targetRegister = PhysicalRegister(X64Target.registerByName(regName), type)
      parameterMap[ParameterReference(sseVar.index, type)] = targetRegister
    }
    val stackVars = vars - integral.take(intArgRegNames.size) - sse.take(sseArgRegNames.size)
    cfg.insertSpillCode(stackVars.map { it.value.id })
    // FIXME: deal with X87 here
    var paramOffset = INITIAL_MEM_ARG_OFFSET
    for ((index, variable) in stackVars) {
      val type = variable.type.unqualify().normalize()
      val stackVar = StackVariable(variable.tid)
      parameterMap[ParameterReference(index, type)] = stackVar
      stackParamOffsets[stackVar.id] =
          StackValue(StackSlot(stackVar, target.machineTargetData), paramOffset)
      val varSize = target.machineTargetData.sizeOf(type)
      paramOffset += varSize.coerceAtLeast(EIGHTBYTE) alignTo EIGHTBYTE
    }
  }

  /**
   * System V ABI: 3.2.1, figure 3.3
   */
  override fun genFunctionPrologue(alloc: AllocationResult): List<X64Instruction> {
    val prologue = mutableListOf<MachineInstruction>()
    prologue += push.match(rbp)
    prologue += mov.match(rbp, rsp)
    // FIXME: deal with MEMORY class function arguments (see paramSourceMap)
    // FIXME: save callee-saved registers
    finalStackSizeBytes = alloc.stackSlots.sumBy { it.sizeBytes } alignTo ALIGNMENT_BYTES
    // See if we can use the red zone
    if (isLeaf && finalStackSizeBytes <= RED_ZONE_BYTES) {
      // FIXME: we could avoid setting up the stack frame entirely in this case,
      //   but for that we need rsp-relative stack values
    } else {
      prologue += sub.match(rsp, IntConstant(finalStackSizeBytes, SignedIntType))
    }
    return prologue.map { miToX64Instr(it, alloc, emptyMap()) }
  }

  /**
   * System V ABI: 3.2.1, figure 3.3
   */
  override fun genFunctionEpilogue(alloc: AllocationResult): List<X64Instruction> {
    val epilogue = mutableListOf<MachineInstruction>()
    // FIXME: restore callee-saved registers
    if (isLeaf && finalStackSizeBytes <= RED_ZONE_BYTES) {
      // FIXME: we could avoid setting up the stack frame entirely in this case,
      //   but for that we need rsp-relative stack values
    } else {
      epilogue += add.match(rsp, IntConstant(finalStackSizeBytes, SignedIntType))
    }
    epilogue += leave.match()
    epilogue += ret.match()
    return epilogue.map { miToX64Instr(it, alloc, emptyMap()) }
  }

  override fun applyAllocation(alloc: AllocationResult): Map<BasicBlock, List<X64Instruction>> {
    var currentStackOffset = 0
    val stackOffsets = alloc.allocations.values.filterIsInstance<StackSlot>().associateWith {
      val offset = currentStackOffset
      currentStackOffset += it.sizeBytes
      offset
    }
    val asm = mutableMapOf<BasicBlock, List<X64Instruction>>()
    for (block in cfg.postOrderNodes) {
      val result = blockToX64Instr(block, alloc, stackOffsets)
      asm += block to result
    }
    asm += returnBlock to emptyList()
    return asm
  }

  /**
   * System V ABI: 3.2.2, page 18; 3.2.3, page 20
   */
  override fun createCall(
      result: LoadableValue,
      callable: IRValue,
      args: List<IRValue>
  ): List<MachineInstruction> {
    isLeaf = false
    val selected = mutableListOf<MachineInstruction>()
    // Save caller-saved registers
    selected += dummyCallSave.match()
    val intArgs = args
        .filter { target.registerClassOf(it.type) == X64RegisterClass.INTEGER }
        .withIndex()
    val fltArgs = args
        .filter { target.registerClassOf(it.type) == X64RegisterClass.SSE }
        .withIndex()
    // Move register arguments in place
    val intRegArgs = intArgs.take(intArgRegNames.size)
    val fltRegArgs = fltArgs.take(sseArgRegNames.size)
    val registerArguments = intRegArgs.map { intArgRegNames[it.index] to it.value } +
        fltRegArgs.map { sseArgRegNames[it.index] to it.value }
    for ((physRegName, arg) in registerArguments) {
      val argType = arg.type.unqualify().normalize()
      val reg = PhysicalRegister(X64Target.registerByName(physRegName), argType)
      selected += matchTypedMov(reg, arg)
    }
    // Push stack arguments in order, aligned
    val intStackArgs = intArgs.drop(intArgRegNames.size)
    val fltStackArgs = fltArgs.drop(sseArgRegNames.size)
    val stackArgs = (intStackArgs + fltStackArgs).sortedBy { it.index }
    val stackArgsSize = stackArgs.sumBy {
      target.machineTargetData.sizeOf(it.value.type).coerceAtLeast(EIGHTBYTE)
    }
    if (stackArgsSize % ALIGNMENT_BYTES != 0) {
      selected += sub.match(rsp, IntConstant(EIGHTBYTE, SignedIntType))
    }
    for (stackArg in stackArgs.map { it.value }.asReversed()) {
      // FIXME: if MemoryLocation would support stuff like [rsp + 24] we could avoid this sub
      selected += sub.match(rsp, IntConstant(EIGHTBYTE, SignedIntType))
      selected += pushOnStack(stackArg)
    }
    // The ABI says al must contain the number of vector arguments
    selected += mov.match(al, IntConstant(fltRegArgs.size, SignedCharType))
    selected += call.match(callable)
    selected += getCallResult(result)
    // Clean up pushed arguments
    val cleanStackSize = stackArgsSize + stackArgsSize / ALIGNMENT_BYTES
    selected += add.match(rsp, IntConstant(cleanStackSize, SignedIntType))
    // Restore caller-saved registers
    selected += dummyCallRestore.match()
    return selected
  }

  /**
   * System V ABI: "Returning of Values", page 24
   */
  private fun getCallResult(result: LoadableValue): List<MachineInstruction> {
    // When the call returns void, do nothing here
    if (result.type is VoidType) return emptyList()
    val rc = target.registerClassOf(result.type)
    if (rc is Memory) TODO("some weird thing with caller storage in rdi, see ABI")
    require(rc is X64RegisterClass)
    if (rc == X64RegisterClass.X87) TODO("deal with x87")
    val returnRegisterName = when (rc) {
      X64RegisterClass.INTEGER -> "rax"
      X64RegisterClass.SSE -> "xmm0"
      else -> logger.throwICE("Unreachable")
    }
    val callResult = PhysicalRegister(X64Target.registerByName(returnRegisterName),
        result.type.unqualify().normalize())
    return listOf(matchTypedMov(result, callResult))
  }

  override fun createReturn(retVal: LoadableValue): List<MachineInstruction> {
    val selected = mutableListOf<MachineInstruction>()
    val retType = X64Target.registerClassOf(retVal.type)
    if (retType == Memory) {
      TODO("deal with this")
    } else {
      require(retType is X64RegisterClass)
      if (retType == X64RegisterClass.INTEGER) {
        val rax = PhysicalRegister(X64Target.registerByName("rax"),
            retVal.type.unqualify().normalize())
        selected += mov.match(rax, retVal)
      } else {
        TODO("deal with this")
      }
    }
    return selected
  }

  private fun pushOnStack(value: IRValue): List<MachineInstruction> {
    val selected = mutableListOf<MachineInstruction>()
    // If the thing is in memory, move it to a register, to avoid an illegal mem to mem operation
    val actualValue = if (value is MemoryLocation) {
      val resultType = (value.ptr.type as PointerType).referencedType
      val result = VirtualRegister(cfg.registerIds(), resultType)
      selected += matchTypedMov(result, value)
      result
    } else {
      value
    }
    val registerClass = target.registerClassOf(actualValue.type)
    if (registerClass is Memory) {
      TODO("move memory argument to stack, push for ints, and" +
          " sub rsp, 16/movq [rsp], XMMi")
    }
    require(registerClass is X64RegisterClass)
    selected += when (registerClass) {
      X64RegisterClass.INTEGER -> pushInteger(actualValue)
      X64RegisterClass.SSE -> pushSSE(actualValue)
      X64RegisterClass.X87 -> TODO("push x87")
    }
    return selected
  }

  private fun pushInteger(value: IRValue): MachineInstruction {
    // 64-bit values can be directly pushed on the stack
    // FIXME: this is wrong, we space is already allocated, don't use push
//    if (target.machineTargetData.sizeOf(value.type) == EIGHTBYTE) {
//      return push.match(value)
//    }
    // Space is already allocated, move thing to [rsp]
    // Use the value's type for rsp, because the value might be < 8 bytes, and the mov should match
    val topOfStack = MemoryLocation(PhysicalRegister(rsp.reg, PointerType(value.type, emptyList())))
    return matchTypedMov(topOfStack, value)
  }

  // FIXME: redundant code
  private fun pushSSE(value: IRValue): MachineInstruction {
    val topOfStack = MemoryLocation(PhysicalRegister(rsp.reg, PointerType(value.type, emptyList())))
    return matchTypedMov(topOfStack, value)
  }

  private fun blockToX64Instr(
      block: BasicBlock,
      alloc: AllocationResult,
      stackOffsets: Map<StackSlot, Int>
  ): List<X64Instruction> {
    val result = mutableListOf<X64Instruction>()
    val currentInUse = mutableListOf<Pair<MachineRegister, TypeName>>()
    currentInUse += cfg.liveIns.getValue(block).map { alloc.allocations.getValue(it) to it.type }
    var lastSavedForCall: List<Pair<MachineRegister, TypeName>>? = null
    for (mi in alloc.partial.getValue(block)) {
      // Track in-use registers
      currentInUse -= mi.uses
          .filter { it !is PhysicalRegister && it !is StackVariable && it !is MemoryLocation }
          .filter { alloc.lastUses[it] == Label(block, mi.irLabelIndex) }
          .map { alloc.allocations.getValue(it) to it.type }
      currentInUse += mi.defs
          .filter { it !is PhysicalRegister && it !is StackVariable && it !is MemoryLocation }
          .map { alloc.allocations.getValue(it) to it.type }

      if (mi.template in dummyUse) continue
      if (mi.template in dummyCallSave) {
        lastSavedForCall = currentInUse.filterNot { target.isPreservedAcrossCalls(it.first) }
        result += saveRegisters(lastSavedForCall).map { miToX64Instr(it, alloc, emptyMap()) }
        continue
      }
      if (mi.template in dummyCallRestore) {
        requireNotNull(lastSavedForCall) {
          "Dummy call restore created without matching dummy call save"
        }
        result += restoreRegisters(lastSavedForCall.asReversed())
            .map { miToX64Instr(it, alloc, emptyMap()) }
        lastSavedForCall = null
        continue
      }
      // Add current instruction
      result += miToX64Instr(mi, alloc, stackOffsets)
    }
    return result
  }

  private fun miToX64Instr(
      mi: MachineInstruction,
      alloc: AllocationResult,
      stackOffsets: Map<StackSlot, Int>
  ): X64Instruction {
    require(mi.template is X64InstrTemplate)
    val ops = mi.operands.map { operandToX64(it, alloc, stackOffsets) }
    return X64Instruction(mi.template, ops)
  }

  private fun operandToX64(
      value: IRValue,
      alloc: AllocationResult,
      stackOffsets: Map<StackSlot, Int>
  ): X64Value {
    if (value is ConstantValue) return ImmediateValue(value)
    if (value is PhysicalRegister) {
      return RegisterValue(value.reg, X64Target.machineTargetData.sizeOf(value.type))
    }
    if (value is Variable && value.isUndefined) {
      // Undefined behaviour, do whatever
      val reg = (target.registers - target.forbidden)
          .first { reg -> reg.valueClass == target.registerClassOf(value.type) }
      return RegisterValue(reg, X64Target.machineTargetData.sizeOf(value.type))
    }
    val unwrapped = if (value is MemoryLocation) value.ptr else value
    val machineRegister =
        if (unwrapped is PhysicalRegister) unwrapped.reg
        else alloc.allocations.getValue(unwrapped)
    if (value is MemoryLocation && value.ptr !is StackVariable) {
      return MemoryValue(machineRegister, X64Target.machineTargetData.sizeOf(value.type))
    }
    if (machineRegister !is StackSlot) {
      return RegisterValue(machineRegister, X64Target.machineTargetData.sizeOf(value.type))
    }
    return if (machineRegister.id in stackParamOffsets) {
      stackParamOffsets.getValue(machineRegister.id)
    } else {
      StackValue.fromFrame(machineRegister, stackOffsets.getValue(machineRegister))
    }
  }

  private fun saveRegisters(
      toSave: List<Pair<MachineRegister, TypeName>>
  ): List<MachineInstruction> {
    val result = mutableListOf<MachineInstruction>()
    val size = toSave.sumBy { target.machineTargetData.sizeOf(it.second) }
    if (size % ALIGNMENT_BYTES != 0) {
      result += sub.match(rsp, IntConstant((size alignTo ALIGNMENT_BYTES) - size, SignedIntType))
    }
    for ((register, type) in toSave) {
      result += sub.match(rsp, IntConstant(target.machineTargetData.sizeOf(type), SignedIntType))
      result += pushOnStack(PhysicalRegister(register, type))
    }
    return result
  }

  private fun restoreRegisters(
      toRestore: List<Pair<MachineRegister, TypeName>>
  ): List<MachineInstruction> {
    val result = mutableListOf<MachineInstruction>()
    val size = toRestore.sumBy { target.machineTargetData.sizeOf(it.second) }
    for ((register, type) in toRestore) {
      result += popRegister(register, type)
    }
    if (size % ALIGNMENT_BYTES != 0) {
      result += add.match(rsp, IntConstant((size alignTo ALIGNMENT_BYTES) - size, SignedIntType))
    }
    return result
  }

  private fun popRegister(popTo: MachineRegister, type: TypeName): List<MachineInstruction> {
    require(popTo.valueClass is X64RegisterClass)
    val result = mutableListOf<MachineInstruction>()
    val topOfStack = MemoryLocation(PhysicalRegister(rsp.reg, PointerType(type, emptyList())))
    when (popTo.valueClass) {
      X64RegisterClass.SSE, X64RegisterClass.INTEGER -> {
        result += matchTypedMov(PhysicalRegister(popTo, type), topOfStack)
      }
      X64RegisterClass.X87 -> TODO("pop X87 thing")
    }
    result += add.match(rsp, IntConstant(target.machineTargetData.sizeOf(type), SignedIntType))
    return result
  }

  companion object {
    private val logger = LogManager.getLogger()

    private val al = PhysicalRegister(X64Target.registerByName("rax"), SignedCharType)
    private val rbp = PhysicalRegister(
        X64Target.registerByName("rbp"), PointerType(UnsignedLongType, emptyList()))
    private val rsp = PhysicalRegister(
        X64Target.registerByName("rsp"), PointerType(UnsignedLongType, emptyList()))

    private const val EIGHTBYTE = 8
    private const val INITIAL_MEM_ARG_OFFSET = 16
    private const val ALIGNMENT_BYTES = 16
    private const val RED_ZONE_BYTES = 128

    private infix fun Int.alignTo(alignment: Int): Int {
      return if (this % alignment != 0) this + alignment - this % alignment else this
    }

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
