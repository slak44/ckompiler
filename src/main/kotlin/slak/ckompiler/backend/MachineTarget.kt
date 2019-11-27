package slak.ckompiler.backend

import slak.ckompiler.analysis.BasicBlock
import slak.ckompiler.analysis.CFG
import slak.ckompiler.analysis.IRInstruction
import slak.ckompiler.analysis.IRValue

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

enum class VariableUse {
  DEF, USE, DEF_USE
}

interface InstructionTemplate {
  val name: String
  val operandUse: List<VariableUse>
}

data class MachineInstruction(val template: InstructionTemplate, val operands: List<IRValue>)

interface MachineTarget {
  val targetName: String
  val registers: List<MachineRegister>

  /**
   * Instruction selection for one [IRInstruction].
   */
  fun expandMacroFor(i: IRInstruction): List<MachineInstruction>

  /**
   * Runs a pass over a [BasicBlock], and possibly mutates the IR in it.
   *
   * Useful for stuff like SSA destruction.
   *
   * Is executed before instruction selection, that is, before [expandMacroFor] is called.
   */
  fun localIRTransform(bb: BasicBlock)

  fun genFunctionPrologue(labels: List<Label>): List<MachineInstruction>
  fun genFunctionEpilogue(labels: List<Label>): List<MachineInstruction>
}

fun MachineTarget.registerByName(name: String): MachineRegister {
  return registers.first { reg ->
    reg.regName == name || name in reg.aliases.map { it.first }
  }
}

fun MachineTarget.instructionSelection(cfg: CFG): List<Label> {
  return cfg.nodes
      .onEach(::localIRTransform)
      .map { block ->
        Label(block, block.instructions
            .asSequence()
            .flatMap { expandMacroFor(it).asSequence() }
            .toList()
        )
      }
}

fun MachineTarget.instructionScheduling(labels: List<Label>): List<Label> {
  // FIXME: deal with this sometime
  return labels
}

data class Label(val bb: BasicBlock, val instructions: List<MachineInstruction>) {
  override fun toString(): String {
    var str = "Label[bb=$bb"
    str += instructions.joinToString(separator = "\n\t", prefix = "\n\t", postfix = "\n") {
      "${it.template.name} " + it.operands.joinToString(", ")
    }
    str += "]"
    return str
  }
}
