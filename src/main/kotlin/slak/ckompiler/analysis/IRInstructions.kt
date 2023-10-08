package slak.ckompiler.analysis

import kotlinx.serialization.Serializable
import io.github.oshai.kotlinlogging.KotlinLogging
import slak.ckompiler.analysis.external.PhiInstructionSerializer
import slak.ckompiler.lexer.Punctuators
import slak.ckompiler.parser.BinaryOperators
import slak.ckompiler.parser.PointerType
import slak.ckompiler.printVariableVersions
import slak.ckompiler.throwICE
import kotlin.js.JsExport

private val logger = KotlinLogging.logger {}

/**
 * Common superclass of all IR instructions.
 */
@Serializable
@JsExport
sealed class IRInstruction {
  abstract val result: LoadableValue

  abstract override fun toString(): String
}

/**
 * A φ-function for a particular variable. [incoming] stores the list of versions that the φ has
 * to choose from; that is, it stores which values come from which predecessor [BasicBlock].
 */
@Serializable(with = PhiInstructionSerializer::class)
@JsExport
data class PhiInstruction(
    val variable: Variable,
    val incoming: Map<BasicBlock, Variable>,
) {
  override fun toString() = "store $variable = φ(${
    incoming.entries.joinToString(", ") {
      if (printVariableVersions) {
        "BB${it.key.hashCode()} v${it.value.version}"
      } else {
        "BB${it.key.hashCode()}"
      }
    }
  })"
}

/**
 * Read the value pointed at by [loadFrom], and store it in [result].
 */
@Serializable
@JsExport
data class LoadMemory(
    override val result: LoadableValue,
    val loadFrom: IRValue,
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
@Serializable
@JsExport
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
@Serializable
@JsExport
data class MoveInstr(override val result: LoadableValue, val value: IRValue) : IRInstruction() {
  override fun toString() = "move $result = $value"
}

/**
 * Modifies [operand] to have the type specified by [result]. May fail if the specified conversion
 * is impossible.
 */
@Serializable
@JsExport
data class StructuralCast(
    override val result: LoadableValue,
    val operand: IRValue,
) : IRInstruction() {
  override fun toString() = "$result = cast $operand to ${result.type}"
}

/**
 * Reinterprets [operand]'s data as another type. Basically changes the type without touching the
 * data in memory.
 */
@Serializable
@JsExport
data class ReinterpretCast(
    override val result: LoadableValue,
    val operand: IRValue,
) : IRInstruction() {
  override fun toString() = "$result = reinterpret $operand"
}

/**
 * A call to a named function. This type of call just assumes the function referred to by [name]
 * will exist at link time (that is, it doesn't check if the function named exists, because it might
 * be linked in later).
 */
@Serializable
@JsExport
data class NamedCall(
    override val result: LoadableValue,
    val name: NamedConstant,
    val args: List<IRValue>,
) : IRInstruction() {
  override fun toString() = "$result = call $name with args ${args.joinToString(", ")}"
}

/**
 * A call to the function specified by the function pointer stored in [callable].
 */
@Serializable
@JsExport
data class IndirectCall(
    override val result: LoadableValue,
    val callable: VirtualRegister,
    val args: List<IRValue>,
) : IRInstruction() {
  override fun toString() = "$result = call *($callable) with args ${args.joinToString(", ")}"
}

/**
 * Generic instruction with two operands.
 */
@JsExport
interface BinaryInstruction {
  val result: LoadableValue
  val lhs: IRValue
  val rhs: IRValue
}

/**
 * Lists possible comparison orders for arithmetic types.
 */
@Serializable
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
@Serializable
@JsExport
sealed class IntegralInstruction : IRInstruction()

@Serializable
enum class IntegralBinaryOps(val operator: String) {
  ADD(Punctuators.PLUS.s), SUB(Punctuators.MINUS.s),
  MUL(Punctuators.STAR.s), DIV(Punctuators.SLASH.s), REM(Punctuators.PERCENT.s),
  LSH(Punctuators.LSH.s), RSH(Punctuators.RSH.s),
  AND(Punctuators.AMP.s), OR(Punctuators.PIPE.s), XOR(Punctuators.CARET.s);

  override fun toString() = operator
}

@Serializable
@JsExport
data class IntBinary(
    override val result: LoadableValue,
    val op: IntegralBinaryOps,
    override val lhs: IRValue,
    override val rhs: IRValue,
) : IntegralInstruction(), BinaryInstruction {
  override fun toString() = "$result = int op $lhs $op $rhs"
}

@Serializable
@JsExport
data class IntCmp(
    override val result: LoadableValue,
    override val lhs: IRValue,
    override val rhs: IRValue,
    val cmp: Comparisons,
) : IntegralInstruction(), BinaryInstruction {
  override fun toString() = "$result = int cmp $lhs $cmp $rhs"
}

/**
 * Flip bits: ~
 */
@Serializable
@JsExport
data class IntInvert(
    override val result: LoadableValue,
    val operand: IRValue,
) : IntegralInstruction() {
  override fun toString() = "$result = invert $operand"
}

@Serializable
@JsExport
data class IntNeg(
    override val result: LoadableValue,
    val operand: IRValue,
) : IntegralInstruction() {
  override fun toString() = "$result = int negate $operand"
}

/**
 * Represents instructions that operate on float arguments. Its operands are of type
 * [slak.ckompiler.parser.FloatingType].
 */
@Serializable
@JsExport
sealed class FloatingPointInstruction : IRInstruction()

@Serializable
enum class FloatingBinaryOps(val operator: String) {
  ADD(Punctuators.PLUS.s), SUB(Punctuators.MINUS.s),
  MUL(Punctuators.STAR.s), DIV(Punctuators.SLASH.s);

  override fun toString() = operator
}

@Serializable
@JsExport
data class FltBinary(
    override val result: LoadableValue,
    val op: FloatingBinaryOps,
    override val lhs: IRValue,
    override val rhs: IRValue,
) : FloatingPointInstruction(), BinaryInstruction {
  override fun toString() = "$result = flt op $lhs $op $rhs"
}

@Serializable
@JsExport
data class FltCmp(
    override val result: LoadableValue,
    override val lhs: IRValue,
    override val rhs: IRValue,
    val cmp: Comparisons,
) : FloatingPointInstruction(), BinaryInstruction {
  override fun toString() = "$result = flt cmp $lhs $cmp $rhs"
}

@Serializable
@JsExport
data class FltNeg(
    override val result: LoadableValue,
    val operand: IRValue,
) : FloatingPointInstruction() {
  override fun toString() = "$result = flt negate $operand"
}
