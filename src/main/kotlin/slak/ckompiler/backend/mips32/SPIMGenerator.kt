package slak.ckompiler.backend.mips32

import slak.ckompiler.backend.AsmEmitter
import slak.ckompiler.backend.TargetFunGenerator

class SPIMGenerator(
    override val externals: List<String>,
    override val functions: List<TargetFunGenerator<MIPS32Instruction>>,
    override val mainCfg: TargetFunGenerator<MIPS32Instruction>?,
) : AsmEmitter<MIPS32Instruction> {
  override fun emitAsm(): String {
    TODO("not implemented")
  }
}
