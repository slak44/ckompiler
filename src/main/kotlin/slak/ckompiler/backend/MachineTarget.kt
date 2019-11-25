package slak.ckompiler.backend

import slak.ckompiler.analysis.BasicBlock
import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.DataReference
import slak.ckompiler.analysis.IRInstruction

interface MachineRegisterClass {
  val id: Int
  val displayName: String
}

/**
 * Alias name + alias size.
 */
typealias RegisterAlias = Pair<String, Int>

fun alias(name: String, size: Int): RegisterAlias = name to size

interface MachineRegister {
  val id: Int
  val regName: String
  val sizeBytes: Int
  val valueClass: MachineRegisterClass
  val aliases: List<RegisterAlias>
}

class RegisterBuilder<T : MachineRegister>(
    val regs: MutableList<T>,
    val valueClass: MachineRegisterClass
)

inline fun <T : MachineRegister> registers(block: MutableList<T>.() -> Unit): List<T> {
  val regs = mutableListOf<T>()
  regs.block()
  return regs
}

inline fun <T : MachineRegister> MutableList<T>.ofClass(
    valueClass: MachineRegisterClass,
    block: RegisterBuilder<T>.() -> Unit
) {
  val builder = RegisterBuilder(this, valueClass)
  builder.block()
}

interface InstructionTemplate

data class MachineInstruction(val template: InstructionTemplate, val operands: List<DataReference>)

interface MachineTarget {
  val targetName: String
  val registers: List<MachineRegister>

  fun registerByName(name: String): MachineRegister {
    return registers.first { reg ->
      reg.regName == name || name in reg.aliases.map { it.first }
    }
  }

  fun expandMacroFor(i: IRInstruction): MachineInstruction

  fun instructionSelection(cfg: CFG): List<Label> {
    return cfg.allNodes.map(this::expander)
  }

  private fun expander(bb: BasicBlock): Label {
    return Label(bb, bb.instructions.asSequence().map(::expandMacroFor).toList())
  }
}

data class Label(val bb: BasicBlock, val instructions: List<MachineInstruction>)
