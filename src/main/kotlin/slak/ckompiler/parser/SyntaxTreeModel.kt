package slak.ckompiler.parser

import org.apache.logging.log4j.LogManager
import slak.ckompiler.SourcedRange
import slak.ckompiler.analysis.IdCounter
import slak.ckompiler.lexer.*
import slak.ckompiler.throwICE

private val logger = LogManager.getLogger("AST")

val Declarator.name get() = (this as NamedDeclarator).name
val ExternalDeclaration.fn get() = this as FunctionDefinition
val BlockItem.st get() = (this as StatementItem).statement
val BlockItem.decl get() = (this as DeclarationItem).declaration

/**
 * Base class of all nodes from an Abstract Syntax Tree.
 * @param isRoot set to true if this [ASTNode] is the root node for the tree
 */
sealed class ASTNode(val isRoot: Boolean = false) : SourcedRange {
  private var lateTokenRange: IntRange? = null

  /**
   * The range of 'stuff' in this node. Usually created from [LexicalToken]'s range data.
   * @throws slak.ckompiler.InternalCompilerError if accessed on a node without a range set
   */
  override val range: IntRange by lazy {
    if (lateTokenRange == null) {
      logger.throwICE("Attempt to access missing token range data") { this }
    }
    return@lazy lateTokenRange!!
  }

  /** Sets this node's token range. */
  fun setRange(range: IntRange) {
    if (range.first > range.last) {
      logger.throwICE("Bad token range on ASTNode") { "this: $this, range: $range" }
    }
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
sealed class Expression : Statement() {
  /** The [TypeName] of this expression's result. */
  abstract val type: TypeName
}

@Suppress("DELEGATED_MEMBER_HIDES_SUPERTYPE_OVERRIDE")
class ErrorExpression : ExprConstantNode(), ErrorNode by ErrorNodeImpl {
  override val type = ErrorType
}

/**
 * Like [IdentifierNode], but with an attached [TypeName].
 *
 * A [TypedIdentifier] should be "unique", in that for each variable in a function, all uses of it
 * must use the same [TypedIdentifier] instance, with the same [TypedIdentifier.id]. Even though
 * two instances with different ids can compare equal, they refer to different variables with the
 * same name (name shadowing). Creating other instances is fine as long as they are not leaked to
 * the rest of the AST.
 */
data class TypedIdentifier(override val name: String,
                           override val type: TypeName
) : Expression(), OrdinaryIdentifier, Terminal {
  constructor(ds: DeclarationSpecifier,
              decl: NamedDeclarator) : this(decl.name.name, typeNameOf(ds, decl)) {
    withRange(decl.name.range)
  }

  var id = varCounter()
    private set

  override val kindName = "variable"

  /**
   * Makes a copy of this [TypedIdentifier], that has the same [id].
   * Useful for having a different [range].
   */
  fun copy(): TypedIdentifier {
    val other = TypedIdentifier(name, type)
    other.id = id
    return other
  }

  override fun toString() = "$type $name"

  companion object {
    private val varCounter = IdCounter()
  }
}

data class TernaryConditional(val cond: Expression,
                              val success: Expression,
                              val failure: Expression) : Expression() {
  override val type = resultOfTernary(success, failure)
}

/**
 * Represents a function call in an [Expression].
 *
 * C standard: 6.5.2.2
 * @param calledExpr must have [Expression.type] be [FunctionType] (or [PointerType] to a
 * [FunctionType])
 */
data class FunctionCall(val calledExpr: Expression, val args: List<Expression>) : Expression() {
  override val type = calledExpr.type.asCallable()?.returnType
      ?: logger.throwICE("Attempt to call non-function") { "$calledExpr($args)" }
}

/**
 * This does not represent the entire "unary-expression" from the standard, just the
 * "unary-operator cast-expression" part of it.
 * C standard: A.2.1
 */
data class UnaryExpression(val op: UnaryOperators, val operand: Expression) : Expression() {
  override val type = op.applyTo(operand.type)
}

/** C standard: A.2.1 */
data class SizeofTypeName(val typeName: TypeName) : Expression() {
  override val type = UnsignedIntType

  init {
    // FIXME: disallow function types/incomplete types/bitfield members
  }
}

/** C standard: A.2.1 */
data class SizeofExpression(val sizeExpr: Expression) : Expression() {
  // FIXME: do we have to keep the expression? can we just take sizeExpr.type and forget the rest?
  override val type = UnsignedIntType

  init {
    // FIXME: disallow function types/incomplete types/bitfield members
  }
}

/**
 * Represents the argument of ++x, x++, --x and x--.
 */
interface IncDecOperation {
  val expr: Expression
}

/**
 * @param expr expression to increment. Expected to be of correct type.
 */
data class PrefixIncrement(override val expr: Expression) : Expression(), IncDecOperation {
  override val type = expr.type
}

/**
 * @param expr expression to increment. Expected to be of correct type.
 */
data class PrefixDecrement(override val expr: Expression) : Expression(), IncDecOperation {
  override val type = expr.type
}

data class PostfixIncrement(override val expr: Expression) : Expression(), IncDecOperation {
  override val type = expr.type

  init {
    // FIXME: filter on expr.type (6.5.3.1)
  }
}

data class PostfixDecrement(override val expr: Expression) : Expression(), IncDecOperation {
  override val type = expr.type

  init {
    // FIXME: filter on expr.type (6.5.3.1)
  }
}

/** Represents a binary operation in an expression. */
data class BinaryExpression(val op: BinaryOperators, val lhs: Expression, val rhs: Expression) :
    Expression() {
  override val type = op.applyTo(lhs.type, rhs.type)
  override fun toString() = "($lhs $op $rhs)"
}

data class ArraySubscript(val subscripted: Expression,
                          val subscript: Expression,
                          override val type: TypeName) : Expression() {
  override fun toString() = "$subscripted[$subscript]"
}

data class CastExpression(val target: Expression, override val type: TypeName) : Expression() {
  override fun toString() = "($type) $target"
}

sealed class ExprConstantNode : Expression(), Terminal

data class IntegerConstantNode(val value: Long,
                               val suffix: IntegralSuffix) : ExprConstantNode() {
  override val type = when (suffix) {
    IntegralSuffix.UNSIGNED -> UnsignedIntType
    IntegralSuffix.UNSIGNED_LONG -> UnsignedLongType
    IntegralSuffix.UNSIGNED_LONG_LONG -> UnsignedLongLongType
    IntegralSuffix.LONG -> SignedLongType
    IntegralSuffix.LONG_LONG -> SignedLongLongType
    IntegralSuffix.NONE -> SignedIntType
  }

  override fun toString() = "$value$suffix"
}

data class FloatingConstantNode(val value: Double,
                                val suffix: FloatingSuffix) : ExprConstantNode() {
  override val type = when (suffix) {
    FloatingSuffix.FLOAT -> FloatType
    FloatingSuffix.LONG_DOUBLE -> LongDoubleType
    FloatingSuffix.NONE -> DoubleType
  }

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
data class CharacterConstantNode(val char: Int,
                                 val encoding: CharEncoding) : ExprConstantNode() {
  override val type = UnsignedIntType
}

/**
 * FIXME: UTF-8 handling. Array size is not string.length
 * FIXME: wchar_t & friends should have more specific element type
 */
data class StringLiteralNode(val string: String,
                             val encoding: StringEncoding) : ExprConstantNode() {
  override val type = ArrayType(when (encoding) {
    StringEncoding.CHAR, StringEncoding.UTF8 -> UnsignedIntType
    else -> UnsignedLongLongType
  }, ExpressionSize(IntegerConstantNode(string.length.toLong(), IntegralSuffix.NONE)))
}

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
                             val scope: LexicalScope,
                             val variadic: Boolean = false) : DeclaratorSuffix() {
  override fun toString(): String {
    val paramStr = params.joinToString(", ")
    val variadicStr = if (!variadic) "" else ", ..."
    return "($paramStr$variadicStr)"
  }
}

data class ParameterDeclaration(val declSpec: DeclarationSpecifier,
                                val declarator: Declarator) : ASTNode() {
  override fun toString() = "$declSpec $declarator"
}

typealias TypeQualifierList = List<Keyword>

@JvmName("TypeQualifierList#stringify")
fun TypeQualifierList.stringify() = '*' + joinToString(" ") { (value) -> value.keyword }

@JvmName("List_TypeQualifierList_#stringify")
fun List<TypeQualifierList>.stringify() = joinToString { it.stringify() }

data class IdentifierNode(val name: String) : ASTNode(), Terminal {
  constructor(lexerIdentifier: Identifier) : this(lexerIdentifier.name)

  override fun toString() = name

  companion object {
    /**
     * Creates an [IdentifierNode] from an [Identifier].
     * @param identifier this [LexicalToken] is casted to an [Identifier]
     */
    fun from(identifier: LexicalToken) =
        IdentifierNode(identifier as Identifier).withRange(identifier.range)
  }
}

sealed class Declarator : ASTNode() {
  abstract val indirection: List<TypeQualifierList>
  abstract val suffixes: List<DeclaratorSuffix>

  fun isFunction() = suffixes.isNotEmpty() && suffixes[0] is ParameterTypeList
  fun isArray() = suffixes.isNotEmpty() && suffixes[0] is ArrayTypeSize

  fun getFunctionTypeList(): ParameterTypeList = suffixes[0] as ParameterTypeList
  fun getArrayTypeSize(): ArrayTypeSize = suffixes[0] as ArrayTypeSize
}

/** C standard: 6.7.6 */
data class NamedDeclarator(val name: IdentifierNode,
                           override val indirection: List<TypeQualifierList>,
                           override val suffixes: List<DeclaratorSuffix>) : Declarator() {
  override fun toString(): String {
    val suffixesStr = suffixes.joinToString("")
    return "${indirection.stringify()}$name$suffixesStr"
  }
}

/** C standard: 6.7.7.0.1 */
data class AbstractDeclarator(override val indirection: List<TypeQualifierList>,
                              override val suffixes: List<DeclaratorSuffix>) : Declarator() {
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
  override fun toString() = expr.toString()

  companion object {
    fun from(expr: Expression) = ExpressionInitializer(expr).withRange(expr.range)
  }
}

data class StructMember(val declarator: Declarator, val constExpr: Expression?) : ASTNode()

data class StructDeclaration(val declSpecs: DeclarationSpecifier,
                             val declaratorList: List<StructMember>) : ASTNode()

/**
 * Contains the size of an array type.
 *
 * C standard: 6.7.6.2
 */
sealed class ArrayTypeSize(val hasVariableSize: Boolean) : DeclaratorSuffix()

/** Describes an array type that specifies no size in the square brackets. */
object NoSize : ArrayTypeSize(false) {
  override fun toString() = ""
}

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
                                  val vlaStar: Punctuator) : ArrayTypeSize(true) {
  override fun toString() = "${typeQuals.stringify()} *"
}

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
  }

  override fun toString(): String {
    val exprStr = if (expr == null) "" else " $expr"
    return "${if (isStatic) "static " else ""}${typeQuals.stringify()}$exprStr"
  }
}

/**
 * Describes an array type whose size is specified by [expr]. A non-constant [expr] describes a VLA,
 * which this implementation does **not support**.
 * @param expr integral expression
 */
data class ExpressionSize(val expr: Expression) :
    ArrayTypeSize(false /* FIXME: not always false */) {
  override fun toString() = "$expr"
}

/** C standard: A.2.4 */
sealed class ExternalDeclaration : ASTNode()

/**
 * Represents a declaration that actually declares variables.
 *
 * C standard: A.2.2
 */
data class Declaration(val declSpecs: DeclarationSpecifier,
                       val declaratorList: List<Pair<Declarator, Initializer?>>
) : ExternalDeclaration() {

  private var lateIdents: Set<TypedIdentifier>? = null

  fun idents(parent: LexicalScope): Set<TypedIdentifier> {
    if (lateIdents != null) return lateIdents!!
    val dIdents = declaratorList.map { TypedIdentifier(declSpecs, it.first as NamedDeclarator) }
    val idents = mutableSetOf<TypedIdentifier>()
    identLoop@ for (ident in dIdents) {
      var scope: LexicalScope? = parent
      do {
        val found = scope!!.idents.mapNotNull { it as? TypedIdentifier }.firstOrNull { it == ident }
        if (found != null) {
          idents += found
          continue@identLoop
        }
        scope = scope.parentScope
      } while (scope != null)
      logger.throwICE("Cannot find ident in current scopes") { "$ident\n$parent" }
    }
    lateIdents = idents
    return idents
  }

  override fun toString(): String {
    val tIdents = declaratorList.map {
      TypedIdentifier(declSpecs, it.first as NamedDeclarator)
    }
    val idents = tIdents.zip(declaratorList.map { it.second })
    val nameAndInits = idents.joinToString(", ") {
      val initStr = if (it.second == null) "" else " = ${it.second}"
      "${it.first.name}$initStr"
    }
    return "Declaration(${idents.first().first.type} $nameAndInits)"
  }
}

/** C standard: A.2.4 */
data class FunctionDefinition(val funcIdent: TypedIdentifier,
                              val functionType: FunctionType,
                              val parameters: List<TypedIdentifier>,
                              val compoundStatement: Statement) : ExternalDeclaration() {
  val name = funcIdent.name
  val block get() = compoundStatement as CompoundStatement

  override fun toString(): String {
    return "FunctionDefinition($funcIdent, $compoundStatement)"
  }

  companion object {
    operator fun invoke(declSpec: DeclarationSpecifier,
                        functionDeclarator: Declarator,
                        compoundStatement: Statement,
                        functionType: FunctionType): FunctionDefinition {
      val funcIdent = TypedIdentifier(declSpec, functionDeclarator as NamedDeclarator)
      val paramDecls = functionDeclarator.getFunctionTypeList().params
      if (funcIdent.type !is FunctionType) {
        logger.throwICE("Function identifier type is not FunctionType") { funcIdent.type }
      }
      val paramTypes = funcIdent.type.asCallable()!!.params
      val params = paramDecls.zip(paramTypes).mapNotNull { (paramDecl, typeName) ->
        if (paramDecl.declarator !is NamedDeclarator) return@mapNotNull null
        if (compoundStatement !is CompoundStatement) return@mapNotNull null
        val blockIdents = compoundStatement.scope.idents.mapNotNull { it as? TypedIdentifier }
        blockIdents.first {
          it.name == paramDecl.declarator.name.name && it.type == typeName
        }
      }
      return FunctionDefinition(funcIdent, functionType, params, compoundStatement)
    }
  }
}

/** C standard: A.2.3, 6.8.2 */
sealed class BlockItem : ASTNode()

data class DeclarationItem(val declaration: Declaration) : BlockItem() {
  override fun toString() = declaration.toString()
}

data class StatementItem(val statement: Statement) : BlockItem() {
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
  override fun toString(): String {
    val stuff = items.joinToString(",") { "\n\t$it" }
    return "{\n$scope$stuff\n}"
  }
}

/** C standard: 6.8.1 */
data class LabeledStatement(val label: IdentifierNode, val statement: Statement) : Statement()

/** C standard: 6.8.4.1 */
data class IfStatement(val cond: Expression,
                       val success: Statement,
                       val failure: Statement?) : Statement()

/** C standard: 6.8.4.2 */
data class SwitchStatement(val switch: Expression, val body: Statement) : Statement()

/** C standard: 6.8.5.1 */
data class WhileStatement(val cond: Expression, val loopable: Statement) : Statement()

/** C standard: 6.8.5.2 */
data class DoWhileStatement(val cond: Expression, val loopable: Statement) : Statement()

sealed class ForInitializer : ASTNode()

class EmptyInitializer : ForInitializer(), Terminal, StringClassName by StringClassNameImpl

@Suppress("DELEGATED_MEMBER_HIDES_SUPERTYPE_OVERRIDE")
class ErrorInitializer : ForInitializer(), ErrorNode by ErrorNodeImpl

data class DeclarationInitializer(val value: Declaration) : ForInitializer() {
  init {
    withRange(value.range)
  }

  override fun toString() = value.toString()
}

data class ForExpressionInitializer(val value: Expression) : ForInitializer() {
  init {
    withRange(value.range)
  }
}

/** C standard: 6.8.5.3 */
data class ForStatement(val init: ForInitializer,
                        val cond: Expression?,
                        val loopEnd: Expression?,
                        val loopable: Statement,
                        val scope: LexicalScope) : Statement()

/** C standard: 6.8.6.2 */
class ContinueStatement : Statement(), Terminal, StringClassName by StringClassNameImpl

/** C standard: 6.8.6.3 */
class BreakStatement : Statement(), Terminal, StringClassName by StringClassNameImpl

/** C standard: 6.8.6.1 */
data class GotoStatement(val identifier: IdentifierNode) : Statement()

/** C standard: 6.8.6.4 */
data class ReturnStatement(val expr: Expression?) : Statement()
