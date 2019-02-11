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
  // FIXME: arrays, functions
  return when (specQuals.typeSpec) {
    null, is TagSpecifier -> ErrorType
    is EnumSpecifier -> TODO()
    is TypedefNameSpecifier -> TODO("typedefname should have suffixes")
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
}

object ErrorType : TypeName()

/**
 * All pointer types are complete.
 *
 * C standard: 6.2.5.
 */
data class PointerType(val referencedType: TypeName, val ptrQuals: TypeQualifierList) : TypeName()

data class FunctionType(val returnType: TypeName, val params: List<TypeName>) : TypeName()

data class ArrayType(val elementType: TypeName, val size: ArrayTypeSize) : TypeName()

// FIXME: implement these too
data class BitfieldType(val elementType: TypeName, val size: Expression) : TypeName()

data class StructureType(val name: String?, val members: List<TypeName>?) : TypeName()

data class UnionType(val name: String?, val optionTypes: List<TypeName>?) : TypeName()

sealed class BasicType : TypeName()

/**
 * We consider character types to be integral, and char to behave as signed char.
 *
 * C standard: 6.2.5.0.4, 6.2.5.0.15
 */
sealed class SignedIntegralType : BasicType()

object SignedCharType : SignedIntegralType()
object SignedShortType : SignedIntegralType()
object SignedIntType : SignedIntegralType()
object SignedLongType : SignedIntegralType()
object SignedLongLongType : SignedIntegralType()

/** C standard: 6.2.5.0.6 */
sealed class UnsignedIntegralType : BasicType()

object BooleanType : UnsignedIntegralType()
object UnsignedCharType : UnsignedIntegralType()
object UnsignedShortType : UnsignedIntegralType()
object UnsignedIntType : UnsignedIntegralType()
object UnsignedLongType : UnsignedIntegralType()
object UnsignedLongLongType : UnsignedIntegralType()

/** C standard: 6.2.5.0.10 */
sealed class FloatingType : BasicType()

object FloatType : FloatingType()
object DoubleType : FloatingType()
object LongDoubleType : FloatingType()

object VoidType : BasicType()
