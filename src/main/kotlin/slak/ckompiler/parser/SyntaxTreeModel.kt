package slak.ckompiler.parser

import org.apache.logging.log4j.LogManager
import slak.ckompiler.*
import slak.ckompiler.lexer.*
import slak.ckompiler.parser.Expression.ValueType.*

private val logger = LogManager.getLogger()

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

  override var sourceText: String? = null
  override var sourceFileName: SourceFileName? = null
  override var expandedName: String? = null
  override var expandedFrom: SourcedRange? = null

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
  fun setRange(src: SourcedRange) {
    if (src.range.first > src.range.last) {
      logger.throwICE("Bad token range on ASTNode") { "this: $this, range: $src" }
    }
    lateTokenRange = src.range
    sourceFileName = src.sourceFileName
    sourceText = src.sourceText
  }

  override fun equals(other: Any?) = other is ASTNode
  override fun hashCode() = javaClass.hashCode()
}

/** Sets a node's token range, and returns the node. */
fun <T : ASTNode> T.withRange(range: SourcedRange): T {
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
  /** @see ValueType */
  abstract val valueType: ValueType

  /**
   * [LVALUE] designates an object.
   * [MODIFIABLE_LVALUE] is an [LVALUE] with a bunch of restrictions on type.
   * [RVALUE] is the value of an expression (see note 64).
   *
   * C standard: 6.3.2.1.0.1, note 64
   */
  enum class ValueType {
    LVALUE, MODIFIABLE_LVALUE, RVALUE
  }
}

@Suppress("DELEGATED_MEMBER_HIDES_SUPERTYPE_OVERRIDE")
class ErrorExpression : ExprConstantNode(), ErrorNode by ErrorNodeImpl {
  override val type = ErrorType
}

/**
 * C standard: 6.3.2.1.0.1
 */
private fun valueTypeOf(type: TypeName): Expression.ValueType {
  val isArrayPtr = type is PointerType && type.decayedFrom is ArrayType
  return if (type is PointerType && type.decayedFrom is FunctionType) {
    // Function designators are lvalues
    LVALUE
  } else if (type is QualifiedType && Keywords.CONST in type.typeQuals) {
    // Const-qualification means it's not modifiable
    LVALUE
  } else if (type.isCompleteObjectType() && !isArrayPtr) {
    MODIFIABLE_LVALUE
  } else {
    // FIXME: is everything else an lvalue?
    LVALUE
  }
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
data class TypedIdentifier(
    override val name: String,
    override val type: TypeName
) : Expression(), OrdinaryIdentifier, Terminal {
  constructor(
      ds: DeclarationSpecifier,
      decl: NamedDeclarator
  ) : this(decl.name.name, typeNameOf(ds, decl).normalize()) {
    withRange(decl.name)
  }

  override val valueType = valueTypeOf(type)

  var id: AtomicId = varCounter()
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

  override fun toString(): String {
    return if (type is FunctionType) {
      type.toString().replace("${type.returnType} ", "${type.returnType} $name")
    } else {
      "$type $name"
    }
  }

  companion object {
    private val varCounter = IdCounter()
  }
}

data class TernaryConditional(
    val cond: Expression,
    val success: Expression,
    val failure: Expression
) : Expression() {
  override val type = resultOfTernary(success, failure)

  /**
   * "A conditional expression does not yield an lvalue."
   *
   * C standard: note 110
   */
  override val valueType: ValueType = RVALUE

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
  override val valueType: ValueType = RVALUE
}

/**
 * This does not represent the entire "unary-expression" from the standard, just the
 * "unary-operator cast-expression" part of it.
 * C standard: A.2.1
 */
data class UnaryExpression(
    val op: UnaryOperators,
    val operand: Expression,
    override val type: TypeName
) : Expression() {
  /**
   * C standard: 6.5.3.2.0.4
   */
  override val valueType = when {
    op != UnaryOperators.DEREF -> RVALUE
    operand.type is QualifiedType && Keywords.CONST in operand.type.typeQuals -> LVALUE
    else -> MODIFIABLE_LVALUE
  }
}

/**
 * Represents an application of the unary `sizeof` operator.
 *
 * Since we do not support VLAs, `sizeof` always returns an integer constant.
 *
 * C standard: A.2.1. 6.5.3.4
 * @param sizeOfWho which type to take the size of
 * @param type the resulting type of the `sizeof` expression, is `size_t` for the current target
 */
data class SizeofTypeName(
    val sizeOfWho: TypeName,
    override val type: UnqualifiedTypeName
) : Expression() {
  override val valueType: ValueType = RVALUE
}

/**
 * Represents ++x, x++, --x and x--.
 */
data class IncDecOperation(
    val expr: Expression,
    val isDecrement: Boolean,
    val isPostfix: Boolean
) : Expression() {
  override val type = expr.type

  /**
   * For prefix ops, we have to apply assignment rules, and 6.5.16.0.3 says the result is not an
   * lvalue.
   *
   * For postfix ops, 6.5.2.4.0.2 says that "The result of the postfix ++ operator is the value of
   * the operand". The value of an expression is an rvalue.
   *
   * C standard: 6.5.16.0.3, 6.5.2.4.0.2
   */
  override val valueType = RVALUE
}

/**
 * Represents `a.b` and `a->b`.
 */
data class MemberAccessExpression(
    val target: Expression,
    val accessOperator: Punctuator,
    val memberName: IdentifierNode,
    override val type: TypeName
) : Expression() {
  /**
   * FIXME: this is semi-broken for a variety of reasons
   *
   * C standard: 6.5.2.3.0.3, 6.5.2.3.0.4
   */
  override val valueType: ValueType
    get() = when (accessOperator.pct) {
      Punctuators.DOT -> if (target.valueType != RVALUE) valueTypeOf(target.type) else RVALUE
      Punctuators.ARROW -> valueTypeOf(target.type)
      else -> logger.throwICE("Illegal punctuator used as member access operator")
    }
}

/** Represents a binary operation in an expression. */
data class BinaryExpression(
    val op: BinaryOperators,
    val lhs: Expression,
    val rhs: Expression,
    override val type: TypeName
) : Expression() {
  override fun toString() = "($lhs $op $rhs)"

  /** C standard: 6.5.16.0.3 */
  override val valueType = RVALUE
}

data class ArraySubscript(
    val subscripted: Expression,
    val subscript: Expression,
    override val type: TypeName
) : Expression() {
  override fun toString() = "$subscripted[$subscript]"

  /**
   * C standard: 6.5.2.1.0.2
   */
  override val valueType =
      if (subscripted.type is QualifiedType && Keywords.CONST in subscripted.type.typeQuals) {
        LVALUE
      } else {
        MODIFIABLE_LVALUE
      }
}

data class CastExpression(val target: Expression, override val type: TypeName) : Expression() {
  override fun toString() = "($type) $target"

  /**
   * "A cast does not yield an lvalue."
   *
   * C standard: note 104
   */
  override val valueType = RVALUE
}

sealed class ExprConstantNode : Expression(), Terminal {
  override val valueType: ValueType = RVALUE
}

class VoidExpression : ExprConstantNode() {
  override val type = VoidType
}

data class IntegerConstantNode(
    val value: Long,
    val suffix: IntegralSuffix = IntegralSuffix.NONE
) : ExprConstantNode() {
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

data class FloatingConstantNode(
    val value: Double,
    val suffix: FloatingSuffix
) : ExprConstantNode() {
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
data class CharacterConstantNode(
    val char: Int,
    val encoding: CharEncoding
) : ExprConstantNode() {
  override val type = UnsignedIntType

  override fun toString() = "${encoding.prefix}'${char.toChar()}'"
}

/**
 * FIXME: UTF-8 handling. Array size is not string.length
 * FIXME: wchar_t & friends should have more specific element type
 */
data class StringLiteralNode(
    val string: String,
    val encoding: StringEncoding
) : ExprConstantNode() {
  override val type = ArrayType(when (encoding) {
    StringEncoding.CHAR, StringEncoding.UTF8 -> UnsignedIntType
    else -> UnsignedLongLongType
  }, ConstantSize(IntegerConstantNode(string.length.toLong())))

  override fun toString() = "${encoding.prefix}\"$string\""
}

/**
 * Stores declaration specifiers that come before declarators.
 *
 * FIXME: alignment specifier (A.2.2/6.7.5)
 */
data class DeclarationSpecifier(
    val storageClass: Keyword? = null,
    val threadLocal: Keyword? = null,
    val typeQualifiers: List<Keyword> = emptyList(),
    val functionSpecs: List<Keyword> = emptyList(),
    val typeSpec: TypeSpecifier? = null
) : ASTNode() {

  /** @return true if no specifiers were found */
  fun isBlank() = !hasStorageClass() && !isThreadLocal() && typeQualifiers.isEmpty() &&
      functionSpecs.isEmpty() && typeSpec == null

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
data class ParameterTypeList(
    val params: List<ParameterDeclaration>,
    val scope: LexicalScope,
    val variadic: Boolean = false
) : DeclaratorSuffix() {
  override fun toString(): String {
    val paramStr = params.joinToString(", ")
    val variadicStr = if (!variadic) "" else ", ..."
    return "($paramStr$variadicStr)"
  }
}

data class ParameterDeclaration(
    val declSpec: DeclarationSpecifier,
    val declarator: Declarator
) : ASTNode() {
  override fun toString() = "$declSpec $declarator"
}

data class IdentifierNode(val name: String) : ASTNode(), Terminal {
  constructor(lexerIdentifier: Identifier) : this(lexerIdentifier.name)

  override fun toString() = name

  companion object {
    /**
     * Creates an [IdentifierNode] from an [Identifier].
     * @param identifier this [LexicalToken] is casted to an [Identifier]
     */
    fun from(identifier: LexicalToken) =
        IdentifierNode(identifier as Identifier).withRange(identifier)
  }
}

/** C standard: 6.7.6 */
typealias TypeQualifierList = List<Keyword>

/**
 * Represents what the standard labels `pointer` in the syntax.
 *
 * C standard: 6.7.6
 */
typealias Indirection = List<TypeQualifierList>

@JvmName("TypeQualifierList#stringify")
fun TypeQualifierList.stringify() = joinToString(" ") { (value) -> value.keyword }

@JvmName("Indirection#stringify")
fun Indirection.stringify() = joinToString { '*' + it.stringify() }

operator fun TypeQualifierList.contains(k: Keywords): Boolean = k in map { it.value }

typealias DeclaratorSuffixTier = List<DeclaratorSuffix>

/**
 * Common superclass for "declarators". This exists in an effort to unify what the standard calls
 * `declarator` and `abstract-declarator`.
 */
sealed class Declarator : ASTNode() {
  abstract val indirection: List<Indirection>
  abstract val suffixes: List<DeclaratorSuffixTier>

  /**
   * A declarator that consists of no tokens. It is not only valid grammar, it can actually occur in
   * parameter declarations. Yes, it is ridiculous.
   *
   * The token range for these declarators is intentionally missing. Callers should know not to use
   * it or add it themselves.
   */
  fun isBlank() = this is AbstractDeclarator && indirection.isEmpty() && suffixes.isEmpty()

  private fun hasSuffix() = suffixes.isNotEmpty() && suffixes[0].isNotEmpty()

  fun isFunction() = hasSuffix() && suffixes[0][0] is ParameterTypeList
  fun isArray() = hasSuffix() && suffixes[0][0] is ArrayTypeSize

  fun getFunctionTypeList(): ParameterTypeList = suffixes[0][0] as ParameterTypeList
  fun getArrayTypeSize(): ArrayTypeSize = suffixes[0][0] as ArrayTypeSize
}

/** C standard: 6.7.6 */
data class NamedDeclarator(
    val name: IdentifierNode,
    override val indirection: List<Indirection>,
    override val suffixes: List<DeclaratorSuffixTier>
) : Declarator() {
  override fun toString(): String {
    val indStr = indirection.joinToString("", prefix = "(") { it.stringify() }
    val suffixesStr = suffixes.joinToString("", postfix = ")") { it.joinToString("") }
    val canTrim = indirection.size == 1 &&
        indirection[0].isEmpty() &&
        suffixes.size == 1 &&
        suffixes[0].isEmpty()
    return if (canTrim) name.name else "$indStr$name$suffixesStr"
  }

  companion object {
    fun base(name: IdentifierNode, indirection: Indirection, suffixes: List<DeclaratorSuffix>) =
        NamedDeclarator(name, listOf(indirection), listOf(suffixes))
  }
}

/** C standard: 6.7.7.0.1 */
data class AbstractDeclarator(
    override val indirection: List<Indirection>,
    override val suffixes: List<DeclaratorSuffixTier>
) : Declarator() {
  override fun toString(): String {
    // Basically abuse NamedDeclarator.toString
    return NamedDeclarator(IdentifierNode(" "), indirection, suffixes).toString()
  }

  companion object {
    fun base(indirection: Indirection, suffixes: DeclaratorSuffixTier) =
        AbstractDeclarator(listOf(indirection), listOf(suffixes))

    /** @see Declarator.isBlank */
    fun blank() = AbstractDeclarator(emptyList(), emptyList())
  }
}

@Suppress("DELEGATED_MEMBER_HIDES_SUPERTYPE_OVERRIDE")
class ErrorDeclarator : Declarator(), ErrorNode by ErrorNodeImpl {
  override val suffixes = emptyList<DeclaratorSuffixTier>()
  override val indirection = emptyList<Indirection>()
}

// FIXME: initializer (6.7.9/A.2.2) can be either expression or initializer-list
sealed class Initializer : ASTNode() {
  abstract val assignTok: Punctuator
}

data class ExpressionInitializer(
    val expr: Expression,
    override val assignTok: Punctuator
) : Initializer() {
  init {
    withRange(expr)
  }

  override fun toString() = expr.toString()
}

data class StructMember(val declarator: Declarator, val constExpr: Expression?) : ASTNode()

data class StructDeclaration(
    val declSpecs: DeclarationSpecifier,
    val declaratorList: List<StructMember>
) : ASTNode()

/**
 * C standard: 6.7.2.2, 6.7.2.3
 */
data class Enumerator(
    val ident: IdentifierNode,
    val value: ExprConstantNode?
) : ASTNode(), OrdinaryIdentifier {
  override val name = ident.name
  override val kindName = "enumeration constant"
  // FIXME: hardcoded enum type to int
  override val type = SignedIntType

  init {
    withRange(ident..(value ?: ident))
  }
}

/**
 * Contains the size of an array type.
 *
 * C standard: 6.7.6.2
 */
sealed class ArrayTypeSize : DeclaratorSuffix()

sealed class VariableArraySize : ArrayTypeSize()

sealed class ConstantArraySize : ArrayTypeSize() {
  abstract val size: ExprConstantNode
}

/**
 * Describes an array type that specifies no size in the square brackets.
 *
 * This can mean multiple things:
 * 1. If on a [ExternalDeclaration]'s declarator, it's a tentative array definition
 * 2. If on a function parameter, it's basically a pointer
 * 3. If on a local declarator, it's an error
 */
object NoSize : ArrayTypeSize() {
  override fun toString() = "[]"
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
data class UnconfinedVariableSize(
    val typeQuals: TypeQualifierList,
    val vlaStar: Punctuator
) : VariableArraySize() {
  override fun toString() = "[${typeQuals.stringify()} *]"
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
data class FunctionParameterSize(
    val typeQuals: TypeQualifierList,
    val isStatic: Boolean,
    val expr: Expression?
) : VariableArraySize() {
  init {
    if (isStatic && expr == null) logger.throwICE("Array size, static without expr") { this }
    if (expr == null && typeQuals.isEmpty()) {
      logger.throwICE("Array size, no type quals and no expr") { this }
    }
  }

  override fun toString(): String {
    val exprStr = if (expr == null) "" else " $expr"
    return "[${if (isStatic) "static " else ""}${typeQuals.stringify()}$exprStr]"
  }
}

/**
 * Describes an array type whose size is exactly [size], where the type is a function
 * parameter's type.
 *
 * C standard: 6.7.6.2.0.3, 6.7.6.3.0.7, 6.7.6.2.0.4
 * @param typeQuals the `type-qualifier`s between the square brackets
 * @param isStatic if the keyword "static" appears between the square brackets
 */
data class FunctionParameterConstantSize(
    val typeQuals: TypeQualifierList,
    val isStatic: Boolean,
    override val size: ExprConstantNode
) : ConstantArraySize() {
  override fun toString(): String {
    return "[${if (isStatic) "static " else ""}${typeQuals.stringify()}$size]"
  }
}

/**
 * Describes an array type whose size is specified by [expr]. A non-constant [expr] describes a VLA,
 * which this implementation does **not support**.
 */
data class ExpressionSize(val expr: Expression) : VariableArraySize() {
  override fun toString() = "$expr"
}

/**
 * Describes an array type whose size is exactly [size].
 * @param size result of integer constant expression
 */
data class ConstantSize(override val size: ExprConstantNode) : ConstantArraySize() {
  override fun toString() = "[$size]"
}

/** C standard: A.2.4 */
sealed class ExternalDeclaration : ASTNode()

/**
 * Represents a declaration that actually declares variables.
 *
 * C standard: A.2.2
 */
data class Declaration(
    val declSpecs: DeclarationSpecifier,
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
    val declStrs = declaratorList.joinToString("", prefix = "\t", postfix = ";\n") { (decl, init) ->
      val initStr = if (init == null) "" else " = $init"
      "$declSpecs $decl$initStr"
    }
    return "Declaration {\n$declStrs}"
  }
}

/** C standard: A.2.4 */
data class FunctionDefinition(
    val funcIdent: TypedIdentifier,
    val functionType: FunctionType,
    val parameters: List<TypedIdentifier>,
    val compoundStatement: Statement
) : ExternalDeclaration() {
  val name = funcIdent.name
  val block get() = compoundStatement as CompoundStatement

  override fun toString(): String {
    return "FunctionDefinition($funcIdent, $compoundStatement)"
  }

  companion object {
    operator fun invoke(
        declSpec: DeclarationSpecifier,
        functionDeclarator: Declarator,
        compoundStatement: Statement
    ): FunctionDefinition {
      require(functionDeclarator is NamedDeclarator) { "Declarator missing name" }
      val functionType = typeNameOf(declSpec, functionDeclarator)
      require(functionType is FunctionType) { "Declarator not function type" }
      val funcIdent = TypedIdentifier(functionDeclarator.name.name, functionType)
      val paramDecls = functionDeclarator.getFunctionTypeList().params
      val params = paramDecls.zip(functionType.params).mapNotNull { (paramDecl, typeName) ->
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

/** C standard: 6.8.4.1 */
data class IfStatement(
    val cond: Expression,
    val success: Statement,
    val failure: Statement?
) : Statement()

/** C standard: 6.8.4.2 */
data class SwitchStatement(val controllingExpr: Expression, val statement: Statement) : Statement()

/** C standard: 6.8.1 */
sealed class StatementWithLabel : Statement() {
  /** Labeled statement. */
  abstract val statement: Statement
}

/** C standard: 6.8.1 */
data class LabeledStatement(
    val label: IdentifierNode,
    override val statement: Statement
) : StatementWithLabel()

/** C standard: 6.8.1 */
data class CaseStatement(
    val caseExpr: ExprConstantNode,
    override val statement: Statement
) : StatementWithLabel()

/** C standard: 6.8.1 */
data class DefaultStatement(override val statement: Statement) : StatementWithLabel()

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
    withRange(value)
  }

  override fun toString() = value.toString()
}

data class ForExpressionInitializer(val value: Expression) : ForInitializer() {
  init {
    withRange(value)
  }
}

/** C standard: 6.8.5.3 */
data class ForStatement(
    val init: ForInitializer,
    val cond: Expression?,
    val loopEnd: Expression?,
    val loopable: Statement,
    val scope: LexicalScope
) : Statement()

/** C standard: 6.8.6.2 */
class ContinueStatement : Statement(), Terminal, StringClassName by StringClassNameImpl

/** C standard: 6.8.6.3 */
class BreakStatement : Statement(), Terminal, StringClassName by StringClassNameImpl

/** C standard: 6.8.6.1 */
data class GotoStatement(val identifier: IdentifierNode) : Statement()

/** C standard: 6.8.6.4 */
data class ReturnStatement(val expr: Expression?) : Statement()
