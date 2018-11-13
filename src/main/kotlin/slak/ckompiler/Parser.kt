package slak.ckompiler

import mu.KotlinLogging
import java.util.*

private val logger = KotlinLogging.logger("Parser")

interface ASTNode

class RootNode : ASTNode {
  private val decls = mutableListOf<ASTNode>()

  fun getDeclarations(): List<ASTNode> = decls

  /**
   * @param n either [ExternalDeclaration] or [ErrorNode]
   * @throws InternalCompilerError if the parameter is of the wrong type
   */
  fun addExternalDeclaration(n: ASTNode) {
    if (n !is ExternalDeclaration && n !is ErrorNode) {
      logger.throwICE("parseDeclaration() didn't return an slak.ckompiler.ExternalDeclaration") {
        "token: $n"
      }
    }
    decls.add(n)
  }
}

class ErrorNode : ASTNode {
  override fun equals(other: Any?) = other is ErrorNode

  override fun hashCode() = javaClass.hashCode()

  override fun toString() = "<ERROR>"
}

data class IdentifierNode(val name: String) : ASTNode

data class IntegerConstantNode(val value: Long, val suffix: IntegralSuffix) : ASTNode {
  override fun toString() = "int $value ${suffix.name.toLowerCase()}"
}

data class FloatingConstantNode(val value: Double, val suffix: FloatingSuffix) : ASTNode

/**
 * According to the C standard, the value of multi-byte character constants is
 * implementation-defined. This implementation truncates the constants to the first byte.
 *
 * Also, empty char constants are not defined; here they are equal to 0, and produce a warning.
 *
 * C standard: 6.4.4.4 paragraph 10
 */
data class CharacterConstantNode(val char: Int, val encoding: CharEncoding) : ASTNode

data class StringLiteralNode(val string: String, val encoding: StringEncoding) : ASTNode

data class BinaryNode(val op: Operators, val lhs: ASTNode, val rhs: ASTNode) : ASTNode {
  override fun toString() = "($lhs $op $rhs)"
}

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
                                    val hasNoReturn: Boolean = false) : DeclarationSpecifier()

/** C standard: 6.7.6 */
sealed class Declarator : ASTNode

data class InitDeclarator(val declarator: ASTNode, val initializer: ASTNode? = null) : Declarator()

// FIXME: params can also be abstract-declarators (6.7.6/A.2.4)
data class FunctionDeclarator(val declarator: ASTNode,
                              val params: List<Declaration>,
                              val isVararg: Boolean = false) : Declarator()

/** C standard: A.2.4 */
sealed class ExternalDeclaration : ASTNode

data class Declaration(val declSpecs: DeclarationSpecifier,
                       val declaratorList: List<Declarator>) : ExternalDeclaration()

/** C standard: A.2.4 */
data class FunctionDefinition(val declSpec: DeclarationSpecifier,
                              val declarator: FunctionDeclarator,
                              val block: ASTNode) : ExternalDeclaration()

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
   * Creates a "sub-parser" context for a given list of tokens. However many elements are eaten in
   * the sub context will be eaten in the parent context too. Useful for parsing parenthesis and the
   * like.
   */
  private fun <T> tokenContext(tokens: List<Token>, block: (List<Token>) -> T): T {
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
   * @returns true if we ate a semicolon, false if we hit the end
   */
  private fun eatToSemi(): Boolean {
    while (!isEaten() && current().asPunct() != Punctuators.SEMICOLON) eat()
    // Also eat the final token if there is one
    return if (!isEaten()) {
      eat()
      true
    } else {
      false
    }
  }

  private fun parserDiagnostic(build: DiagnosticBuilder.() -> Unit) {
    diags.add(createDiagnostic {
      sourceFileName = srcFileName
      sourceText = srcText
      origin = "Parser"
      this.build()
    })
  }

  private fun parseExpr(endIdx: Int): ASTNode = tokenContext(takeUntil(endIdx)) {
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
   * @returns the [ASTNode] of the terminal, or null if no terminal was found
   */
  private fun parseTerminal(): ASTNode? {
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
   * @returns -1 if a [Punctuators.SEMICOLON] was found before parens were balanced, or the (real)
   * idx of the rightmost paren otherwise
   */
  private fun findParenMatch(lparen: Punctuators, rparen: Punctuators): Int {
    var stack = 0
    val end = indexOfFirst {
      if (it !is Punctuator) return@indexOfFirst false
      when (it.pct) {
        lparen -> {
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
    if (end == -1 || tokStack.peek()[end].asPunct() != rparen) {
      parserDiagnostic {
        id = DiagnosticId.UNMATCHED_PAREN
        formatArgs(rparen.s)
        columns(range(end - idxStack.peek()))
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
   * Example of what it parses:
   * void f(int a, int x)
   *        ^^^^^^^^^^^^
   */
  private fun parseParameterList(endIdx: Int): List<Declaration> = tokenContext(takeUntil(endIdx)) {
    TODO()
  }

  /** C standard: A.2.2, 6.7 */
  private fun parseDirectDeclarator(endIdx: Int): ASTNode? = tokenContext(takeUntil(endIdx)) {
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
          isEaten() -> return@tokenContext name
          current() == Punctuators.LPAREN -> {
            val end = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
            eat() // Get rid of "("
            // FIXME: we can return something better than an ErrorNode (have the ident)
            if (end == -1) return@tokenContext ErrorNode()
            val declarator = FunctionDeclarator(name, parseParameterList(end))
            eat() // Get rid of ")"
            return@tokenContext declarator
          }
          current() == Punctuators.LSQPAREN -> {
            val end = findParenMatch(Punctuators.LSQPAREN, Punctuators.RSQPAREN)
            if (end == -1) return@tokenContext ErrorNode()
            // FIXME parse "1 until end" slice (A.2.2/6.7.6 direct-declarator)
            logger.throwICE("Unimplemented grammar") { tokStack.peek() }

          }
          else -> return@tokenContext name
        }
      }
      // FIXME: Can't happen? current() either is or isn't an identifier
      else -> return@tokenContext null
    }
  }

  private fun parseDeclarator(endIdx: Int): ASTNode? {
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

  private fun parseDeclaration(): ASTNode? {
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
        eatToSemi()
        break
      }
      if (isEaten()) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_AFTER_DECL
          // FIXME add missing columns call, it crashes (?)
        }
        declaratorList.add(InitDeclarator(initDeclarator, null))
        break
      }
      val initializer = if (current().asPunct() == Punctuators.ASSIGN) parseInitializer() else null
      declaratorList.add(InitDeclarator(initDeclarator, initializer))
      if (current().asPunct() == Punctuators.SEMICOLON) {
        eat()
        break
      }
      if (current().asPunct() == Punctuators.COMMA) {
        // Expected case; there are chained init-declarators
        eat()
        continue
      } else {
        // Missing semicolon
        eat()
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_AFTER_DECL
          columns(range(0))
        }
        break
      }
    }
    return Declaration(declSpec, declaratorList)
  }

  /** C standard: A.2.4, A.2.2, 6.9.1 */
  private fun parseFunctionDefinition(): ASTNode? {
    val declSpec = parseDeclSpecifiers()
    if (declSpec is MissingDeclarationSpecifier) return null
    if (declSpec is ErrorDeclarationSpecifier) return ErrorNode()
    declSpec as RealDeclarationSpecifier
    // FIXME finish validation of declSpec
    if (declSpec.storageSpecifier != Keywords.STATIC &&
        declSpec.storageSpecifier != Keywords.EXTERN) {
      parserDiagnostic {
        id = DiagnosticId.ILLEGAL_STORAGE_CLASS_FUNC
        // FIXME debug data in declSpec
      }
    }
    val firstBracket = indexOfFirst { it.asPunct() == Punctuators.LBRACKET }
    // If no bracket is found, it must be an error,
    // because function prototypes (no compound-statement) are caught by parseDeclaration
    if (firstBracket == -1) return ErrorNode()
    val declarator = parseDeclarator(firstBracket)
    TODO()
  }

  /** C standard: A.2.4, 6.9 */
  private tailrec fun translationUnit() {
    if (isEaten()) return
    if (current() is ErrorToken) {
      // If we got here it means this isn't actually a translation unit
      // FIXME does this code path make any sense?
      // So spit out an error and eat tokens until the next semicolon/line
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_EXTERNAL_DECL
        columns(range(0))
      }
      eatToSemi()
    }
    parseDeclaration()?.let {
      root.addExternalDeclaration(it)
    } ?: parseFunctionDefinition()?.let {
      root.addExternalDeclaration(it)
    }
    if (isEaten()) return
    else translationUnit()
  }
}
