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

class Parser(tokens: List<Token>, private val srcFile: SourceFile) {
  private var consumed: Int = 0
  private val tokens = tokens.toMutableList()
  val inspections = mutableListOf<Diagnostic>()

  private fun eat() {
    consumed++
    tokens.removeAt(0)
  }

  // FIXME track col/line data via tokens
  private fun parserDiagnostic(build: DiagnosticBuilder.() -> Unit) {
    inspections.add(newDiagnostic {
      sourceFile = srcFile
      origin = "Parser"
      this.build()
    })
  }

  private fun tokenToOperator(token: Token): Optional<Operators> {
    return if (token !is Punctuator) Empty()
    // FIXME maybe make toBinaryOperator() ?
    else token.punctuator.toOperator()
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
      if (op.arity != Arity.BINARY || op.precedence <= minPrecedence) break
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
        if (innerOp.arity != Arity.BINARY) break
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
      tok is Punctuator && tok.punctuator == Punctuators.LPAREN -> {
        val next = tokens[1]
        if (next is Punctuator && next.punctuator == Punctuators.RPAREN) {
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
}
