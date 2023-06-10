package slak.ckompiler.backend

import slak.ckompiler.analysis.CFG
import slak.ckompiler.backend.mips32.SPIMRuntimeProvider

typealias GetTranslationUnitCFG = (text: String, relPath: String) -> List<CFG>

interface TargetRuntimeProvider {
  fun provideRuntimeFunctions(
      externals: List<String>,
      target: MachineTarget<*>,
      getCFGs: GetTranslationUnitCFG,
  ): List<AnyFunGenerator>
}

object NoRuntimeProvider : TargetRuntimeProvider {
  override fun provideRuntimeFunctions(
      externals: List<String>,
      target: MachineTarget<*>,
      getCFGs: GetTranslationUnitCFG,
  ): List<AnyFunGenerator> {
    return emptyList()
  }
}

fun createTargetRuntimeProvider(isaType: ISAType): TargetRuntimeProvider {
  return when (isaType) {
    ISAType.X64 -> NoRuntimeProvider
    ISAType.MIPS32 -> SPIMRuntimeProvider()
  }
}
