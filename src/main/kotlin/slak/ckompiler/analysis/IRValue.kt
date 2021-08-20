package slak.ckompiler.analysis

import org.apache.logging.log4j.LogManager
import slak.ckompiler.AtomicId
import slak.ckompiler.MachineTargetData
import slak.ckompiler.backend.MachineRegister
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

private val logger = LogManager.getLogger()

/**
 * Represents an operand to an IR instruction.
 */
sealed class IRValue {
  /**
   * This label exists for debugging purposes.
   */
  abstract val name: String
  abstract val type: TypeName
}

/**
 * Represents the [index]th parameter of a function. It might be a memory location, or it might be
 * a register, depending on the target.
 *
 * [type] is the type of the value, not a pointer to it.
 */
data class ParameterReference(val index: Int, override val type: TypeName) : IRValue() {
  override val name = "param$index"
  override fun toString() = name
}

/**
 * An [IRValue] that can be "written" to. What writing to it means depends on the value.
 */
sealed class LoadableValue : IRValue() {
  /**
   * If true, signals that the stored value is irrelevant: that means not even generate code for operations on it!
   *
   * Garbage in, garbage out.
   */
  abstract val isUndefined: Boolean
}

/**
 * These are [LoadableValue]s that represent SSA definitions.
 */
sealed class AllocatableValue : LoadableValue() {
  /**
   * A SSA-transcendent id for this value. SSA constrains us to one definition per SSA variable. However, we're interested in linking
   * different versions using this id, that will be the same for all versions of a variable.
   */
  abstract val identityId: Int
}

sealed class VersionedValue : AllocatableValue() {
  abstract val version: Int?

  fun versionString(): String = when (this) {
    is Variable -> "v${version}"
    is DerefStackValue -> name
  }
}

enum class VRegType {
  /** Your average temporary variable, created from stuff like 1 + 2 + 3. */
  REGULAR,

  /** Contents are undefined, like using a variable before initializing it. */
  UNDEFINED,

  /** Like [UNDEFINED], referring to a "constraint". For example, a caller saved register after a function call. */
  CONSTRAINED,
}

/**
 * These are one-version variables, they are only written to once. They cannot escape the block they're declared in.
 *
 * @see VRegType
 */
class VirtualRegister(
    val registerId: AtomicId,
    override val type: TypeName,
    val kind: VRegType = VRegType.REGULAR
) : AllocatableValue() {
  override val identityId: AtomicId = -1 * registerId

  override val isUndefined: Boolean = kind == VRegType.UNDEFINED || kind == VRegType.CONSTRAINED

  override val name get() = "${if (isUndefined) "dummy" else type.toString()} vreg$registerId"
  override fun toString() = name

  override fun equals(other: Any?) = (other as? VirtualRegister)?.registerId == registerId
  override fun hashCode() = registerId
}

/**
 * An index inside a basic block's labels.
 */
typealias LabelIndex = Int

/**
 * Identifies a label inside a block.
 */
typealias Label = Pair<BasicBlock, LabelIndex>

/**
 * The [variable] definition that reaches a point in the CFG, along with information about where it
 * was defined.
 *
 * @see VariableRenamer.reachingDefs
 */
data class ReachingDefinition(
    val variable: Variable,
    val definedIn: BasicBlock,
    val definitionIdx: LabelIndex
)

/**
 * Wraps a [tid], adds [version]. Represents state that is mutable between [BasicBlock]s.
 *
 * The [version] starts as 0, and the variable renaming process updates that value. Versioned
 * variables are basically equivalent to [VirtualRegister]s.
 * @see ReachingDefinition
 */
class Variable(val tid: TypedIdentifier) : VersionedValue() {
  override val identityId get() = tid.id

  override val name get() = tid.name
  override val type get() = tid.type

  override var version = 0
    private set

  /**
   * Returns true if this variable is not defined, either from use before definition, or as a dummy
   * for a φ-function.
   */
  override val isUndefined get() = version == 0

  fun copy(version: Int = this.version): Variable {
    val v = Variable(tid)
    v.version = version
    return v
  }

  /**
   * Sets the version from the [newerVersion] parameter. No-op if [newerVersion] is null.
   */
  fun replaceWith(newerVersion: ReachingDefinition?) {
    if (newerVersion == null) return
    if (tid.id != newerVersion.variable.tid.id || newerVersion.variable.version < version) {
      // FIXME: this might actually be legit if we insert a constraint copy with a newer version
      logger.throwICE("Illegal variable version replacement") { "$this -> $newerVersion" }
    }
    version = newerVersion.variable.version
  }

  override fun toString() = "$tid v$version"

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Variable) return false

    if (tid.id != other.tid.id) return false
    if (version != other.version) return false

    return true
  }

  override fun hashCode(): Int {
    var result = tid.id
    result = 31 * result + version
    return result
  }
}

/**
 * Much like [StackVariable], this is a _value_, and it represents a pointer. It is not a pointer to [referenceTo]. Rather, it's a pointer
 * into a stack slot. [referenceTo] is the value that will get spilled/moved into that stack slot.
 *
 * The primary difference between this and [StackVariable] is that [StackValue] gets created by the spiller, dynamically, for any
 * [AllocatableValue] that might get spilled, while [StackVariable] is "pre-spilled" by the code generator.
 *
 * They could, in theory, become the same type.
 */
class StackValue(val referenceTo: AllocatableValue) : LoadableValue() {
  override val type: PointerType = PointerType(referenceTo.type.normalize(), emptyList())
  override val isUndefined: Boolean = false

  override val name get() = "stackval ${referenceTo.identityId}"
  override fun toString() = name
}

/**
 * Half [MemoryLocation], half [Variable]. This value is functionally equivalent to a [MemoryLocation], as [stackValue] is a pointer, and
 * this is its dereferenced value.
 *
 * This exists as a way to differentiate spill targets (which participate in SSA/φs) from regular pointer dereference ([MemoryLocation]s,
 * which do not and cannot participate in SSA).
 *
 * In a way, it is a roundabout way to use [StackValue.referenceTo].
 */
class DerefStackValue(val stackValue: StackValue) : VersionedValue() {
  override val name: String = stackValue.name
  override val type: TypeName = stackValue.referenceTo.type
  override val isUndefined: Boolean = stackValue.referenceTo.isUndefined
  override val version: Int? = (stackValue.referenceTo as? VersionedValue)?.version
  override val identityId: Int = stackValue.referenceTo.identityId
  override fun toString(): String = "mem[$stackValue]"
}

/**
 * Represents a dereferenced pointer.
 */
data class MemoryLocation(val ptr: IRValue) : LoadableValue() {
  init {
    require(ptr.type is PointerType)
    require(ptr !is StackValue) { "You probably meant to use DerefStackValue" }
  }

  override val name = ptr.name
  override val type = (ptr.type as PointerType).referencedType
  override val isUndefined = false

  override fun toString() = "mem[$ptr]"
}

/**
 * Is basically a pointer to the variable, like &x. To actually use the value of x, it must be
 * loaded to a [VirtualRegister] using [LoadMemory], then modified with [StoreMemory].
 *
 * The [id]s of these variables are in a shared pool with [Variable]s.
 */
data class StackVariable(val tid: TypedIdentifier) : LoadableValue() {
  val id get() = tid.id

  override val name get() = tid.name
  override val type: PointerType = PointerType(tid.type.normalize(), emptyList())
  override val isUndefined = false

  override fun toString() = "stack $type $name"
}

/**
 * Reference to a pinned physical register. For things like respecting ABI return registers, or
 * explicit register saving.
 *
 * Should not be generated by [createInstructions].
 */
data class PhysicalRegister(
    val reg: MachineRegister,
    override val type: TypeName
) : LoadableValue() {
  override val name = reg.regName
  override val isUndefined = false
  override fun toString() = reg.regName

  override fun equals(other: Any?) = (other as? PhysicalRegister)?.reg == reg
  override fun hashCode() = reg.hashCode()
}

sealed class ConstantValue : IRValue()
data class IntConstant(val value: Long, override val type: TypeName) : ConstantValue() {
  constructor(int: Int, type: TypeName) : this(int.toLong(), type)
  constructor(int: IntegerConstantNode) : this(int.value, int.type)
  constructor(char: CharacterConstantNode) : this(char.char.toLong(), char.type)

  override val name = value.toString()
  override fun toString() = name
}

data class FltConstant(val value: Double, override val type: TypeName) : ConstantValue() {
  constructor(flt: FloatingConstantNode) : this(flt.value, flt.type)

  override val name = value.toString()
  override fun toString() = name
}

data class StrConstant(val value: String, override val type: TypeName) : ConstantValue() {
  constructor(str: StringLiteralNode) : this(str.string, str.type)

  override val name = "\"${value.replace("\n", "\\n")}\""
  override fun toString() = name
}

data class JumpTargetConstant(val target: AtomicId) : ConstantValue() {
  constructor(basicBlock: BasicBlock) : this(basicBlock.nodeId)

  override val type = VoidType
  override val name = ".block_$target"
  override fun toString() = name
}

data class NamedConstant(override val name: String, override val type: TypeName) : ConstantValue() {
  override fun toString() = name
}

/**
 * Return a copy of an [IRValue], coercing its type to the provided parameter.
 */
fun MachineTargetData.copyWithType(value: IRValue, type: TypeName): IRValue = when (value) {
  is VirtualRegister -> VirtualRegister(value.registerId, type, value.kind)
  is Variable -> Variable(value.tid.copy(type = type))
  is PhysicalRegister ->
    if (sizeOf(type) in value.reg.aliases.map { it.second } || sizeOf(type) == value.reg.sizeBytes) {
      value.copy(type = type)
    } else {
      TODO()
    }
  is DerefStackValue -> TODO()
  is MemoryLocation -> TODO()
  is StackVariable -> TODO()
  is StackValue -> TODO()
  is IntConstant -> value.copy(type = type) // FIXME: check type limits
  is FltConstant -> TODO()
  is StrConstant -> TODO()
  is ParameterReference, is JumpTargetConstant, is NamedConstant -> logger.throwICE("Illegal target of IRValue copy")
}
