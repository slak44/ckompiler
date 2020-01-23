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

  override fun expandMacroFor(i: IRInstruction): List<MachineInstruction> = when (i) {
    is LoadInstr -> listOf(mov.match(i.result, i.target))
    is StoreInstr -> listOf(mov.match(i.target, i.value))
    is ConstantRegisterInstr -> listOf(mov.match(i.result, i.const))
    is StructuralCast -> TODO()
    is ReinterpretCast -> TODO()
    is NamedCall -> TODO()
    is IndirectCall -> TODO()
    is IntBinary -> when (i.op) {
      IntegralBinaryOps.ADD -> when (i.result) {
        i.lhs -> listOf(add.match(i.lhs, i.rhs))
        i.rhs -> listOf(add.match(i.rhs, i.lhs))
        else -> {
          require(i.lhs !is ConstantValue || i.rhs !is ConstantValue)
          val nonImm = if (i.lhs is ConstantValue) i.rhs else i.lhs
          val maybeImm = if (i.lhs === nonImm) i.rhs else i.lhs
          listOf(
              mov.match(i.result, nonImm),
              add.match(i.result, maybeImm)
          )
        }
      }
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
    is IntCmp -> TODO()
    is IntInvert -> TODO()
    is IntNeg -> TODO()
    is FltBinary -> TODO()
    is FltCmp -> TODO()
    is FltNeg -> TODO()
    is PhiInstr -> TODO()
  }

  override fun localIRTransform(bb: BasicBlock) {
  }

  override fun genFunctionPrologue(lists: InstructionMap): List<MachineInstruction> {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  override fun genFunctionEpilogue(lists: InstructionMap): List<MachineInstruction> {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }
}
