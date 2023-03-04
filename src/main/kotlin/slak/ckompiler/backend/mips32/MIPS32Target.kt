package slak.ckompiler.backend.mips32

import mu.KotlinLogging
import slak.ckompiler.MachineTargetData
import slak.ckompiler.analysis.PhysicalRegister
import slak.ckompiler.backend.*
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

class MIPS32Target(override val options: TargetOptions = TargetOptions.defaults) : MachineTarget {
  private val logger = KotlinLogging.logger {}

  override val isaType = ISAType.MIPS32
  override val machineTargetData = MachineTargetData.mips32
  override val targetName: String = "mips32"
  override val registerClasses: List<MachineRegisterClass> = MIPS32RegisterClass.values().toList()
  override val registers: List<MachineRegister> = getMIPS32Registers()
  override val forbidden: Set<MachineRegister> = listOf(
      "\$zero", "\$at", "\$k1", "\$k0", "\$gp", "\$sp", "\$fp", "\$ra"
  ).mapTo(mutableSetOf(), ::registerByName)

  val calleeSaved = listOf("\$s0", "\$s1", "\$s2", "\$s3", "\$s4", "\$s5", "\$s6", "\$s7")
      .mapTo(mutableSetOf(), ::registerByName)

  val callerSaved = registers - forbidden - calleeSaved

  val intArgRegs = listOf("\$a0", "\$a1", "\$a2", "\$a3").map(::registerByName)

  override fun isPreservedAcrossCalls(register: MachineRegister): Boolean {
    return register in calleeSaved
  }

  override fun registerClassOf(type: TypeName): MachineRegisterClass = when (type) {
    VoidType, ErrorType -> logger.throwICE("$type type cannot make it to codegen")
    is BitfieldType -> TODO("no idea what to do with this")
    is QualifiedType -> registerClassOf(type.unqualified)
    is ArrayType, is FunctionType -> registerClassOf(type.normalize())
    is PointerType -> MIPS32RegisterClass.INTEGER
    is StructureType -> Memory
    // FIXME: maybe get each member class and do some magic?
    is UnionType -> Memory
    is IntegralType -> MIPS32RegisterClass.INTEGER
    is FloatingType -> MIPS32RegisterClass.FLOAT
  }

  fun ptrRegisterByName(name: String): PhysicalRegister {
    return PhysicalRegister(registerByName(name), PointerType(machineTargetData.ptrDiffType, emptyList()))
  }

  companion object {
    const val WORD = 4
    const val ALIGNMENT_BYTES = 8
  }
}
