package slak.ckompiler.backend

import slak.ckompiler.analysis.FltConstant
import slak.ckompiler.analysis.StrConstant
import slak.ckompiler.backend.mips32.MIPS32Instruction
import slak.ckompiler.backend.mips32.SPIMGenerator
import slak.ckompiler.backend.x64.NasmEmitter
import slak.ckompiler.backend.x64.X64Instruction

abstract class AsmEmitter<T : AsmInstruction>(
    val externals: List<String>,
    val functions: List<TargetFunGenerator<T>>,
    val mainCfg: TargetFunGenerator<T>?,
) {
  /**
   * The data section of the assembly. Where string data goes, for instance.
   */
  protected val data = mutableListOf<String>()

  /**
   * This maps literals to a label in .data with their value. It also enables deduplication, because
   * it is undefined behaviour to modify string literals.
   *
   * C standard: 6.4.5.0.7
   */
  protected val stringRefs = mutableMapOf<StrConstant, String>()

  /**
   * Maps a float to a label with the value.
   * @see stringRefs
   */
  protected val floatRefs = mutableMapOf<FltConstant, String>()

  abstract fun emitAsm(): String

  fun createFloatConstantText(const: FltConstant): String {
    val labelName = const.value.toString()
        .replace('.', '_')
        .replace('+', 'p')
        .replace('-', 'm')

    return "f_${labelName}_${floatRefs.size}"
  }

  fun createStringConstantText(const: StrConstant): String {
    val stringPeek = const.value.filter(Char::isLetterOrDigit).take(5)
    stringRefs[const] = "s_${stringPeek}_${stringRefs.size}"
    return const.value.encodeToByteArray().joinToString(", ")
  }
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

typealias Instructions = List<String>

class InstructionBuilder {
  private val instr = mutableListOf<String>()

  fun label(s: String) {
    instr += "$s:"
  }

  fun emit(s: String) {
    instr += "  $s"
  }

  fun emit(s: Instructions) {
    for (i in s) instr += i
  }

  fun toInstructions(): Instructions = instr
}

inline fun instrGen(block: InstructionBuilder.() -> Unit): Instructions {
  val builder = InstructionBuilder()
  builder.block()
  return builder.toInstructions()
}
