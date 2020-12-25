package slak.ckompiler.backend.x64

import slak.ckompiler.AtomicId
import slak.ckompiler.IdCounter
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*
import slak.ckompiler.backend.x64.X64Target.Companion.ALIGNMENT_BYTES
import slak.ckompiler.backend.x64.X64Target.Companion.EIGHTBYTE
import slak.ckompiler.backend.x64.X64Target.Companion.INITIAL_MEM_ARG_OFFSET
import slak.ckompiler.backend.x64.X64Target.Companion.RED_ZONE_BYTES
import slak.ckompiler.backend.x64.X64Target.Companion.alignTo
import slak.ckompiler.backend.x64.X64Target.Companion.intArgRegNames
import slak.ckompiler.backend.x64.X64Target.Companion.sseArgRegNames
import slak.ckompiler.parser.PointerType
import slak.ckompiler.parser.SignedIntType
import slak.ckompiler.parser.UnsignedLongType
import kotlin.properties.Delegates

class X64FunAssembler(private val target: X64Target, val cfg: CFG, val stackSlotIds: IdCounter) : FunctionAssembler {
  /**
   * If this is set to true, it means this function does not make any calls (ie is a leaf function).
   * This is useful for knowing whether to use the red zone or not.
   *
   * System V ABI: page 19
   */
  var isLeaf = true

  /** The starting offset for the stack; the prologue can put things on the stack before the [StackSlot]s. */
  private var stackBeginOffset = 0

  /** Set of registers saved, that will need to be restored. */
  private lateinit var calleeSaved: Set<X64Register>

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
   * id of the relevant [Variable]/[StackVariable]/[FullVariableSlot] to its `[rbp + 0x123]` [MemoryValue].
   */
  private val stackParamOffsets = mutableMapOf<AtomicId, MemoryValue>()

  private val rbp = PhysicalRegister(
      target.registerByName("rbp"), PointerType(UnsignedLongType, emptyList()))
  private val rsp = PhysicalRegister(
      target.registerByName("rsp"), PointerType(UnsignedLongType, emptyList()))

  init {
    mapFunctionParams()
  }

  /** @see parameterMap */
  private fun mapFunctionParams() {
    val vars = cfg.f.parameters.map(::Variable).withIndex()
    val integral =
        vars.filter { target.registerClassOf(it.value.type) == X64RegisterClass.INTEGER }
    val sse = vars.filter { target.registerClassOf(it.value.type) == X64RegisterClass.SSE }
    for ((intVar, regName) in integral.zip(intArgRegNames)) {
      val type = intVar.value.type.unqualify().normalize()
      val targetRegister = PhysicalRegister(target.registerByName(regName), type)
      parameterMap[ParameterReference(intVar.index, type)] = targetRegister
    }
    for ((sseVar, regName) in sse.zip(sseArgRegNames)) {
      val type = sseVar.value.type.unqualify().normalize()
      val targetRegister = PhysicalRegister(target.registerByName(regName), type)
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
          MemoryValue.frameAbs(FullVariableSlot(stackVar, stackSlotIds(), target.machineTargetData), paramOffset)
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
    // This cast cannot fail
    @Suppress("UNCHECKED_CAST")
    calleeSaved = alloc.allocations.values
        .filterIsInstanceTo(mutableSetOf<X64Register>())
        .intersect(target.calleeSaved) as Set<X64Register>
    stackBeginOffset = calleeSaved.sumBy { it.sizeBytes }
    finalStackSizeBytes = (stackBeginOffset + alloc.stackSlots.sumBy { it.sizeBytes }) alignTo ALIGNMENT_BYTES
    // See if we can use the red zone
    if (target.options.useRedZone && isLeaf && finalStackSizeBytes <= RED_ZONE_BYTES) {
      // FIXME: we could avoid setting up the stack frame entirely in this case,
      //   but for that we need rsp-relative stack values
    } else {
      prologue += sub.match(rsp, IntConstant(finalStackSizeBytes, SignedIntType))
    }
    val registerSaves = mutableListOf<X64Instruction>()
    for ((idx, reg) in calleeSaved.withIndex()) {
      val toSave = RegisterValue(reg, EIGHTBYTE)
      val stackLoc = MemoryValue(EIGHTBYTE, base = RegisterValue(rbp), displacement = -(idx + 1) * EIGHTBYTE)
      registerSaves += mov.matchAsm(stackLoc, toSave)
    }
    return prologue.map { miToX64Instr(it, alloc, emptyMap()) } + registerSaves
  }

  /**
   * System V ABI: 3.2.1, figure 3.3
   */
  override fun genFunctionEpilogue(alloc: AllocationResult): List<X64Instruction> {
    val epilogue = mutableListOf<X64Instruction>()
    if (target.options.useRedZone && isLeaf && finalStackSizeBytes <= RED_ZONE_BYTES) {
      // FIXME: we could avoid setting up the stack frame entirely in this case,
      //   but for that we need rsp-relative stack values
    } else {
      val stackSlotSize = IntConstant(finalStackSizeBytes - stackBeginOffset, SignedIntType)
      epilogue += add.matchAsm(RegisterValue(rsp), ImmediateValue(stackSlotSize))
    }
    var savedOffset = stackBeginOffset
    for (reg in calleeSaved.reversed()) {
      epilogue += restoreRegister(reg, savedOffset)
      savedOffset -= EIGHTBYTE
    }
    epilogue += leave.matchAsm()
    epilogue += ret.matchAsm()
    return epilogue
  }

  private fun restoreRegister(register: X64Register, offset: Int): X64Instruction {
    val regVal = RegisterValue(register, EIGHTBYTE)
    // Can't directly pop xmm regs, so always generate a move
    return if (target.options.useRedZone || register.valueClass == X64RegisterClass.SSE) {
      val mem = MemoryValue(EIGHTBYTE, base = RegisterValue(rbp), displacement = -offset)
      target.matchAsmMov(regVal, mem)
    } else {
      pop.matchAsm(regVal)
    }
  }

  override fun applyAllocation(alloc: AllocationResult): Map<AtomicId, List<X64Instruction>> {
    var currentStackOffset = stackBeginOffset
    val stackOffsets = alloc.allocations.values.filterIsInstance<StackSlot>().associateWith {
      val offset = currentStackOffset
      currentStackOffset += it.sizeBytes
      offset
    }
    val asm = mutableMapOf<AtomicId, List<X64Instruction>>()
    for (blockId in alloc.graph.blocks) {
      val result = blockToX64Instr(blockId, alloc, stackOffsets)
      asm += blockId to result
    }
    return asm
  }

  private fun blockToX64Instr(
      blockId: AtomicId,
      alloc: AllocationResult,
      stackOffsets: Map<StackSlot, Int>
  ): List<X64Instruction> {
    val result = mutableListOf<X64Instruction>()
    for (mi in alloc.graph[blockId]) {
      if (mi.template in dummyUse) continue

      // Add linked before (eg cdq before div)
      for ((linkedMi) in mi.links.filter { it.pos == LinkPosition.BEFORE }) {
        result += miToX64Instr(linkedMi, alloc, stackOffsets)
      }
      // Add current instruction
      result += miToX64Instr(mi, alloc, stackOffsets)
      // Add linked after
      for ((linkedMi) in mi.links.filter { it.pos == LinkPosition.AFTER }) {
        result += miToX64Instr(linkedMi, alloc, stackOffsets)
      }
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
    val typeSize = target.machineTargetData.sizeOf(value.type)
    if (value is PhysicalRegister) {
      return RegisterValue(value.reg, typeSize)
    }
    if (value is LoadableValue && value.isUndefined) {
      // Undefined behaviour, can do whatever; we pick the first register of the correct class
      // This is useful, because all undefined things will be "in" the same register, which will create a bunch of
      // "mov rax, rax"-type instructions that are easy to remove
      val reg = (target.registers - target.forbidden)
          .first { reg -> reg.valueClass == target.registerClassOf(value.type) }
      return RegisterValue(reg, typeSize)
    }
    val unwrapped = if (value is MemoryLocation) value.ptr else value
    val machineRegister = when (unwrapped) {
      is PhysicalRegister -> unwrapped.reg
      else -> alloc.allocations.getValue(unwrapped)
    }
    if (value is MemoryLocation && value.ptr !is StackVariable && value.ptr !is StackValue) {
      return MemoryValue(typeSize, RegisterValue(machineRegister, target.machineTargetData.sizeOf(unwrapped.type)))
    }
    if (machineRegister !is StackSlot) {
      return RegisterValue(machineRegister, typeSize)
    }
    return if (machineRegister.id in stackParamOffsets) {
      stackParamOffsets.getValue(machineRegister.id)
    } else {
      MemoryValue.inFrame(machineRegister, stackOffsets.getValue(machineRegister))
    }
  }
}
