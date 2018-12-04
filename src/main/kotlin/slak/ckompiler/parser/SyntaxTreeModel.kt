package slak.ckompiler.parser

import slak.ckompiler.lexer.*

/** Base interface of all nodes from an Abstract Syntax Tree. */
interface ASTNode

/**
 * Signals an error condition in the parser. If some part of the grammar cannot be parsed, an
 * instance of this interface is returned.
 *
 * All instances of [ErrorNode] should be equal.
 */
interface ErrorNode : ASTNode {
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
  override fun hashCode() = javaClass.packageName.hashCode()
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
class RootNode : ASTNode {
  private val declarations = mutableListOf<ExternalDeclaration>()
  val decls: List<ExternalDeclaration> = declarations

  fun addExternalDeclaration(n: ExternalDeclaration) {
    declarations.add(n)
  }
}

/** C standard: 6.7.6 */
sealed class Declarator

class ErrorDeclarator : Declarator(), ErrorNode by ErrorNodeImpl

/** C standard: A.2.3, 6.8 */
interface Statement : ASTNode

class ErrorStatement : Statement, ErrorNode by ErrorNodeImpl

/** The standard says no-ops are expressions, but here it is represented separately. */
object Noop : Statement {
  override fun toString() = "<no-op>"
}

/**
 * Represents an expression.
 * C standard: A.2.3, 6.8.3
 */
interface Expression : Statement

class ErrorExpression : Expression, ErrorNode by ErrorNodeImpl

/**
 * This interface does not match with what the standard calls "primary-expression".
 * C standard: A.2.1
 */
interface PrimaryExpression : ASTNode, Expression

/** C standard: A.2.1 */
data class SizeofExpression(val sizeExpr: Expression) : PrimaryExpression

data class PrefixIncrement(val expr: Expression) : PrimaryExpression
data class PrefixDecrement(val expr: Expression) : PrimaryExpression
data class PostfixIncrement(val expr: Expression) : PrimaryExpression
data class PostfixDecrement(val expr: Expression) : PrimaryExpression

data class FunctionCall(val calledExpr: Expression, val args: List<Expression>) : PrimaryExpression

/**
 * This does not represent the entire "unary-expression" from the standard, just the
 * "unary-operator cast-expression" part of it.
 * C standard: A.2.1
 */
data class UnaryExpression(val op: Operators, val operand: Expression) : PrimaryExpression

/** Represents a binary operation in an expression. */
data class BinaryExpression(val op: Operators,
                            val lhs: Expression,
                            val rhs: Expression) : PrimaryExpression {
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

/**
 * Stores declaration specifiers that come before declarators.
 * FIXME: more complex specifiers are missing (6.7.2)
 * FIXME: alignment specifier (A.2.2/6.7.5)
 */
data class DeclarationSpecifier(val storageClassSpecs: List<Keyword>,
                                val typeSpecifiers: List<Keyword>,
                                val typeQualifiers: List<Keyword>,
                                val functionSpecs: List<Keyword>) {
  fun isEmpty() = storageClassSpecs.isEmpty() && typeSpecifiers.isEmpty() &&
      typeQualifiers.isEmpty() && functionSpecs.isEmpty()

  override fun toString(): String {
    val text = listOf(storageClassSpecs, typeSpecifiers,
        typeQualifiers, functionSpecs).joinToString(" ") { it.joinToString(" ") }
    return "($text)"
  }
}

// FIXME: initializer (6.7.9/A.2.2) can be either expression or initializer-list
data class InitDeclarator(val declarator: Declarator,
                          val initializer: Expression) : Declarator()

data class ParameterDeclaration(val declSpec: DeclarationSpecifier,
                                val declarator: Declarator) : ASTNode

// FIXME: params can also be abstract-declarators (6.7.6/A.2.4)
data class FunctionDeclarator(val declarator: Declarator,
                              val params: List<ParameterDeclaration>,
                              val isVararg: Boolean = false) : Declarator()

/** C standard: A.2.4 */
sealed class ExternalDeclaration : ASTNode

/** C standard: A.2.2 */
sealed class Declaration : ExternalDeclaration()

data class RealDeclaration(val declSpecs: DeclarationSpecifier,
                           val declaratorList: List<Declarator>) : Declaration()

class ErrorDeclaration : Declaration(), ErrorNode by ErrorNodeImpl

/** C standard: A.2.4 */
data class FunctionDefinition(val declSpec: DeclarationSpecifier,
                              val functionDeclarator: Declarator,
                              val compoundStatement: Statement) : ExternalDeclaration()

/** C standard: A.2.3, 6.8.2 */
sealed class BlockItem : ASTNode
data class DeclarationItem(val declaration: Declaration) : BlockItem()
data class StatementItem(val statement: Statement) : BlockItem()

/** C standard: A.2.3, 6.8.2 */
data class CompoundStatement(val items: List<BlockItem>) : Statement

/** C standard: 6.8.1 */
data class LabeledStatement(val label: IdentifierNode, val statement: Statement) : Statement

/** C standard: 6.8.4 */
interface SelectionStatement : Statement

/** C standard: 6.8.4.1 */
data class IfStatement(val cond: Expression,
                       val success: Statement,
                       val failure: Statement?) : SelectionStatement

/** C standard: 6.8.4.2 */
data class SwitchStatement(val switch: Expression, val body: Statement) : SelectionStatement

/** C standard: 6.8.5 */
interface IterationStatement : Statement

/** C standard: 6.8.5.1 */
data class WhileStatement(val cond: Expression, val loopable: Statement) : IterationStatement

/** C standard: 6.8.5.2 */
data class DoWhileStatement(val cond: Expression, val loopable: Statement) : IterationStatement

sealed class ForInitializer : ASTNode
object EmptyInitializer : ForInitializer(), StringClassName by StringClassNameImpl
data class DeclarationInitializer(val value: Declaration) : ForInitializer()
data class ExpressionInitializer(val value: Expression) : ForInitializer()
class ErrorInitializer : ForInitializer(), ErrorNode by ErrorNodeImpl

/** C standard: 6.8.5.3 */
data class ForStatement(val init: ForInitializer,
                        val cond: Expression?,
                        val loopEnd: Expression?,
                        val loopable: Statement) : IterationStatement

/** C standard: 6.8.6 */
sealed class JumpStatement : Statement

/** C standard: 6.8.6.2 */
object ContinueStatement : JumpStatement(), StringClassName by StringClassNameImpl

/** C standard: 6.8.6.3 */
object BreakStatement : JumpStatement(), StringClassName by StringClassNameImpl

/** C standard: 6.8.6.1 */
data class GotoStatement(val identifier: IdentifierNode) : JumpStatement()

/** C standard: 6.8.6.4 */
data class ReturnStatement(val expr: Expression?) : JumpStatement()
