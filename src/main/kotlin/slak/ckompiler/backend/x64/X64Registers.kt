package slak.ckompiler.backend.x64

import slak.ckompiler.IdCounter
import slak.ckompiler.backend.*
import slak.ckompiler.exhaustive

enum class X64RegisterClass : MachineRegisterClass {
  INTEGER, SSE, X87;

  override val id: Int get() = ordinal
  override val displayName: String get() = name
}

class X64Register(
    override val regName: String,
    override val sizeBytes: Int,
    override val valueClass: MachineRegisterClass,
    override val aliases: List<RegisterAlias>,
) : MachineRegister {
  override val id = ids()

  override fun toString(): String {
    return "register $regName"
  }

  companion object {
    private val ids = IdCounter()
  }
}

private fun RegisterBuilder<X64Register>.register(
    name: String,
    sizeBytes: Int,
    vararg aliases: RegisterAlias,
) {
  regs += X64Register(name, sizeBytes, valueClass, aliases.toList())
}

fun getX64Registers(featureSet: X64SupportedFeatures): List<X64Register> = registers {
  ofClass(X64RegisterClass.INTEGER) {
    for (c in arrayOf('a', 'b', 'c', 'd')) {
      register("r${c}x", 8,
          alias("e${c}x", 4),
          alias("${c}x", 2),
          alias("${c}l", 1),
          alias("${c}h", 1)
      )
    }
    for (si in arrayOf("si", "di", "sp", "bp")) {
      register("r$si", 8,
          alias("e$si", 4),
          alias(si, 2),
          alias("${si}l", 1)
      )
    }
    for (n in 8..15) {
      register("r$n", 8,
          alias("r${n}d", 4),
          alias("r${n}w", 2),
          alias("r${n}b", 1)
      )
    }
  }
  ofClass(X64RegisterClass.SSE) {
    val sseRegisterCount = if (featureSet == X64SupportedFeatures.AVX512) 32 else 16
    for (n in 0 until sseRegisterCount) {
      val globalAliases = arrayOf(
          // XMM can be used for single doubles and floats too
          alias("xmm$n", 8),
          alias("xmm$n", 4)
      )
      when (featureSet) {
        X64SupportedFeatures.SSE2, X64SupportedFeatures.SSE3, X64SupportedFeatures.SSE4 -> {
          register("xmm$n", 16, *globalAliases)
        }
        X64SupportedFeatures.AVX, X64SupportedFeatures.AVX2 -> {
          register("ymm$n", 32,
              alias("xmm$n", 16),
              *globalAliases
          )
        }
        X64SupportedFeatures.AVX512 -> {
          register("zmm$n", 64,
              alias("ymm$n", 32),
              alias("xmm$n", 16),
              *globalAliases
          )
        }
      }.exhaustive
    }
  }
  // FIXME: x86_64 registers are ridiculous, add the remaining ones
}
