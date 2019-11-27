package slak.ckompiler.backend.x64

import slak.ckompiler.analysis.*
import slak.ckompiler.backend.Label
import slak.ckompiler.backend.MachineInstruction
import slak.ckompiler.backend.MachineTarget

object X64Target : MachineTarget {
  override val targetName = "x64"
  override val registers = x64Registers

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
    // FIXME: this is a very poor way to "deconstruct" SSA:
    bb.phiFunctions.clear()
  }

  override fun genFunctionPrologue(labels: List<Label>): List<MachineInstruction> {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  override fun genFunctionEpilogue(labels: List<Label>): List<MachineInstruction> {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }
}
