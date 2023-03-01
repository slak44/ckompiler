package slak.ckompiler.backend.mips32

import slak.ckompiler.analysis.IRValue
import slak.ckompiler.analysis.LoadableValue
import slak.ckompiler.analysis.PhysicalRegister
import slak.ckompiler.backend.FunctionCallGenerator
import slak.ckompiler.backend.MachineInstruction
import slak.ckompiler.backend.Memory
import slak.ckompiler.backend.registerByName

class MIPS32CallGenerator(val target: MIPS32Target) : FunctionCallGenerator {
  override fun createCall(result: LoadableValue, callable: IRValue, args: List<IRValue>): List<MachineInstruction> {
    TODO("not implemented")
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
      selected += target.matchTypedCopy(physReg, retVal)
    }
    return selected
  }
}
