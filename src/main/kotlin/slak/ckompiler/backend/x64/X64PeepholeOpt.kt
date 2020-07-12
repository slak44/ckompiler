package slak.ckompiler.backend.x64

import slak.ckompiler.backend.PeepholeOptimizer
import slak.ckompiler.backend.TargetFunGenerator

class X64PeepholeOpt : PeepholeOptimizer<X64Instruction> {
  override fun optimize(targetFun: TargetFunGenerator, asm: List<X64Instruction>): List<X64Instruction> {
    // Filter useless movs
    return asm.filterNot { it.template in mov && it.operands[0] == it.operands[1] }
  }
}
