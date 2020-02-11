package slak.ckompiler.backend.x64

import slak.ckompiler.analysis.IdCounter
import slak.ckompiler.backend.*

enum class X64RegisterClass : MachineRegisterClass {
  INTEGER, SSE, X87;

  override val id: Int get() = ordinal
  override val displayName: String get() = name
}

class X64Register(
    override val regName: String,
    override val sizeBytes: Int,
    override val valueClass: MachineRegisterClass,
    override val aliases: List<RegisterAlias>
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
    vararg aliases: RegisterAlias
) {
  regs += X64Register(name, sizeBytes, valueClass, aliases.toList())
}

val x64Registers: List<X64Register> = registers {
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
    for (n in 0..31) {
      // FIXME: some of these are for AVX-512 only
      register("zmm$n", 64,
          alias("ymm$n", 32),
          alias("xmm$n", 16),
          // XMM can be used for single doubles and floats too:
          alias("xmm$n", 8),
          alias("xmm$n", 4)
      )
    }
  }
  // FIXME: x86_64 registers are ridiculous, add the remaining ones
}
