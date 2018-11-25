package slak.ckompiler.parser

import mu.KotlinLogging
import slak.ckompiler.*

private val logger = KotlinLogging.logger("AST")

/** Base interface of all nodes from an Abstract Syntax Tree. */
interface ASTNode

/** Can either be [ErrorNode] or an [ASTNode]. */
sealed class EitherNode<out N : ASTNode> {
  data class Value<out N : ASTNode>(val value: N) : EitherNode<N>() {
    override fun toString() = value.toString()
  }

  /**
   * Coerces an [EitherNode] to the concrete value of type [N].
   * @throws InternalCompilerError if called on an [ErrorNode]
   * @return [EitherNode.Value.value]
   */
  fun asVal(): N {
    if (this is ErrorNode) {
      logger.throwICE("An error node was coerced to a real node") { this }
    }
    return (this as Value).value
  }

  fun orNull(): N? = if (this is ErrorNode) null else (this as Value).value

  override fun toString(): String {
    return if (this is Value) value.toString() else (this as ErrorNode).toString()
  }
}

/**
 * Signals an error condition in the parser. If some part of the grammar cannot be parsed, this is
 * returned.
 *
 * All instances of [ErrorNode] are equal.
 */
class ErrorNode : ASTNode, EitherNode<Nothing>() {
  override fun equals(other: Any?) = other is ErrorNode
  override fun hashCode() = javaClass.hashCode()
  override fun toString() = "<ERROR>"
}

/** Transform a concrete [ASTNode] instance into an [EitherNode.Value] instance. */
fun <T : ASTNode> T.wrap(): EitherNode<T> = EitherNode.Value(this)

/** The root node of a translation unit. Stores top-level [ExternalDeclaration]s. */
class RootNode : ASTNode {
  private val declarations = mutableListOf<EitherNode<ExternalDeclaration>>()
  val decls: List<EitherNode<ExternalDeclaration>> = declarations

  fun addExternalDeclaration(n: EitherNode<ExternalDeclaration>) {
    declarations.add(n)
  }
}

/** C standard: 6.7.6 */
sealed class Declarator : ASTNode {
  fun name(): IdentifierNode = when (this) {
    is IdentifierNode -> this
    is FunctionDeclarator -> declarator.asVal().name()
    is InitDeclarator -> declarator.asVal().name()
  }
}

/** C standard: A.2.3, 6.8 */
interface Statement : BlockItem

/** The standard says no-ops are expressions, but here it is represented separately. */
object Noop : Statement {
  override fun toString() = "<no-op>"
}

/**
 * Represents an expression.
 * C standard: A.2.3, 6.8.3
 */
interface Expression : Statement, ForInitializer

/**
 * This interface does not match with what the standard calls "primary-expression".
 * C standard: A.2.1
 */
interface PrimaryExpression : ASTNode, Expression

/** C standard: A.2.1 */
data class SizeofExpression(val sizeExpr: EitherNode<Expression>) : PrimaryExpression

data class PrefixIncrement(val expr: EitherNode<Expression>) : PrimaryExpression
data class PrefixDecrement(val expr: EitherNode<Expression>) : PrimaryExpression
data class PostfixIncrement(val expr: EitherNode<Expression>) : PrimaryExpression
data class PostfixDecrement(val expr: EitherNode<Expression>) : PrimaryExpression

data class FunctionCall(val calledExpr: EitherNode<Expression>,
                        val args: List<EitherNode<Expression>>) : PrimaryExpression

/**
 * This does not represent the entire "unary-expression" from the standard, just the
 * "unary-operator cast-expression" part of it.
 * C standard: A.2.1
 */
data class UnaryExpression(val op: Operators,
                           val operand: EitherNode<Expression>) : PrimaryExpression

/** Represents a binary operation in an expression. */
data class BinaryExpression(val op: Operators,
                            val lhs: EitherNode<Expression>,
                            val rhs: EitherNode<Expression>) : PrimaryExpression {
  override fun toString() = "($lhs $op $rhs)"
}

/** Represents a leaf node in an expression. */
interface Terminal : PrimaryExpression

data class IdentifierNode(val name: String) : Terminal, Declarator()

data class IntegerConstantNode(val value: Long, val suffix: IntegralSuffix) : Terminal {
  override fun toString() = "int $value ${suffix.name.toLowerCase()}"
}

data class FloatingConstantNode(val value: Double, val suffix: FloatingSuffix) : Terminal

/**
 * Stores a character constant.
 *
 * According to the C standard, the value of multi-byte character constants is
 * implementation-defined. This implementation truncates the constants to the first byte.
 *
 * Also, empty char constants are not defined; here they are equal to 0, and produce a warning.
 *
 * C standard: 6.4.4.4 paragraph 10
 */
data class CharacterConstantNode(val char: Int, val encoding: CharEncoding) : Terminal

data class StringLiteralNode(val string: String, val encoding: StringEncoding) : Terminal

/**
 * Lists the possible permutations of the type specifiers.
 *
 * **NOTES**:
 * 1. `char`, `signed char` and `unsigned char` are distinct in the standard (6.7.2 paragraph 2).
 * In here, `char == signed char`.
 * 2. Same thing applies to `short`, `int`, etc.
 * 3. We do not currently support complex types, and they produce errors in the parser.
 * 4. FIXME: certain specifiers are not implemented
 */
enum class TypeSpecifier {
  VOID, BOOL,
  SIGNED_CHAR,
  UNSIGNED_CHAR,
  SIGNED_SHORT, UNSIGNED_SHORT,
  SIGNED_INT, UNSIGNED_INT,
  SIGNED_LONG, UNSIGNED_LONG,
  SIGNED_LONG_LONG, UNSIGNED_LONG_LONG,
  FLOAT, DOUBLE, LONG_DOUBLE,
  // COMPLEX_FLOAT, COMPLEX_DOUBLE, COMPLEX_LONG_DOUBLE,
  ATOMIC_TYPE_SPEC,
  STRUCT_OR_UNION_SPEC, ENUM_SPEC, TYPEDEF_NAME
}

sealed class DeclarationSpecifier
object MissingDeclarationSpecifier : DeclarationSpecifier()
object ErrorDeclarationSpecifier : DeclarationSpecifier()

/**
 * Actual instance of [DeclarationSpecifier]. Stores declaration specifiers that come before
 * declarators.
 * FIXME: carry debug data in this for each thing
 * FIXME: alignment specifier (A.2.2/6.7.5)
 */
data class RealDeclarationSpecifier(val storageSpecifier: Keywords? = null,
                                    val typeSpecifier: TypeSpecifier,
                                    val hasThreadLocal: Boolean = false,
                                    val hasConst: Boolean = false,
                                    val hasRestrict: Boolean = false,
                                    val hasVolatile: Boolean = false,
                                    val hasAtomic: Boolean = false,
                                    val hasInline: Boolean = false,
                                    val hasNoReturn: Boolean = false) : DeclarationSpecifier() {
  override fun toString(): String {
    val list = listOf(
        if (hasInline) "inline" else "",
        if (hasNoReturn) "_Noreturn" else "",
        if (hasVolatile) "volatile" else "",
        if (hasAtomic) "_Atomic" else "",
        if (hasRestrict) "restrict" else "",
        if (hasThreadLocal) "_Thread_local" else "",
        if (hasConst) "const" else "",
        storageSpecifier?.keyword ?: "",
        typeSpecifier.toString()
    )
    return "(${list.filter { it.isNotEmpty() }.joinToString(" ")})"
  }
}

// FIXME: initializer (6.7.9/A.2.2) can be either expression or initializer-list
data class InitDeclarator(val declarator: EitherNode<Declarator>,
                          val initializer: EitherNode<Expression>? = null) : Declarator()

data class ParameterDeclaration(val declSpec: DeclarationSpecifier,
                                val declarator: EitherNode<Declarator>) : ASTNode

// FIXME: params can also be abstract-declarators (6.7.6/A.2.4)
data class FunctionDeclarator(val declarator: EitherNode<Declarator>,
                              val params: List<ParameterDeclaration>,
                              val isVararg: Boolean = false) : Declarator()

/** C standard: A.2.3, 6.8.2 */
interface BlockItem : ASTNode

/** C standard: A.2.4 */
sealed class ExternalDeclaration : ASTNode

/** C standard: A.2.2 */
data class Declaration(val declSpecs: DeclarationSpecifier,
                       val declaratorList: List<InitDeclarator>
) : ExternalDeclaration(), BlockItem, ForInitializer

/** C standard: A.2.4 */
data class FunctionDefinition(val declSpec: DeclarationSpecifier,
                              val declarator: EitherNode<FunctionDeclarator>,
                              val block: EitherNode<CompoundStatement>) : ExternalDeclaration()

/** C standard: A.2.3, 6.8.2 */
data class CompoundStatement(val items: List<EitherNode<BlockItem>>) : Statement

/** C standard: 6.8.1 */
data class LabeledStatement(val label: IdentifierNode,
                            val statement: EitherNode<Statement>) : Statement

/** C standard: 6.8.4 */
interface SelectionStatement : Statement

/** C standard: 6.8.4.1 */
data class IfStatement(val cond: EitherNode<Expression>,
                       val success: EitherNode<Statement>,
                       val failure: EitherNode<Statement>?) : SelectionStatement

/** C standard: 6.8.4.2 */
data class SwitchStatement(val switch: Expression, val body: Statement) : SelectionStatement

/** C standard: 6.8.5 */
interface IterationStatement : Statement

/** C standard: 6.8.5.1 */
data class WhileStatement(val cond: EitherNode<Expression>,
                          val loopable: EitherNode<Statement>) : IterationStatement

/** C standard: 6.8.5.2 */
data class DoWhileStatement(val cond: EitherNode<Expression>,
                            val loopable: EitherNode<Statement>) : IterationStatement

interface ForInitializer : ASTNode

/** C standard: 6.8.5.3 */
data class ForStatement(val init: EitherNode<ForInitializer>?,
                        val cond: EitherNode<Expression>?,
                        val loopEnd: EitherNode<Expression>?,
                        val loopable: EitherNode<Statement>) : IterationStatement

/** C standard: 6.8.6 */
sealed class JumpStatement : Statement

/** C standard: 6.8.6.2 */
object ContinueStatement : JumpStatement() {
  override fun toString() = javaClass.simpleName!!
}

/** C standard: 6.8.6.3 */
object BreakStatement : JumpStatement() {
  override fun toString() = javaClass.simpleName!!
}

/** C standard: 6.8.6.1 */
data class GotoStatement(val identifier: IdentifierNode) : JumpStatement()

/** C standard: 6.8.6.4 */
data class ReturnStatement(val expr: EitherNode<Expression>?) : JumpStatement()
