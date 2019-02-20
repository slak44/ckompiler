package slak.ckompiler.parser

import mu.KotlinLogging
import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler
import slak.ckompiler.lexer.Punctuator
import slak.ckompiler.throwICE
import slak.ckompiler.parser.BinaryOperators.*
import slak.ckompiler.parser.UnaryOperators.*

private val logger = KotlinLogging.logger("TypeSystem")

fun typeNameOfTag(tagSpecifier: TagSpecifier): TypeName {
  val tagName = if (tagSpecifier.isAnonymous) null else tagSpecifier.tagIdent.name
//  val tagDef = if (tagSpecifier.isAnonymous) null else searchTag(tagSpecifier.tagIdent)
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
  if (decl is ErrorDeclarator || specQuals.isEmpty()) return ErrorType
  // Pointers
  if (decl.indirection.isNotEmpty()) {
    val referencedType = typeNameOf(specQuals, AbstractDeclarator(emptyList(), decl.suffixes))
    return decl.indirection.fold(referencedType) { type, curr -> PointerType(type, curr) }
  }
  // Structure/Union
  if (specQuals.isTag()) return typeNameOfTag(specQuals.typeSpec as TagSpecifier)
  // Functions
  if (decl.isFunction()) {
    val ptl = decl.getFunctionTypeList()
    val paramTypes = ptl.params.map { typeNameOf(it.declSpec, it.declarator) }
    val retType = typeNameOf(specQuals, AbstractDeclarator(emptyList(), decl.suffixes.drop(1)))
    return FunctionType(retType, paramTypes, ptl.variadic)
  }
  // FIXME: arrays
  return when (specQuals.typeSpec) {
    null, is TagSpecifier -> ErrorType
    is EnumSpecifier -> TODO()
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

  fun isRealType(): Boolean = this is ArithmeticType // We don't implement complex types yet.

  /** C standard: 6.2.5.0.21 */
  fun isScalar(): Boolean = this is ArithmeticType || this is PointerType
}

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
  override val corespondingType get() = logger.throwICE("No signed corespondent for _Bool") {}
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
  REF -> {
    if (target is ErrorType) ErrorType
    // FIXME: check ALL the constraints from 6.5.3.2.1
    else PointerType(target, emptyList())
  }
  DEREF -> {
    if (target !is PointerType) ErrorType else target.referencedType
  }
}

/**
 * C standard: 6.5.5.0.2
 * @return the type of the expression after applying the binary operator on the operands
 * ([ErrorType] if it can't be applied)
 */
fun BinaryOperators.applyTo(lhs: TypeName, rhs: TypeName): TypeName = when (this) {
  MUL, DIV -> {
    if (lhs !is ArithmeticType || rhs !is ArithmeticType) ErrorType
    else usualArithmeticConversions(lhs, rhs)
  }
  MOD -> {
    if (lhs !is IntegralType || rhs !is IntegralType) ErrorType
    else usualArithmeticConversions(lhs, rhs)
  }
  ADD -> TODO()
  SUB -> TODO()
  LSH -> TODO()
  RSH -> TODO()
  LT -> TODO()
  GT -> TODO()
  LEQ -> TODO()
  GEQ -> TODO()
  EQ -> TODO()
  NEQ -> TODO()
  BIT_AND -> TODO()
  BIT_XOR -> TODO()
  BIT_OR -> TODO()
  AND -> TODO()
  OR -> TODO()
  ASSIGN -> TODO()
  MUL_ASSIGN -> TODO()
  DIV_ASSIGN -> TODO()
  MOD_ASSIGN -> TODO()
  PLUS_ASSIGN -> TODO()
  SUB_ASSIGN -> TODO()
  LSH_ASSIGN -> TODO()
  RSH_ASSIGN -> TODO()
  AND_ASSIGN -> TODO()
  XOR_ASSIGN -> TODO()
  OR_ASSIGN -> TODO()
  COMMA -> TODO()
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
    columns(lhs.tokenRange)
    columns(rhs.tokenRange)
    formatArgs(op.op.s, lhs.type, rhs.type)
  }
  return ErrorType
}
