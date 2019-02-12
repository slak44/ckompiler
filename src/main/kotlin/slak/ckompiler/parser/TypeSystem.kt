package slak.ckompiler.parser

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
  if (decl.isFunction()) {
    // FIXME: do this correctly
    val retType = typeNameOf(specQuals, AbstractDeclarator(emptyList(), emptyList()))
    return FunctionType(retType, emptyList())
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
 * Instances represent types. Not what the standard calls `type-name`.
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

  fun isRealType(): Boolean = isArithmetic() // We don't implement complex types yet.

  /** C standard: 6.2.5.0.18 */
  fun isArithmetic(): Boolean = this is IntegralType || this is FloatingType

  /** C standard: 6.2.5.0.21 */
  fun isScalar(): Boolean = isArithmetic() || this is PointerType
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

data class FunctionType(val returnType: TypeName, val params: List<TypeName>) : TypeName() {
  override fun toString(): String {
    // FIXME: this doesn't really work when the return type is a function/array
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

sealed class IntegralType : BasicType()

/**
 * We consider character types to be integral, and char to behave as signed char.
 *
 * C standard: 6.2.5.0.4, 6.2.5.0.15
 */
sealed class SignedIntegralType : IntegralType()

object SignedCharType : SignedIntegralType() {
  override fun toString() = "signed char"
}

object SignedShortType : SignedIntegralType() {
  override fun toString() = "signed short"
}

object SignedIntType : SignedIntegralType() {
  override fun toString() = "signed int"
}

object SignedLongType : SignedIntegralType() {
  override fun toString() = "signed long"
}

object SignedLongLongType : SignedIntegralType() {
  override fun toString() = "signed long long"
}

/** C standard: 6.2.5.0.6 */
sealed class UnsignedIntegralType : IntegralType()

object BooleanType : UnsignedIntegralType() {
  override fun toString() = "_Bool"
}

object UnsignedCharType : UnsignedIntegralType() {
  override fun toString() = "unsigned char"
}

object UnsignedShortType : UnsignedIntegralType() {
  override fun toString() = "unsigned short"
}

object UnsignedIntType : UnsignedIntegralType() {
  override fun toString() = "unsigned int"
}

object UnsignedLongType : UnsignedIntegralType() {
  override fun toString() = "unsigned long"
}

object UnsignedLongLongType : UnsignedIntegralType() {
  override fun toString() = "unsigned long long"
}

/** C standard: 6.2.5.0.10 */
sealed class FloatingType : BasicType()

object FloatType : FloatingType() {
  override fun toString() = "float"
}

object DoubleType : FloatingType() {
  override fun toString() = "double"
}

object LongDoubleType : FloatingType() {
  override fun toString() = "long double"
}

object VoidType : BasicType() {
  override fun toString() = "void"
}
