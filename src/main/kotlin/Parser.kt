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
      logger.throwICE("parseDeclaration() didn't return an ExternalDeclaration") {
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

/** C standard: A.2.4 */
sealed class ExternalDeclaration : ASTNode

// FIXME this is a lot more complex than this
data class FunctionDefinition(val name: String) : ExternalDeclaration()

data class InitDeclarator(val declarator: ASTNode, val initializer: ASTNode? = null)

data class Declaration(val declSpecs: List<Keywords>,
                       val declaratorList: List<InitDeclarator>) : ExternalDeclaration()

private val storageClassSpecifier = listOf(Keywords.TYPEDEF, Keywords.EXTERN, Keywords.STATIC,
    Keywords.THREAD_LOCAL, Keywords.AUTO, Keywords.REGISTER)
// FIXME missing type specifiers
private val typeSpecifier = listOf(Keywords.VOID, Keywords.CHAR, Keywords.SHORT, Keywords.INT,
    Keywords.LONG, Keywords.FLOAT, Keywords.DOUBLE, Keywords.SIGNED, Keywords.UNSIGNED,
    Keywords.BOOL, Keywords.COMPLEX)
private val typeQualifier = listOf(Keywords.CONST, Keywords.RESTRICT, Keywords.VOLATILE,
    Keywords.ATOMIC)
private val functionSpecifier = listOf(Keywords.INLINE, Keywords.NORETURN)
// FIXME alignment specifier

/**
 * Parses a translation unit.
 *
 * C standard: A.2.4, 6.9
 */
class Parser(tokens: List<Token>, private val srcFileName: SourceFileName) {
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

  private fun eatNewlines() {
    val idx = indexOfFirst { it.asPunct() != Punctuators.NEWLINE }
    if (idx == -1) return else eatList(idx - idxStack.peek())
  }

  // FIXME track col/line data via tokens
  private fun parserDiagnostic(build: DiagnosticBuilder.() -> Unit) {
    diags.add(createDiagnostic {
      sourceFileName = srcFileName
      origin = "Parser"
      this.build()
    })
  }

  private fun parseExpr(endIdx: Int): ASTNode = tokenContext(takeUntil(endIdx)) {
    val primary: ASTNode = parsePrimaryExpr().ifNull {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_PRIMARY
      }
      return@tokenContext ErrorNode()
    }
    return@tokenContext parseExprImpl(primary, 0)
  }

  private fun parseExprImpl(lhsInit: ASTNode, minPrecedence: Int): ASTNode {
    var lhs = lhsInit
    while (true) {
      eatNewlines()
      if (isEaten()) break
      val op = current().asOperator() ?: break
      if (op !in Operators.binaryExprOps) break
      if (op.precedence < minPrecedence) break
      eat()
      var rhs = parsePrimaryExpr().ifNull {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_PRIMARY
        }
        return ErrorNode()
      }
      while (true) {
        eatNewlines()
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
          }
          0
        }
        return CharacterConstantNode(char, tok.encoding)
      }
      is StringLiteral -> return StringLiteralNode(tok.data, tok.encoding)
      else -> return null
    }
  }

  /** C standard: A.2.2, 6.7 */
  private fun parseDeclSpecifiers(): List<Keywords> {
    if (current() !is Keyword) return emptyList()
    val endIdx = indexOfFirst {
      if (it !is Keyword) return@indexOfFirst true
      if (it.value !in storageClassSpecifier &&
          it.value !in typeSpecifier &&
          it.value !in typeQualifier &&
          it.value !in functionSpecifier) return@indexOfFirst true
      return@indexOfFirst false
    }
    val k = takeUntil(endIdx).map { it.asKeyword()!! }
    eatList(k.size)
    return k
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
      }
      parserDiagnostic {
        id = DiagnosticId.MATCH_PAREN_TARGET
        formatArgs(lparen.s)
      }
      return -1
    }
    return end
  }

  /** C standard: A.2.2, 6.7 */
  private fun parseDirectDeclarator(endIdx: Int): ASTNode? = tokenContext(takeUntil(endIdx)) {
    if (it.isEmpty()) return@tokenContext null
    when {
      current().asPunct() == Punctuators.LPAREN -> {
        val end = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
        if (end == -1) return@tokenContext ErrorNode()
        // If the declarator slice will be empty, error out
        if (1 == end - 1) {
          parserDiagnostic {
            id = DiagnosticId.EXPECTED_DECL
          }
          return@tokenContext ErrorNode()
        }
        // FIXME handle case where there is more shit (eg LPAREN/LSQPAREN cases) after end
        return@tokenContext parseDeclarator(end)
      }
      it.size > 1 && lookahead().asPunct() == Punctuators.LPAREN -> {
        val end = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
        if (end == -1) return@tokenContext ErrorNode()
        // FIXME parse "1 until end" slice (A.2.2/6.7.6 direct-declarator)
        logger.throwICE("Unimplemented grammar") { tokStack.peek() }
      }
      it.size > 1 && lookahead().asPunct() == Punctuators.LSQPAREN -> {
        val end = findParenMatch(Punctuators.LSQPAREN, Punctuators.RSQPAREN)
        if (end == -1) return@tokenContext ErrorNode()
        // FIXME parse "1 until end" slice (A.2.2/6.7.6 direct-declarator)
        logger.throwICE("Unimplemented grammar") { tokStack.peek() }
      }
      current() is Identifier -> {
        val node = IdentifierNode((current() as Identifier).name)
        eat()
        return@tokenContext node
      }
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
    val declSpecs = parseDeclSpecifiers()
    // FIXME validate declSpecs according to standard 6.7.{1-6}
    val declaratorList = mutableListOf<InitDeclarator>()
    while (true) {
      val initDeclarator = parseDeclarator(tokStack.peek().size).ifNull {
        // Simply not a declaration, move on
        if (declSpecs.isEmpty()) return@parseDeclaration null
        // This means that there were decl specs, but no declarator, which is a problem
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_DECL
        }
        return@parseDeclaration ErrorNode()
      }
      eatNewlines()
      val initializer =
          if (current().asPunct() == Punctuators.ASSIGN) parseInitializer() else null
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
        }
        break
      }
    }
    return Declaration(declSpecs, declaratorList)
  }

//  /** C standard: A.2.4, A.2.2, 6.9.1 */
//  private fun parseFunctionDefinition(): ASTNode? {
//    val declSpecs = parseDeclSpecifiers()
//    // It must have at least one to be valid grammar
//    if (declSpecs.isEmpty()) return null
//    // Function definitions can only have extern or static as storage class specifiers
//    val storageClass = declSpecs.asSequence().filter { it in storageClassSpecifier }
//    if (storageClass.any { it != Keywords.EXTERN && it != Keywords.STATIC }) {
//      parserDiagnostic { }
//    }
//
//    TODO()
//  }

  /** C standard: A.2.4, 6.9 */
  private tailrec fun translationUnit() {
    if (isEaten()) return
    if (current() is ErrorToken) {
      // If we got here it means this isn't actually a translation unit
      // FIXME does this code path make any sense?
      // So spit out an error and eat tokens until the next semicolon/line
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_EXTERNAL_DECL
      }
      while (!isEaten() && current().asPunct() != Punctuators.SEMICOLON &&
          current().asPunct() != Punctuators.NEWLINE) eat()
      // Also eat the final token if there is one
      if (tokStack.firstElement().isNotEmpty()) eat()
    }
    parseDeclaration()?.let {
      root.addExternalDeclaration(it)
    }
    if (isEaten()) return
    else translationUnit()
  }
}
