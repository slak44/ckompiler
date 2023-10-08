package slak.ckompiler.backend.x64

import io.github.oshai.kotlinlogging.KotlinLogging
import slak.ckompiler.IdCounter
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*
import slak.ckompiler.backend.x64.X64Target.Companion.ALIGNMENT_BYTES
import slak.ckompiler.backend.x64.X64Target.Companion.EIGHTBYTE
import slak.ckompiler.backend.x64.X64Target.Companion.intArgRegNames
import slak.ckompiler.backend.x64.X64Target.Companion.sseArgRegNames
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

private val logger = KotlinLogging.logger {}

class X64CallGenerator(val target: X64Target, val registerIds: IdCounter) : FunctionCallGenerator {
  private val al = PhysicalRegister(target.registerByName("rax"), SignedCharType)
  private val rsp = PhysicalRegister(target.registerByName("rsp"), PointerType(UnsignedLongType, emptyList()))

  /**
   * System V ABI: 3.2.2, page 18; 3.2.3, page 20
   */
  override fun createCall(result: LoadableValue, callable: IRValue, args: List<IRValue>): List<MachineInstruction> {
    val before = mutableListOf<MachineInstruction>()
    val beforeLinked = mutableListOf<MachineInstruction>()
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
    val argConstraints = mutableListOf<Constraint>()
    for ((physRegName, arg) in registerArguments) {
      val argType = arg.type.unqualify().normalize()
      val reg = target.registerByName(physRegName)
      when (arg) {
        is AllocatableValue -> argConstraints += arg constrainedTo reg
        is ConstantValue -> beforeLinked += matchTypedMov(PhysicalRegister(reg, argType), arg)
        is LoadableValue -> {
          val copyTarget = VirtualRegister(registerIds(), argType)
          before += matchTypedMov(copyTarget, arg)
          argConstraints += copyTarget constrainedTo reg
        }
        is ParameterReference -> logger.throwICE("Unreachable; these have to be removed")
      }
    }
    // Push stack arguments in order, aligned
    val intStackArgs = intArgs.drop(intArgRegNames.size)
    val fltStackArgs = fltArgs.drop(sseArgRegNames.size)
    val stackArgs = (intStackArgs + fltStackArgs).sortedBy { it.index }
    val stackArgsSize = stackArgs.sumOf {
      target.machineTargetData.sizeOf(it.value.type).coerceAtLeast(EIGHTBYTE)
    }
    if (stackArgsSize % ALIGNMENT_BYTES != 0) {
      before += sub.match(rsp, IntConstant(EIGHTBYTE, SignedIntType))
    }
    for (stackArg in stackArgs.map { it.value }.asReversed()) {
      // FIXME: if MemoryLocation would support stuff like [rsp + 24] we could avoid this sub
      before += sub.match(rsp, IntConstant(EIGHTBYTE, SignedIntType))
      before += pushOnStack(stackArg)
    }
    // The ABI says al must contain the number of vector arguments
    beforeLinked += mov.match(al, IntConstant(fltRegArgs.size, SignedCharType))

    val callInstr = call.match(callable)

    val afterLinked = mutableListOf<MachineInstruction>()
    val after = mutableListOf<MachineInstruction>()
    // Clean up pushed arguments
    val cleanStackSize = stackArgsSize + stackArgsSize / ALIGNMENT_BYTES
    afterLinked += add.match(rsp, IntConstant(cleanStackSize, SignedIntType))

    // Add result value constraint
    val resultRegister = callResultConstraint(result)
    val resultConstraint = if (resultRegister == null) {
      null
    } else {
      val copyTarget = VirtualRegister(registerIds(), result.type)
      after += matchTypedMov(result, copyTarget)
      copyTarget constrainedTo resultRegister
    }

    // Create actual call instruction
    val allLinks = beforeLinked.map { LinkedInstruction(it, LinkPosition.BEFORE) } +
        afterLinked.map { LinkedInstruction(it, LinkPosition.AFTER) }

    // FIXME: maybe when calling our other functions, we could track what regs they use, and not save them if we know
    //  they won't be clobbered
    fun makeDummy() = VirtualRegister(registerIds(), target.machineTargetData.ptrDiffType, VRegType.CONSTRAINED)
    val dummies = List(target.callerSaved.size) { makeDummy() }
    val callerSavedConstraints = dummies.zip(target.callerSaved).map { (dummy, reg) -> dummy constrainedTo reg }

    val finalCall = callInstr.copy(
        links = allLinks,
        constrainedArgs = argConstraints + (makeDummy() constrainedTo al.reg),
        constrainedRes = listOfNotNull(resultConstraint) + callerSavedConstraints
    )

    return before + finalCall + after
  }

  /**
   * System V ABI: "Returning of Values", page 24
   *
   * @return the register to which the result is constrained, or null for void return
   */
  private fun callResultConstraint(result: LoadableValue): MachineRegister? {
    // When the call returns void, do nothing here
    if (result.type is VoidType) return null
    val rc = target.registerClassOf(result.type)
    if (rc is Memory) TODO("some weird thing with caller storage in rdi, see ABI")
    require(rc is X64RegisterClass)
    if (rc == X64RegisterClass.X87) TODO("deal with x87")
    val returnRegisterName = when (rc) {
      X64RegisterClass.INTEGER -> "rax"
      X64RegisterClass.SSE -> "xmm0"
      else -> logger.throwICE("Unreachable")
    }
    return target.registerByName(returnRegisterName)
  }

  private fun pushOnStack(value: IRValue): List<MachineInstruction> {
    val selected = mutableListOf<MachineInstruction>()
    // If the thing is in memory, move it to a register, to avoid an illegal mem to mem operation
    // Since this function is used before the bulk of the functions, there will always be a free register
    val actualValue = if (value is MemoryLocation) {
      val resultType = (value.ptr.type as PointerType).referencedType
      val result = VirtualRegister(registerIds(), resultType)
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

  override fun createReturn(retVal: LoadableValue): List<MachineInstruction> {
    val selected = mutableListOf<MachineInstruction>()
    val retType = target.registerClassOf(retVal.type)
    if (retType == Memory) {
      TODO("deal with this")
    } else {
      require(retType is X64RegisterClass)
      val returnRegister = when (retType) {
        X64RegisterClass.INTEGER -> target.registerByName("rax")
        X64RegisterClass.SSE -> target.registerByName("xmm0")
        X64RegisterClass.X87 -> TODO("deal with this")
      }
      val physReg = PhysicalRegister(returnRegister, retVal.type.unqualify().normalize())
      selected += matchTypedMov(physReg, retVal)
    }
    return selected
  }
}
