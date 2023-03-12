package slak.ckompiler.backend.mips32

import slak.ckompiler.AtomicId
import slak.ckompiler.IdCounter
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*

class MIPS32FunAssembler(val cfg: CFG, val target: MIPS32Target, val stackSlotIds: IdCounter) : FunctionAssembler<MIPS32Instruction> {
  override val parameterMap = mutableMapOf<ParameterReference, IRValue>()

  private val sp = target.ptrRegisterByName("\$sp")
  private val fp = target.ptrRegisterByName("\$fp")
  private val ra = target.ptrRegisterByName("\$ra")

  /**
   * Stores actual stack values for function parameters passed on the stack.
   * On MIPS, the arguments passed in registers also have assigned stack slots, but they are not populated by default.
   * This maps the id of the relevant [Variable]/[StackVariable]/[FullVariableSlot] to its `0x123($fp)` [MIPS32MemoryValue].
   */
  private val stackParamOffsets = mutableMapOf<AtomicId, MIPS32MemoryValue>()

  init {
    val vars = cfg.f.parameters.map(::Variable).withIndex()
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
      stackParamOffsets[stackVar.id] = MIPS32MemoryValue(fullVariableSlot.sizeBytes, MIPS32RegisterValue(fp), paramOffset)
      val varSize = target.machineTargetData.sizeOf(type)
      paramOffset += varSize.coerceAtLeast(MIPS32Target.WORD) alignTo MIPS32Target.WORD
    }
  }

  override fun genFunctionPrologue(alloc: AllocationResult): List<MIPS32Instruction> {
    val prologue = mutableListOf<MachineInstruction>()
    prologue += addiu.match(sp, sp, -MIPS32Generator.wordSizeConstant)
    prologue += sw.match(fp, MemoryLocation(sp))
    prologue += matchTypedCopy(fp, sp)

    return prologue.map { mi -> miToMIPS32Instruction(mi, alloc, emptyMap()) }
  }

  override fun genFunctionEpilogue(alloc: AllocationResult): List<MIPS32Instruction> {
    val epilogue = mutableListOf<MachineInstruction>()
    epilogue += lw.match(sp, MemoryLocation(fp))
    epilogue += addiu.match(sp, sp, MIPS32Generator.wordSizeConstant)
    epilogue += jr.match(ra)

    return epilogue.map { mi -> miToMIPS32Instruction(mi, alloc, emptyMap()) }
  }

  override fun applyAllocation(alloc: AllocationResult): Map<AtomicId, List<MIPS32Instruction>> {
    val asm = mutableMapOf<AtomicId, List<MIPS32Instruction>>()

    val stackOffsets = alloc.generateStackSlotOffsets(0)

    for (blockId in alloc.graph.blocks) {
      val result = convertBlockInstructions(blockId, alloc) { mi ->
        miToMIPS32Instruction(mi, alloc, stackOffsets)
      }
      asm += blockId to result
    }
    return asm
  }

  private fun miToMIPS32Instruction(mi: MachineInstruction, alloc: AllocationResult, stackOffsets: Map<StackSlot, Int>): MIPS32Instruction {
    require(mi.template is MIPS32InstructionTemplate)
    val ops = mi.operands.map { operandToMIPS32(it, alloc, stackOffsets) }
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
      MIPS32MemoryValue.inFrame(MIPS32RegisterValue(fp), machineRegister, stackOffsets.getValue(machineRegister))
    }
  }

  companion object {
    const val INITIAL_MEM_ARG_OFFSET = 0
  }
}
