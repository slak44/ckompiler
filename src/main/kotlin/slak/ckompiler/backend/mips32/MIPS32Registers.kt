package slak.ckompiler.backend.mips32

import slak.ckompiler.IdCounter
import slak.ckompiler.backend.*

enum class MIPS32RegisterClass : MachineRegisterClass {
  INTEGER, FLOAT;

  override val id: Int get() = ordinal
  override val displayName: String get() = name
}

class MIPS32Register(
    override val regName: String,
    val regNumber: Int,
    override val sizeBytes: Int,
    override val valueClass: MIPS32RegisterClass,
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

private fun RegisterBuilder<MIPS32Register>.register(name: String, regNumber: Int) {
  regs += MIPS32Register("\$$name", regNumber, 4, valueClass as MIPS32RegisterClass, emptyList())
}

fun getMIPS32Registers(): List<MIPS32Register> = registers {
  ofClass(MIPS32RegisterClass.INTEGER) {
    register("zero", 0)
    register("at", 1)

    for (i in 0..1) {
      register("v$i", 2 + i)
    }

    for (i in 0..3) {
      register("a$i", 4 + i)
    }

    for (i in 0..7) {
      register("t$i", 8 + i)
    }

    for (i in 0..7) {
      register("s$i", 16 + i)
    }

    for (i in 8..9) {
      register("t$i", 24 + i)
    }

    for (i in 0..1) {
      register("k$i", 26 + i)
    }

    register("gp", 28)
    register("sp", 29)
    register("fp", 30)
    register("ra", 31)
  }

  ofClass(MIPS32RegisterClass.FLOAT) {
    for (i in 0..31) {
      register("f$i", i)
    }
  }
}
