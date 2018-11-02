import mu.KotlinLogging

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

class ErrorNode : ASTNode

data class IdentifierNode(val name: String) : ASTNode

data class IntegerConstantNode(val value: Long, val suffix: IntegralSuffix) : ASTNode

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

data class BinaryNode(val op: Operators, val lhs: ASTNode, val rhs: ASTNode) : ASTNode

/** C standard: A.2.4 */
sealed class ExternalDeclaration : ASTNode

// FIXME this is a lot more complex than this
data class FunctionDefinition(val name: String) : ExternalDeclaration()

data class InitDeclarator(val declarator: ASTNode, val initializer: ASTNode?)

data class Declaration(val declSpecs: List<Keywords>,
                       val declaratorList: List<InitDeclarator>) : ExternalDeclaration()

enum class StorageClassSpecifier {
  TYPEDEF, EXTERN, STATIC, THREAD_LOCAL, AUTO, REGISTER;

  companion object {
    fun fromKeywords(k: Keywords): StorageClassSpecifier? = when (k) {
      Keywords.TYPEDEF -> TYPEDEF
      Keywords.EXTERN -> EXTERN
      Keywords.STATIC -> STATIC
      Keywords.THREAD_LOCAL -> THREAD_LOCAL
      Keywords.AUTO -> AUTO
      Keywords.REGISTER -> REGISTER
      else -> null
    }
  }
}

enum class TypeSpecifier {
  VOID, CHAR, SHORT, INT, LONG, FLOAT, DOUBLE, SIGNED, UNSIGNED, BOOL, COMPLEX
}

enum class TypeQualifier {
  CONST, RESTRICT, VOLATILE, ATOMIC
}

enum class FunctionSpecifier {
  INLINE, NORETURN
}

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
  // FIXME try to remove mutability if possible
  private var consumed: Int = 0
  private val tokens = tokens.toMutableList()
  val inspections = mutableListOf<Diagnostic>()

  val root = RootNode()

  init {
    parseTranslationUnit()
    inspections.forEach { it.print() }
  }

  private fun eat() {
    consumed++
    tokens.removeAt(0)
  }

  private fun eatList(length: Int) {
    consumed += length
    for (i in 0 until length) tokens.removeAt(0)
  }

  private fun eatNewlines() {
    while (tokens[0].asPunct() == Punctuators.NEWLINE) eat()
  }

  // FIXME track col/line data via tokens
  private fun parserDiagnostic(build: DiagnosticBuilder.() -> Unit) {
    inspections.add(createDiagnostic {
      sourceFileName = srcFileName
      origin = "Parser"
      this.build()
    })
  }

  private fun tokenToOperator(token: Token): Optional<Operators> {
    return token.asPunct()?.toOperator() ?: Empty()
  }

  private fun parseExpr(): ASTNode {
    val primary: ASTNode = parsePrimaryExpr().ifNull {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_PRIMARY
      }
      return@parseExpr ErrorNode()
    }
    return parseExprImpl(primary, 0)
  }

  private fun parseExprImpl(lhsInit: ASTNode, minPrecedence: Int): ASTNode {
    var lhs = lhsInit
    while (true) {
      eatNewlines()
      val op = tokenToOperator(tokens[0]).orNull() ?: break
      if (op !in Operators.binaryExprOps || op.precedence <= minPrecedence) break
      eat()
      var rhs = parsePrimaryExpr().ifNull {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_PRIMARY
        }
        return ErrorNode()
      }
      eat()
      while (true) {
        eatNewlines()
        val innerOp = tokenToOperator(tokens[0]).orNull() ?: break
        if (op !in Operators.binaryExprOps) break
        if (innerOp.precedence <= op.precedence &&
            !(innerOp.assoc == Associativity.RIGHT_TO_LEFT && innerOp.precedence == op.precedence)) {
          break
        }
        rhs = parseExprImpl(rhs, innerOp.precedence)
        eat()
      }
      lhs = BinaryNode(op, lhs, rhs)
    }
    return lhs
  }

  // FIXME: actually implement this
  private fun parsePrimaryExpr(): ASTNode? = parseTerminal()

  /**
   * C standard: A.2.1, 6.5.1, 6.4.4.4
   * @see CharacterConstantNode
   * @returns the [ASTNode] of the terminal, or [Empty] if no primary expr was found
   */
  private fun parseTerminal(): ASTNode? {
    val tok = tokens[0]
    when {
      tok is Identifier -> return IdentifierNode(tok.name)
      tok is IntegralConstant -> {
        return IntegerConstantNode(tok.n.toLong(tok.radix.toInt()), tok.suffix)
      }
      tok is FloatingConstant -> {
        // FIXME conversions might fail here?
        return FloatingConstantNode(tok.f.toDouble(), tok.suffix)
      }
      tok is CharLiteral -> {
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
      tok is StringLiteral -> return StringLiteralNode(tok.data, tok.encoding)
      tok.asPunct() == Punctuators.LPAREN -> {
        val next = tokens[1]
        if (next.asPunct() == Punctuators.RPAREN) {
          parserDiagnostic {
            id = DiagnosticId.EXPECTED_EXPR
          }
          return ErrorNode()
        }
        return parseExpr()
      }
      // FIXME implement generic-selection A.2.1/6.5.1.1
      else -> return null
    }
  }

  /** C standard: A.2.2, 6.7 */
  private fun parseDeclSpecifiers(): List<Keywords> {
    if (tokens[0] !is Keyword) return emptyList()
    val endIdx = tokens.indexOfFirst {
      if (it !is Keyword) return@indexOfFirst true
      if (it.value !in storageClassSpecifier &&
          it.value !in typeSpecifier &&
          it.value !in typeQualifier &&
          it.value !in functionSpecifier) return@indexOfFirst true
      return@indexOfFirst false
    }
    val keywords = tokens.slice(0 until endIdx).map { it.asKeyword()!! }
    eatList(keywords.size)
    return keywords
  }

  /**
   * Find matching parenthesis in token list. Handles nested parens. Prints errors about unmatched
   * parens.
   * @returns -1 if a [Punctuators.SEMICOLON] was found before parens were balanced, or the idx of
   * the rightmost paren otherwise
   */
  private fun findParenMatch(tokens: List<Token>, lparen: Punctuators, rparen: Punctuators): Int {
    var stack = 0
    val end = tokens.indexOfFirst {
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
    if (end == -1 || (tokens[end] as Punctuator).pct != rparen) {
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
  private fun parseDirectDeclarator(endIdx: Int): ASTNode? {
    val tokens = tokens.slice(0 until endIdx)
    // FIXME check this condition for correctness
    if (tokens.isEmpty()) return null
    val tok = tokens[0]
    val next = tokens.getOrNull(1)
    when {
      tok.asPunct() == Punctuators.LPAREN -> {
        val end = findParenMatch(tokens, Punctuators.LPAREN, Punctuators.RPAREN)
        if (end == -1) return ErrorNode()
        // If the declarator slice will be empty, error out
        if (1 == end - 1) {
          parserDiagnostic {
            id = DiagnosticId.EXPECTED_DECL
          }
          return ErrorNode()
        }
        // FIXME handle case where there is more shit (eg LPAREN/LSQPAREN cases) after end
        return parseDeclarator(end)
      }
      next?.asPunct() == Punctuators.LPAREN -> {
        val end = findParenMatch(tokens, Punctuators.LPAREN, Punctuators.RPAREN)
        if (end == -1) return ErrorNode()
        // FIXME parse "1 until end" slice (A.2.2/6.7.6 direct-declarator)
        logger.throwICE("Unimplemented grammar") { tokens }
      }
      next?.asPunct() == Punctuators.LSQPAREN -> {
        val end = findParenMatch(tokens, Punctuators.LSQPAREN, Punctuators.RSQPAREN)
        if (end == -1) return ErrorNode()
        // FIXME parse "1 until end" slice (A.2.2/6.7.6 direct-declarator)
        logger.throwICE("Unimplemented grammar") { tokens }
      }
      tok is Identifier -> {
        val node = IdentifierNode(tok.name)
        eat()
        return node
      }
      else -> return null
    }
  }

  private fun parseDeclarator(endIdx: Int): ASTNode? {
    // FIXME missing pointer parsing
    return parseDirectDeclarator(endIdx)
  }

  private fun parseInitializer(): ASTNode? {
    eat() // Get rid of "="
    // Error case, no initializer here
    if (tokens[0].asPunct() == Punctuators.COMMA || tokens[0].asPunct() == Punctuators.SEMICOLON) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_EXPR
      }
      return ErrorNode()
    }
    // Parse initializer-list
    if (tokens[0].asPunct() == Punctuators.LBRACKET) {
      TODO("parse initializer-list (A.2.2/6.7.9)")
    }
    // Simple expression
    return parseExpr()
  }

  private fun parseDeclaration(): ASTNode? {
    val declSpecs = parseDeclSpecifiers()
    // FIXME validate declSpecs according to standard 6.7.{1-6}
    val declaratorList = mutableListOf<InitDeclarator>()
    while (true) {
      val initDeclarator = parseDeclarator(tokens.size).ifNull {
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
          if (tokens[0].asPunct() == Punctuators.ASSIGN) parseInitializer() else null
      declaratorList.add(InitDeclarator(initDeclarator, initializer))
      if (tokens[0].asPunct() == Punctuators.SEMICOLON) {
        eat()
        break
      }
      if (tokens[0].asPunct() == Punctuators.COMMA) {
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

  /** C standard: A.2.4, A.2.2, 6.9.1 */
  private fun parseFunctionDefinition(): ASTNode? {
    val declSpecs = parseDeclSpecifiers()
    // It must have at least one to be valid grammar
    if (declSpecs.isEmpty()) return null
    // Function definitions can only have extern or static as storage class specifiers
    val storageClass = declSpecs.asSequence().filter { it in storageClassSpecifier }
    if (storageClass.any { it != Keywords.EXTERN && it != Keywords.STATIC }) {
      parserDiagnostic { }
    }

    TODO()
  }

  /** C standard: A.2.4, 6.9 */
  private tailrec fun parseTranslationUnit() {
    if (tokens.isEmpty()) return
    if (tokens[0] is ErrorToken) {
      // If we got here it means this isn't actually a translation unit
      // FIXME does this code path make any sense?
      // So spit out an error and eat tokens until the next semicolon/line
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_EXTERNAL_DECL
      }
      while (tokens.isNotEmpty() && tokens[0].asPunct() != Punctuators.SEMICOLON &&
          tokens[0].asPunct() != Punctuators.NEWLINE) eat()
      // Also eat the final token if there is one
      if (tokens.isNotEmpty()) eat()
    }
    parseDeclaration()?.let {
      root.addExternalDeclaration(it)
    }
    if (tokens.isEmpty()) return
    else parseTranslationUnit()
  }
}
