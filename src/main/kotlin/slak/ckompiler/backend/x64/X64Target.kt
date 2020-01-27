package slak.ckompiler.backend.x64

import org.apache.logging.log4j.LogManager
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.*
import slak.ckompiler.backend.*
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

object X64Target : MachineTarget {
  private val logger = LogManager.getLogger()

  override val machineTargetData = MachineTargetData.x64
  override val targetName = "x64"
  override val registers = x64Registers
  // FIXME: make rbp conditional on -fomit-frame-pointer
  override val forbidden = listOf(registerByName("rsp"), registerByName("rbp"))

  override fun registerClassOf(type: TypeName): MachineRegisterClass = when (type) {
    VoidType, ErrorType -> logger.throwICE("ErrorType cannot make it to codegen")
    is BitfieldType -> TODO("no idea what to do with this")
    is QualifiedType -> registerClassOf(type.unqualified)
    is ArrayType, is FunctionType -> registerClassOf(type.normalize())
    is PointerType -> X64RegisterClass.INTEGER
    is StructureType -> Memory
    // FIXME: maybe get each member class and do some magic?
    is UnionType -> Memory
    is IntegralType -> X64RegisterClass.INTEGER
    is FloatingType -> X64RegisterClass.SSE
  }

  private fun matchAdd(i: IntBinary) = when (i.result) {
    // result = result OP rhs
    i.lhs -> listOf(add.match(i.lhs, i.rhs))
    // result = lhs OP result
    i.rhs -> listOf(add.match(i.rhs, i.lhs))
    else -> {
      // Can't have result = imm OP imm
      require(i.lhs !is ConstantValue || i.rhs !is ConstantValue)
      val nonImm = if (i.lhs is ConstantValue) i.rhs else i.lhs
      val maybeImm = if (i.lhs === nonImm) i.rhs else i.lhs
      listOf(
          mov.match(i.result, nonImm),
          add.match(i.result, maybeImm)
      )
    }
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
    return listOf(
        cmp.match(i.lhs, i.rhs),
        setcc.getValue(setValue).match(i.result)
    )
  }

  override fun selectBlockInstrs(block: BasicBlock): List<MachineInstruction> {
    val selected = mutableListOf<MachineInstruction>()
    // FIXME: deal with φ
    for (irInstr in block.ir) {
      selected += expandMacroFor(irInstr)
    }
    when (block.terminator) {
      is CondJump -> {
        val condTerm = block.terminator as CondJump
        for (irInstr in condTerm.cond.dropLast(1)) {
          selected += expandMacroFor(irInstr)
        }
        when (val l = condTerm.cond.last()) {
          is IntCmp -> {
            selected += selectCondJmp(l, JumpTargetConstant(condTerm.target))
          }
          is FltCmp -> TODO("floats")
          else -> TODO("no idea what happens here")
        }
      }
      is SelectJump -> TODO("deal with switches later")
      is ImpossibleJump -> TODO("return value")
      is ConstantJump, is UncondJump -> TODO("unconditionally jmp")
      MissingJump -> logger.throwICE("Incomplete BasicBlock")
    }
    return selected
  }

  private fun selectCondJmp(i: IntCmp, jumpTrue: JumpTargetConstant): MachineInstruction {
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

  private fun expandMacroFor(i: IRInstruction): List<MachineInstruction> = when (i) {
    is LoadInstr -> listOf(mov.match(i.result, i.target))
    is StoreInstr -> listOf(mov.match(i.target, i.value))
    is ConstantRegisterInstr -> listOf(mov.match(i.result, i.const))
    is StructuralCast -> TODO()
    is ReinterpretCast -> TODO()
    is NamedCall -> TODO()
    is IndirectCall -> TODO()
    is IntBinary -> when (i.op) {
      IntegralBinaryOps.ADD -> matchAdd(i)
      IntegralBinaryOps.SUB -> TODO()
      IntegralBinaryOps.MUL -> TODO()
      IntegralBinaryOps.DIV -> TODO()
      IntegralBinaryOps.REM -> TODO()
      IntegralBinaryOps.LSH -> TODO()
      IntegralBinaryOps.RSH -> TODO()
      IntegralBinaryOps.AND -> TODO()
      IntegralBinaryOps.OR -> TODO()
      IntegralBinaryOps.XOR -> TODO()
    }
    is IntCmp -> matchCmp(i)
    is IntInvert -> TODO()
    is IntNeg -> TODO()
    is FltBinary -> TODO()
    is FltCmp -> TODO()
    is FltNeg -> TODO()
    is PhiInstr -> TODO()
  }

  override fun applyAllocation(cfg: CFG, alloc: AllocationResult): List<X64Instruction> {
    val (instrs, allocated, stackSlots) = alloc
    var currentStackOffset = 0
    val stackOffsets = stackSlots.associateWith {
      val offset = currentStackOffset
      currentStackOffset += it.sizeBytes
      offset
    }
    val result = mutableListOf<X64Instruction>()
    for (block in cfg.postOrderNodes) {
      for ((template, operands) in instrs.getValue(block)) {
        require(template is X64InstrTemplate)
        val ops = operands.map {
          if (it is ConstantValue) return@map ImmediateValue(it)
          val machineRegister = allocated.getValue(it)
          if (machineRegister is StackSlot) {
            return@map StackValue(machineRegister, stackOffsets.getValue(machineRegister))
          } else {
            return@map RegisterValue(machineRegister, machineTargetData.sizeOf(it.type))
          }
        }
        result += X64Instruction(template, ops)
      }
    }
    return result
  }

  override fun genFunctionPrologue(alloc: AllocationResult): List<AsmInstruction> {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  override fun genFunctionEpilogue(alloc: AllocationResult): List<AsmInstruction> {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }
}
