package slak.ckompiler.parser

import mu.KLogger
import mu.KotlinLogging
import slak.ckompiler.*
import slak.ckompiler.lexer.Punctuators
import slak.ckompiler.lexer.Token
import slak.ckompiler.lexer.asPunct

/**
 * @param tokens list of tokens to parse
 * @param srcFileName the name of the file in which the tokens were extracted from
 */
class Parser(tokens: List<Token>, srcFileName: SourceFileName, srcText: String) {
  private val parser: TranslationUnitParser

  init {
    val debugHandler = DebugHandler(srcFileName, srcText)
    val tokenHandler = TokenHandler(tokens, debugHandler)
    val scopeHandler = ScopeHandler(debugHandler)
    val parenMatcher = ParenMatcher(debugHandler, tokenHandler)
    val expressionParser = ExpressionParser(scopeHandler, parenMatcher)
    val declParser = DeclarationParser(scopeHandler, expressionParser)
    declParser.specParser = SpecParser(declParser)
    val controlKeywordParser = ControlKeywordParser(expressionParser)
    val statementParser = StatementParser(declParser, controlKeywordParser)
    parser = TranslationUnitParser(statementParser)
  }

  val diags = parser.diags
  val root = parser.root
}

interface IDebugHandler {
  val logger: KLogger
  val diags: List<Diagnostic>
  fun parserDiagnostic(build: DiagnosticBuilder.() -> Unit)
}

class DebugHandler(private val srcFileName: SourceFileName,
                   private val srcText: String) : IDebugHandler {
  override val logger: KLogger get() = DebugHandler.logger
  override val diags = mutableListOf<Diagnostic>()

  override fun parserDiagnostic(build: DiagnosticBuilder.() -> Unit) {
    diags += createDiagnostic {
      sourceFileName = srcFileName
      sourceText = srcText
      origin = "Parser"
      this.build()
    }
  }

  companion object {
    private val logger = KotlinLogging.logger("Parser")
  }
}

/**
 * Parses a translation unit.
 *
 * C standard: A.2.4, 6.9
 */
private class TranslationUnitParser(statementParser: StatementParser) :
    IDebugHandler by statementParser,
    ITokenHandler by statementParser,
    IScopeHandler by statementParser,
    IDeclarationParser by statementParser,
    IStatementParser by statementParser {

  val root = RootNode()

  init {
    root.setRange(tokenAt(0) until relative(tokenCount - 1))
    translationUnit()
    diags.forEach { it.print() }
  }

  /**
   * Parses a function _definition_. That means everything after the declarator, so not the
   * `declaration-specifiers` or the [FunctionDeclarator], but the `compound-statement` and
   * optionally the `declaration-list` (for old-style functions). Function _declarations_ are
   * **not** parsed here (see [parseDeclaration]).
   *
   * C standard: A.2.4, A.2.2, 6.9.1
   * @param declSpec pre-parsed declaration specifier
   * @param funDecl pre-parsed function declarator
   * @return the [FunctionDefinition]
   */
  private fun parseFunctionDefinition(declSpec: DeclarationSpecifier,
                                      funDecl: FunctionDeclarator): FunctionDefinition {
    funDecl.name()?.let { name -> newIdentifier(name) }
    if (current().asPunct() != Punctuators.LBRACKET) {
      TODO("possible unimplemented grammar (old-style K&R functions?)")
    }
    val block = parseCompoundStatement(funDecl.scope) ?: ErrorStatement().withRange(rangeOne())
    val start = if (declSpec.isEmpty()) block.tokenRange else declSpec.range!!
    return FunctionDefinition(declSpec, funDecl, block).withRange(start between  block.tokenRange)
  }

  /** C standard: A.2.4, 6.9 */
  private tailrec fun translationUnit() {
    if (isEaten()) return
    val (declSpec, declarator) = preParseDeclarator()
    if (declSpec.isEmpty()) {
      // If we got here it means the current thing isn't a translation unit
      // So spit out an error and eat tokens
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_EXTERNAL_DECL
        errorOn(safeToken(0))
      }
      eatToSemi()
      if (!isEaten()) eat()
      return translationUnit()
    }
    if (declarator is FunctionDeclarator && current().asPunct() != Punctuators.SEMICOLON) {
      root.addExternalDeclaration(parseFunctionDefinition(declSpec, declarator))
    } else if (declSpec.canBeTag() && declarator == null) {
      root.addExternalDeclaration(Declaration(declSpec, emptyList()))
      // FIXME: struct/union/enum/something declaration or definition, do something with it
    } else {
      root.addExternalDeclaration(parseDeclaration(declSpec, declarator!!))
    }
    if (isEaten()) return
    else translationUnit()
  }
}
