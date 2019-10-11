package slak.ckompiler.analysis

import org.apache.logging.log4j.LogManager
import slak.ckompiler.lexer.Punctuators
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

private val logger = LogManager.getLogger()

/**
 * Common superclass of all IR instructions.
 */
sealed class IRInstruction

/**
 * An [IRInstruction] that returns its result in the [result] register.
 */
sealed class ResultInstruction : IRInstruction() {
  abstract val result: VirtualRegister
}

/**
 * A φ-function that's part of a [BasicBlock]. [incoming] stores the list of versions that the φ has
 * to choose from; that is, it stores which values come from which predecessor [BasicBlock].
 */
data class PhiInstr(
    val variable: Variable,
    val incoming: Map<BasicBlock, Variable>
) : IRInstruction() {
  override fun toString() = "$variable = φ(${incoming.entries.joinToString(", ") {
    "n${it.key.hashCode()} v${it.value.version}"
  }})"
}

/**
 * Stores a [value] to a memory [target].
 */
data class VarStoreInstr(val target: Variable, val value: IRValue) : IRInstruction() {
  override fun toString() = "store $target = $value"
}

/**
 * Stores a [value] to a memory location specified by [ptr].
 */
data class DataStoreInstr(val ptr: IRValue, val value: IRValue) : IRInstruction() {
  override fun toString() = "store *($ptr) = $value"
}

/**
 * Stores a [ConstantValue] in a [VirtualRegister].
 *
 * This exists so that a constant expression (think `return 0;`) can be transformed to a list of
 * valid [IRInstruction]s.
 */
data class ConstantRegisterInstr(
    override val result: VirtualRegister,
    val const: ConstantValue
) : ResultInstruction() {
  override fun toString() = "$result = $const"
}

/**
 * Loads the value from the specified memory [target].
 */
data class LoadInstr(
    override val result: VirtualRegister,
    val target: Variable
) : ResultInstruction() {
  override fun toString() = "$result = load $target"
}

/**
 * Modifies [operand] to have the type specified by [castTo]. May fail if the specified conversion
 * is impossible.
 */
data class StructuralCast(
    override val result: VirtualRegister,
    val castTo: TypeName,
    val operand: IRValue
) : ResultInstruction() {
  override fun toString() = "$result = cast $operand to $castTo"
}

/**
 * Reinterprets [operand]'s data as another type. Basically changes the type without touching the
 * data in memory.
 */
data class ReinterpretCast(
    override val result: VirtualRegister,
    val castTo: TypeName,
    val operand: IRValue
) : ResultInstruction() {
  override fun toString() = "$result = reinterpret $operand as $castTo"
}

/**
 * Maps to the unary & operator as applied to an identifier.
 *
 * This prevents transforming the [variable] to a [VirtualRegister]; it will always be stored and
 * loaded from memory when necessary.
 */
data class AddressOfVar(
    override val result: VirtualRegister,
    val variable: Variable
) : ResultInstruction() {
  override fun toString() = "$result = addressof $variable"
}

/**
 * Maps to the unary & operator as applied to an lvalue other than an identifier.
 */
data class AddressOf(
    override val result: VirtualRegister,
    val expr: IRValue
) : ResultInstruction() {
  override fun toString() = "$result = addressof ($expr)"
}

/**
 * Maps to the unary * operator.
 */
data class ValueOf(override val result: VirtualRegister, val ptr: IRValue) : ResultInstruction() {
  override fun toString() = "$result = dereference $ptr"
}

/**
 * A call to a named function. This type of call just assumes the function referred to by [name]
 * will exist at link time (that is, it doesn't check if the function named exists, because it might
 * be linked in later).
 */
data class NamedCall(
    override val result: VirtualRegister,
    val name: String,
    val type: FunctionType,
    val args: List<IRValue>
) : ResultInstruction() {
  override fun toString() = "$result = call $name with args ${args.joinToString(", ")}"
}

/**
 * A call to the function specified by the function pointer stored in [callable].
 */
data class IndirectCall(
    override val result: VirtualRegister,
    val callable: VirtualRegister,
    val args: List<IRValue>
) : ResultInstruction() {
  override fun toString() = "$result = call *($callable) with args ${args.joinToString(", ")}"
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
sealed class IntegralInstruction : ResultInstruction()

enum class IntegralBinaryOps(val operator: String) {
  ADD(Punctuators.PLUS.s), SUB(Punctuators.MINUS.s),
  MUL(Punctuators.STAR.s), DIV(Punctuators.SLASH.s), REM(Punctuators.PERCENT.s),
  LSH(Punctuators.LSH.s), RSH(Punctuators.RSH.s),
  AND(Punctuators.AMP.s), OR(Punctuators.PIPE.s), XOR(Punctuators.CARET.s);

  override fun toString() = operator
}

data class IntBinary(
    override val result: VirtualRegister,
    val op: IntegralBinaryOps,
    val lhs: IRValue,
    val rhs: IRValue
) : IntegralInstruction() {
  override fun toString() = "$result = int op $lhs $op $rhs"
}

data class IntCmp(
    override val result: VirtualRegister,
    val lhs: IRValue,
    val rhs: IRValue,
    val cmp: Comparisons
) : IntegralInstruction() {
  override fun toString() = "$result = int cmp $lhs $cmp $rhs"
}

/**
 * Flip bits: ~
 */
data class IntInvert(
    override val result: VirtualRegister,
    val operand: IRValue
) : IntegralInstruction() {
  override fun toString() = "$result = invert $operand"
}

data class IntNeg(
    override val result: VirtualRegister,
    val operand: IRValue
) : IntegralInstruction() {
  override fun toString() = "$result = int negate $operand"
}

/**
 * Represents instructions that operate on float arguments. Its operands are of type
 * [slak.ckompiler.parser.FloatingType].
 */
sealed class FloatingPointInstruction : ResultInstruction()

enum class FloatingBinaryOps(val operator: String) {
  ADD(Punctuators.PLUS.s), SUB(Punctuators.MINUS.s),
  MUL(Punctuators.STAR.s), DIV(Punctuators.SLASH.s);

  override fun toString() = operator
}

data class FltBinary(
    override val result: VirtualRegister,
    val op: FloatingBinaryOps,
    val lhs: IRValue,
    val rhs: IRValue
) : FloatingPointInstruction() {
  override fun toString() = "$result = flt op $lhs $op $rhs"
}

data class FltCmp(
    override val result: VirtualRegister,
    val lhs: IRValue,
    val rhs: IRValue,
    val cmp: Comparisons
) : FloatingPointInstruction() {
  override fun toString() = "$result = flt cmp $lhs $cmp $rhs"
}

data class FltNeg(
    override val result: VirtualRegister,
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

typealias RegisterId = Int

/**
 * A virtual register where an [IRInstruction]'s result is stored. These registers abide by SSA, so
 * they are only written to once.
 */
data class VirtualRegister(
    val id: RegisterId,
    override val type: TypeName
) : IRValue() {
  override val name = "reg$id"
  override fun toString() = name
}

sealed class ConstantValue : IRValue()

data class IntConstant(val value: Long, override val type: TypeName) : ConstantValue() {
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

/**
 * The [variable] definition that reaches a point in the CFG, along with information about where it
 * was defined. If [definitionIdx] is -1, the definition occurred in a φ-function.
 *
 * @see VariableRenamer.reachingDefs
 */
data class ReachingDefinition(
    val variable: Variable,
    val definedIn: BasicBlock,
    val definitionIdx: Int
)

/**
 * Wraps a [tid], adds [version]. Represents a non-SSA value in the IR: stores and loads operate on
 * [Variable]s. These values are stored in memory, and SSA conversion tries to convert these to
 * [VirtualRegister]s. The [version] starts as 0, the variable renaming process updates that value,
 * and a pass does the virtual register replacement.
 * @see ReachingDefinition
 */
class Variable(val tid: TypedIdentifier) {
  var version = 0
    private set

  fun copy(version: Int): Variable {
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
    var result = tid.hashCode()
    result = 31 * result + version
    result = 31 * result + tid.id
    return result
  }
}
