package slak.ckompiler.parser

import org.apache.logging.log4j.LogManager
import slak.ckompiler.*
import slak.ckompiler.lexer.LexicalToken
import slak.ckompiler.lexer.Punctuator
import slak.ckompiler.parser.BinaryOperators.*
import slak.ckompiler.parser.UnaryOperators.*

private val logger = LogManager.getLogger("TypeSystem")

fun typeNameOfTag(tagSpecifier: TagSpecifier): TypeName {
  val tagName = if (tagSpecifier.isAnonymous) null else tagSpecifier.tagIdent.name
//  val tagDef = if (tagSpecifier.isAnonymous) null else searchTag(tagSpecifier.tagIdent)
  // FIXME: How do we deal with incomplete struct/union types?
  val tagDef: TagSpecifier? = null // FIXME: ???
  // The tag type differs, so error
  if (tagDef != null && tagDef.tagKindKeyword != tagSpecifier.tagKindKeyword) return ErrorType
  val tagMembers = when (tagDef) {
    is StructDefinition -> tagDef.decls
    is UnionDefinition -> tagDef.decls
    else -> null
  }?.flatMap { it.declaratorList.map { (declarator, _) -> typeNameOf(it.declSpecs, declarator) } }
  return when (tagSpecifier) {
    is StructNameSpecifier -> StructureType(tagName, tagMembers)
    is UnionNameSpecifier -> UnionType(tagName, tagMembers)
    is StructDefinition -> {
      if (tagMembers != null) ErrorType
      else StructureType(tagName, tagMembers)
    }
    is UnionDefinition -> {
      if (tagMembers != null) ErrorType
      else UnionType(tagName, tagMembers)
    }
  }
}

fun typeNameOf(specQuals: DeclarationSpecifier, decl: Declarator): TypeName {
  if (decl is ErrorDeclarator || specQuals.isBlank()) return ErrorType
  // Pointers
  if (decl.indirection.isNotEmpty()) {
    val referencedType = typeNameOf(specQuals, AbstractDeclarator(emptyList(), decl.suffixes))
    return decl.indirection.fold(referencedType) { type, curr -> PointerType(type, curr) }
  }
  // Functions
  if (decl.isFunction()) {
    val ptl = decl.getFunctionTypeList()
    val paramTypes = ptl.params.map { typeNameOf(it.declSpec, it.declarator) }
    val retType = typeNameOf(specQuals, AbstractDeclarator(emptyList(), decl.suffixes.drop(1)))
    return FunctionType(retType, paramTypes, ptl.variadic)
  }
  // Structure/Union
  if (specQuals.isTag()) return typeNameOfTag(specQuals.typeSpec as TagSpecifier)
  // Arrays
  if (decl.isArray()) {
    val size = decl.getArrayTypeSize()
    val elemType = typeNameOf(specQuals, AbstractDeclarator(emptyList(), decl.suffixes.drop(1)))
    return ArrayType(elemType, size)
  }
  return when (specQuals.typeSpec) {
    null, is TagSpecifier -> ErrorType
    is EnumSpecifier -> TODO("enums not implemented yet")
    is TypedefNameSpecifier -> specQuals.typeSpec.typedefName.type
    is VoidTypeSpec -> VoidType
    is Bool -> BooleanType
    is Signed, is IntType, is SignedInt -> SignedIntType
    is Unsigned, is UnsignedInt -> UnsignedIntType
    is SignedChar, is Char -> SignedCharType
    is UnsignedChar -> UnsignedCharType
    is SignedShort, is Short -> SignedShortType
    is UnsignedShort -> UnsignedShortType
    is SignedLong, is LongType -> SignedLongType
    is UnsignedLong -> UnsignedLongType
    is LongLong, is SignedLongLong -> SignedLongLongType
    is UnsignedLongLong -> UnsignedLongLongType
    is FloatTypeSpec -> FloatType
    is DoubleTypeSpec -> DoubleType
    is LongDouble -> LongDoubleType
  }
}

/**
 * Instances represent types.
 *
 * C standard: 6.2.5, 6.2.5.0.20
 */
sealed class TypeName {
  /**
   * @return null if this type can't be called, or the [FunctionType] to call otherwise
   */
  fun asCallable(): FunctionType? = when (this) {
    is FunctionType -> this
    is PointerType -> this.referencedType as? FunctionType
    else -> null
  }

  fun isCallable() = asCallable() != null

  fun isRealType(): Boolean = this is ArithmeticType // We don't implement complex types yet.

  /** C standard: 6.2.5.0.21 */
  fun isScalar(): Boolean = this is ArithmeticType || this is PointerType

  /**
   * FIXME: check for incomplete struct/union types; they are currently not even represented
   *
   * C standard: 6.2.5.0.1
   */
  fun isCompleteObjectType() = this !is FunctionType && this !is VoidType &&
      (this as? ArrayType)?.size !is NoSize

  /**
   * System V ABI: 3.2.3, page 17
   */
  fun isSSEType() = this is FloatType || this is DoubleType

  /**
   * System V ABI: 3.2.3, page 17
   */
  fun isABIIntegerType() = this is IntegralType || this is PointerType
}

/**
 * Represents the type of an expression that is invalid, either due to syntax errors, or due to
 * violations of semantic requirements.
 */
object ErrorType : TypeName() {
  override fun toString() = "<error type>"
}

/**
 * All pointer types are complete.
 *
 * C standard: 6.2.5
 */
data class PointerType(val referencedType: TypeName, val ptrQuals: TypeQualifierList) : TypeName() {
  override fun toString() = "$referencedType ${ptrQuals.stringify()}"
}

data class FunctionType(val returnType: TypeName,
                        val params: List<TypeName>,
                        val variadic: Boolean = false) : TypeName() {
  override fun toString(): String {
    // This doesn't really work when the return type is a function/array, but that isn't valid
    // anyway
    return "$returnType (${params.joinToString()})"
  }
}

data class ArrayType(val elementType: TypeName, val size: ArrayTypeSize) : TypeName() {
  override fun toString() = "$elementType[$size]"
}

// FIXME: implement these too
data class BitfieldType(val elementType: TypeName, val size: Expression) : TypeName()

data class StructureType(val name: String?, val members: List<TypeName>?) : TypeName() {
  override fun toString(): String {
    val nameStr = if (name == null) "" else "$name "
    return "struct $nameStr{...}"
  }
}

data class UnionType(val name: String?, val optionTypes: List<TypeName>?) : TypeName() {
  override fun toString(): String {
    val nameStr = if (name == null) "" else "$name "
    return "union $nameStr{...}"
  }
}

sealed class BasicType : TypeName()

/** C standard: 6.2.5.0.18 */
sealed class ArithmeticType : BasicType() {
  /**
   * If integer promotions are applied to this type, [promotedType] is the resulting type.
   *
   * FIXME: bitfields are not handled
   * FIXME: the results of these promotions can depend on the size of the types
   *
   * C standard: 6.3.1.1.0.2
   */
  abstract val promotedType: ArithmeticType
}

sealed class IntegralType : ArithmeticType(), Comparable<IntegralType> {
  /** C standard: 6.3.1.1.0.1 */
  abstract val conversionRank: Int
  /**
   * The same type with opposite signedness. Examples:
   * [SignedLongLongType] -> [UnsignedLongLongType]
   * [UnsignedCharType] -> [SignedCharType]
   * [BooleanType] -> throws ICE
   */
  abstract val corespondingType: IntegralType

  /** @see conversionRank */
  override operator fun compareTo(other: IntegralType) = other.conversionRank - this.conversionRank
}

/**
 * We consider character types to be integral, and char to behave as signed char.
 *
 * C standard: 6.2.5.0.4, 6.2.5.0.15
 */
sealed class SignedIntegralType : IntegralType()

object SignedCharType : SignedIntegralType() {
  override val conversionRank = 0x0000F
  override val corespondingType = UnsignedCharType
  override val promotedType = SignedIntType
  override fun toString() = "signed char"
}

object SignedShortType : SignedIntegralType() {
  override val conversionRank = 0x000F0
  override val corespondingType = UnsignedShortType
  override val promotedType = SignedIntType
  override fun toString() = "signed short"
}

object SignedIntType : SignedIntegralType() {
  override val conversionRank = 0x00F00
  override val corespondingType = UnsignedIntType
  override val promotedType = SignedIntType
  override fun toString() = "signed int"
}

object SignedLongType : SignedIntegralType() {
  override val conversionRank = 0x0F000
  override val corespondingType = UnsignedLongType
  override val promotedType = SignedLongType
  override fun toString() = "signed long"
}

object SignedLongLongType : SignedIntegralType() {
  override val conversionRank = 0xF0000
  override val corespondingType = UnsignedLongLongType
  override val promotedType = SignedLongLongType
  override fun toString() = "signed long long"
}

/** C standard: 6.2.5.0.6 */
sealed class UnsignedIntegralType : IntegralType()

object BooleanType : UnsignedIntegralType() {
  override val conversionRank = 0x00001
  override val corespondingType get() = logger.throwICE("No signed corespondent for _Bool")
  override val promotedType = SignedIntType
  override fun toString() = "_Bool"
}

object UnsignedCharType : UnsignedIntegralType() {
  override val conversionRank = SignedCharType.conversionRank
  override val corespondingType = SignedCharType
  override val promotedType = SignedIntType
  override fun toString() = "unsigned char"
}

object UnsignedShortType : UnsignedIntegralType() {
  override val conversionRank = SignedShortType.conversionRank
  override val corespondingType = SignedShortType
  override val promotedType = SignedIntType
  override fun toString() = "unsigned short"
}

object UnsignedIntType : UnsignedIntegralType() {
  override val conversionRank = SignedIntType.conversionRank
  override val corespondingType = SignedIntType
  override val promotedType = UnsignedIntType
  override fun toString() = "unsigned int"
}

object UnsignedLongType : UnsignedIntegralType() {
  override val conversionRank = SignedLongType.conversionRank
  override val corespondingType = SignedLongType
  override val promotedType = UnsignedLongType
  override fun toString() = "unsigned long"
}

object UnsignedLongLongType : UnsignedIntegralType() {
  override val conversionRank = SignedLongLongType.conversionRank
  override val corespondingType = SignedLongLongType
  override val promotedType = UnsignedLongLongType
  override fun toString() = "unsigned long long"
}

/** C standard: 6.2.5.0.10 */
sealed class FloatingType : ArithmeticType()

object FloatType : FloatingType() {
  override val promotedType = FloatType
  override fun toString() = "float"
}

object DoubleType : FloatingType() {
  override val promotedType = DoubleType
  override fun toString() = "double"
}

object LongDoubleType : FloatingType() {
  override val promotedType = LongDoubleType
  override fun toString() = "long double"
}

object VoidType : BasicType() {
  override fun toString() = "void"
}

/**
 * Applies the usual arithmetic conversions from the standard. Propagates [ErrorType].
 *
 * Throws if either type is not arithmetic.
 *
 * C standard: 6.3.1.8
 * @return the "common real type" everything gets converted to
 */
fun usualArithmeticConversions(lhs: TypeName, rhs: TypeName): TypeName {
  if (lhs is ErrorType || rhs is ErrorType) return ErrorType
  if (lhs !is ArithmeticType || rhs !is ArithmeticType) {
    logger.throwICE("Applying arithmetic conversions on non-arithmetic operands") {
      "lhs: $lhs, rhs: $rhs"
    }
  }
  if (lhs is LongDoubleType || rhs is LongDoubleType) return LongDoubleType
  if (lhs is DoubleType || rhs is DoubleType) return DoubleType
  if (lhs is FloatType || rhs is FloatType) return FloatType
  val lInt = lhs.promotedType as IntegralType
  val rInt = rhs.promotedType as IntegralType
  if (lInt.javaClass == rInt.javaClass) return lhs
  val big = maxOf(lInt, rInt)
  val small = minOf(lInt, rInt)
  // Check for same signed-ness
  if (lInt is UnsignedIntegralType == rInt is UnsignedIntegralType) return big
  if (big is UnsignedIntegralType) return big
  big as SignedIntegralType
  // Check if big is *strictly* bigger
  if (big > small) return big
  return big.corespondingType
}

/**
 * Validates the [TypeName]s used in a cast. No-op if [targetType] is [ErrorType].
 *
 * C standard: 6.5.4
 * @param castParenRange diagnostic range for the parenthesised cast type name
 */
fun IDebugHandler.validateCast(originalType: TypeName,
                               targetType: TypeName,
                               castParenRange: SourcedRange) = when {
  targetType is ErrorType -> {
    // Don't report bogus diags for ErrorType
  }
  targetType !is VoidType && !targetType.isScalar() -> diagnostic {
    id = DiagnosticId.INVALID_CAST_TYPE
    formatArgs(targetType.toString())
    errorOn(castParenRange)
  }
  (originalType is FloatingType && targetType is PointerType) ||
      (originalType is PointerType && targetType is FloatingType) -> diagnostic {
    id = DiagnosticId.POINTER_FLOAT_CAST
    val floating = originalType as? FloatingType ?: targetType
    val pointer = originalType as? PointerType ?: targetType
    formatArgs(floating.toString(), pointer.toString())
    errorOn(castParenRange)
  }
  else -> {
    // Nothing to say about other types
  }
}

/**
 * One of the expressions must be a pointer to a complete object type (or an array, which is
 * technically the same thing). The other must be an integral type. Return what type does the
 * subscript will have, and whether or not [subscripted] and [subscript] are swapped.
 *
 * C standard: 6.5.2.1.0.1
 */
fun IDebugHandler.typeOfSubscript(subscripted: Expression,
                                  subscript: Expression,
                                  endSqBracket: LexicalToken): Pair<TypeName, Boolean> {
  val fullRange = subscripted..endSqBracket

  fun TypeName.isSubscriptable() =
      (this is PointerType && this.referencedType.isCompleteObjectType()) || this is ArrayType

  fun processSubscript(subscripted: Expression, subscript: Expression): TypeName {
    if (subscripted.type.isCallable()) {
      diagnostic {
        id = DiagnosticId.SUBSCRIPT_OF_FUNCTION
        formatArgs(subscripted.type.toString())
        errorOn(subscripted)
      }
      return ErrorType
    }
    if (!subscripted.type.isSubscriptable()) {
      diagnostic {
        id = DiagnosticId.INVALID_SUBSCRIPTED
        errorOn(fullRange)
      }
      return ErrorType
    }
    // Don't report bogus diagnostics
    if (subscript.type is ErrorType) return ErrorType
    if (subscript.type !is IntegralType) {
      diagnostic {
        id = DiagnosticId.SUBSCRIPT_NOT_INTEGRAL
        errorOn(subscript)
      }
      return ErrorType
    }
    return (subscripted.type as? ArrayType)?.elementType
        ?: (subscripted.type as? PointerType)?.referencedType
        ?: logger.throwICE("Subscripted type is either array or pointer") { subscripted.type }
  }

  if (subscripted.type.isCallable()) {
    diagnostic {
      id = DiagnosticId.SUBSCRIPT_OF_FUNCTION
      formatArgs(subscripted.type.toString())
      errorOn(subscripted)
    }
    return ErrorType to false
  }
  return if (!subscripted.type.isSubscriptable()) {
    // Try swapping the subscripted/subscript, and fail if it doesn't work
    // This is the `123[vec]` case
    processSubscript(subscript, subscripted) to true
  } else {
    processSubscript(subscripted, subscript) to false
  }
}

/**
 * FIXME: where do the these promotions actually happen?
 *
 * C standard: 6.5.3.3.1, 6.5.3.3.5
 * @return the type of the expression after applying a unary operator ([ErrorType] if it can't be
 * applied)
 */
fun UnaryOperators.applyTo(target: TypeName): TypeName = when (this) {
  PLUS, MINUS -> if (target !is ArithmeticType) ErrorType else target.promotedType
  BIT_NOT -> {
    // FIXME: do 6.5.3.3.4
    if (target !is IntegralType) ErrorType else target.promotedType
  }
  NOT -> {
    // The result of this operator is always `int`, as per 6.5.3.3.5
    if (!target.isScalar()) ErrorType else SignedIntType
  }
  // FIXME: check ALL the constraints from 6.5.3.2.1
  REF -> when (target) {
    is ErrorType -> ErrorType
    is PointerType -> {
      if (target.referencedType is FunctionType) target
      else PointerType(target, emptyList())
    }
    else -> PointerType(target, emptyList())
  }
  DEREF -> {
    if (target !is PointerType) ErrorType else target.referencedType
  }
}

/**
 * Boilerplate that checks [lhs]/[rhs] to be [ArithmeticType], then applies
 * [usualArithmeticConversions]. Returns [ErrorType] if a check fails.
 */
private fun arithmOp(lhs: TypeName, rhs: TypeName): TypeName {
  return if (lhs !is ArithmeticType || rhs !is ArithmeticType) ErrorType
  else usualArithmeticConversions(lhs, rhs)
}

/**
 * Boilerplate that checks [lhs]/[rhs] to be [IntegralType], then applies
 * [usualArithmeticConversions]. Returns [ErrorType] if a check fails.
 */
private fun intOp(lhs: TypeName, rhs: TypeName): TypeName {
  return if (lhs !is IntegralType || rhs !is IntegralType) ErrorType
  else usualArithmeticConversions(lhs, rhs)
}

/**
 * C standard: 6.5.5.0.2, 6.5.5, 6.5.6, 6.5.7, 6.5.8, 6.5.8.0.6, 6.5.9, 6.5.10, 6.5.11, 6.5.12,
 * 6.5.13, 6.5.14, 6.5.17
 * @return the type of the expression after applying the binary operator on the operands
 * ([ErrorType] if it can't be applied)
 */
fun BinaryOperators.applyTo(lhs: TypeName, rhs: TypeName): TypeName = when (this) {
  MUL, DIV -> arithmOp(lhs, rhs)
  MOD -> intOp(lhs, rhs)
  ADD -> {
    val ptr = lhs as? PointerType ?: rhs as? PointerType
    if (ptr == null) {
      // Regular arithmetic
      arithmOp(lhs, rhs)
    } else {
      // Pointer arithmetic
      // FIXME: handle ArrayType (6.5.6.0.8)
      // FIXME: the ptr.referencedType must be a complete object type (6.5.6.0.2)
      val other = if (lhs is PointerType) rhs else lhs
      if (other !is IntegralType) ErrorType else ptr
    }
  }
  SUB -> {
    val ptr = lhs as? PointerType
    // FIXME: the ptr.referencedType must be a complete object type (6.5.6.0.2)
    if (ptr == null) {
      // Regular arithmetic
      arithmOp(lhs, rhs)
    } else {
      // Pointer arithmetic
      val otherPtr = rhs as? PointerType
      // FIXME: handle ArrayType (6.5.6.0.8)
      // FIXME: the otherPtr.referencedType must be a complete object type (6.5.6.0.2)
      if (otherPtr == null) {
        // ptr minus integer case
        if (rhs !is IntegralType) ErrorType else ptr
      } else {
        // ptr minus ptr case
        // FIXME: this should actually return ptrdiff_t (6.5.6.0.9)
        // FIXME: overly strict check, compatibles are allowed (6.5.6.0.3)
        if (ptr.referencedType != otherPtr.referencedType) ErrorType else ptr
      }
    }
  }
  LSH, RSH -> intOp(lhs, rhs)
  LT, GT, LEQ, GEQ -> {
    val lPtr = lhs as? PointerType
    val rPtr = rhs as? PointerType
    if (lPtr == null || rPtr == null) {
      // FIXME: the arithmetic conversions get lost here
      arithmOp(lhs, rhs)
      if (lhs.isRealType() && rhs.isRealType()) SignedIntType else ErrorType
    } else {
      // FIXME: the referenced types must be compatible object types (6.5.8.0.2)
      SignedIntType
    }
  }
  EQ, NEQ -> {
    if (lhs is ArithmeticType && rhs is ArithmeticType) {
      usualArithmeticConversions(lhs, rhs)
    } else {
      TODO("lots of pointer arithmetic shit (6.5.9)")
    }
  }
  BIT_AND, BIT_XOR, BIT_OR -> intOp(lhs, rhs)
  AND, OR -> {
    if (!lhs.isScalar() || !rhs.isScalar()) ErrorType else SignedIntType
  }
  // FIXME: assignment operator semantics are very complicated (6.5.16)
  ASSIGN -> lhs
  MUL_ASSIGN -> lhs
  DIV_ASSIGN -> lhs
  MOD_ASSIGN -> lhs
  PLUS_ASSIGN -> lhs
  SUB_ASSIGN -> lhs
  LSH_ASSIGN -> lhs
  RSH_ASSIGN -> lhs
  AND_ASSIGN -> lhs
  XOR_ASSIGN -> lhs
  OR_ASSIGN -> lhs
  COMMA -> rhs
}

fun IDebugHandler.validateAssignment(pct: Punctuator, lhs: Expression, rhs: Expression) {
  val op = pct.asBinaryOperator() ?: logger.throwICE("Not a binary operator") {
    "lhs: $lhs, rhs: $rhs, pct: $pct"
  }
  if (op !in assignmentOps) return
  // Don't bother when lhs is an error; these diagnostics would be bogus
  if (lhs.type == ErrorType) return
  when (lhs) {
    is CastExpression -> diagnostic {
      id = DiagnosticId.ILLEGAL_CAST_ASSIGNMENT
      errorOn(pct)
      errorOn(lhs)
    }
    is BinaryExpression, is TernaryConditional -> diagnostic {
      id = DiagnosticId.EXPRESSION_NOT_ASSIGNABLE
      errorOn(pct)
      errorOn(lhs)
    }
    is ExprConstantNode -> diagnostic {
      id = DiagnosticId.CONSTANT_NOT_ASSIGNABLE
      errorOn(pct)
      errorOn(lhs)
    }
  }
}

/**
 * Runs [BinaryOperators.applyTo]. Prints appropriate diagnostics if [ErrorType] is returned. Does
 * not print anything if either [lhs] or [rhs] are [ErrorType].
 * @throws [slak.ckompiler.InternalCompilerError] if [pct] does not represent a binary operator
 */
fun IDebugHandler.binaryDiags(pct: Punctuator, lhs: Expression, rhs: Expression): TypeName {
  val op = pct.asBinaryOperator() ?: logger.throwICE("Not a binary operator") {
    "lhs: $lhs, rhs: $rhs, pct: $pct"
  }
  if (lhs.type is ErrorType || rhs.type is ErrorType) return ErrorType
  val res = op.applyTo(lhs.type, rhs.type)
  if (res != ErrorType) return res
  diagnostic {
    id = DiagnosticId.INVALID_ARGS_BINARY
    errorOn(pct)
    errorOn(lhs)
    errorOn(rhs)
    formatArgs(op.op.s, lhs.type, rhs.type)
  }
  return ErrorType
}

/**
 * Check that an array doesn't have an element type of [FunctionType]. Print diagnostic otherwise.
 */
fun IDebugHandler.checkArrayElementType(declSpec: DeclarationSpecifier, declarator: Declarator) {
  if (!declarator.isArray()) return
  val elemType = (typeNameOf(declSpec, declarator) as? ArrayType)?.elementType ?: return
  if (elemType is FunctionType) diagnostic {
    id = DiagnosticId.INVALID_ARR_TYPE
    formatArgs(declarator.name, elemType)
    errorOn(declarator)
  }
}

/**
 * Get resulting type of a ? : ternary operator expression.
 *
 * C standard: 6.5.15.0.3
 */
fun resultOfTernary(success: Expression, failure: Expression): TypeName {
  if (success.type is ErrorType || failure.type is ErrorType) return ErrorType
  if (success.type is ArithmeticType && failure.type is ArithmeticType) {
    return usualArithmeticConversions(success.type, failure.type)
  }
  if (success.type is StructureType &&
      failure.type is StructureType &&
      success.type == failure.type) {
    return success.type
  }
  if (success.type is UnionType && failure.type is UnionType && success.type == failure.type) {
    return success.type
  }
  if (success.type is VoidType && failure.type is VoidType){
    return VoidType
  }
  // FIXME: is null pointer constant case handled?
  if (success.type is PointerType && failure.type is PointerType) {
    // FIXME: overly strict check, compatibles are allowed
    val successRef = (success.type as PointerType).referencedType
    val failRef = (failure.type as PointerType).referencedType
    // FIXME: these 2 ifs are incomplete, see standard
    if (successRef is VoidType) return failure.type
    if (failRef is VoidType) return success.type
    return if (successRef != failRef) ErrorType else success.type
  }
  // FIXME: arrays have much of the same problems as pointers here
  if (success.type is ArrayType && failure.type is ArrayType) {
    val successRef = (success.type as ArrayType).elementType
    val failRef = (failure.type as ArrayType).elementType
    return if (successRef != failRef) ErrorType else PointerType(successRef, emptyList())
  }
  return ErrorType
}

/**
 * Applies a cast to a common type to [operand]. Useful for actually performing usual arithmetic
 * conversions and the like.
 */
fun convertToCommon(commonType: TypeName, operand: Expression): Expression {
  if (commonType == operand.type) return operand
  if (commonType == ErrorType) return operand
  // FIXME: this does not seem terribly correct, but 6.5.4.0.3 does say pointers need explicit casts
  if (operand.type is PointerType || commonType is PointerType) return operand
  return CastExpression(operand, commonType).withRange(operand)
}
