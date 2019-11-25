package slak.ckompiler.backend.x64

import slak.ckompiler.analysis.*
import slak.ckompiler.backend.Label
import slak.ckompiler.backend.MachineInstruction
import slak.ckompiler.backend.MachineTarget

object X64Target : MachineTarget {
  override val targetName = "x64"
  override val registers = x64Registers

  override fun expandMacroFor(i: IRInstruction): MachineInstruction = when (i) {
    is LoadInstr -> mov.match(i.result, i.target)
    is StructuralCast -> TODO()
    is ReinterpretCast -> TODO()
    is AddressOfVar -> TODO()
    is AddressOf -> TODO()
    is ValueOf -> TODO()
    is NamedCall -> TODO()
    is IndirectCall -> TODO()
    is IntBinary -> when (i.op) {
      IntegralBinaryOps.ADD -> when (i.result) {
        i.lhs -> add.match(i.lhs, i.rhs)
        i.rhs -> add.match(i.rhs, i.lhs)
        else -> TODO("move one operand to result register, and then match the add")
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
    is ConstantRegisterInstr -> TODO()
    is VarStoreInstr -> TODO()
    is DataStoreInstr -> TODO()
  }

  override fun genFunctionPrologue(labels: List<Label>): List<MachineInstruction> {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  override fun genFunctionEpilogue(labels: List<Label>): List<MachineInstruction> {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }
}
