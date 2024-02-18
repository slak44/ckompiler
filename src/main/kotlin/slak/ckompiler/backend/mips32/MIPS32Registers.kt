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
    val isFPUControl: Boolean = false,
) : MachineRegister {
  override val id = ids()

  override fun toString(): String {
    return "register $regName"
  }

  companion object {
    private val ids = IdCounter()
  }
}

private fun RegisterBuilder<MIPS32Register>.register(name: String, regNumber: Int, sizeBytes: Int = 4) {
  val regName = "\$$name"
  regs += MIPS32Register(regName, regNumber, sizeBytes, valueClass as MIPS32RegisterClass, listOf(regName to 1))
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
    // TODO: figure out aliases for doubles
    //   a double in MIPS takes up two float registers
    //   so f0-f1, f2-f3, f4-f5 etc can hold either two separate floats, or one double
    //   maybe we need to insert both floats with double alias, and double with float alias? how does register allocation deal with this?
    for (i in 0..31) {
      register("f$i", i)
    }

    // See CFC1 instruction
    regs += MIPS32Register("\$fcsr", -1, 0, MIPS32RegisterClass.FLOAT, emptyList(), isFPUControl = true)
  }
}
