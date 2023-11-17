package slak.ckompiler.backend.mips32

import slak.ckompiler.AtomicId
import slak.ckompiler.IdCounter
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*

class MIPS32FunAssembler(val cfg: CFG, val target: MIPS32Target, val stackSlotIds: IdCounter) : FunctionAssembler<MIPS32Instruction> {
  override val parameterMap = mutableMapOf<ParameterReference, IRValue>()

  private val sp = MIPS32RegisterValue(target.ptrRegisterByName("\$sp"))
  private val fp = MIPS32RegisterValue(target.ptrRegisterByName("\$fp"))
  private val ra = MIPS32RegisterValue(target.ptrRegisterByName("\$ra"))
  private val stackTwoWordsSize = MIPS32Generator.wordSizeConstant * 2

  /**
   * Stores actual stack values for function parameters passed on the stack.
   * On MIPS, the arguments passed in registers also have assigned stack slots, but they are not populated by default.
   * This maps the id of the relevant [Variable]/[StackVariable]/[FullVariableSlot] to its `0x123($fp)` [MIPS32MemoryValue].
   */
  private val stackParamOffsets = mutableMapOf<AtomicId, MIPS32MemoryValue>()

  /** The starting offset for the stack; the prologue can put things on the stack before the [StackSlot]s. */
  private var stackBeginOffset = 0

  /** Set of registers saved, that will need to be restored. */
  private lateinit var calleeSaved: Set<MIPS32Register>

  init {
    val vars = cfg.functionParameters.map(::Variable).withIndex()
    val integral = vars.filter { target.registerClassOf(it.value.type) == MIPS32RegisterClass.INTEGER }
    for ((intVar, reg) in integral.zip(target.intArgRegs)) {
      val type = intVar.value.type.unqualify().normalize()
      parameterMap[ParameterReference(intVar.index, type)] = PhysicalRegister(reg, type)
    }
    val stackVars = vars - integral.take(target.intArgRegs.size).toSet()
    cfg.insertSpillCode(stackVars.map { it.value.identityId })
    var paramOffset = INITIAL_MEM_ARG_OFFSET + integral.size * MIPS32Target.WORD
    for ((index, variable) in stackVars) {
      val type = variable.type.unqualify().normalize()
      val stackVar = StackVariable(variable.tid)
      parameterMap[ParameterReference(index, type)] = stackVar
      val fullVariableSlot = FullVariableSlot(stackVar, stackSlotIds(), target.machineTargetData)
      stackParamOffsets[stackVar.id] = MIPS32MemoryValue(fullVariableSlot.sizeBytes, fp, paramOffset)
      val varSize = target.machineTargetData.sizeOf(type)
      paramOffset += varSize.coerceAtLeast(MIPS32Target.WORD) alignTo MIPS32Target.WORD
    }
  }

  override fun genFunctionPrologue(alloc: AllocationResult): List<MIPS32Instruction> {
    val frameSetup = mutableListOf<MIPS32Instruction>()

    frameSetup += addi.matchAsm(sp, sp, MIPS32ImmediateValue(-stackTwoWordsSize))
    frameSetup += sw.matchAsm(fp, MIPS32MemoryValue(sp.size, sp, 0))
    frameSetup += sw.matchAsm(ra, MIPS32MemoryValue(sp.size, sp, MIPS32Target.WORD))
    frameSetup += move.matchAsm(fp, sp)

    calleeSaved = alloc.allocations.values
        .filterIsInstanceTo(mutableSetOf<MIPS32Register>())
        .intersect(target.calleeSaved)
    stackBeginOffset = calleeSaved.sumOf { it.sizeBytes }

    for ((idx, reg) in calleeSaved.withIndex()) {
      val toSave = MIPS32RegisterValue(reg, MIPS32Target.WORD)
      val stackLoc = MIPS32MemoryValue(MIPS32Target.WORD, fp, -(idx + 1) * MIPS32Target.WORD)
      frameSetup += sw.matchAsm(toSave, stackLoc)
    }

    return frameSetup
  }

  override fun genFunctionEpilogue(alloc: AllocationResult): List<MIPS32Instruction> {
    val epilogue = mutableListOf<MIPS32Instruction>()

    var savedOffset = stackBeginOffset
    for (reg in calleeSaved.reversed()) {
      epilogue += restoreRegister(reg, savedOffset)
      savedOffset -= MIPS32Target.WORD
    }

    epilogue += move.matchAsm(sp, fp)
    epilogue += lw.matchAsm(fp, MIPS32MemoryValue(sp.size, sp, 0))
    epilogue += lw.matchAsm(ra, MIPS32MemoryValue(sp.size, sp, MIPS32Target.WORD))
    epilogue += addiu.matchAsm(sp, sp, MIPS32ImmediateValue(stackTwoWordsSize))
    epilogue += jr.matchAsm(ra)

    return epilogue
  }

  private fun restoreRegister(reg: MIPS32Register, offset: Int): MIPS32Instruction = when (reg.valueClass) {
    MIPS32RegisterClass.INTEGER -> {
      lw.matchAsm(MIPS32RegisterValue(reg, MIPS32Target.WORD), MIPS32MemoryValue(fp.size, fp, -offset))
    }
    MIPS32RegisterClass.FLOAT -> TODO()
  }

  override fun applyAllocation(alloc: AllocationResult): Map<AtomicId, List<MIPS32Instruction>> {
    val asm = mutableMapOf<AtomicId, List<MIPS32Instruction>>()

    val stackOffsets = alloc.generateStackSlotOffsets(0)
    val skipDummies = { template: InstructionTemplate<OperandTemplate> ->
      template.isDummy && template !in stackAddrMove
    }

    for (blockId in alloc.graph.blocks) {
      val result = convertBlockInstructions(blockId, alloc, skipDummies) { mi ->
        miToMIPS32Instruction(mi, alloc, stackOffsets)
      }
      asm += blockId to result
    }
    return asm
  }

  private fun miToMIPS32Instruction(mi: MachineInstruction, alloc: AllocationResult, stackOffsets: Map<StackSlot, Int>): MIPS32Instruction {
    require(mi.template is MIPS32InstructionTemplate)

    val ops = mi.operands.map { operandToMIPS32(it, alloc, stackOffsets) }
    check(!(mi.template in move && ops.any { it is MIPS32MemoryValue })) {
      "Attempting a move instruction with memory operands: $ops"
    }

    if (mi.template in stackAddrMove) {
      val stackAddr = ops[1]
      check(stackAddr is MIPS32MemoryValue) { "This dummy instruction must move a memory value" }
      val (_, base, displacement) = stackAddr
      val asConstant = IntConstant(displacement, target.machineTargetData.ptrDiffType)
      return addi.matchAsm(ops[0], base, MIPS32ImmediateValue(asConstant))
    }

    return MIPS32Instruction(mi.template, ops)
  }

  private fun operandToMIPS32(value: IRValue, alloc: AllocationResult, stackOffsets: Map<StackSlot, Int>): MIPS32Value {
    if (value is ConstantValue) return MIPS32ImmediateValue(value)
    val typeSize = target.machineTargetData.sizeOf(value.type)
    if (value is PhysicalRegister) {
      return MIPS32RegisterValue(value.reg, typeSize)
    }
    if (value is LoadableValue && value.isUndefined) {
      val reg = target.getUndefinedRegisterFor(value)
      return MIPS32RegisterValue(reg, typeSize)
    }
    val unwrapped = when (value) {
      is MemoryLocation -> value.ptr
      is DerefStackValue -> value.stackValue
      else -> value
    }
    val machineRegister = when (unwrapped) {
      is PhysicalRegister -> unwrapped.reg
      else -> alloc.allocations.getValue(unwrapped)
    }
    if (value is MemoryLocation && value.ptr !is StackVariable && value.ptr !is StackValue) {
      return MIPS32MemoryValue(typeSize, MIPS32RegisterValue(machineRegister, target.machineTargetData.sizeOf(unwrapped.type)), 0)
    }
    if (machineRegister !is StackSlot) {
      return MIPS32RegisterValue(machineRegister, typeSize)
    }
    return if (machineRegister is FullVariableSlot && machineRegister.value.id in stackParamOffsets) {
      stackParamOffsets.getValue(machineRegister.value.id)
    } else {
      MIPS32MemoryValue.inFrame(fp, machineRegister, stackOffsets.getValue(machineRegister))
    }
  }

  companion object {
    const val INITIAL_MEM_ARG_OFFSET = 0
  }
}
