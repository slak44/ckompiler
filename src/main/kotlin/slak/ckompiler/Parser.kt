package slak.ckompiler

import mu.KotlinLogging
import java.util.*

private val logger = KotlinLogging.logger("Parser")

/** Base interface of all nodes from an Abstract Syntax Tree. */
interface ASTNode

/** Can either be [ErrorNode] or an [ASTNode]. */
@Suppress("unused")
sealed class EitherNode<out N : ASTNode> {
  data class Value<out N : ASTNode>(val value: N) : EitherNode<N>()
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
fun <T : ASTNode> T.asEither(): EitherNode<T> = EitherNode.Value(this)

/** The root node of a translation unit. Stores top-level [ExternalDeclaration]s. */
class RootNode : ASTNode {
  private val decls = mutableListOf<EitherNode<ExternalDeclaration>>()

  fun getDeclarations(): List<EitherNode<ExternalDeclaration>> = decls

  fun addExternalDeclaration(n: EitherNode<ExternalDeclaration>) {
    decls.add(n)
  }
}

/** C standard: 6.7.6 */
interface Declarator : ASTNode

/** C standard: A.2.3, 6.8 */
interface Statement : BlockItem

/** C standard: A.2.1 */
interface PrimaryExpression : ASTNode

interface Terminal : PrimaryExpression

data class IdentifierNode(val name: String) : Terminal, Declarator

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

data class BinaryNode(val op: Operators, val lhs: ASTNode, val rhs: ASTNode) : PrimaryExpression {
  override fun toString() = "($lhs $op $rhs)"
}

/**
 * Represents an expression. A null expression represents a no-op, ie an expression that's just made
 * up of a semicolon
 * C standard: A.2.3, 6.8.3
 */
data class Expression(val root: EitherNode<PrimaryExpression>?) : Statement

private val storageClassSpecifier =
    listOf(Keywords.EXTERN, Keywords.STATIC, Keywords.AUTO, Keywords.REGISTER)
private val typeSpecifier = listOf(Keywords.VOID, Keywords.CHAR, Keywords.SHORT, Keywords.INT,
    Keywords.LONG, Keywords.FLOAT, Keywords.DOUBLE, Keywords.SIGNED, Keywords.UNSIGNED,
    Keywords.BOOL, Keywords.COMPLEX)

enum class TypeSpecifier {
  VOID, BOOL,
  // "char", "signed char" and "unsigned char" are distinct in the standard (6.7.2 paragraph 2)
  // In here "char" == "signed char"
  // Same for short, int, etc.
  SIGNED_CHAR,
  UNSIGNED_CHAR,
  SIGNED_SHORT, UNSIGNED_SHORT,
  SIGNED_INT, UNSIGNED_INT,
  SIGNED_LONG, UNSIGNED_LONG,
  SIGNED_LONG_LONG, UNSIGNED_LONG_LONG,
  FLOAT, DOUBLE, LONG_DOUBLE,
  // We do not currently support complex types, and they produce errors in the parser
//    COMPLEX_FLOAT, COMPLEX_DOUBLE, COMPLEX_LONG_DOUBLE,
  ATOMIC_TYPE_SPEC,
  STRUCT_OR_UNION_SPEC, ENUM_SPEC, TYPEDEF_NAME
}

sealed class DeclarationSpecifier
object MissingDeclarationSpecifier : DeclarationSpecifier()
object ErrorDeclarationSpecifier : DeclarationSpecifier()

// FIXME carry debug data in this for each thing
// FIXME alignment specifier (A.2.2/6.7.5)
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
                          val initializer: ASTNode? = null) : Declarator

data class ParameterDeclaration(val declSpec: DeclarationSpecifier,
                                val declarator: EitherNode<Declarator>) : ASTNode

// FIXME: params can also be abstract-declarators (6.7.6/A.2.4)
data class FunctionDeclarator(val declarator: EitherNode<Declarator>,
                              val params: List<ParameterDeclaration>,
                              val isVararg: Boolean = false) : Declarator

/** C standard: A.2.3, 6.8.2 */
interface BlockItem : ASTNode

/** C standard: A.2.4 */
sealed class ExternalDeclaration : ASTNode

/** C standard: A.2.2 */
data class Declaration(val declSpecs: DeclarationSpecifier,
                       val declaratorList: List<InitDeclarator>) : ExternalDeclaration(), BlockItem

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
data class IfStatement(val cond: Expression,
                       val success: Statement,
                       val failure: Statement?) : SelectionStatement

/** C standard: 6.8.4.2 */
data class SwitchStatement(val switch: Expression, val body: Statement) : SelectionStatement

enum class IterationKind {
  WHILE, DO_WHILE, FOR_DECLARE, FOR_CLASSIC
}

/** C standard: 6.8.5 */
data class IterationStatement(val kind: IterationKind,
                              val cond: Expression,
                              val loopable: Statement,
                              val loopEnd: Expression?,
                              val init: Expression?,
                              val initDecl: Declaration?) : Statement

/** C standard: 6.8.6 */
sealed class JumpStatement : Statement

object ContinueStatement : JumpStatement()
object BreakStatement : JumpStatement()
data class GotoStatement(val identifier: IdentifierNode) : JumpStatement()
data class ReturnStatement(val expr: Expression) : JumpStatement()

/**
 * Parses a translation unit.
 *
 * C standard: A.2.4, 6.9
 *
 * @param tokens list of tokens to parse
 * @param srcFileName the name of the file in which the tokens were extracted from
 * @param tokStartIdxes a list of indices in the original source string, for the start of each token
 */
class Parser(tokens: List<Token>,
             private val srcFileName: SourceFileName,
             private val srcText: String,
             private val tokStartIdxes: List<Int>) {
  private val tokStack = Stack<List<Token>>()
  private val idxStack = Stack<Int>()
  val diags = mutableListOf<Diagnostic>()
  val root = RootNode()

  init {
    tokStack.push(tokens)
    idxStack.push(0)
    translationUnit()
    diags.forEach { it.print() }
  }

  /**
   * Get the [IntRange] spanned by the [Token] in the original code string.
   * FIXME write tests for the ranges, and make sure the offsets in the diags are correct
   * @param offset the offset in the topmost token list, eg -1 to take the token before the one
   * given by [current], or 1 for the one in [lookahead].
   */
  private fun range(offset: Int): IntRange {
    val startIdx = tokStartIdxes[idxStack.peek() + offset]
    return startIdx until startIdx + tokStack.peek()[idxStack.peek() + offset].consumedChars
  }

  /**
   * When all the tokens have been eaten, get the column in the original code string, plus one.
   */
  private fun colPastTheEnd(): Int {
    return tokStartIdxes[idxStack.peek() - 1] + 1
  }

  /**
   * Creates a "sub-parser" context for a given list of tokens. However many elements are eaten in
   * the sub context will be eaten in the parent context too. Useful for parsing parenthesis and the
   * like.
   *
   * The list of tokens starts at the current index (inclusive), and ends at the
   * given [endIdx] (exclusive).
   */
  private fun <T> tokenContext(endIdx: Int, block: (List<Token>) -> T): T {
    val tokens = takeUntil(endIdx)
    tokStack.push(tokens)
    idxStack.push(0)
    val result = block(tokens)
    tokStack.pop()
    val eatenInContext = idxStack.pop()
    eatList(eatenInContext)
    return result
  }

  /** @returns the first (real) index matching the condition, or -1 if there is none */
  private fun indexOfFirst(block: (Token) -> Boolean): Int {
    val idx = tokStack.peek().drop(idxStack.peek()).indexOfFirst(block)
    return if (idx == -1) -1 else idx + idxStack.peek()
  }

  /**
   * Get the tokens until the given index.
   * Eats nothing.
   * @param endIdx the (real) idx of the sublist end (exclusive)
   */
  private fun takeUntil(endIdx: Int): List<Token> = tokStack.peek().subList(idxStack.peek(), endIdx)

  private fun isEaten(): Boolean = idxStack.peek() >= tokStack.peek().size

  private fun current(): Token = tokStack.peek()[idxStack.peek()]

  private fun lookahead(): Token = tokStack.peek()[idxStack.peek() + 1]

  private fun eat() = eatList(1)

  private fun eatList(length: Int) {
    idxStack.push(idxStack.pop() + length)
  }

  /**
   * Eats tokens unconditionally until a semicolon or the end of the token list.
   * Does not eat the semicolon.
   */
  private fun eatToSemi() {
    while (!isEaten() && current().asPunct() != Punctuators.SEMICOLON) eat()
  }

  private fun parserDiagnostic(build: DiagnosticBuilder.() -> Unit) {
    diags.add(createDiagnostic {
      sourceFileName = srcFileName
      sourceText = srcText
      origin = "Parser"
      this.build()
    })
  }

  private fun parseExpr(endIdx: Int): ASTNode = tokenContext(endIdx) {
    val primary: ASTNode = parsePrimaryExpr().ifNull {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_PRIMARY
        columns(range(1))
      }
      return@tokenContext ErrorNode()
    }
    return@tokenContext parseExprImpl(primary, 0)
  }

  private fun parseExprImpl(lhsInit: ASTNode, minPrecedence: Int): ASTNode {
    var lhs = lhsInit
    while (true) {
      if (isEaten()) break
      val op = current().asOperator() ?: break
      if (op !in Operators.binaryExprOps) break
      if (op.precedence < minPrecedence) break
      eat()
      var rhs = parsePrimaryExpr().ifNull {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_PRIMARY
          columns(range(0))
        }
        return ErrorNode()
      }
      while (true) {
        if (isEaten()) break
        val innerOp = current().asOperator() ?: break
        if (innerOp !in Operators.binaryExprOps) break
        if (innerOp.precedence <= op.precedence &&
            !(innerOp.assoc == Associativity.RIGHT_TO_LEFT && innerOp.precedence == op.precedence)) {
          break
        }
        rhs = parseExprImpl(rhs, innerOp.precedence)
      }
      lhs = BinaryNode(op, lhs, rhs)
    }
    return lhs
  }

  /**
   * C standard: A.2.1, 6.4.4
   * @see parseTerminal
   * @return null if no primary was found
   * FIXME: proper return type for this function
   */
  private fun parsePrimaryExpr(): ASTNode? = when {
    current().asPunct() == Punctuators.LPAREN -> {
      if (lookahead().asPunct() == Punctuators.RPAREN) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_EXPR
          columns(range(1))
        }
        ErrorNode()
      }
      val endParenIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
      eat() // Get rid of the LPAREN
      val expr = parseExpr(endParenIdx)
      eat() // Get rid of the RPAREN
      expr
    }
    // FIXME implement generic-selection A.2.1/6.5.1.1
    else -> {
      parseTerminal()?.let {
        eat()
        it
      }
    }
  }

  /**
   * All terminals are one token long. Does not eat anything.
   * C standard: A.2.1, 6.5.1, 6.4.4.4
   * @see CharacterConstantNode
   * @returns the [Terminal] node, or null if no terminal was found
   */
  private fun parseTerminal(): Terminal? {
    val tok = current()
    when (tok) {
      is Identifier -> return IdentifierNode(tok.name)
      is IntegralConstant -> {
        // FIXME conversions might fail here?
        return IntegerConstantNode(tok.n.toLong(tok.radix.toInt()), tok.suffix)
      }
      is FloatingConstant -> {
        // FIXME conversions might fail here?
        return FloatingConstantNode(tok.f.toDouble(), tok.suffix)
      }
      // FIXME handle enum constants
      is CharLiteral -> {
        val char = if (tok.data.isNotEmpty()) {
          tok.data[0].toInt()
        } else {
          parserDiagnostic {
            id = DiagnosticId.EMPTY_CHAR_CONSTANT
            columns(range(0))
          }
          0
        }
        return CharacterConstantNode(char, tok.encoding)
      }
      is StringLiteral -> return StringLiteralNode(tok.data, tok.encoding)
      else -> return null
    }
  }

  private fun diagDuplicate(k: Keywords) = parserDiagnostic {
    id = DiagnosticId.DUPLICATE_DECL_SPEC
    formatArgs(k.keyword)
    columns(range(0))
  }

  private fun diagIncompat(k: Keywords) = parserDiagnostic {
    id = DiagnosticId.INCOMPATIBLE_DECL_SPEC
    formatArgs(k.keyword)
    columns(range(0))
  }

  private fun diagNotSigned(k: Keywords) = parserDiagnostic {
    id = DiagnosticId.TYPE_NOT_SIGNED
    formatArgs(k)
    columns(range(0))
  }

  /**
   * FIXME missing type specifiers (A.2.2/6.7.2):
   * 1. atomic-type-specifier (6.7.2.4)
   * 2. struct-or-union-specifier (6.7.2.1)
   * 3. enum-specifier (6.7.2.2)
   * 4. typedef-name (6.7.8)
   */
  private fun parseTypeSpecifier(typeSpec: List<Keywords>): TypeSpecifier? {
    if (typeSpec.isEmpty()) {
      parserDiagnostic {
        id = DiagnosticId.MISSING_TYPE_SPEC
        columns(range(0))
      }
      return null
    }

    // FIXME we are now going to pretend this implementation is finished, correct, complete,
    // standards-compliant, and reports sensible errors (lmao)

    val isSigned = typeSpec.contains(Keywords.SIGNED)
    val isUnsigned = typeSpec.contains(Keywords.UNSIGNED)
    if (isSigned && isUnsigned) {
      diagIncompat(Keywords.SIGNED)
      return null
    }
    if (typeSpec.contains(Keywords.VOID)) return TypeSpecifier.VOID
    if (typeSpec.contains(Keywords.FLOAT)) return TypeSpecifier.FLOAT
    if (typeSpec.contains(Keywords.LONG) && typeSpec.contains(Keywords.DOUBLE))
      return TypeSpecifier.LONG_DOUBLE
    if (typeSpec.contains(Keywords.DOUBLE)) return TypeSpecifier.DOUBLE

    if (typeSpec.contains(Keywords.CHAR)) {
      return if (isUnsigned) TypeSpecifier.UNSIGNED_CHAR
      else TypeSpecifier.SIGNED_CHAR
    }
    if (typeSpec.contains(Keywords.SHORT)) {
      return if (isUnsigned) TypeSpecifier.UNSIGNED_SHORT
      else TypeSpecifier.SIGNED_SHORT
    }
    // RIP long long
    if (typeSpec.contains(Keywords.LONG)) {
      return if (isUnsigned) TypeSpecifier.UNSIGNED_LONG
      else TypeSpecifier.SIGNED_LONG
    }
    if (typeSpec.contains(Keywords.INT)) {
      return if (isUnsigned) TypeSpecifier.UNSIGNED_INT
      else TypeSpecifier.SIGNED_INT
    }
    return null // Sure why not
  }

  /** C standard: A.2.2, 6.7 */
  private fun parseDeclSpecifiers(): DeclarationSpecifier {
    val typeSpec = mutableListOf<Keywords>()
    var storageSpecifier: Keywords? = null
    var hasThreadLocal = false
    var hasConst = false
    var hasRestrict = false
    var hasVolatile = false
    var hasAtomic = false
    var hasInline = false
    var hasNoReturn = false

    var hitError = false
    var declSpecTokenCount = 0
    while (current() is Keyword) {
      val k = (current() as Keyword).value
      when (k) {
        Keywords.COMPLEX -> {
          parserDiagnostic {
            id = DiagnosticId.UNSUPPORTED_COMPLEX
            columns(range(0))
          }
          hitError = true
        }
        in typeSpecifier -> typeSpec.add(k)
        Keywords.THREAD_LOCAL -> {
          if (hasThreadLocal) diagDuplicate(k)
          else if (storageSpecifier != null && storageSpecifier != Keywords.EXTERN &&
              storageSpecifier != Keywords.STATIC) {
            diagIncompat(storageSpecifier)
            hitError = true
          }
          hasThreadLocal = true
        }
        in storageClassSpecifier -> {
          if (k == storageSpecifier) diagDuplicate(k)
          else if (storageSpecifier != null) {
            diagIncompat(storageSpecifier)
            hitError = true
          } else if (hasThreadLocal &&
              (k != Keywords.EXTERN && k != Keywords.STATIC)) {
            diagIncompat(Keywords.THREAD_LOCAL)
            hitError = true
          }
          storageSpecifier = k
        }
        Keywords.CONST -> {
          if (hasConst) diagDuplicate(k)
          hasConst = true
        }
        Keywords.RESTRICT -> {
          if (hasRestrict) diagDuplicate(k)
          hasRestrict = true
        }
        Keywords.VOLATILE -> {
          if (hasVolatile) diagDuplicate(k)
          hasVolatile = true
        }
        Keywords.ATOMIC -> {
          if (hasAtomic) diagDuplicate(k)
          hasAtomic = true
        }
        Keywords.INLINE -> {
          if (hasInline) diagDuplicate(k)
          hasInline = true
        }
        Keywords.NORETURN -> {
          if (hasNoReturn) diagDuplicate(k)
          hasNoReturn = true
        }
        Keywords.TYPEDEF -> logger.throwICE("Typedef not implemented") { this }
        else -> null
      } ?: break
      eat()
      declSpecTokenCount++
    }

    if (declSpecTokenCount == 0) return MissingDeclarationSpecifier
    if (hitError) return ErrorDeclarationSpecifier

    val ts = parseTypeSpecifier(typeSpec) ?: return ErrorDeclarationSpecifier

    return RealDeclarationSpecifier(storageSpecifier, ts, hasThreadLocal,
        hasConst, hasRestrict, hasVolatile, hasAtomic, hasInline, hasNoReturn)
  }

  /**
   * Find matching parenthesis in token list. Handles nested parens. Prints errors about unmatched
   * parens.
   * @returns -1 if the parens are unbalanced or a [Punctuators.SEMICOLON] was found before they can
   * get balanced, the size of the token stack if there were no parens, or the (real) idx of the
   * rightmost paren otherwise
   */
  private fun findParenMatch(lparen: Punctuators, rparen: Punctuators): Int {
    var hasParens = false
    var stack = 0
    val end = indexOfFirst {
      if (it !is Punctuator) return@indexOfFirst false
      when (it.pct) {
        lparen -> {
          hasParens = true
          stack++
          return@indexOfFirst false
        }
        rparen -> {
          stack--
          return@indexOfFirst stack == 0
        }
        Punctuators.SEMICOLON -> return@indexOfFirst true
        else -> return@indexOfFirst false
      }
    }
    if (end == -1 && !hasParens) {
      // This is the case where there aren't any lparens until the end
      return tokStack.peek().size
    }
    if (!hasParens) {
      // This is the case where there aren't any lparens until a semicolon
      return end
    }
    if (end == -1 || tokStack.peek()[end].asPunct() != rparen) {
      parserDiagnostic {
        id = DiagnosticId.UNMATCHED_PAREN
        formatArgs(rparen.s)
        if (end == -1) {
          column(colPastTheEnd())
        } else {
          columns(range(end - idxStack.peek()))
        }
      }
      parserDiagnostic {
        id = DiagnosticId.MATCH_PAREN_TARGET
        formatArgs(lparen.s)
        columns(range(0))
      }
      return -1
    }
    return end
  }

  /**
   * Parses the params in a function declaration.
   * Examples of what it parses:
   * void f(int a, int x);
   *        ^^^^^^^^^^^^
   * void g();
   *        (here this function gets nothing to parse, and returns an empty list)
   */
  private fun parseParameterList(endIdx: Int): List<ParameterDeclaration> = tokenContext(endIdx) {
    // No parameters; this is not an error case
    if (isEaten()) return@tokenContext emptyList()
    val params = mutableListOf<ParameterDeclaration>()
    while (!isEaten()) {
      // We don't precisely care if we have an error in the DeclarationSpecifier
      val specs = parseDeclSpecifiers()
      if (specs is MissingDeclarationSpecifier) {
        TODO("possible unimplemented grammar (old-style K&R functions?)")
      }
      // The parameter can have parens with commas in them
      // We're interested in the comma that comes after the parameter
      // So balance the parens, and look for the first comma after them
      // Also, we do not eat what we find; we're only searching for the end of the current param
      // Once found, parseDeclarator handles parsing the param and eating it
      val parenEndIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
      if (parenEndIdx == -1) {
        TODO("handle error case where there is an unmatched paren in the parameter list")
      }
      val commaIdx = indexOfFirst { c -> c == Punctuators.COMMA }
      val declarator = parseDeclarator(if (commaIdx == -1) it.size else commaIdx)
          ?: TODO("handle error case with a null (error'd) declarator")
      params.add(ParameterDeclaration(specs, declarator))
      if (!isEaten() && current().asPunct() == Punctuators.COMMA) {
        // Expected case; found comma that separates params
        eat()
      }
    }
    return@tokenContext params
  }

  /** C standard: A.2.2, 6.7 */
  private fun parseDirectDeclarator(endIdx: Int): EitherNode<Declarator>? = tokenContext(endIdx) {
    if (it.isEmpty()) return@tokenContext null
    when {
      current().asPunct() == Punctuators.LPAREN -> {
        val end = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
        if (end == -1) return@tokenContext ErrorNode()
        // If the declarator slice will be empty, error out
        if (end - 1 == 0) {
          parserDiagnostic {
            id = DiagnosticId.EXPECTED_DECL
            columns(range(1))
          }
          eatToSemi()
          return@tokenContext ErrorNode()
        }
        val declarator = parseDeclarator(end)
        if (declarator is ErrorNode) eatToSemi()
        // FIXME: handle case where there is more shit (eg LPAREN/LSQPAREN cases) after end
        return@tokenContext declarator
      }
      current() !is Identifier -> {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_IDENT_OR_PAREN
          columns(range(0))
        }
        return@tokenContext ErrorNode()
      }
      current() is Identifier -> {
        val name = IdentifierNode((current() as Identifier).name)
        eat()
        when {
          isEaten() -> return@tokenContext name.asEither()
          current().asPunct() == Punctuators.LPAREN -> {
            val rparenIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
            eat() // Get rid of "("
            // FIXME: we can return something better than an ErrorNode (have the ident)
            if (rparenIdx == -1) return@tokenContext ErrorNode()
            val paramList = parseParameterList(rparenIdx)
            eat() // Get rid of ")"
            return@tokenContext FunctionDeclarator(name.asEither(), paramList).asEither()
          }
          current().asPunct() == Punctuators.LSQPAREN -> {
            val end = findParenMatch(Punctuators.LSQPAREN, Punctuators.RSQPAREN)
            if (end == -1) return@tokenContext ErrorNode()
            // FIXME parse "1 until end" slice (A.2.2/6.7.6 direct-declarator)
            logger.throwICE("Unimplemented grammar") { tokStack.peek() }
          }
          else -> return@tokenContext name.asEither()
        }
      }
      // FIXME: Can't happen? current() either is or isn't an identifier
      else -> return@tokenContext null
    }
  }

  private fun parseDeclarator(endIdx: Int): EitherNode<Declarator>? {
    // FIXME missing pointer parsing
    return parseDirectDeclarator(endIdx)
  }

  private fun parseInitializer(): ASTNode? {
    eat() // Get rid of "="
    // Error case, no initializer here
    if (current().asPunct() == Punctuators.COMMA || current().asPunct() == Punctuators.SEMICOLON) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_EXPR
        columns(range(0))
      }
      return ErrorNode()
    }
    // Parse initializer-list
    if (current().asPunct() == Punctuators.LBRACKET) {
      TODO("parse initializer-list (A.2.2/6.7.9)")
    }
    // Simple expression
    return parseExpr(tokStack.peek().size)
  }

  /**
   * Parses a declaration, including function declarations.
   * @returns null if there is no declaration, or a [Declaration] otherwise
   */
  private fun parseDeclaration(): EitherNode<Declaration>? {
    // FIXME typedef is to be handled specially, see 6.7.1 paragraph 5
    val declSpec = parseDeclSpecifiers()
    if (declSpec is MissingDeclarationSpecifier) return null
    // FIXME validate declSpecs according to standard 6.7.{1-6}
    val declaratorList = mutableListOf<InitDeclarator>()
    while (true) {
      val initDeclarator = parseDeclarator(tokStack.peek().size).ifNull {
        // This means that there were decl specs, but no declarator, which is a problem
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_DECL
          columns(range(0))
        }
        return@parseDeclaration ErrorNode()
      }
      if (initDeclarator is ErrorNode) {
        val parenEndIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
        if (parenEndIdx == -1) {
          TODO("handle error case where there is an unmatched paren in the initializer")
        }
        val stopIdx = indexOfFirst {
          it.asPunct() == Punctuators.COMMA || it.asPunct() == Punctuators.SEMICOLON
        }
        eatList(takeUntil(stopIdx).size)
      }
      if (isEaten()) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_AFTER_DECL
          column(colPastTheEnd())
        }
        declaratorList.add(InitDeclarator(initDeclarator, null))
        break
      }
      val initializer = if (current().asPunct() == Punctuators.ASSIGN) parseInitializer() else null
      declaratorList.add(InitDeclarator(initDeclarator, initializer))
      if (!isEaten() && current().asPunct() == Punctuators.COMMA) {
        // Expected case; there are chained init-declarators
        eat()
        continue
      } else if (!isEaten() && current().asPunct() == Punctuators.SEMICOLON) {
        // Expected case; semi at the end of declaration
        eat()
        break
      } else {
        // Missing semicolon
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_AFTER_DECL
          column(colPastTheEnd())
        }
        break
      }
    }
    return Declaration(declSpec, declaratorList).asEither()
  }

  /**
   * C standard: A.2.3
   * @returns the [LabeledStatement] if it is there, or null if there is no such statement
   */
  private fun parseLabeledStatement(): EitherNode<LabeledStatement>? {
    if (current() !is Identifier || lookahead().asPunct() != Punctuators.COLON) return null
    val label = IdentifierNode((current() as Identifier).name)
    eatList(2) // Get rid of ident and COLON
    val labeled = parseStatement()
    if (labeled == null) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_STATEMENT
        columns(range(-1))
      }
      return ErrorNode()
    }
    return LabeledStatement(label, labeled).asEither()
  }

  /**
   * C standard: A.2.3
   * @returns null if no statement was found, or the [Statement] otherwise
   */
  private fun parseStatement(): EitherNode<Statement>? {
    TODO()
  }

  /** C standard: A.2.3 */
  private fun parseCompoundStatement(endIdx: Int): CompoundStatement = tokenContext(endIdx) {
    val items = mutableListOf<EitherNode<BlockItem>>()
    while (!isEaten()) {
      val declaration = parseDeclaration()
      if (declaration != null) {
        items.add(declaration)
        continue
      }
      val statement = parseStatement()
      if (statement != null) {
        items.add(statement)
        continue
      }
    }
    return@tokenContext CompoundStatement(items)
  }

  /**
   * Parses a function _definition_. That includes the compound-statement. Function _declarations_
   * are not parsed here (see [parseDeclaration]).
   * C standard: A.2.4, A.2.2, 6.9.1
   * @returns null if this is not a function definition, or a [FunctionDefinition] otherwise
   */
  private fun parseFunctionDefinition(): EitherNode<FunctionDefinition>? {
    val firstBracket = indexOfFirst { it.asPunct() == Punctuators.LBRACKET }
    // If no bracket is found, it isn't a function, it might be a declaration
    if (firstBracket == -1) return null
    val declSpec = parseDeclSpecifiers()
    if (declSpec is MissingDeclarationSpecifier) return null
    // FIXME finish validation of declSpec
    if (declSpec is RealDeclarationSpecifier &&
        declSpec.storageSpecifier != Keywords.STATIC &&
        declSpec.storageSpecifier != Keywords.EXTERN) {
      parserDiagnostic {
        id = DiagnosticId.ILLEGAL_STORAGE_CLASS_FUNC
        // FIXME debug data in declSpec
      }
    }
    val declarator = parseDeclarator(firstBracket)?.let {
      if (it is EitherNode.Value && it.value is FunctionDeclarator) {
        // FIXME: what diag to print here?
        return@let it.value.asEither()
      }
      return@let ErrorNode()
    } ?: ErrorNode()
    if (current().asPunct() != Punctuators.LBRACKET) {
      TODO("possible unimplemented grammar (old-style K&R functions?)")
    }
    val rbracket = findParenMatch(Punctuators.LBRACKET, Punctuators.RBRACKET)
    if (rbracket == -1) {
      parserDiagnostic {
        id = DiagnosticId.UNMATCHED_PAREN
        formatArgs(Punctuators.RBRACKET.s)
        column(colPastTheEnd())
      }
      return FunctionDefinition(declSpec, declarator, ErrorNode()).asEither()
    }
    val block = parseCompoundStatement(rbracket).asEither()
    return FunctionDefinition(declSpec, declarator, block).asEither()
  }

  /** C standard: A.2.4, 6.9 */
  private tailrec fun translationUnit() {
    if (isEaten()) return
    val res = parseDeclaration()?.let {
      root.addExternalDeclaration(it)
    } ?: parseFunctionDefinition()?.let {
      root.addExternalDeclaration(it)
    }
    if (res == null) {
      // If we got here it means the current thing isn't a translation unit
      // So spit out an error and eat tokens
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_EXTERNAL_DECL
        columns(range(0))
      }
      eatToSemi()
      if (!isEaten()) eat()
    }
    if (isEaten()) return
    else translationUnit()
  }
}
