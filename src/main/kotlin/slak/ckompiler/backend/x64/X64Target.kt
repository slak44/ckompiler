package slak.ckompiler.backend.x64

import org.apache.logging.log4j.LogManager
import slak.ckompiler.MachineTargetData
import slak.ckompiler.backend.*
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

class X64Target(override val options: X64TargetOpts = X64TargetOpts.defaults) : MachineTarget {
  private val logger = LogManager.getLogger()

  override val machineTargetData = MachineTargetData.x64
  override val targetName = "x64"
  override val registerClasses = X64RegisterClass.values().toList()
  override val registers = x64Registers
  // FIXME: make rbp conditional on -fomit-frame-pointer
  override val forbidden = listOf(registerByName("rsp"), registerByName("rbp"))

  /**
   * System V ABI: figure 3.4, page 23
   */
  val calleeSaved = listOf(
      "rbx", "rsp", "rbp", "r12", "r13", "r14", "r15"
  ).mapTo(mutableSetOf(), this::registerByName)

  val callerSaved = registers - calleeSaved

  /**
   * System V ABI: figure 3.4, page 23
   */
  override fun isPreservedAcrossCalls(register: MachineRegister): Boolean {
    return register in calleeSaved
  }

  /**
   * System V ABI: 3.2.3, page 17
   */
  override fun registerClassOf(type: TypeName): MachineRegisterClass = when (type) {
    VoidType, ErrorType -> logger.throwICE("$type type cannot make it to codegen")
    is BitfieldType -> TODO("no idea what to do with this")
    is QualifiedType -> registerClassOf(type.unqualified)
    is ArrayType, is FunctionType -> registerClassOf(type.normalize())
    is PointerType -> X64RegisterClass.INTEGER
    is StructureType -> Memory
    // FIXME: maybe get each member class and do some magic?
    is UnionType -> Memory
    is IntegralType -> X64RegisterClass.INTEGER
    is FloatingType -> X64RegisterClass.SSE
  }
}
