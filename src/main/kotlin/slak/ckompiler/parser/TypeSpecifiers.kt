package slak.ckompiler.parser

import slak.ckompiler.lexer.Keyword
import slak.ckompiler.lexer.Keywords

/**
 * FIXME: add `ComplexFloat` `ComplexDouble` `ComplexLongDouble`
 * FIXME: add atomic-type-specifier (6.7.2.4)
 */
sealed class TypeSpecifier

data class TypedefNameSpecifier(
    val name: IdentifierNode,
    val typedefName: TypedefName
) : TypeSpecifier() {
  override fun toString() = "${name.name} (aka ${typedefName.typedefedToString()})"
}

/**
 * FIXME: if a declaration has an incomplete type that never gets completed, print a diagnostic
 *
 * C standard: 6.7.2.1, 6.7.2.3
 */
sealed class TagSpecifier : TypeSpecifier() {
  abstract val name: IdentifierNode?
  /**
   * Must be [Keywords.STRUCT] or [Keywords.UNION] or [Keywords.ENUM].
   */
  abstract val kind: Keyword
}

data class EnumSpecifier(
    override val name: IdentifierNode?,
    val enumerators: List<Enumerator>?,
    override val kind: Keyword
) : TagSpecifier() {
  override fun toString(): String {
    val name = if (name != null) "${name.name} " else ""
    return "enum $name"
  }
}

data class TagDefinitionSpecifier(
    override val name: IdentifierNode?,
    val decls: List<StructDeclaration>,
    override val kind: Keyword
) : TagSpecifier() {
  override fun toString(): String {
    val name = if (name != null) "${name.name} " else ""
    return "${kind.value.keyword} $name{...}"
  }
}

data class TagNameSpecifier(
    override val name: IdentifierNode,
    override val kind: Keyword
) : TagSpecifier() {
  override fun toString() = "${kind.value.keyword} ${name.name}"
}

sealed class BasicTypeSpecifier(val first: Keyword) : TypeSpecifier() {
  override fun equals(other: Any?) = this.javaClass == other?.javaClass
  override fun hashCode() = javaClass.hashCode()
}

class VoidTypeSpec(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = Keywords.VOID.keyword
}

class Bool(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = Keywords.BOOL.keyword
}

class Signed(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = Keywords.SIGNED.keyword
}

class Unsigned(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = Keywords.UNSIGNED.keyword
}

class Char(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = Keywords.CHAR.keyword
}

class SignedChar(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = "${Keywords.SIGNED.keyword} $Char"
}

class UnsignedChar(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = "${Keywords.UNSIGNED.keyword} $Char"
}

class Short(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = Keywords.SHORT.keyword
}

class SignedShort(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = "${Keywords.SIGNED.keyword} $Short"
}

class UnsignedShort(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = "${Keywords.UNSIGNED.keyword} $Short"
}

class IntType(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = Keywords.INT.keyword
}

class SignedInt(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = "${Keywords.SIGNED.keyword} ${Keywords.INT.keyword}"
}

class UnsignedInt(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = "${Keywords.UNSIGNED.keyword} ${Keywords.INT.keyword}"
}

class LongType(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = Keywords.LONG.keyword
}

class SignedLong(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = "${Keywords.SIGNED.keyword} $Long"
}

class UnsignedLong(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = "${Keywords.UNSIGNED.keyword} $Long"
}

class LongLong(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = "$Long $Long"
}

class SignedLongLong(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = "${Keywords.SIGNED.keyword} $Long $Long"
}

class UnsignedLongLong(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = "${Keywords.UNSIGNED.keyword} $Long $Long"
}

class FloatTypeSpec(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = Keywords.FLOAT.keyword
}

class DoubleTypeSpec(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = Keywords.DOUBLE.keyword
}

class LongDouble(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = "$Long $Double"
}
