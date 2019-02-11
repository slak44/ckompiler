package slak.ckompiler.parser

import mu.KotlinLogging
import slak.ckompiler.lexer.*
import slak.ckompiler.throwICE

private val logger = KotlinLogging.logger("AST")

val Declarator.name get() = (this as NamedDeclarator).name
val ExternalDeclaration.fn get() = this as FunctionDefinition
val FunctionDefinition.name get() = functionDeclarator.name
val FunctionDefinition.block get() = compoundStatement as CompoundStatement
val BlockItem.st get() = (this as StatementItem).statement

infix fun LexicalToken.until(other: LexicalToken): IntRange = this.startIdx until other.startIdx

operator fun LexicalToken.rangeTo(other: LexicalToken) =
    startIdx until (other.startIdx + other.consumedChars)

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
   * The range of 'stuff' in this node. Usually created from [TokenObject]'s range data.
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

  override fun equals(other: Any?) = other is ASTNode
  override fun hashCode() = javaClass.hashCode()
}

/** Sets a node's token range, and returns the node. */
fun <T : ASTNode> T.withRange(range: IntRange): T {
  this.setRange(range)
  return this
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
class RootNode(val scope: LexicalScope) : ASTNode(isRoot = true) {
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
sealed class Expression(val type: TypeName) : Statement()

@Suppress("DELEGATED_MEMBER_HIDES_SUPERTYPE_OVERRIDE")
class ErrorExpression : Expression(VoidType), ErrorNode by ErrorNodeImpl

/**
 * Represents a function call in an [Expression].
 *
 * C standard: 6.5.2.2
 * @param calledExpr must have [Expression.type] be [FunctionType] (or [PointerType] to a
 * [FunctionType])
 */
data class FunctionCall(val calledExpr: Expression, val args: List<Expression>) :
    Expression(calledExpr.type.asCallable()
        ?: logger.throwICE("Attempt to call non-function") { "$calledExpr($args)" }) {
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
data class UnaryExpression(val op: Operators, val operand: Expression) : Expression(when (op) {
  // FIXME: this is slightly more nuanced (6.5.3.3)
  Operators.PLUS, Operators.MINUS, Operators.BIT_NOT,
  Operators.NOT, Operators.REF, Operators.DEREF -> operand.type
  else -> logger.throwICE("Cannot determine type of UnaryExpression") { "$op" }
}) {
  init {
    if (op !in Operators.unaryOperators) {
      logger.throwICE("UnaryExpression with non-unary operator") { this }
    }
    operand.setParent(this)
  }
}

/**
 * C standard: A.2.1
 */
data class SizeofExpression(val sizeExpr: Expression) : Expression(UnsignedIntType) {
  init {
    // FIXME: disallow function types/incomplete types/bitfield members
    sizeExpr.setParent(this)
  }
}

data class PrefixIncrement(val expr: Expression) : Expression(expr.type) {
  init {
    // FIXME: filter on expr.type (6.5.3.1)
    expr.setParent(this)
  }
}

data class PrefixDecrement(val expr: Expression) : Expression(expr.type) {
  init {
    // FIXME: filter on expr.type (6.5.3.1)
    expr.setParent(this)
  }
}

data class PostfixIncrement(val expr: Expression) : Expression(expr.type) {
  init {
    // FIXME: filter on expr.type (6.5.3.1)
    expr.setParent(this)
  }
}

data class PostfixDecrement(val expr: Expression) : Expression(expr.type) {
  init {
    // FIXME: filter on expr.type (6.5.3.1)
    expr.setParent(this)
  }
}

/** Represents a binary operation in an expression. */
data class BinaryExpression(val op: Operators,
                            val lhs: Expression,
                            val rhs: Expression) : Expression(lhs.type /* FIXME: clearly temp */) {
  init {
    // FIXME: disallow non-binary ops
    // FIXME: check lhs/rhs types against what the op expects
    lhs.setParent(this)
    rhs.setParent(this)
  }

  override fun toString() = "($lhs $op $rhs)"
}

class IdentifierNode(override val name: String, type: TypeName = VoidType /* FIXME: temp */) :
    Expression(type), Terminal, OrdinaryIdentifier {
  constructor(lexerIdentifier: Identifier, type: TypeName = VoidType /* FIXME: clearly temp */) :
      this(lexerIdentifier.name, type)

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is IdentifierNode) return false

    if (name != other.name) return false

    return true
  }

  override fun hashCode(): Int {
    var result = super.hashCode()
    result = 31 * result + name.hashCode()
    return result
  }

  override fun toString() = name

  override val kindName = "variable"

  companion object {
    /**
     * Creates an [IdentifierNode] from an [Identifier].
     * @param identifier this [LexicalToken] is casted to an [Identifier]
     */
    fun from(identifier: LexicalToken) =
        IdentifierNode(identifier as Identifier).withRange(identifier.range)
  }
}

data class IntegerConstantNode(val value: Long, val suffix: IntegralSuffix) :
    Expression(when (suffix) {
      IntegralSuffix.UNSIGNED -> UnsignedIntType
      IntegralSuffix.UNSIGNED_LONG -> UnsignedLongType
      IntegralSuffix.UNSIGNED_LONG_LONG -> UnsignedLongLongType
      IntegralSuffix.LONG -> SignedLongType
      IntegralSuffix.LONG_LONG -> SignedLongLongType
      IntegralSuffix.NONE -> SignedIntType
    }), Terminal {
  override fun toString() = "$value$suffix"
}

data class FloatingConstantNode(val value: Double, val suffix: FloatingSuffix) :
    Expression(when (suffix) {
      FloatingSuffix.FLOAT -> FloatType
      FloatingSuffix.LONG_DOUBLE -> LongDoubleType
      FloatingSuffix.NONE -> DoubleType
    }), Terminal {
  override fun toString() = "$value$suffix"
}

/**
 * Stores a character constant.
 *
 * According to the standard, the value of multi-byte character constants is
 * implementation-defined. This implementation truncates the constants to the first char.
 *
 * Also, empty char constants are not defined; here they are equal to 0, and produce a warning.
 *
 * C standard: 6.4.4.4.0.10
 */
data class CharacterConstantNode(val char: Int, val encoding: CharEncoding) :
    Expression(UnsignedIntType), Terminal

/**
 * FIXME: UTF-8 handling. Array size is not string.length
 * FIXME: wchar_t & friends should have more specific element type
 */
data class StringLiteralNode(val string: String, val encoding: StringEncoding) :
    Expression(ArrayType(when (encoding) {
      StringEncoding.CHAR, StringEncoding.UTF8 -> UnsignedIntType
      else -> UnsignedLongLongType
    }, ExpressionSize(IntegerConstantNode(string.length.toLong(), IntegralSuffix.NONE)))), Terminal

/**
 * Stores declaration specifiers that come before declarators.
 * FIXME: alignment specifier (A.2.2/6.7.5)
 */
data class DeclarationSpecifier(val storageClass: Keyword? = null,
                                val threadLocal: Keyword? = null,
                                val typeQualifiers: List<Keyword> = emptyList(),
                                val functionSpecs: List<Keyword> = emptyList(),
                                val typeSpec: TypeSpecifier? = null) : ASTNode() {

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
}

sealed class DeclaratorSuffix : ASTNode()

@Suppress("DELEGATED_MEMBER_HIDES_SUPERTYPE_OVERRIDE")
class ErrorSuffix : DeclaratorSuffix(), ErrorNode by ErrorNodeImpl

/**
 * The scope contains names from [params].
 *
 * C standard: 6.7.6.0.1
 */
data class ParameterTypeList(val params: List<ParameterDeclaration>,
                             val scope: LexicalScope = LexicalScope(),
                             val variadic: Boolean = false) : DeclaratorSuffix() {
  init {
    params.forEach { it.setParent(this) }
  }

  override fun toString(): String {
    val paramStr = params.joinToString(", ")
    val variadicStr = if (!variadic) "" else ", ..."
    return "($paramStr$variadicStr)"
  }
}

data class ParameterDeclaration(val declSpec: DeclarationSpecifier,
                                val declarator: Declarator) : ASTNode() {
  init {
    declSpec.setParent(this)
    declarator.setParent(this)
  }

  override fun toString() = "$declSpec $declarator"
}

typealias TypeQualifierList = List<Keyword>

fun List<TypeQualifierList>.stringify() =
    this.joinToString { '*' + it.joinToString(" ") { (value) -> value.keyword } }

sealed class Declarator : ASTNode() {
  abstract val indirection: List<TypeQualifierList>
  abstract val suffixes: List<DeclaratorSuffix>

  fun isFunction(): Boolean {
    // FIXME: this might not be enough
    return suffixes.isNotEmpty() && suffixes[0] is ParameterTypeList
  }

  fun getFunctionTypeList(): ParameterTypeList = suffixes[0] as ParameterTypeList
}

/** C standard: 6.7.6 */
data class NamedDeclarator(val name: IdentifierNode,
                           override val indirection: List<TypeQualifierList>,
                           override val suffixes: List<DeclaratorSuffix>) : Declarator() {
  init {
    name.setParent(this)
    suffixes.forEach { it.setParent(this) }
  }

  override fun toString() = "${indirection.stringify()} $name $suffixes"
}

/** C standard: 6.7.7.0.1 */
data class AbstractDeclarator(override val indirection: List<TypeQualifierList>,
                              override val suffixes: List<DeclaratorSuffix>) : Declarator() {
  init {
    suffixes.forEach { it.setParent(this) }
  }

  override fun toString() = "(${indirection.stringify()}) $suffixes"
}

@Suppress("DELEGATED_MEMBER_HIDES_SUPERTYPE_OVERRIDE")
class ErrorDeclarator : Declarator(), ErrorNode by ErrorNodeImpl {
  override val suffixes = emptyList<DeclaratorSuffix>()
  override val indirection = emptyList<TypeQualifierList>()
}

// FIXME: initializer (6.7.9/A.2.2) can be either expression or initializer-list
sealed class Initializer : ASTNode()

data class ExpressionInitializer(val expr: Expression) : Initializer() {
  init {
    expr.setParent(this)
  }

  override fun toString() = expr.toString()

  companion object {
    fun from(expr: Expression) = ExpressionInitializer(expr).withRange(expr.tokenRange)
  }
}

data class StructMember(val declarator: Declarator, val constExpr: Expression?) : ASTNode() {
  init {
    declarator.setParent(this)
    constExpr?.setParent(this)
  }
}

data class StructDeclaration(val declSpecs: DeclarationSpecifier,
                             val declaratorList: List<StructMember>) : ASTNode() {
  init {
    declSpecs.setParent(this)
    declaratorList.forEach { it.setParent(this) }
  }
}

/**
 * Contains the size of an array type.
 *
 * C standard: 6.7.6.2
 */
sealed class ArrayTypeSize(val hasVariableSize: Boolean) : DeclaratorSuffix()

/** Describes an array type that specifies no size in the square brackets. */
object NoSize : ArrayTypeSize(false)

/**
 * Describes an array type of variable length ("variable length array", "VLA").
 * Can only appear in declarations or `type-name`s with function prototype scope.
 * This kind of array is **unsupported** by this implementation.
 *
 * C standard: 6.7.6.2.4, 6.10.8.3.1
 * @param typeQuals the `type-qualifier`s before the *: `int v[const *];`
 * @param vlaStar (diagnostic data) the * in the square brackets: `int v[*];`
 */
data class UnconfinedVariableSize(val typeQuals: TypeQualifierList,
                                  val vlaStar: Punctuator) : ArrayTypeSize(true)

/**
 * Describes an array type whose size is specified by [expr], where the type is a function
 * parameter's type. A non-constant [expr] describes a VLA, which this implementation does **not
 * support**. A null [expr] means that [typeQuals] must be non-empty. If [isStatic] is true, [expr]
 * must not be null.
 *
 * C standard: 6.7.6.2.3, 6.7.6.3.7
 * @param typeQuals the `type-qualifier`s between the square brackets
 * @param isStatic if the keyword "static" appears between the square brackets
 */
data class FunctionParameterSize(val typeQuals: TypeQualifierList,
                                 val isStatic: Boolean,
                                 val expr: Expression?) :
    ArrayTypeSize(false /* FIXME: not always false */) {
  init {
    if (isStatic && expr == null) logger.throwICE("Array size, static without expr") { this }
    if (expr == null && typeQuals.isEmpty()) {
      logger.throwICE("Array size, no type quals and no expr") { this }
    }
    expr?.setParent(this)
  }
}

/**
 * Describes an array type whose size is specified by [expr]. A non-constant [expr] describes a VLA,
 * which this implementation does **not support**.
 * @param expr integral expression
 */
data class ExpressionSize(val expr: Expression) :
    ArrayTypeSize(false /* FIXME: not always false */) {
  init {
    expr.setParent(this)
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
                       val declaratorList: List<Pair<Declarator, Initializer?>>) :
    ExternalDeclaration() {
  init {
    declSpecs.setParent(this)
    declaratorList.forEach {
      it.first.setParent(this)
      it.second?.setParent(this)
    }
  }

  /**
   * @return a list of [IdentifierNode]s of declarators in the declaration.
   */
  fun identifiers(): List<IdentifierNode> {
    return declaratorList.map { it.first.name }
  }
}

/** C standard: A.2.4 */
data class FunctionDefinition(val declSpec: DeclarationSpecifier,
                              val functionDeclarator: Declarator,
                              val compoundStatement: Statement) : ExternalDeclaration() {
  init {
    declSpec.setParent(this)
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

  companion object {
    fun from(value: Declaration) = DeclarationInitializer(value).withRange(value.tokenRange)
  }
}

data class ForExpressionInitializer(val value: Expression) : ForInitializer() {
  init {
    value.setParent(this)
  }

  companion object {
    fun from(expr: Expression) = ForExpressionInitializer(expr).withRange(expr.tokenRange)
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
