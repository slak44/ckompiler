package slak.ckompiler.backend.mips32

import slak.ckompiler.analysis.IRValue
import slak.ckompiler.analysis.LoadableValue
import slak.ckompiler.backend.FunctionCallGenerator
import slak.ckompiler.backend.MachineInstruction

class MIPS32CallGenerator : FunctionCallGenerator {
  override fun createCall(result: LoadableValue, callable: IRValue, args: List<IRValue>): List<MachineInstruction> {
    TODO("not implemented")
  }

  override fun createReturn(retVal: LoadableValue): List<MachineInstruction> {
    TODO("not implemented")
  }
}
