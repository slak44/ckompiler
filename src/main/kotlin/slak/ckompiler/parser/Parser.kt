package slak.ckompiler.parser

import mu.KotlinLogging
import slak.ckompiler.*
import slak.ckompiler.lexer.*

/**
 * Eats tokens unconditionally until a semicolon or the end of the token list.
 * Does not eat the semicolon.
 */
fun ITokenHandler.eatToSemi() {
  while (isNotEaten() && current().asPunct() != Punctuators.SEMICOLON) eat()
}

/**
 * Like [TokenHandler.indexOfFirst], but stops on the first [Keyword]/[Punctuator] whose
 * [StaticToken.enum] matches one of the [t] arguments.
 * @see TokenHandler.indexOfFirst
 */
fun ITokenHandler.indexOfFirst(vararg t: StaticTokenEnum): Int {
  return indexOfFirst { tok ->
    t.any { tok.asKeyword() == it || tok.asPunct() == it }
  }
}

/**
 * Generic way to construct [ErrorNode] instances, like [ErrorExpression] or [ErrorStatement].
 *
 * Disgusting hack since Kotlin/JS doesn't actually support reflection.
 */
inline fun <reified T> ITokenHandler.error(): T where T : ASTNode, T : ErrorNode {
  val errorNode: T = when (T::class) {
    ErrorDeclInitializer::class -> throw IllegalArgumentException("Has arguments, call constructor")
    ErrorDeclarator::class -> ErrorDeclarator() as T
    ErrorExpression::class -> ErrorExpression() as T
    ErrorInitializer::class -> ErrorInitializer() as T
    ErrorStatement::class -> ErrorStatement() as T
    ErrorSuffix::class -> ErrorSuffix() as T
    else -> throw IllegalArgumentException("unreachable")
  }

  return errorNode.withRange(rangeOne())
}

/**
 * @param tokens list of tokens to parse
 * @param srcFileName the name of the file in which the tokens were extracted from
 */
class Parser(
    tokens: List<LexicalToken>,
    srcFileName: SourceFileName,
    srcText: String,
    machineTargetData: MachineTargetData
) {
  private val trParser: TranslationUnitParser

  init {
    val debugHandler = DebugHandler("Parser", srcFileName, srcText)
    val tokenHandler = TokenHandler(tokens)
    val scopeHandler = ScopeHandler(debugHandler)
    val parenMatcher = ParenMatcher(debugHandler, tokenHandler)
    val declParser = DeclarationParser(parenMatcher, scopeHandler)
    declParser.expressionParser = ExpressionParser(parenMatcher, declParser, declParser, machineTargetData)
    declParser.constExprParser = ConstantExprParser(ConstantExprType.DECLARATOR_ARRAY_SIZE, declParser.expressionParser)
    declParser.specParser = SpecParser(
        declParser,
        ConstantExprParser(ConstantExprType.INTEGER_CONSTANT, declParser.expressionParser)
    )
    declParser.initializerParser = InitializerParser(
        parenMatcher,
        scopeHandler,
        ConstantExprParser(ConstantExprType.DECLARATOR_ARRAY_SIZE, declParser.expressionParser)
    )
    val controlKeywordParser = ControlKeywordParser(declParser.expressionParser)
    val statementParser = StatementParser(
        declParser,
        controlKeywordParser,
        ConstantExprParser(ConstantExprType.INTEGER_CONSTANT, declParser.expressionParser)
    )
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
private class TranslationUnitParser(
    private val specParser: SpecParser,
    statementParser: StatementParser
) : IDebugHandler by statementParser,
    ITokenHandler by statementParser,
    IScopeHandler by statementParser,
    ISpecParser by specParser,
    IDeclarationParser by statementParser,
    IStatementParser by statementParser {

  val root = RootNode(rootScope)

  init {
    translationUnit()
    val isTranslationUnitEmpty =
        root.decls.isEmpty() && rootScope.tagNames.isEmpty() && rootScope.idents.isEmpty()
    if (isTranslationUnitEmpty && diags.isEmpty()) diagnostic {
      id = DiagnosticId.TRANSLATION_UNIT_NEEDS_DECL
      column(0)
    }
  }

  /**
   * Parses a function _definition_. That means everything after the declarator, so not the
   * `declaration-specifiers` or the declarator, but the `compound-statement` and
   * optionally the `declaration-list` (for old-style functions). Function _declarations_ are
   * **not** parsed here (see [parseDeclaration]).
   *
   * C standard: A.2.4, A.2.2, 6.9.1
   * @param declSpec pre-parsed declaration specifier
   * @param funDecl pre-parsed function declarator
   * @return the [FunctionDefinition]
   */
  private fun parseFunctionDefinition(
      declSpec: DeclarationSpecifier,
      funDecl: Declarator
  ): FunctionDefinition {
    if (!funDecl.isFunction()) logger.throwICE("Not a function declarator") { funDecl }
    if (funDecl !is NamedDeclarator) logger.throwICE("Function definition without name") { funDecl }
    val funTid = TypedIdentifier(declSpec, funDecl)
    newIdentifier(funTid)
    if (current().asPunct() != Punctuators.LBRACKET) {
      TODO("possible unimplemented grammar (old-style K&R functions?)")
    }
    if (declSpec.storageClass?.value == Keywords.TYPEDEF) diagnostic {
      id = DiagnosticId.FUNC_DEF_HAS_TYPEDEF
      errorOn(declSpec.storageClass)
      errorOn(safeToken(0))
    }
    funDecl.getFunctionTypeList().params.forEach {
      if (it.declarator !is NamedDeclarator) diagnostic {
        id = DiagnosticId.PARAM_NAME_OMITTED
        formatArgs(typeNameOf(it.declSpec, it.declarator))
        errorOn(it.declSpec..it.declarator)
      }
    }
    val block = parseCompoundStatement(funDecl.getFunctionTypeList().scope, funTid)
        ?: error<ErrorStatement>()
    val start = if (declSpec.isBlank()) block else declSpec
    return FunctionDefinition(declSpec, funDecl, block).withRange(start..block)
  }

  /**
   * Check function/prototype return type.
   * Disallow directly returning [FunctionType] or [ArrayType].
   *
   * C standard: 6.5.2.2
   */
  private fun checkFunctionReturnType(declSpec: DeclarationSpecifier, declarator: Declarator) {
    if (!declarator.isFunction()) return
    val returnType = (typeNameOf(declSpec, declarator) as? FunctionType)?.returnType ?: return
    if (returnType is FunctionType || returnType is ArrayType) diagnostic {
      id = DiagnosticId.INVALID_RET_TYPE
      formatArgs(if (returnType is FunctionType) "function" else "array", returnType)
      errorOn(declSpec..declarator)
    }
  }

  /** C standard: A.2.4, 6.9 */
  private tailrec fun translationUnit() {
    if (isEaten()) return
    val (declSpec, declarator) = preParseDeclarator(SpecValidationRules.FILE_SCOPED_VARIABLE)
    if (declSpec.isBlank()) {
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
    if (declarator == null) return translationUnit()
    checkFunctionReturnType(declSpec, declarator)
    if (declarator.isFunction() && isNotEaten() && current().asPunct() != Punctuators.SEMICOLON) {
      if (declarator.name.name == "main") {
        SpecValidationRules.MAIN_FUNCTION_DECLARATION.validate(specParser, declSpec)
      }
      root.addExternalDeclaration(parseFunctionDefinition(declSpec, declarator))
    } else {
      // FIXME: here are missing some validations, like tentative array definitions
      root.addExternalDeclaration(parseDeclaration(declSpec, declarator))
    }
    if (isEaten()) return
    else return translationUnit()
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
