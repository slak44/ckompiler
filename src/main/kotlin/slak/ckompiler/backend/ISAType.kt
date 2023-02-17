package slak.ckompiler.backend

import slak.ckompiler.MachineTargetData

enum class ISAType(val optionsString: String, val machineTargetData: MachineTargetData) {
  X64("x86_64", MachineTargetData.x64), MIPS32("mips32", MachineTargetData.mips32);

  companion object {
    fun fromOptionsString(optionsString: String): ISAType {
      return values().first { it.optionsString == optionsString }
    }
  }
}
