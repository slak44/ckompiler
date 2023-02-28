package slak.ckompiler.backend

import slak.ckompiler.backend.mips32.MIPS32Instruction
import slak.ckompiler.backend.mips32.SPIMGenerator
import slak.ckompiler.backend.x64.NasmEmitter
import slak.ckompiler.backend.x64.X64Instruction

interface AsmEmitter<T : AsmInstruction> {
  val externals: List<String>
  val functions: List<TargetFunGenerator<T>>
  val mainCfg: TargetFunGenerator<T>?

  fun emitAsm(): String
}

fun createAsmEmitter(
    isaType: ISAType,
    externals: List<String>,
    functions: List<AnyFunGenerator>,
    mainCfg: AnyFunGenerator?,
): AsmEmitter<out AsmInstruction> {
  @Suppress("UNCHECKED_CAST")
  return when (isaType) {
    ISAType.X64 -> NasmEmitter(
        externals,
        functions as List<TargetFunGenerator<X64Instruction>>,
        mainCfg as TargetFunGenerator<X64Instruction>?
    )
    ISAType.MIPS32 -> SPIMGenerator(
        externals,
        functions as List<TargetFunGenerator<MIPS32Instruction>>,
        mainCfg as TargetFunGenerator<MIPS32Instruction>?
    )
  }
}
