package slak.ckompiler.backend.mips32

import mu.KotlinLogging
import slak.ckompiler.IdCounter
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*
import slak.ckompiler.parser.PointerType
import slak.ckompiler.parser.UnsignedIntType
import slak.ckompiler.parser.VoidType
import slak.ckompiler.throwICE

class MIPS32CallGenerator(val target: MIPS32Target, val registerIds: IdCounter) : FunctionCallGenerator {
  private val logger = KotlinLogging.logger {}

  private val sp = target.ptrRegisterByName("\$sp")

  /**
   * MIPS System V ABI: 3-11
   */
  override fun createCall(result: LoadableValue, callable: IRValue, args: List<IRValue>): List<MachineInstruction> {
    val before = mutableListOf<MachineInstruction>()
    val beforeLinked = mutableListOf<MachineInstruction>()
    val intArgs = args
        .filter { target.registerClassOf(it.type) == MIPS32RegisterClass.INTEGER }
        .withIndex()
    // Move register arguments in place
    val intRegArgs = intArgs.take(target.intArgRegs.size)
    val registerArguments = intRegArgs.map { target.intArgRegs[it.index] to it.value }
    val argConstraints = mutableListOf<Constraint>()
    for ((reg, arg) in registerArguments) {
      val argType = arg.type.unqualify().normalize()
      when (arg) {
        is AllocatableValue -> argConstraints += arg constrainedTo reg
        is ConstantValue -> beforeLinked += matchTypedCopy(PhysicalRegister(reg, argType), arg)
        is LoadableValue -> {
          val copyTarget = VirtualRegister(registerIds(), argType)
          before += matchTypedCopy(copyTarget, arg)
          argConstraints += copyTarget constrainedTo reg
        }
        is ParameterReference -> logger.throwICE("Unreachable; these have to be removed")
      }
    }

    // For MIPS, the ABI says that if the function is variadic, all arguments are pushed on the stack, including register arguments
    val isVariadic = callable is NamedConstant && callable.type.asCallable()?.variadic == true

    // Push stack arguments in order, aligned
    val intStackArgs = if (isVariadic) intArgs else intArgs.drop(target.intArgRegs.size)
    val stackArgs = intStackArgs.sortedBy { it.index }
    val stackArgsSize = stackArgs.sumOf {
      target.machineTargetData.sizeOf(it.value.type).coerceAtLeast(MIPS32Target.WORD)
    }
    if (stackArgsSize % MIPS32Target.ALIGNMENT_BYTES != 0) {
      before += addiu.match(sp, sp, -MIPS32Generator.wordSizeConstant)
    }
    for (stackArg in stackArgs.map { it.value }.asReversed()) {
      before += pushOnStack(stackArg)
    }

    // If variadic, space was allocated and values were pushed above
    // If not variadic, we just need to allocate the space on the stack
    if (!isVariadic) {
      before += addiu.match(sp, sp, -MIPS32Generator.wordSizeConstant * intRegArgs.size)
    }

    val callInstr = when (callable) {
      is NamedConstant -> jal.match(callable)
      is AllocatableValue, is PhysicalRegister -> jalr.match(callable)
      else -> TODO("is this value even callable?")
    }

    val afterLinked = mutableListOf<MachineInstruction>()
    val after = mutableListOf<MachineInstruction>()
    // Clean up pushed arguments
    val cleanStackSize = stackArgsSize + stackArgsSize / MIPS32Target.ALIGNMENT_BYTES
    afterLinked += addi.match(sp, sp, IntConstant(cleanStackSize, UnsignedIntType))

    // Add result value constraint
    val resultRegister = callResultConstraint(result)
    val resultConstraint = if (resultRegister == null) {
      null
    } else {
      val copyTarget = VirtualRegister(registerIds(), result.type)
      after += matchTypedCopy(result, copyTarget)
      copyTarget constrainedTo resultRegister
    }

    // Create actual call instruction
    val allLinks = beforeLinked.map { LinkedInstruction(it, LinkPosition.BEFORE) } +
        afterLinked.map { LinkedInstruction(it, LinkPosition.AFTER) }

    fun makeDummy() = VirtualRegister(registerIds(), target.machineTargetData.ptrDiffType, VRegType.CONSTRAINED)
    val dummies = List(target.callerSaved.size) { makeDummy() }
    val callerSavedConstraints = dummies.zip(target.callerSaved).map { (dummy, reg) -> dummy constrainedTo reg }

    val finalCall = callInstr.copy(
        links = allLinks,
        constrainedArgs = argConstraints,
        constrainedRes = listOfNotNull(resultConstraint) + callerSavedConstraints
    )

    return before + finalCall + after
  }

  private fun pushOnStack(value: IRValue): List<MachineInstruction> {
    val selected = mutableListOf<MachineInstruction>()
    // If the thing is in memory, move it to a register, to avoid an illegal mem to mem operation
    // Since this function is used before the bulk of the functions, there will always be a free register
    val actualValue = if (value is MemoryLocation) {
      val resultType = (value.ptr.type as PointerType).referencedType
      val result = VirtualRegister(registerIds(), resultType)
      selected += matchTypedCopy(result, value)
      result
    } else {
      value
    }
    val registerClass = target.registerClassOf(actualValue.type)
    if (registerClass is Memory) {
      TODO("move memory argument to stack")
    }
    require(registerClass is MIPS32RegisterClass)
    selected += when (registerClass) {
      MIPS32RegisterClass.INTEGER -> pushIntOnStack(value)
      MIPS32RegisterClass.FLOAT -> TODO()
    }
    return selected
  }

  private fun pushIntOnStack(value: IRValue): List<MachineInstruction> {
    val push = mutableListOf<MachineInstruction>()
    push += addiu.match(sp, sp, -MIPS32Generator.wordSizeConstant)
    val toStore = when (value) {
      is StrConstant, is FltConstant, is IntConstant -> {
        val registerConstant = VirtualRegister(registerIds(), sp.type)
        push += matchTypedCopy(registerConstant, value)
        registerConstant
      }
      else -> value
    }
    push += matchTypedCopy(MemoryLocation(sp), toStore)
    return push
  }

  private fun callResultConstraint(result: LoadableValue): MachineRegister? {
    // When the call returns void, do nothing here
    if (result.type is VoidType) return null
    val rc = target.registerClassOf(result.type)
    if (rc is Memory) TODO("caller allocates space, address in $4 and $2? see sysv mips abi")
    require(rc is MIPS32RegisterClass)
    val returnRegisterName = when (rc) {
      MIPS32RegisterClass.INTEGER -> "\$v0"
      MIPS32RegisterClass.FLOAT -> when (target.machineTargetData.sizeOf(result.type)) {
        4 -> "\$f0"
        else -> TODO("doubles are returned in two registers")
      }
    }
    return target.registerByName(returnRegisterName)
  }

  override fun createReturn(retVal: LoadableValue): List<MachineInstruction> {
    val selected = mutableListOf<MachineInstruction>()
    val retType = target.registerClassOf(retVal.type)
    if (retType == Memory) {
      TODO("deal with this")
    } else {
      require(retType is MIPS32RegisterClass)
      val returnRegister = when (retType) {
        MIPS32RegisterClass.INTEGER -> target.registerByName("\$v0")
        MIPS32RegisterClass.FLOAT -> TODO()
      }
      val physReg = PhysicalRegister(returnRegister, retVal.type.unqualify().normalize())
      selected += matchTypedCopy(physReg, retVal)
    }
    return selected
  }
}
