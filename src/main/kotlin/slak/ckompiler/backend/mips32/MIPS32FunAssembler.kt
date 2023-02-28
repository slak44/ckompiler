package slak.ckompiler.backend.mips32

import slak.ckompiler.AtomicId
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*

class MIPS32FunAssembler(val target: MIPS32Target) : FunctionAssembler<MIPS32Instruction> {
  override val parameterMap: Map<ParameterReference, IRValue>
    get() = TODO("not implemented")

  private val sp = target.ptrRegisterByName("\$sp")
  private val fp = target.ptrRegisterByName("\$fp")
  private val ra = target.ptrRegisterByName("\$ra")

  override fun genFunctionPrologue(alloc: AllocationResult): List<MIPS32Instruction> {
    val prologue = mutableListOf<MachineInstruction>()
    prologue += addiu.match(sp, sp, -MIPS32Generator.wordSizeConstant)
    prologue += sw.match(fp, MemoryLocation(sp))

    return prologue.map { mi -> miToMIPS32Instruction(mi, alloc) }
  }

  override fun genFunctionEpilogue(alloc: AllocationResult): List<MIPS32Instruction> {
    val epilogue = mutableListOf<MachineInstruction>()
    epilogue += lw.match(sp, MemoryLocation(fp))
    epilogue += addiu.match(sp, sp, MIPS32Generator.wordSizeConstant)
    epilogue += jr.match(ra)

    return epilogue.map { mi -> miToMIPS32Instruction(mi, alloc) }
  }

  override fun applyAllocation(alloc: AllocationResult): Map<AtomicId, List<MIPS32Instruction>> {
    val asm = mutableMapOf<AtomicId, List<MIPS32Instruction>>()
    for (blockId in alloc.graph.blocks) {
      val result = convertBlockInstructions(blockId, alloc) { mi ->
        miToMIPS32Instruction(mi, alloc)
      }
      asm += blockId to result
    }
    return asm
  }

  private fun miToMIPS32Instruction(mi: MachineInstruction, alloc: AllocationResult): MIPS32Instruction {
    require(mi.template is MIPS32InstructionTemplate)
    val ops = mi.operands.map { operandToMIPS32(it, alloc) }
    return MIPS32Instruction(mi.template, ops)
  }

  private fun operandToMIPS32(value: IRValue, alloc: AllocationResult): MIPS32Value {
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
    TODO("function parameters")
  }
}
