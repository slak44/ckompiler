interface ASTNode {
  val children: List<ASTNode>
  val childrenLimit: Int
}

/** C standard: A.2.1, 6.5.1 */
interface Terminal : ASTNode {
  override val children: List<ASTNode> get() = emptyList()
  override val childrenLimit: Int get() = 0
}

class ErrorNode : Terminal

data class IdentifierNode(val name: String) : Terminal

data class IntegerConstantNode(val value: Long, val suffix: IntegralSuffix) : Terminal

data class FloatingConstantNode(val value: Double, val suffix: FloatingSuffix) : Terminal

/**
 * According to the C standard, the value of multi-byte character constants is
 * implementation-defined. This implementation truncates the constants to the first byte.
 *
 * Also, empty char constants are not defined; here they are equal to 0, and produce a warning.
 *
 * C standard: 6.4.4.4 paragraph 10
 */
data class CharacterConstantNode(val char: Int, val encoding: CharEncoding) : Terminal

data class StringLiteralNode(val string: String, val encoding: StringEncoding) : Terminal

class Parser(tokens: List<Token>, private val srcFile: SourceFile) {
  private var consumed: Int = 0
  private val tokens = tokens.toMutableList()
  val inspections = mutableListOf<Diagnostic>()

  // FIXME track col/line data via tokens
  private fun parserDiagnostic(build: DiagnosticBuilder.() -> Unit) {
    inspections.add(newDiagnostic {
      sourceFile = srcFile
      origin = "Parser"
      this.build()
    })
  }

  private fun parseExpr(): ASTNode = TODO("implement this")

  /**
   * C standard: A.2.1, 6.5.1, 6.4.4.4
   * @see CharacterConstantNode
   */
  private fun parsePrimaryExpr(): Optional<ASTNode> {
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
