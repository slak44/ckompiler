package slak.ckompiler.parser

import org.apache.logging.log4j.LogManager
import slak.ckompiler.*
import slak.ckompiler.lexer.Keywords
import slak.ckompiler.lexer.LexicalToken
import slak.ckompiler.lexer.Punctuator
import slak.ckompiler.parser.BinaryOperators.*
import slak.ckompiler.parser.UnaryOperators.*

private val logger = LogManager.getLogger()

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
  val isStorageRegister = specQuals.storageClass?.value == Keywords.REGISTER
  // Pointers
  if (decl.indirection.isNotEmpty()) {
    val referencedType = typeNameOf(specQuals, AbstractDeclarator(emptyList(), decl.suffixes))
    val ind = decl.indirection.dropLast(1).fold(referencedType) { type, curr ->
      PointerType(type, curr, false)
    }
    return PointerType(ind, decl.indirection.last(), isStorageRegister)
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
    return ArrayType(elemType, size, isStorageRegister)
  }
  val unqualified = when (specQuals.typeSpec) {
    null, is TagSpecifier -> ErrorType
    is EnumSpecifier -> TODO("enums not implemented yet")
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
    is TypedefNameSpecifier -> {
      // FIXME: add specQuals quals to the typedef's quals
      return specQuals.typeSpec.typedefName.type
    }
  }
  return if (specQuals.typeQualifiers.isNotEmpty() || isStorageRegister) {
    QualifiedType(unqualified, specQuals.typeQualifiers, isStorageRegister)
  } else {
    unqualified
  }
}

/**
 * Instances represent maybe-qualified types.
 *
 * C standard: 6.2.5, 6.2.5.0.26
 */
sealed class TypeName {
  abstract val typeQuals: TypeQualifierList
  abstract val isStorageRegister: Boolean

  /**
   * @return null if this type can't be called, or the [FunctionType] to call otherwise
   */
  fun asCallable(): FunctionType? = when (this) {
    is FunctionType -> this
    is PointerType -> this.referencedType as? FunctionType
    else -> null
  }

  fun isCallable() = asCallable() != null

  fun isRealType(): Boolean = unqualify() is ArithmeticType // We don't implement complex types yet.

  /** C standard: 6.2.5.0.21 */
  fun isScalar(): Boolean = unqualify() is ArithmeticType || this is PointerType

  /**
   * FIXME: check for incomplete struct/union types; they are currently not even represented
   *
   * C standard: 6.2.5.0.1
   */
  fun isCompleteObjectType() = this !is FunctionType && unqualify() !is VoidType &&
      (this as? ArrayType)?.size !is NoSize

  /**
   * System V ABI: 3.2.3, page 17
   */
  fun isSSEType() = unqualify() is FloatType || unqualify() is DoubleType

  /**
   * System V ABI: 3.2.3, page 17
   */
  fun isABIIntegerType() = unqualify() is IntegralType || unqualify() is PointerType

  /**
   * Applies conversions specified by the standard (functions & arrays to pointers).
   *
   * C standard: 6.3.2.1.0.3, 6.3.2.1.0.4
   */
  fun normalize(): TypeName = when (this) {
    is FunctionType -> PointerType(this, emptyList(), this)
    is ArrayType -> PointerType(elementType, emptyList(), this)
    else -> this
  }

  /**
   * If this is [QualifiedType], return [QualifiedType.unqualified]. Otherwise, return this.
   */
  fun unqualify(): TypeName {
    return if (this is QualifiedType) unqualified else this
  }

  /**
   * FIXME: the rules for compatible types are VERY annoying, and spread across the entire standard
   *
   * C standard: 6.2.7
   */
  fun isCompatibleWith(other: TypeName): Boolean {
    if (this is ErrorType || other is ErrorType) return false
    if (typeQuals != other.typeQuals) return false
    val unqualified = unqualify()
    val otherUnqual = other.unqualify()
    return unqualified == otherUnqual
  }
}

data class QualifiedType(
    val unqualified: UnqualifiedTypeName,
    override val typeQuals: TypeQualifierList,
    override val isStorageRegister: Boolean
) : TypeName() {
  override fun toString(): String {
    val typeQualStr = if (typeQuals.isEmpty()) "" else (typeQuals.stringify() + " ")
    val registerStr = if (isStorageRegister) "register " else ""
    return "$registerStr$typeQualStr$unqualified"
  }
}

data class FunctionType(
    val returnType: TypeName,
    val params: List<TypeName>,
    val variadic: Boolean = false
) : TypeName() {
  // FIXME: functions have their own qualifiers
  override val typeQuals: TypeQualifierList = emptyList()

  override val isStorageRegister = false

  override fun toString(): String {
    // This doesn't really work when the return type is a function/array, but that isn't valid
    // anyway
    val variadicStr = if (variadic) ", ..." else ""
    return "$returnType (${params.joinToString()}$variadicStr)"
  }
}

/**
 * All pointer types are complete.
 *
 * C standard: 6.2.5
 */
data class PointerType(
    val referencedType: TypeName,
    override val typeQuals: TypeQualifierList,
    override val isStorageRegister: Boolean
) : TypeName() {
  constructor(
      referencedType: TypeName,
      ptrQuals: TypeQualifierList,
      decayedFrom: TypeName
  ) : this(referencedType, ptrQuals, decayedFrom.isStorageRegister) {
    this.decayedFrom = decayedFrom
  }

  /**
   * If this pointer type is actually another type that was implicitly converted to a pointer, this
   * property stores that original type ([FunctionType] or [ArrayType], basically). It is null in
   * any other case.
   */
  var decayedFrom: TypeName? = null
    private set

  override fun toString() = "$referencedType *${typeQuals.stringify()}"
}

data class ArrayType(
    val elementType: TypeName,
    val size: ArrayTypeSize,
    override val isStorageRegister: Boolean
) : TypeName() {
  /**
   * Arrays can't be qualified, only their element.
   *
   * C standard: 6.7.2.4.0.9
   */
  override val typeQuals: TypeQualifierList = emptyList()

  override fun toString() = "${if (isStorageRegister) "register " else ""}$elementType[$size]"
}

// FIXME: implement these too
data class BitfieldType(
    val elementType: TypeName,
    val size: Expression,
    override val isStorageRegister: Boolean
) : TypeName() {
  override val typeQuals: TypeQualifierList = emptyList()
}

/**
 * Instances represent explicitly unqualified types.
 *
 * C standard: 6.2.5.0.20
 */
sealed class UnqualifiedTypeName : TypeName() {
  // Self-explanatory
  override val typeQuals: TypeQualifierList = emptyList()
  override val isStorageRegister = false
}

/**
 * Represents the type of an expression that is invalid, either due to syntax errors, or due to
 * violations of semantic requirements.
 */
object ErrorType : UnqualifiedTypeName() {
  override fun toString() = "<error type>"
}

data class StructureType(val name: String?, val members: List<TypeName>?) : UnqualifiedTypeName() {
  override fun toString(): String {
    val nameStr = if (name == null) "" else "$name "
    return "struct $nameStr{...}"
  }
}

data class UnionType(val name: String?, val optionTypes: List<TypeName>?) : UnqualifiedTypeName() {
  override fun toString(): String {
    val nameStr = if (name == null) "" else "$name "
    return "union $nameStr{...}"
  }
}

sealed class BasicType : UnqualifiedTypeName()

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
  override operator fun compareTo(other: IntegralType) = this.conversionRank - other.conversionRank
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
fun usualArithmeticConversions(
    lhs: UnqualifiedTypeName,
    rhs: UnqualifiedTypeName
): UnqualifiedTypeName {
  if (lhs is ErrorType || rhs is ErrorType) return ErrorType
  if (lhs !is ArithmeticType || rhs !is ArithmeticType) {
    logger.throwICE("Applying arithmetic conversions on non-arithmetic operands") {
      "lhs: $lhs, rhs: $rhs"
    }
  }
  if (lhs is LongDoubleType || rhs is LongDoubleType) return LongDoubleType
  if (lhs is DoubleType || rhs is DoubleType) return DoubleType
  if (lhs is FloatType || rhs is FloatType) return FloatType
  // Same type, don't bother with anything else
  if (lhs.javaClass == rhs.javaClass) return lhs
  val lInt = lhs.promotedType as IntegralType
  val rInt = rhs.promotedType as IntegralType
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
fun IDebugHandler.validateCast(
    originalType: TypeName,
    targetType: TypeName,
    castParenRange: SourcedRange
) = when {
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
fun IDebugHandler.typeOfSubscript(
    subscripted: Expression,
    subscript: Expression,
    endSqBracket: LexicalToken
): Pair<TypeName, Boolean> {
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
 * C standard: 6.5.3.3.0.1, 6.5.3.3.0.5, 6.3.2.1.0.3, 6.3.2.1.0.4
 * @return the type of the expression after applying a unary operator ([ErrorType] if it can't be
 * applied)
 */
fun UnaryOperators.applyTo(target: TypeName): TypeName = when (this) {
  PLUS, MINUS -> if (target !is ArithmeticType) ErrorType else target.promotedType
  BIT_NOT -> {
    // FIXME: do 6.5.3.3.0.4
    if (target !is IntegralType) ErrorType else target.promotedType
  }
  NOT -> {
    // The result of this operator is always `int`, as per 6.5.3.3.0.5
    if (!target.isScalar()) ErrorType else SignedIntType
  }
  REF -> when (target) {
    is ErrorType -> ErrorType
    is ArrayType -> PointerType(target.elementType, emptyList(), isStorageRegister = false)
    is PointerType -> {
      val original = target.decayedFrom
      if (original != null) {
        // & is an exception to these implicit conversions, so don't nest pointer types
        PointerType(original, target.typeQuals, isStorageRegister = false)
      } else {
        PointerType(target, emptyList(), isStorageRegister = false)
      }
    }
    else -> PointerType(target, emptyList(), isStorageRegister = false)
  }
  DEREF -> {
    if (target !is PointerType) ErrorType else target.referencedType
  }
}

/**
 * C standard: 6.5.3.2.0.1
 */
fun IDebugHandler.validateAddressOf(expr: UnaryExpression) {
  require(expr.op == REF) { "Not an address of operation" }
  if (expr.operand.type.isStorageRegister) {
    diagnostic {
      id = DiagnosticId.ADDRESS_OF_REGISTER
      formatArgs(if (expr.operand is TypedIdentifier) expr.operand.name else "???")
      errorOn(expr.operand)
    }
  }
  if (expr.operand.type == ErrorType) return
  if (expr.operand.type is BitfieldType) {
    diagnostic {
      id = DiagnosticId.ADDRESS_OF_BITFIELD
      errorOn(expr)
    }
  }
  if (expr.operand.valueType == Expression.ValueType.RVALUE) {
    diagnostic {
      id = DiagnosticId.ADDRESS_REQUIRES_LVALUE
      formatArgs(expr.type.toString())
      errorOn(expr)
    }
  }
}

/**
 * Boilerplate that checks [lhs]/[rhs] to be [ArithmeticType], then applies
 * [usualArithmeticConversions]. Returns [ErrorType] if a check fails.
 */
private fun arithmOp(lhs: TypeName, rhs: TypeName): UnqualifiedTypeName {
  return if (lhs !is ArithmeticType || rhs !is ArithmeticType) ErrorType
  else usualArithmeticConversions(lhs, rhs)
}

/**
 * Boilerplate that checks [lhs]/[rhs] to be [IntegralType], then applies
 * [usualArithmeticConversions]. Returns [ErrorType] if a check fails.
 */
private fun intOp(lhs: TypeName, rhs: TypeName): UnqualifiedTypeName {
  return if (lhs !is IntegralType || rhs !is IntegralType) ErrorType
  else usualArithmeticConversions(lhs, rhs)
}

/**
 * The resulting types of a [BinaryExpression] on two operands.
 *
 * [exprType] is the type of the [BinaryExpression] that will be created.
 * [operandCommonType] is the type each operand must be converted to before the [BinaryExpression]
 * is applied.
 *
 * Both are [ErrorType] if the types and the [BinaryOperators] don't match.
 *
 * @see convertToCommon
 */
data class BinaryResult(val exprType: TypeName, val operandCommonType: TypeName) {
  constructor(type: TypeName) : this(type, type)
}

/**
 * C standard: 6.5.5.0.2, 6.5.5, 6.5.6, 6.5.7, 6.5.8, 6.5.8.0.6, 6.5.9, 6.5.10, 6.5.11, 6.5.12,
 * 6.5.13, 6.5.14, 6.5.17, 6.5.6.0.2
 * @see BinaryResult
 */
fun BinaryOperators.applyTo(lhs: TypeName, rhs: TypeName): BinaryResult = when (this) {
  MUL, DIV -> BinaryResult(arithmOp(lhs, rhs))
  MOD -> BinaryResult(intOp(lhs, rhs))
  ADD -> {
    val ptr = lhs as? PointerType ?: rhs as? PointerType
    if (ptr == null) {
      // Regular arithmetic
      BinaryResult(arithmOp(lhs, rhs))
    } else {
      // Pointer arithmetic
      // FIXME: handle ArrayType (6.5.6.0.8)
      if (!ptr.referencedType.isCompleteObjectType()) {
        BinaryResult(ErrorType)
      } else {
        val other = if (lhs is PointerType) rhs else lhs
        BinaryResult(if (other !is IntegralType) ErrorType else ptr)
      }
    }
  }
  SUB -> {
    val ptr = lhs as? PointerType
    // FIXME: the ptr.referencedType must be a complete object type (6.5.6.0.2)
    if (ptr == null) {
      // Regular arithmetic
      BinaryResult(arithmOp(lhs, rhs))
    } else {
      // Pointer arithmetic
      val otherPtr = rhs as? PointerType
      // FIXME: handle ArrayType (6.5.6.0.8)
      // FIXME: the otherPtr.referencedType must be a complete object type (6.5.6.0.2)
      if (otherPtr == null) {
        // ptr minus integer case
        BinaryResult(if (rhs !is IntegralType) ErrorType else ptr)
      } else {
        // ptr minus ptr case
        // FIXME: this should actually return ptrdiff_t (6.5.6.0.9)
        // FIXME: overly strict check, compatibles are allowed (6.5.6.0.3)
        BinaryResult(if (ptr.referencedType != otherPtr.referencedType) ErrorType else ptr)
      }
    }
  }
  LSH, RSH -> BinaryResult(intOp(lhs, rhs))
  LT, GT, LEQ, GEQ -> {
    val lPtr = lhs as? PointerType
    val rPtr = rhs as? PointerType
    if (lPtr == null || rPtr == null) {
      val commonType = arithmOp(lhs, rhs)
      val resultType = if (lhs.isRealType() && rhs.isRealType()) SignedIntType else ErrorType
      BinaryResult(resultType, commonType)
    } else {
      // FIXME: the referenced types must be compatible object types (6.5.8.0.2)
      BinaryResult(SignedIntType, lPtr)
    }
  }
  EQ, NEQ -> {
    val lPtr = lhs as? PointerType
    val rPtr = rhs as? PointerType
    if (lhs is ArithmeticType && rhs is ArithmeticType) {
      BinaryResult(SignedIntType, usualArithmeticConversions(lhs, rhs))
    } else if (lhs == rhs) {
      BinaryResult(SignedIntType, lhs)
    } else if (lPtr == null && rPtr == null) {
      // They can't be both non-pointers
      BinaryResult(ErrorType)
    } else if (lPtr == rPtr) {
      BinaryResult(SignedIntType, lhs)
    } else {
      TODO("even more pointer cmps stuff / 6.5.9")
    }
  }
  BIT_AND, BIT_XOR, BIT_OR -> BinaryResult(intOp(lhs, rhs))
  AND, OR -> {
    if (!lhs.isScalar() || !rhs.isScalar()) {
      BinaryResult(ErrorType)
    } else {
      val lPtr = lhs as? PointerType
      val rPtr = rhs as? PointerType
      if (lPtr == null || rPtr == null) {
        val commonType = arithmOp(lhs, rhs)
        val resultType = if (lhs.isRealType() && rhs.isRealType()) SignedIntType else ErrorType
        BinaryResult(resultType, commonType)
      } else {
        TODO("handle pointers here")
      }
    }
  }
  // FIXME: assignment operator semantics are very complicated (6.5.16)
  ASSIGN -> BinaryResult(lhs)
  MUL_ASSIGN -> BinaryResult(lhs)
  DIV_ASSIGN -> BinaryResult(lhs)
  MOD_ASSIGN -> BinaryResult(lhs)
  PLUS_ASSIGN -> BinaryResult(lhs)
  SUB_ASSIGN -> BinaryResult(lhs)
  LSH_ASSIGN -> BinaryResult(lhs)
  RSH_ASSIGN -> BinaryResult(lhs)
  AND_ASSIGN -> BinaryResult(lhs)
  XOR_ASSIGN -> BinaryResult(lhs)
  OR_ASSIGN -> BinaryResult(lhs)
  COMMA -> BinaryResult(rhs)
}

/**
 * Technically, this should be checked by [applyTo]. In practice, only real (read: non-synthetic)
 * assignments need to be checked.
 */
fun IDebugHandler.validateAssignment(pct: Punctuator, lhs: Expression, rhs: Expression) {
  val op = pct.asBinaryOperator() ?: logger.throwICE("Not a binary operator") {
    "lhs: $lhs, rhs: $rhs, pct: $pct"
  }
  if (op !in assignmentOps) return
  when {
    // Don't bother when lhs is an error; these diagnostics would be bogus
    lhs.type == ErrorType -> return
    lhs is CastExpression -> diagnostic {
      id = DiagnosticId.ILLEGAL_CAST_ASSIGNMENT
      errorOn(pct)
      errorOn(lhs)
    }
    lhs is ExprConstantNode -> diagnostic {
      id = DiagnosticId.CONSTANT_NOT_ASSIGNABLE
      errorOn(pct)
      errorOn(lhs)
    }
    lhs.type is QualifiedType && Keywords.CONST in lhs.type.typeQuals -> diagnostic {
      id = DiagnosticId.CONST_QUALIFIED_NOT_ASSIGNABLE
      val isVariable = lhs is TypedIdentifier
      formatArgs(
          if (isVariable) "variable" else "expression",
          lhs.sourceText?.substring(lhs.range) ?: "???",
          lhs.type.toString()
      )
      errorOn(pct)
      errorOn(lhs)
    }
    lhs.valueType != Expression.ValueType.MODIFIABLE_LVALUE -> diagnostic {
      id = DiagnosticId.EXPRESSION_NOT_ASSIGNABLE
      errorOn(pct)
      errorOn(lhs)
    }
    else -> return
  }
}

/**
 * Runs [BinaryOperators.applyTo]. Prints appropriate diagnostics if [ErrorType] is returned. Does
 * not print anything if either [lhs] or [rhs] are [ErrorType].
 * @throws [slak.ckompiler.InternalCompilerError] if [pct] does not represent a binary operator
 */
fun IDebugHandler.binaryDiags(pct: Punctuator, lhs: Expression, rhs: Expression): BinaryResult {
  val op = pct.asBinaryOperator() ?: logger.throwICE("Not a binary operator") {
    "lhs: $lhs, rhs: $rhs, pct: $pct"
  }
  if (lhs.type is ErrorType || rhs.type is ErrorType) return BinaryResult(ErrorType)
  val binRes = op.applyTo(lhs.type, rhs.type)
  if (binRes.exprType != ErrorType) return binRes
  diagnostic {
    id = DiagnosticId.INVALID_ARGS_BINARY
    errorOn(pct)
    errorOn(lhs)
    errorOn(rhs)
    formatArgs(op.op.s, lhs.type, rhs.type)
  }
  return BinaryResult(ErrorType)
}

/**
 * Check that an array doesn't have an element type of [FunctionType], or an incomplete one.
 * Check that array size exists.
 *
 * Prints diagnostics.
 */
fun IDebugHandler.checkArrayType(declSpec: DeclarationSpecifier, declarator: Declarator) {
  if (!declarator.isArray()) return
  val typeName = typeNameOf(declSpec, declarator) as ArrayType
  if (typeName.size is NoSize) {
    diagnostic {
      id = DiagnosticId.ARRAY_SIZE_MISSING
      formatArgs(declarator.name.name)
      errorOn(declarator.name)
    }
  }
  val elemType = typeName.elementType
  if (elemType is FunctionType) diagnostic {
    id = DiagnosticId.INVALID_ARR_TYPE
    formatArgs(declarator.name, elemType)
    errorOn(declarator)
  } else if (!elemType.isCompleteObjectType()) diagnostic {
    id = DiagnosticId.ARRAY_OF_INCOMPLETE
    formatArgs(elemType.toString())
    errorOn(declarator)
  }
}

/**
 * Get resulting type of a ? : ternary operator expression.
 *
 * C standard: 6.5.15.0.3
 */
fun resultOfTernary(success: Expression, failure: Expression): TypeName {
  val successType = success.type
  val failureType = failure.type
  if (successType is ErrorType || failureType is ErrorType) return ErrorType
  if (successType is ArithmeticType && failureType is ArithmeticType) {
    return usualArithmeticConversions(successType, failureType)
  }
  if (successType is StructureType &&
      failureType is StructureType &&
      successType == failureType) {
    return successType
  }
  if (successType is UnionType && failureType is UnionType && successType == failureType) {
    return successType
  }
  if (successType is VoidType && failureType is VoidType) {
    return VoidType
  }
  // FIXME: is null pointer constant case handled?
  if (successType is PointerType && failureType is PointerType) {
    // FIXME: overly strict check, compatibles are allowed
    val successRef = successType.referencedType
    val failRef = failureType.referencedType
    // FIXME: these 2 ifs are incomplete, see standard
    if (successRef is VoidType) return failureType
    if (failRef is VoidType) return successType
    return if (successRef != failRef) ErrorType else successType
  }
  // FIXME: arrays have much of the same problems as pointers here
  if (successType is ArrayType && failureType is ArrayType) {
    val successRef = successType.elementType
    val failRef = failureType.elementType
    return if (successRef != failRef) ErrorType
    else PointerType(successRef, emptyList(), isStorageRegister = false)
  }
  return ErrorType
}

/**
 * Applies a cast to a common type to [operand]. Useful for actually performing usual arithmetic
 * conversions and the like.
 *
 * If either parameter has [ErrorType], or if both parameters have identical types, the result is
 * the unchanged [operand] parameter.
 */
fun convertToCommon(commonType: TypeName, operand: Expression): Expression {
  val opType = operand.type
  if (commonType == opType) return operand
  if (commonType == ErrorType || opType == ErrorType) return operand
  // FIXME: this does not seem terribly correct, but 6.5.4.0.3 does say pointers need explicit casts
  if (opType is PointerType || commonType is PointerType) return operand
  // FIXME: this also does not seem terribly correct, but why would we cast int to const int?
  if (commonType.unqualify() == opType.unqualify()) return operand
  return CastExpression(operand, commonType).withRange(operand)
}

/**
 * Checks function type's [FunctionType.params] vs [args]. Reports diagnostics for problems.
 * Handles variadic functions. Performs the required conversions. Returns the transformed args.
 *
 * [calledExpr]'s type MUST be [FunctionType].
 *
 * C standard: 6.5.2.2
 */
fun IDebugHandler.matchFunctionArguments(
    calledExpr: Expression,
    args: List<Expression>
): List<Expression>? {
  require(calledExpr.type.isCallable()) { "Called expression is not callable" }
  val funType = calledExpr.type.asCallable()!!
  if (!funType.variadic && funType.params.size != args.size) {
    diagnostic {
      id = DiagnosticId.FUN_CALL_ARG_COUNT
      val which = if (funType.params.size > args.size) "few" else "many"
      formatArgs(which, funType.params.size, args.size)
      errorOn(calledExpr)
      if (args.isNotEmpty()) errorOn(args.last())
    }
    return null
  }
  if (funType.variadic && args.size < funType.params.size) {
    diagnostic {
      id = DiagnosticId.FUN_CALL_ARG_COUNT_VAR
      formatArgs(funType.params.size, args.size)
      errorOn(calledExpr)
      if (args.isNotEmpty()) errorOn(args.last())
    }
    return null
  }
  val variadicArgs = args.drop(funType.params.size).map(::defaultArgumentPromotions)
  val transformedRegularArgs = mutableListOf<Expression>()
  for ((expectedArgType, actualArg) in funType.params.zip(args)) {
    // 6.5.2.2.0.7 says we treat conversions as if by assignment here
    // FIXME: applyTo is incomplete for assignment
    //   when it gets done, print diagnostic here if types aren't compatible
    val (_, commonType) = ASSIGN.applyTo(expectedArgType, actualArg.type)
    transformedRegularArgs += convertToCommon(commonType, actualArg)
  }
  return transformedRegularArgs + variadicArgs
}

/**
 * Figures out what this argument should be promoted to, and wraps it in a [CastExpression] if
 * required.
 *
 * The default argument promotions are only applied in certain cases (namely variadic args and
 * prototype-less function arguments).
 *
 * [ErrorType] expressions pass through.
 *
 * C standard: 6.5.2.2.0.6
 */
fun defaultArgumentPromotions(sourceArg: Expression): Expression {
  val type = sourceArg.type
  // floats always become doubles
  if (type is FloatType) return CastExpression(sourceArg, DoubleType).withRange(sourceArg)
  // Integer promotions are also applied (only if they're not redundant)
  if (type is IntegralType && type.promotedType != type) {
    return CastExpression(sourceArg, type.promotedType).withRange(sourceArg)
  }
  // Otherwise, do nothing
  return sourceArg
}

/**
 * Validate the operand type for a `sizeof`. Prints diagnostics. Returns the type of the sizeof's
 * operand, with its pointer implicit conversions removed.
 *
 * C standard: 6.3.2.1.0.3, 6.3.2.1.0.4
 * @see PointerType.decayedFrom
 * @see TypeName.normalize
 */
fun IDebugHandler.checkSizeofType(
    typeName: TypeName,
    sizeofRange: SourcedRange,
    targetRange: SourcedRange
): TypeName {
  if (typeName is ErrorType) return ErrorType
  val unconvertedType = if (typeName is PointerType) typeName.decayedFrom ?: typeName else typeName
  if (unconvertedType is BitfieldType) {
    diagnostic {
      id = DiagnosticId.SIZEOF_ON_BITFIELD
      errorOn(sizeofRange)
      errorOn(targetRange)
    }
  } else if (unconvertedType is FunctionType) {
    diagnostic {
      id = DiagnosticId.SIZEOF_ON_FUNCTION
      formatArgs(typeName.toString())
      errorOn(sizeofRange)
      errorOn(targetRange)
    }
  } else if (!unconvertedType.isCompleteObjectType()) {
    diagnostic {
      id = DiagnosticId.SIZEOF_ON_INCOMPLETE
      formatArgs(typeName.toString())
      errorOn(sizeofRange)
      errorOn(targetRange)
    }
  }
  return unconvertedType
}

/**
 * Validate operand of [IncDecOperation]. Print diagnostics. Return type of operation.
 *
 * C standard: 6.5.3.1, 6.5.2.4
 */
fun IDebugHandler.checkIncDec(operand: Expression, isDec: Boolean, range: SourcedRange): TypeName {
  val type = operand.type
  if (type == ErrorType) return ErrorType
  val isActuallyPointer = type is PointerType && type.decayedFrom == null
  if (!type.isRealType() && !isActuallyPointer) {
    diagnostic {
      id = DiagnosticId.INVALID_INC_DEC_ARGUMENT
      formatArgs(if (isDec) "decrement" else "increment", type)
      errorOn(range)
    }
    return ErrorType
  }
  if (operand.valueType != Expression.ValueType.MODIFIABLE_LVALUE) {
    diagnostic {
      id = DiagnosticId.INVALID_MOD_LVALUE_INC_DEC
      errorOn(operand)
    }
    return ErrorType
  }
  return type
}

/**
 * Checks that return statements' returned value (or lack thereof) is consistent with the function's
 * return type. Prints diagnostics.
 */
fun IDebugHandler.validateReturnValue(
    returnKeyword: LexicalToken,
    expr: Expression?,
    expectedType: TypeName,
    funcName: String
) {
  val diagnosticId = when {
    expr == null && expectedType != VoidType -> DiagnosticId.NON_VOID_RETURNS_NOTHING
    expr != null && expr.type != VoidType && expectedType == VoidType ->
      DiagnosticId.VOID_RETURNS_VALUE
    expr != null && expr.type == VoidType && expectedType == VoidType ->
      DiagnosticId.DONT_RETURN_VOID_EXPR
    expr != null && expr.type != expectedType -> DiagnosticId.RET_TYPE_MISMATCH
    else -> null
  }
  if (diagnosticId != null) diagnostic {
    id = diagnosticId
    if (id == DiagnosticId.RET_TYPE_MISMATCH) {
      formatArgs(expectedType.toString(), expr?.type ?: VoidType.toString())
    } else {
      formatArgs(funcName)
    }
    errorOn(returnKeyword)
    expr?.let { errorOn(it) }
  }
}
