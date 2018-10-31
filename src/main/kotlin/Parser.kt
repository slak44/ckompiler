interface ASTNode

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

// FIXME this is a lot more complex than this
data class Declaration(val name: String) : ExternalDeclaration()

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

  init {
    parseTranslationUnit()
    inspections.forEach { it.print() }
  }

  private val root = object : ASTNode {
    val decls = mutableListOf<ExternalDeclaration>()
  }

  private fun eat() {
    consumed++
    tokens.removeAt(0)
  }

  private fun eatList(length: Int) {
    consumed += length
    for (i in 0..length) tokens.removeAt(0)
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
    return (token as? Punctuator)?.pct?.toOperator() ?: Empty()
  }

  private fun parseExpr(): ASTNode {
    val primary = parsePrimaryExpr().orElse {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_PRIMARY
      }
      return ErrorNode()
    }
    return parseExprImpl(primary, 0)
  }

  private fun parseExprImpl(lhsInit: ASTNode, minPrecedence: Int): ASTNode {
    var lhs = lhsInit
    while (true) {
      val op = tokenToOperator(tokens[0]).orNull() ?: break
      if (op !in Operators.binaryExprOps || op.precedence <= minPrecedence) break
      eat()
      var rhs = parsePrimaryExpr().orElse {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_PRIMARY
        }
        return ErrorNode()
      }
      eat()
      while (true) {
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
  private fun parsePrimaryExpr(): Optional<ASTNode> = parseTerminal()

  /**
   * C standard: A.2.1, 6.5.1, 6.4.4.4
   * @see CharacterConstantNode
   * @returns the [ASTNode] of the terminal, or [Empty] if no primary expr was found
   */
  private fun parseTerminal(): Optional<ASTNode> {
    val tok = tokens[0]
    when {
      tok is Identifier -> return IdentifierNode(tok.name).opt()
      tok is IntegralConstant -> {
        return IntegerConstantNode(tok.n.toLong(tok.radix.toInt()), tok.suffix).opt()
      }
      tok is FloatingConstant -> {
        // FIXME conversions might fail here?
        return FloatingConstantNode(tok.f.toDouble(), tok.suffix).opt()
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
        return CharacterConstantNode(char, tok.encoding).opt()
      }
      tok is StringLiteral -> return StringLiteralNode(tok.data, tok.encoding).opt()
      tok is Punctuator && tok.pct == Punctuators.LPAREN -> {
        val next = tokens[1]
        if (next is Punctuator && next.pct == Punctuators.RPAREN) {
          parserDiagnostic {
            id = DiagnosticId.EXPECTED_EXPR
          }
          return ErrorNode().opt()
        }
        return parseExpr().opt()
      }
      // FIXME implement generic-selection A.2.1/6.5.1.1
      else -> return Empty()
    }
  }

  /** C standard: A.2.2, 6.7 */
  private fun parseDeclSpecifiers(): List<Keyword> {
    if (tokens[0] !is Keyword) return emptyList()
    val endIdx = tokens.indexOfFirst {
      if (it !is Keyword) return@indexOfFirst true
      if (it.value !in storageClassSpecifier &&
          it.value !in typeSpecifier &&
          it.value !in typeQualifier &&
          it.value !in functionSpecifier) return@indexOfFirst true
      return@indexOfFirst false
    }
    val keywords = tokens.slice(0 until endIdx).map { it as Keyword }
    eatList(endIdx)
    return keywords
  }

  /** C standard: A.2.2, 6.7 */
  private fun parseDirectDeclarator(tokens: List<Token>): Optional<ASTNode> {
    val tok = tokens[0]
    if (tok is Punctuator && tok.pct == Punctuators.LPAREN) {
      var stack = 1
      val end = tokens.indexOfFirst {
        when ((it as Punctuator).pct) {
          Punctuators.LPAREN -> {
            stack++
            return@indexOfFirst false
          }
          Punctuators.RPAREN -> {
            stack--
            return@indexOfFirst stack == 0
          }
          Punctuators.SEMICOLON -> return@indexOfFirst true
          else -> return@indexOfFirst false
        }
      }
      if ((tokens[end] as Punctuator).pct != Punctuators.RPAREN || end == -1) {
        parserDiagnostic {
          id = DiagnosticId.UNMATCHED_PAREN
        }
        parserDiagnostic {
          id = DiagnosticId.MATCH_PAREN_TARGET
        }
        return Empty()
      }
      return parseDeclarator(tokens.slice(1 until end))
    }
    val next = tokens[1]
    if (next is Punctuator && (next.pct == Punctuators.LPAREN || next.pct == Punctuators.LSQPAREN)) {

    }
    if (tok is Identifier) {
      val node = IdentifierNode(tok.name)
      eat()
      return node.opt()
    }
    return Empty()
  }

//  private fun parsePointer(): List<Token> {
//    if ((tokens[0] as? Punctuator)?.pct != Punctuators.STAR) return emptyList()
//    val endIdx = tokens.indexOfFirst { it !is Keyword || it.value !in typeQualifier }
//
//  }

  private fun parseDeclarator(tokens: List<Token>): Optional<ASTNode> {
    // FIXME missing pointer parsing
    return parseDirectDeclarator(tokens)
  }

  /** C standard: A.2.4, A.2.2, 6.9.1 */
  private fun parseFunctionDefinition(): Optional<ASTNode> {
    val declSpecs = parseDeclSpecifiers()
    // It must have at least one to be valid grammar
    if (declSpecs.isEmpty()) return Empty()
    // Function definitions can only have extern or static as storage class specifiers
    val storageClass = declSpecs.asSequence().filter { it.value in storageClassSpecifier }
    if (storageClass.any { it.value != Keywords.EXTERN && it.value != Keywords.STATIC }) {
      parserDiagnostic { }
    }

    TODO()
  }

  private fun parseDeclaration(): Optional<ASTNode> {
    val declSpecs = parseDeclSpecifiers()
  }

  /** C standard: A.2.4, 6.9 */
  private tailrec fun parseTranslationUnit() {
    if (tokens.isEmpty()) return
//    parseFunctionDefinition().ifPresent {
//      root.decls.add(it as ExternalDeclaration)
//      return parseTranslationUnit()
//    }
    parseDeclaration().ifPresent {
      root.decls.add(it as ExternalDeclaration)
      return parseTranslationUnit()
    }
    if (tokens[0] !is ErrorToken) {
      // If we got here it means this isn't actually a translation unit
      // So spit out an error and eat tokens until the next semicolon/line
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_EXTERNAL_DECL
      }
      while ((tokens[0] as? Punctuator)?.pct != Punctuators.SEMICOLON &&
          (tokens[0] as? Punctuator)?.pct != Punctuators.NEWLINE) eat()
      // Also eat the final token
      eat()
    }
    parseTranslationUnit()
  }
}
