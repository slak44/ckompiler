package slak.ckompiler.parser

import mu.KotlinLogging
import slak.ckompiler.lexer.*
import slak.ckompiler.throwICE

private val logger = KotlinLogging.logger("AST")

val Declarator.name get() = name()!!.name
val ExternalDeclaration.fn get() = this as FunctionDefinition
val FunctionDefinition.name get() = functionDeclarator.name
val FunctionDefinition.block get() = compoundStatement as CompoundStatement
val BlockItem.st get() = (this as StatementItem).statement

infix fun LexicalToken.until(other: LexicalToken): IntRange = this.startIdx until other.startIdx

operator fun LexicalToken.rangeTo(other: LexicalToken) = startIdx until (other.startIdx + other.consumedChars)

operator fun IntRange.rangeTo(other: IntRange) = this.start..other.endInclusive

operator fun LexicalToken.rangeTo(other: ASTNode) = this.startIdx..other.tokenRange.endInclusive

operator fun ASTNode.rangeTo(other: LexicalToken) =
    tokenRange.start until (other.startIdx + other.consumedChars)

operator fun ASTNode.rangeTo(other: ASTNode) = tokenRange..other.tokenRange

/**
 * Base class of all nodes from an Abstract Syntax Tree.
 * @param isRoot set to true if this [ASTNode] is the root node for the tree
 */
sealed class ASTNode(val isRoot: Boolean = false) {
  private var lateParent: ASTNode? = null
  private var lateTokenRange: IntRange? = null

  /**
   * A reference to this node's parent.
   * @throws slak.ckompiler.InternalCompilerError if accessed on a tree without a parent set or on
   * a node with [isRoot] set
   */
  val parent: ASTNode by lazy {
    if (lateParent == null) {
      logger.throwICE("Trying to access parent of detached node") { this }
    }
    return@lazy lateParent!!
  }

  /**
   * Allows late initialization of [ASTNode.parent], for when the tree is built bottom-up.
   *
   * Calls to this function that occur after accessing [ASTNode.parent] are no-ops.
   *
   * @param parent the new parent
   * @throws slak.ckompiler.InternalCompilerError if the [parent] is an instance of [Terminal], or
   * this node is a root node
   */
  fun setParent(parent: ASTNode) {
    if (parent is Terminal) {
      logger.throwICE("Trying to turn a terminal node into a parent") {
        "parent: $parent, this: $this"
      }
    }
    if (isRoot) {
      logger.throwICE("Trying to add a parent to a root node") { "parent: $parent, this: $this" }
    }
    lateParent = parent
  }

  /**
   * The first and last [LexicalToken]s of this node.
   * @throws slak.ckompiler.InternalCompilerError if accessed on a node without a range set
   */
  val tokenRange: IntRange by lazy {
    if (lateTokenRange == null) {
      logger.throwICE("Attempt to access missing token range data") { this }
    }
    return@lazy lateTokenRange!!
  }

  /** Sets this node's token range. */
  fun setRange(range: IntRange) {
    lateTokenRange = range
  }

  /** Gets the piece of the source code that this node was created from. */
  fun originalCode(sourceCode: String) = sourceCode.substring(tokenRange).trim()

  override fun equals(other: Any?) = other is ASTNode
  override fun hashCode() = javaClass.hashCode()
}

/** Sets a node's token range, and returns the node. */
fun <T : ASTNode> T.withRange(range: IntRange): T {
  this.setRange(range)
  return this
}

/**
 * Stores the data of a scoped `typedef`.
 * @param typedefIdent this is the [IdentifierNode] found at the place where the typedef was
 * declared; actual declarations that use this typedef will have their own identifiers
 */
data class TypedefName(val declSpec: DeclarationSpecifier,
                       val indirection: List<TypeQualifierList>,
                       val typedefIdent: IdentifierNode) {
  fun typedefedToString(): String {
    val ind = indirection.joinToString { '*' + it.joinToString(" ") { (value) -> value.keyword } }
    val indStr = if (ind.isBlank()) "" else " $ind"
    // The storage class is "typedef", and we don't want to print it
    val dsNoStorage = DeclarationSpecifier(
        storageClass = null,
        typeSpec = declSpec.typeSpec,
        functionSpecs = declSpec.functionSpecs,
        typeQualifiers = declSpec.typeQualifiers,
        range = null,
        threadLocal = declSpec.threadLocal)
    return "$dsNoStorage$indStr"
  }
}

/**
 * This class stores lexically-scoped identifiers.
 *
 * C standard: 6.2.1
 */
data class LexicalScope(val typedefNames: MutableList<TypedefName> = mutableListOf(),
                        val tagNames: MutableList<TagSpecifier> = mutableListOf(),
                        val idents: MutableList<IdentifierNode> = mutableListOf(),
                        val labels: MutableList<IdentifierNode> = mutableListOf()) {

  override fun toString(): String {
    val identStr = idents.joinToString(", ") { it.name }
    val labelStr = idents.joinToString(", ") { it.name }
    val typedefStr =
        typedefNames.joinToString(", ") { "${it.typedefedToString()} ${it.typedefIdent.name}" }
    return "LexicalScope(idents=[$identStr], labels=[$labelStr], typedefs=[$typedefStr])"
  }
}

/** Represents a leaf node of an AST (ie an [ASTNode] that is a parent to nobody). */
interface Terminal

/**
 * Signals an error condition in the parser. If some part of the grammar cannot be parsed, an
 * instance of this interface is returned.
 *
 * All instances of [ErrorNode] should be equal.
 */
interface ErrorNode : Terminal {
  override fun equals(other: Any?): Boolean
  override fun hashCode(): Int
  override fun toString(): String
}

/**
 * Generic implementation of [Any.equals], [Any.hashCode] and [Any.toString] for an [ErrorNode]
 * implementor. Useful for delegation.
 * @see ErrorNode
 */
private object ErrorNodeImpl : ErrorNode {
  override fun equals(other: Any?) = other is ErrorNode
  override fun hashCode() = javaClass.hashCode()
  override fun toString() = "<ERROR>"
}

interface StringClassName {
  override fun toString(): String
}

/**
 * Generic implementation of [Any.toString] that prints the class' simple name.
 * Useful for delegation.
 * @see StringClassName
 */
private object StringClassNameImpl : StringClassName {
  override fun toString() = javaClass.simpleName!!
}

/** The root node of a translation unit. Stores top-level [ExternalDeclaration]s. */
class RootNode : ASTNode(isRoot = true) {
  private val declarations = mutableListOf<ExternalDeclaration>()
  val decls: List<ExternalDeclaration> = declarations

  fun addExternalDeclaration(n: ExternalDeclaration) {
    declarations += n
  }
}

/** C standard: A.2.3, 6.8 */
sealed class Statement : ASTNode()

@Suppress("DELEGATED_MEMBER_HIDES_SUPERTYPE_OVERRIDE")
class ErrorStatement : Statement(), ErrorNode by ErrorNodeImpl

/** The standard says no-ops are expressions, but here it is represented separately. */
class Noop : Statement(), Terminal {
  override fun toString() = "<no-op>"
}

/**
 * Represents an expression.
 *
 * C standard: A.2.3, 6.8.3
 */
sealed class Expression : Statement()

@Suppress("DELEGATED_MEMBER_HIDES_SUPERTYPE_OVERRIDE")
class ErrorExpression : Expression(), ErrorNode by ErrorNodeImpl

data class FunctionCall(val calledExpr: Expression, val args: List<Expression>) : Expression() {
  init {
    calledExpr.setParent(this)
    args.forEach { it.setParent(this) }
  }
}

/**
 * This does not represent the entire "unary-expression" from the standard, just the
 * "unary-operator cast-expression" part of it.
 * C standard: A.2.1
 */
data class UnaryExpression(val op: Operators, val operand: Expression) : Expression() {
  init {
    operand.setParent(this)
  }
}

/** C standard: A.2.1 */
data class SizeofExpression(val sizeExpr: Expression) : Expression() {
  init {
    sizeExpr.setParent(this)
  }
}

data class PrefixIncrement(val expr: Expression) : Expression() {
  init {
    expr.setParent(this)
  }
}

data class PrefixDecrement(val expr: Expression) : Expression() {
  init {
    expr.setParent(this)
  }
}

data class PostfixIncrement(val expr: Expression) : Expression() {
  init {
    expr.setParent(this)
  }
}

data class PostfixDecrement(val expr: Expression) : Expression() {
  init {
    expr.setParent(this)
  }
}

/** Represents a binary operation in an expression. */
data class BinaryExpression(val op: Operators,
                            val lhs: Expression,
                            val rhs: Expression) : Expression() {
  init {
    lhs.setParent(this)
    rhs.setParent(this)
  }

  override fun toString() = "($lhs $op $rhs)"
}

data class IdentifierNode(val name: String) : Expression(), Terminal {
  constructor(lexerIdentifier: Identifier) : this(lexerIdentifier.name)

  companion object {
    /**
     * Creates an [IdentifierNode] from an [Identifier].
     * @param identifier this [LexicalToken] is casted to an [Identifier]
     */
    fun from(identifier: LexicalToken) =
        IdentifierNode(identifier as Identifier).withRange(identifier.range)
  }
}

data class IntegerConstantNode(val value: Long,
                               val suffix: IntegralSuffix) : Expression(), Terminal {
  override fun toString() = "$value$suffix"
}

data class FloatingConstantNode(val value: Double,
                                val suffix: FloatingSuffix) : Expression(), Terminal {
  override fun toString() = "$value$suffix"
}

/**
 * Stores a character constant.
 *
 * According to the C standard, the value of multi-byte character constants is
 * implementation-defined. This implementation truncates the constants to the first byte.
 *
 * Also, empty char constants are not defined; here they are equal to 0, and produce a warning.
 *
 * C standard: 6.4.4.4.10
 */
data class CharacterConstantNode(val char: Int, val encoding: CharEncoding) : Expression(), Terminal

data class StringLiteralNode(val string: String,
                             val encoding: StringEncoding) : Expression(), Terminal

/**
 * FIXME: add `ComplexFloat` `ComplexDouble` `ComplexLongDouble`
 * FIXME: add atomic-type-specifier (6.7.2.4)
 */
sealed class TypeSpecifier

data class EnumSpecifier(val name: IdentifierNode) : TypeSpecifier()

data class TypedefNameSpecifier(val name: IdentifierNode, val type: TypedefName) : TypeSpecifier() {
  override fun toString() = "${name.name} (aka ${type.typedefedToString()})"
}

// FIXME: if a declaration has an incomplete type that never gets completed, print a diagnostic
sealed class TagSpecifier : TypeSpecifier() {
  abstract val isCompleteType: Boolean
  abstract val isAnonymous: Boolean
  abstract val tagIdent: IdentifierNode
  abstract val tagKindKeyword: Keyword
}

data class StructNameSpecifier(val name: IdentifierNode,
                               override val tagKindKeyword: Keyword) : TagSpecifier() {
  override val isCompleteType = false
  override val isAnonymous = false
  override val tagIdent = name
  override fun toString() = "struct ${name.name}"
}

data class UnionNameSpecifier(val name: IdentifierNode,
                              override val tagKindKeyword: Keyword) : TagSpecifier() {
  override val isCompleteType = false
  override val isAnonymous = false
  override val tagIdent = name
  override fun toString() = "union ${name.name}"
}

data class StructDefinition(val name: IdentifierNode?,
                            val decls: List<Declaration>,
                            override val tagKindKeyword: Keyword) : TagSpecifier() {
  override val isCompleteType = true
  override val isAnonymous get() = name == null
  override val tagIdent: IdentifierNode get() = name!!
  override fun toString() = "struct ${if (name != null) "${name.name} " else ""}{...}"
}

data class UnionDefinition(val name: IdentifierNode?,
                           val decls: List<Declaration>,
                           override val tagKindKeyword: Keyword) : TagSpecifier() {
  override val isCompleteType = true
  override val isAnonymous get() = name == null
  override val tagIdent: IdentifierNode get() = name!!
  override fun toString() = "union ${if (name != null) "${name.name} " else ""}{...}"
}

sealed class BasicTypeSpecifier(val first: Keyword) : TypeSpecifier() {
  override fun equals(other: Any?) = this.javaClass == other?.javaClass
  override fun hashCode() = javaClass.hashCode()
}

class VoidType(first: Keyword) : BasicTypeSpecifier(first) {
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

class FloatType(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = Keywords.FLOAT.keyword
}

class DoubleType(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = Keywords.DOUBLE.keyword
}

class LongDouble(first: Keyword) : BasicTypeSpecifier(first) {
  override fun toString() = "$Long $Double"
}

/**
 * Stores declaration specifiers that come before declarators.
 * FIXME: alignment specifier (A.2.2/6.7.5)
 */
class DeclarationSpecifier(val storageClass: Keyword? = null,
                           val threadLocal: Keyword? = null,
                           val typeQualifiers: List<Keyword> = emptyList(),
                           val functionSpecs: List<Keyword> = emptyList(),
                           val typeSpec: TypeSpecifier? = null,
                           val range: IntRange? = null) {
  /** @return true if no specifiers were found */
  fun isEmpty() = !hasStorageClass() && !isThreadLocal() && typeQualifiers.isEmpty() &&
      functionSpecs.isEmpty() && typeSpec == null

  /**
   * C standard: 6.7.2.1, 6.7.2.3
   * @return true if this [DeclarationSpecifier] is sufficient by itself, and does not necessarily
   * need declarators after it
   */
  fun isTag() = typeSpec is TagSpecifier

  fun isThreadLocal() = threadLocal != null

  fun hasStorageClass() = storageClass != null

  fun isTypedef() = storageClass?.value == Keywords.TYPEDEF

  override fun toString(): String {
    val otherSpecs = listOf(typeQualifiers, functionSpecs)
        .filter { it.isNotEmpty() }
        .joinToString(" ") {
          it.joinToString(" ") { (value) -> value.keyword }
        }
    val otherSpecsStr = if (otherSpecs.isBlank()) "" else "$otherSpecs "
    val storageClassStr = if (hasStorageClass()) "${storageClass!!.value.keyword} " else ""
    val threadLocalStr = if (isThreadLocal()) "${Keywords.THREAD_LOCAL.keyword} " else ""
    return "$threadLocalStr$storageClassStr$otherSpecsStr$typeSpec"
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as DeclarationSpecifier

    if (typeSpec != other.typeSpec) return false
    if (storageClass != other.storageClass) return false
    if (threadLocal != other.threadLocal) return false
    if (typeQualifiers != other.typeQualifiers) return false
    if (functionSpecs != other.functionSpecs) return false

    return true
  }

  override fun hashCode(): Int {
    var result = storageClass.hashCode()
    result = 31 * result + threadLocal.hashCode()
    result = 31 * result + typeSpec.hashCode()
    result = 31 * result + typeQualifiers.hashCode()
    result = 31 * result + functionSpecs.hashCode()
    return result
  }
}

typealias TypeQualifierList = List<Keyword>

/** C standard: 6.7.6 */
sealed class Declarator : ASTNode() {
  private var indirectionImpl: List<TypeQualifierList>? = null

  val indirection: List<TypeQualifierList> by lazy {
    indirectionImpl
        ?: logger.throwICE("Accessing lazily initialized property before it was set") { this }
  }

  fun setIndirection(list: List<TypeQualifierList>) {
    indirectionImpl = list
  }

  fun name(): IdentifierNode? = when (this) {
    is ErrorDeclarator -> null
    is NameDeclarator -> name
    is InitDeclarator -> declarator.name()
    is FunctionDeclarator -> declarator.name()
    is ParameterDeclaration -> declarator.name()
    is StructDeclarator -> declarator.name()
  }
}

@Suppress("DELEGATED_MEMBER_HIDES_SUPERTYPE_OVERRIDE")
class ErrorDeclarator : Declarator(), ErrorNode by ErrorNodeImpl

data class NameDeclarator(val name: IdentifierNode) : Declarator() {
  init {
    name.setParent(this)
  }

  override fun toString() = "NameDeclarator(${name.name})"

  companion object {
    /**
     * Creates a [NameDeclarator] from an [Identifier].
     * @param identifier this [LexicalToken] is casted to an [Identifier]
     * @see IdentifierNode.from
     */
    fun from(identifier: LexicalToken) =
        NameDeclarator(IdentifierNode.from(identifier)).withRange(identifier.range)
  }
}

// FIXME: initializer (6.7.9/A.2.2) can be either expression or initializer-list
data class InitDeclarator(val declarator: Declarator, val initializer: Expression) : Declarator() {
  init {
    declarator.setParent(this)
    initializer.setParent(this)
  }

  override fun toString() = "InitDeclarator($declarator = $initializer)"
}

data class ParameterDeclaration(val declSpec: DeclarationSpecifier,
                                val declarator: Declarator) : Declarator() {
  init {
    declarator.setParent(this)
  }
}

// FIXME: params can also be abstract-declarators (6.7.6/A.2.4)
data class FunctionDeclarator(val declarator: Declarator,
                              val params: List<ParameterDeclaration>,
                              val variadic: Boolean = false,
                              val scope: LexicalScope) : Declarator() {
  init {
    declarator.setParent(this)
    params.forEach { it.setParent(this) }
  }
}

data class StructDeclarator(val declarator: Declarator, val constExpr: Expression?) : Declarator() {
  init {
    declarator.setParent(this)
    constExpr?.setParent(this)
  }
}

/** C standard: A.2.4 */
sealed class ExternalDeclaration : ASTNode()

/**
 * Represents a declaration.
 *
 * If the [declaratorList] is empty, the [declSpecs] define a struct/union.
 *
 * C standard: A.2.2
 */
data class Declaration(val declSpecs: DeclarationSpecifier,
                       val declaratorList: List<Declarator>) : ExternalDeclaration() {
  init {
    declaratorList.forEach { it.setParent(this) }
  }

  /**
   * @return a list of [IdentifierNode]s of declarators in the declaration.
   */
  fun identifiers(): List<IdentifierNode> {
    return declaratorList.mapNotNull { it.name() }
  }
}

/** C standard: A.2.4 */
data class FunctionDefinition(val declSpec: DeclarationSpecifier,
                              val functionDeclarator: Declarator,
                              val compoundStatement: Statement) : ExternalDeclaration() {
  init {
    functionDeclarator.setParent(this)
    compoundStatement.setParent(this)
  }

  override fun toString(): String {
    return "FunctionDefinition($declSpec, $functionDeclarator, $compoundStatement)"
  }
}

/** C standard: A.2.3, 6.8.2 */
sealed class BlockItem : ASTNode()

data class DeclarationItem(val declaration: Declaration) : BlockItem() {
  init {
    declaration.setParent(this)
  }

  override fun toString() = declaration.toString()
}

data class StatementItem(val statement: Statement) : BlockItem() {
  init {
    statement.setParent(this)
  }

  override fun toString() = statement.toString()
}

/**
 * A block of [Statement]s.
 *
 * C standard: A.2.3, 6.8.2
 * @param items the things inside the block
 * @param scope the [LexicalScope] of this block. If this is the block for a function definition,
 * this is a reference to the function's scope, that is pre-filled with the function arguments.
 */
data class CompoundStatement(val items: List<BlockItem>, val scope: LexicalScope) : Statement() {
  init {
    items.forEach { it.setParent(this) }
  }

  override fun toString(): String {
    val stuff = items.joinToString(",") { "\n\t$it" }
    return "{\n$scope$stuff\n}"
  }
}

/** C standard: 6.8.1 */
data class LabeledStatement(val label: IdentifierNode, val statement: Statement) : Statement() {
  init {
    label.setParent(this)
    statement.setParent(this)
  }
}

/** C standard: 6.8.4.1 */
data class IfStatement(val cond: Expression,
                       val success: Statement,
                       val failure: Statement?) : Statement() {
  init {
    cond.setParent(this)
    success.setParent(this)
    failure?.setParent(this)
  }
}

/** C standard: 6.8.4.2 */
data class SwitchStatement(val switch: Expression, val body: Statement) : Statement() {
  init {
    switch.setParent(this)
    body.setParent(this)
  }
}

/** C standard: 6.8.5.1 */
data class WhileStatement(val cond: Expression, val loopable: Statement) : Statement() {
  init {
    cond.setParent(this)
    loopable.setParent(this)
  }
}

/** C standard: 6.8.5.2 */
data class DoWhileStatement(val cond: Expression, val loopable: Statement) : Statement() {
  init {
    cond.setParent(this)
    loopable.setParent(this)
  }
}

sealed class ForInitializer : ASTNode()

class EmptyInitializer : ForInitializer(), Terminal, StringClassName by StringClassNameImpl

@Suppress("DELEGATED_MEMBER_HIDES_SUPERTYPE_OVERRIDE")
class ErrorInitializer : ForInitializer(), ErrorNode by ErrorNodeImpl

data class DeclarationInitializer(val value: Declaration) : ForInitializer() {
  init {
    value.setParent(this)
  }

  override fun toString() = value.toString()
}

data class ExpressionInitializer(val value: Expression) : ForInitializer() {
  init {
    value.setParent(this)
  }
}

/** C standard: 6.8.5.3 */
data class ForStatement(val init: ForInitializer,
                        val cond: Expression?,
                        val loopEnd: Expression?,
                        val loopable: Statement) : Statement() {
  init {
    init.setParent(this)
    cond?.setParent(this)
    loopEnd?.setParent(this)
    loopable.setParent(this)
  }
}

/** C standard: 6.8.6.2 */
class ContinueStatement : Statement(), Terminal, StringClassName by StringClassNameImpl

/** C standard: 6.8.6.3 */
class BreakStatement : Statement(), Terminal, StringClassName by StringClassNameImpl

/** C standard: 6.8.6.1 */
data class GotoStatement(val identifier: IdentifierNode) : Statement() {
  init {
    identifier.setParent(this)
  }
}

/** C standard: 6.8.6.4 */
data class ReturnStatement(val expr: Expression?) : Statement() {
  init {
    expr?.setParent(this)
  }
}
