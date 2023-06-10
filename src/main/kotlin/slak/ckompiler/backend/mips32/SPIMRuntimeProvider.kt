package slak.ckompiler.backend.mips32

import slak.ckompiler.backend.*
import slak.ckompiler.readResource

class SPIMRuntimeProvider : TargetRuntimeProvider {
  override fun provideRuntimeFunctions(
      externals: List<String>,
      target: MachineTarget<*>,
      getCFGs: GetTranslationUnitCFG,
  ): List<AnyFunGenerator> = buildList {
    if ("printf" in externals) {
      val printf = readResource("runtime/spim/printf.c")!!
      val cfgs = getCFGs(printf, "<spim-runtime>")
      this += cfgs.map { createTargetFunGenerator(it, target) }
    }
  }
}
