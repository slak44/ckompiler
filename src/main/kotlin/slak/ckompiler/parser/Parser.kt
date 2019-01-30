package slak.ckompiler.parser

import slak.ckompiler.*
import slak.ckompiler.lexer.Punctuators
import slak.ckompiler.lexer.LexicalToken
import slak.ckompiler.lexer.asPunct

typealias ILexicalTokenHandler = ITokenHandler<LexicalToken>

/**
 * Eats tokens unconditionally until a semicolon or the end of the token list.
 * Does not eat the semicolon.
 */
fun ILexicalTokenHandler.eatToSemi()  {
  while (isNotEaten() && current().asPunct() != Punctuators.SEMICOLON) eat()
}

/**
 * @param tokens list of tokens to parse
 * @param srcFileName the name of the file in which the tokens were extracted from
 */
class Parser(tokens: List<LexicalToken>, srcFileName: SourceFileName, srcText: String) {
  private val trParser: TranslationUnitParser

  init {
    val debugHandler = DebugHandler("Parser", srcFileName, srcText)
    val tokenHandler = TokenHandler(tokens, debugHandler)
    val scopeHandler = ScopeHandler(debugHandler)
    val parenMatcher = ParenMatcher(debugHandler, tokenHandler)
    val expressionParser = ExpressionParser(scopeHandler, parenMatcher)
    val declParser = DeclarationParser(scopeHandler, expressionParser)
    declParser.specParser = SpecParser(declParser)
    val controlKeywordParser = ControlKeywordParser(expressionParser)
    val statementParser = StatementParser(declParser, controlKeywordParser)
    trParser = TranslationUnitParser(declParser.specParser, statementParser)
  }

  val diags = trParser.diags
  val root = trParser.root
}

/**
 * Parses a translation unit.
 *
 * C standard: A.2.4, 6.9
 */
private class TranslationUnitParser(private val specParser: SpecParser,
                                    statementParser: StatementParser) :
    IDebugHandler by statementParser,
    ILexicalTokenHandler by statementParser,
    IScopeHandler by statementParser,
    ISpecParser by specParser,
    IDeclarationParser by statementParser,
    IStatementParser by statementParser {

  val root = RootNode()

  init {
    if (tokenCount > 0) root.setRange(tokenAt(0) until relative(tokenCount - 1))
    translationUnit()
    if (root.decls.isEmpty() &&
        rootScope.tagNames.isEmpty() &&
        rootScope.typedefNames.isEmpty() &&
        diags.isEmpty()) {
      diagnostic {
        id = DiagnosticId.TRANSLATION_UNIT_NEEDS_DECL
        column(0)
      }
    }
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
    return FunctionDefinition(declSpec, funDecl, block).withRange(start..block.tokenRange)
  }

  /** C standard: A.2.4, 6.9 */
  private tailrec fun translationUnit() {
    if (isEaten()) return
    val (declSpec, declaratorOpt) = preParseDeclarator(SpecValidationRules.FILE_SCOPED_VARIABLE)
    if (declSpec.isEmpty()) {
      // If we got here it means the current thing isn't a translation unit
      // So spit out an error and eat tokens
      diagnostic {
        id = DiagnosticId.EXPECTED_EXTERNAL_DECL
        errorOn(safeToken(0))
      }
      eatToSemi()
      if (isNotEaten()) eat()
      return translationUnit()
    }
    if (!declaratorOpt!!.isPresent) return translationUnit()
    val declarator = declaratorOpt.get()
    if (declarator is FunctionDeclarator && current().asPunct() != Punctuators.SEMICOLON) {
      if (declarator.name()?.name == "main") {
        SpecValidationRules.MAIN_FUNCTION_DECLARATION.validate(specParser, declSpec)
      }
      root.addExternalDeclaration(parseFunctionDefinition(declSpec, declarator))
    } else {
      val declaration = parseDeclaration(declSpec, declarator)
      // Typedefs are handled in the [DeclarationParser]; it's not a real declaration
      if (declSpec.isTypedef()) return translationUnit()
      root.addExternalDeclaration(declaration)
    }
    if (isEaten()) return
    else return translationUnit()
  }
}
