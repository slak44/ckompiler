package slak.ckompiler.analysis

import org.apache.logging.log4j.LogManager
import slak.ckompiler.AtomicId
import slak.ckompiler.MachineTargetData
import slak.ckompiler.backend.MachineRegister
import slak.ckompiler.lexer.Punctuators
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

private val logger = LogManager.getLogger()

/**
 * Common superclass of all IR instructions.
 */
sealed class IRInstruction {
  abstract val result: LoadableValue
}

/**
 * A φ-function for a particular variable. [incoming] stores the list of versions that the φ has
 * to choose from; that is, it stores which values come from which predecessor [BasicBlock].
 */
data class PhiInstruction(
    val variable: Variable,
    val incoming: Map<BasicBlock, Variable>
) {
  override fun toString() = "store $variable = φ(${incoming.entries.joinToString(", ") {
    "n${it.key.hashCode()} v${it.value.version}"
  }})"
}

/**
 * Read the value pointed at by [loadFrom], and store it in [result].
 */
data class LoadMemory(
    override val result: LoadableValue,
    val loadFrom: IRValue
) : IRInstruction() {
  init {
    if (loadFrom !is MemoryLocation) {
      require(loadFrom.type is PointerType)
      require(result.type == (loadFrom.type as PointerType).referencedType)
    }
  }

  override fun toString() = "load $result = *($loadFrom)"
}

/**
 * Put [value] at the address pointed to by [storeTo]. [storeTo] must have pointer type, or be a
 * [MemoryLocation].
 */
data class StoreMemory(val storeTo: IRValue, val value: IRValue) : IRInstruction() {
  init {
    if (storeTo !is MemoryLocation) {
      require(storeTo.type is PointerType)
      require(value.type == (storeTo.type as PointerType).referencedType)
    }
  }

  override val result get() = logger.throwICE("If this is used, it's a bug")
  override fun toString() = "store *($storeTo) = $value"
}

/**
 * Stores the [value] in [result].
 */
data class MoveInstr(override val result: LoadableValue, val value: IRValue) : IRInstruction() {
  override fun toString() = "move $result = $value"
}

/**
 * Modifies [operand] to have the type specified by [result]. May fail if the specified conversion
 * is impossible.
 */
data class StructuralCast(
    override val result: LoadableValue,
    val operand: IRValue
) : IRInstruction() {
  override fun toString() = "$result = cast $operand to ${result.type}"
}

/**
 * Reinterprets [operand]'s data as another type. Basically changes the type without touching the
 * data in memory.
 */
data class ReinterpretCast(
    override val result: LoadableValue,
    val operand: IRValue
) : IRInstruction() {
  override fun toString() = "$result = reinterpret $operand"
}

/**
 * A call to a named function. This type of call just assumes the function referred to by [name]
 * will exist at link time (that is, it doesn't check if the function named exists, because it might
 * be linked in later).
 */
data class NamedCall(
    override val result: LoadableValue,
    val name: NamedConstant,
    val args: List<IRValue>
) : IRInstruction() {
  override fun toString() = "$result = call $name with args ${args.joinToString(", ")}"
}

/**
 * A call to the function specified by the function pointer stored in [callable].
 */
data class IndirectCall(
    override val result: LoadableValue,
    val callable: VirtualRegister,
    val args: List<IRValue>
) : IRInstruction() {
  override fun toString() = "$result = call *($callable) with args ${args.joinToString(", ")}"
}

/**
 * Generic instruction with two operands.
 */
interface BinaryInstruction {
  val result: LoadableValue
  val lhs: IRValue
  val rhs: IRValue
}

/**
 * Lists possible comparison orders for arithmetic types.
 */
enum class Comparisons(val operator: String) {
  EQUAL(Punctuators.EQUALS.s),
  NOT_EQUAL(Punctuators.NEQUALS.s),
  LESS_THAN(Punctuators.LT.s),
  GREATER_THAN(Punctuators.GT.s),
  LESS_EQUAL(Punctuators.LEQ.s),
  GREATER_EQUAL(Punctuators.GEQ.s);

  override fun toString() = operator

  companion object {
    fun from(op: BinaryOperators): Comparisons = when (op) {
      BinaryOperators.LT -> LESS_THAN
      BinaryOperators.GT -> GREATER_THAN
      BinaryOperators.LEQ -> LESS_EQUAL
      BinaryOperators.GEQ -> GREATER_EQUAL
      BinaryOperators.EQ -> EQUAL
      BinaryOperators.NEQ -> NOT_EQUAL
      else -> logger.throwICE("BinaryOperators instance not a comparison op") { op }
    }
  }
}

/**
 * Represents instructions that operate on integral arguments. Its operands are of type
 * [slak.ckompiler.parser.IntegralType].
 */
sealed class IntegralInstruction : IRInstruction()

enum class IntegralBinaryOps(val operator: String) {
  ADD(Punctuators.PLUS.s), SUB(Punctuators.MINUS.s),
  MUL(Punctuators.STAR.s), DIV(Punctuators.SLASH.s), REM(Punctuators.PERCENT.s),
  LSH(Punctuators.LSH.s), RSH(Punctuators.RSH.s),
  AND(Punctuators.AMP.s), OR(Punctuators.PIPE.s), XOR(Punctuators.CARET.s);

  override fun toString() = operator
}

data class IntBinary(
    override val result: LoadableValue,
    val op: IntegralBinaryOps,
    override val lhs: IRValue,
    override val rhs: IRValue
) : IntegralInstruction(), BinaryInstruction {
  override fun toString() = "$result = int op $lhs $op $rhs"
}

data class IntCmp(
    override val result: LoadableValue,
    override val lhs: IRValue,
    override val rhs: IRValue,
    val cmp: Comparisons
) : IntegralInstruction(), BinaryInstruction {
  override fun toString() = "$result = int cmp $lhs $cmp $rhs"
}

/**
 * Flip bits: ~
 */
data class IntInvert(
    override val result: LoadableValue,
    val operand: IRValue
) : IntegralInstruction() {
  override fun toString() = "$result = invert $operand"
}

data class IntNeg(
    override val result: LoadableValue,
    val operand: IRValue
) : IntegralInstruction() {
  override fun toString() = "$result = int negate $operand"
}

/**
 * Represents instructions that operate on float arguments. Its operands are of type
 * [slak.ckompiler.parser.FloatingType].
 */
sealed class FloatingPointInstruction : IRInstruction()

enum class FloatingBinaryOps(val operator: String) {
  ADD(Punctuators.PLUS.s), SUB(Punctuators.MINUS.s),
  MUL(Punctuators.STAR.s), DIV(Punctuators.SLASH.s);

  override fun toString() = operator
}

data class FltBinary(
    override val result: LoadableValue,
    val op: FloatingBinaryOps,
    override val lhs: IRValue,
    override val rhs: IRValue
) : FloatingPointInstruction(), BinaryInstruction {
  override fun toString() = "$result = flt op $lhs $op $rhs"
}

data class FltCmp(
    override val result: LoadableValue,
    override val lhs: IRValue,
    override val rhs: IRValue,
    val cmp: Comparisons
) : FloatingPointInstruction(), BinaryInstruction {
  override fun toString() = "$result = flt cmp $lhs $cmp $rhs"
}

data class FltNeg(
    override val result: LoadableValue,
    val operand: IRValue
) : FloatingPointInstruction() {
  override fun toString() = "$result = flt negate $operand"
}

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
 * A [LoadableValue] that will sit in a register after it is allocated.
 */
sealed class AllocatableValue : LoadableValue()

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

enum class VRegType {
  REGULAR, UNDEFINED, CONSTRAINED
}

/**
 * A virtual register where an [IRInstruction]'s result is stored. These registers abide by SSA, so
 * they are only written to once. They also cannot escape the [BasicBlock] they're declared in.
 */
data class VirtualRegister(
    val id: AtomicId,
    override val type: TypeName,
    val kind: VRegType = VRegType.REGULAR
) : AllocatableValue() {
  override val isUndefined: Boolean = kind != VRegType.REGULAR

  override val name = "${if (isUndefined) "dummy" else type.toString()} vreg$id"
  override fun toString() = name

  override fun equals(other: Any?) = (other as? VirtualRegister)?.id == id
  override fun hashCode() = id
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
class Variable(val tid: TypedIdentifier) : AllocatableValue() {
  val id get() = tid.id

  override val name get() = tid.name
  override val type get() = tid.type

  var version = 0
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
 * Represents a dereferenced pointer.
 */
data class MemoryLocation(val ptr: IRValue) : LoadableValue() {
  init {
    require(ptr.type is PointerType)
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
 * Return a copy of an [IRValue], coercing its type to the provided parameter.
 */
fun MachineTargetData.copyWithType(value: IRValue, type: TypeName): IRValue = when (value) {
  is VirtualRegister -> value.copy(type = type)
  is Variable -> Variable(value.tid.copy(type = type))
  is PhysicalRegister ->
    if (sizeOf(type) in value.reg.aliases.map { it.second } || sizeOf(type) == value.reg.sizeBytes) {
      value.copy(type = type)
    } else {
      TODO()
    }
  is MemoryLocation -> TODO()
  is StackVariable -> TODO()
  is IntConstant -> value.copy(type = type) // FIXME: check type limits
  is FltConstant -> TODO()
  is StrConstant -> TODO()
  is ParameterReference, is JumpTargetConstant, is NamedConstant -> logger.throwICE("Illegal target of IRValue copy")
}
