package slak.ckompiler.parser

import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.*
import slak.ckompiler.throwICE

interface IDeclarationParser {
  /**
   * Parses a declaration, including function declarations (prototypes).
   * @return null if there is no declaration, or a [Declaration] otherwise
   */
  fun parseDeclaration(): Declaration?

  /**
   * Parse a [DeclarationSpecifier] and the first [Declarator] that follows.
   * @return if [DeclarationSpecifier.isEmpty] is true, the [Declarator] will be null
   */
  fun preParseDeclarator(): Pair<DeclarationSpecifier, Declarator?>

  /**
   * Parses a declaration where the [DeclarationSpecifier] and the first [Declarator] are already
   * parsed.
   * @see preParseDeclarator
   * @return the [Declaration]
   */
  fun parseDeclaration(declSpec: DeclarationSpecifier, declarator: Declarator): Declaration
}

class DeclarationParser(scopeHandler: ScopeHandler, expressionParser: ExpressionParser) :
    IDeclarationParser,
    IDebugHandler by scopeHandler,
    ITokenHandler by expressionParser,
    IScopeHandler by scopeHandler,
    IParenMatcher by expressionParser,
    IExpressionParser by expressionParser {

  companion object {
    private val storageClassSpecifier =
        listOf(Keywords.EXTERN, Keywords.STATIC, Keywords.AUTO, Keywords.REGISTER)
    private val typeSpecifier = listOf(Keywords.VOID, Keywords.CHAR, Keywords.SHORT, Keywords.INT,
        Keywords.LONG, Keywords.FLOAT, Keywords.DOUBLE, Keywords.SIGNED, Keywords.UNSIGNED,
        Keywords.BOOL, Keywords.COMPLEX)
    private val typeQualifier =
        listOf(Keywords.CONST, Keywords.RESTRICT, Keywords.VOLATILE, Keywords.ATOMIC)
    private val funSpecifier = listOf(Keywords.NORETURN, Keywords.INLINE)
  }

  override fun parseDeclaration(): Declaration? {
    val declSpec = parseDeclSpecifiers()
    if (declSpec.isEmpty()) return null
    return RealDeclaration(declSpec, parseInitDeclaratorList())
  }

  override fun preParseDeclarator(): Pair<DeclarationSpecifier, Declarator?> {
    val declSpec = parseDeclSpecifiers()
    return Pair(declSpec, if (declSpec.isEmpty()) null else parseDeclarator(tokenCount))
  }

  override fun parseDeclaration(declSpec: DeclarationSpecifier,
                                declarator: Declarator): Declaration {
    return RealDeclaration(declSpec, parseInitDeclaratorList(declarator))
  }

  /** C standard: A.2.2, 6.7 */
  private fun parseDeclSpecifiers(): DeclarationSpecifier {
    val keywordsEnd = indexOfFirst { it !is Keyword }
    return tokenContext(if (keywordsEnd == -1) tokenCount else keywordsEnd) {
      val storageSpecs = mutableListOf<Keyword>()
      val typeSpecs = mutableListOf<Keyword>()
      val typeQuals = mutableListOf<Keyword>()
      val funSpecs = mutableListOf<Keyword>()
      it.takeWhile { tok ->
        when ((tok as Keyword).value) {
          Keywords.TYPEDEF -> logger.throwICE("Typedef not implemented") { this }
          in storageClassSpecifier -> storageSpecs.add(tok)
          in typeSpecifier -> typeSpecs.add(tok)
          in typeQualifier -> typeQuals.add(tok)
          in funSpecifier -> funSpecs.add(tok)
          else -> return@takeWhile false
        }
        eat()
        return@takeWhile true
      }
      DeclarationSpecifier(storageSpecs, typeSpecs, typeQuals, funSpecs)
    }
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
      val specs = parseDeclSpecifiers()
      if (specs.isEmpty()) {
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
      params.add(ParameterDeclaration(specs, declarator))
      // Add param name to current scope (which can be either block scope or
      // function prototype scope)
      val declaratorName = declarator.name()
      if (declaratorName != null) newIdentifier(declaratorName)
      if (!isEaten() && current().asPunct() == Punctuators.COMMA) {
        // Expected case; found comma that separates params
        eat()
      }
    }
    return@tokenContext params
  }

  /**
   * This function parses a `declarator` nested in a `direct-declarator`.
   *
   * C standard: 6.7.6.1
   */
  private fun parseNestedDeclarator(): Declarator? {
    if (current().asPunct() != Punctuators.LPAREN) return null
    val end = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
    if (end == -1) return ErrorDeclarator()
    // If the declarator slice will be empty, error out
    if (end - 1 == 0) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_DECL
        errorOn(safeToken(1))
      }
      eatToSemi()
      return ErrorDeclarator()
    }
    val declarator = parseDeclarator(end)
    if (declarator is ErrorNode) eatToSemi()
    return declarator
  }

  /**
   * This function parses the things that can come after a `direct-declarator`.
   * FIXME: parse in a loop, to catch things like `int v[12][23];`
   * C standard: 6.7.6.1
   */
  private fun parseDirectDeclaratorSuffixes(primary: Declarator): Declarator = when {
    isEaten() -> primary
    current().asPunct() == Punctuators.LPAREN -> {
      val rparenIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
      eat() // Get rid of "("
      // FIXME: we can return something better than an ErrorNode (have the ident)
      if (rparenIdx == -1) {
        // FIXME: maybe we should eat stuff here?
        ErrorDeclarator()
      } else scoped {
        val paramList = parseParameterList(rparenIdx)
        eat() // Get rid of ")"
        FunctionDeclarator(declarator = primary, params = paramList, scope = this, isVararg = false)
      }
    }
    current().asPunct() == Punctuators.LSQPAREN -> {
      val end = findParenMatch(Punctuators.LSQPAREN, Punctuators.RSQPAREN)
      if (end == -1) {
        ErrorDeclarator()
      } else {
        // FIXME: A.2.2/6.7.6 direct-declarator with square brackets
        logger.throwICE("Unimplemented grammar") { current() }
      }
    }
    else -> primary
  }

  /** C standard: 6.7.6.1 */
  private fun parseNameDeclarator(): NameDeclarator? {
    val id = current() as? Identifier ?: return null
    val name = NameDeclarator(IdentifierNode(id.name))
    eat() // The identifier token
    return name
  }

  /** C standard: A.2.2, 6.7 */
  private fun parseDirectDeclarator(endIdx: Int): Declarator = tokenContext(endIdx) {
    if (it.isEmpty()) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_IDENT_OR_PAREN
        column(colPastTheEnd(0))
      }
      return@tokenContext ErrorDeclarator()
    }
    val primaryDecl = parseNameDeclarator() ?: parseNestedDeclarator()
    if (primaryDecl == null) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_IDENT_OR_PAREN
        errorOn(safeToken(0))
      }
      return@tokenContext ErrorDeclarator()
    }
    return@tokenContext parseDirectDeclaratorSuffixes(primaryDecl)
  }

  /** C standard: 6.7.6.1 */
  private fun parseDeclarator(endIdx: Int): Declarator = tokenContext(endIdx) {
    // FIXME: missing pointer parsing
    val directDecl = parseDirectDeclarator(it.size)
    if (!isEaten()) {
      // FIXME: this should likely be an error
      logger.warn { "parseDirectDeclarator did not eat all of its tokens" }
    }
    return@tokenContext directDecl
  }

  // FIXME: return type will change with the initializer list
  private fun parseInitializer(): Expression {
    eat() // Get rid of "="
    // Error case, no initializer here
    if (current().asPunct() == Punctuators.COMMA || current().asPunct() == Punctuators.SEMICOLON) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_EXPR
        errorOn(safeToken(0))
      }
      return ErrorExpression()
    }
    // Parse initializer-list
    if (current().asPunct() == Punctuators.LBRACKET) {
      TODO("parse initializer-list (A.2.2/6.7.9)")
    }
    // Simple expression
    // parseExpr should print out the diagnostic in case there is no expr here
    return parseExpr(tokenCount) ?: ErrorExpression()
  }

  /**
   * Parse a `init-declarator-list`, a part of a `declaration`.
   *
   * C standard: A.2.2
   * @param firstDecl if not null, this will be treated as the first declarator in the list that was
   * pre-parsed
   */
  private fun parseInitDeclaratorList(firstDecl: Declarator? = null): List<Declarator> {
    // FIXME typedef is to be handled specially, see 6.7.1 paragraph 5
    val declaratorList = mutableListOf<Declarator>()
    // If firstDecl is null, we act as if it was already processed
    var firstDeclUsed = firstDecl == null
    while (true) {
      val declarator = if (firstDeclUsed) {
        parseDeclarator(tokenCount)
      } else {
        firstDeclUsed = true
        firstDecl!!
      }
      if (declarator is ErrorDeclarator) {
        val parenEndIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
        if (parenEndIdx == -1) {
          TODO("handle error case where there is an unmatched paren in the initializer")
        }
        val stopIdx = indexOfFirst {
          it.asPunct() == Punctuators.COMMA || it.asPunct() == Punctuators.SEMICOLON
        }
        eatUntil(stopIdx)
      }
      declarator.name()?.let { newIdentifier(it) }
      if (isEaten()) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_AFTER
          formatArgs("declarator")
          column(colPastTheEnd(0))
        }
        declaratorList.add(declarator)
        break
      }
      if (current().asPunct() == Punctuators.ASSIGN) {
        declaratorList.add(InitDeclarator(declarator, parseInitializer()))
      } else {
        declaratorList.add(declarator)
      }
      if (!isEaten() && current().asPunct() == Punctuators.COMMA) {
        // Expected case; there are chained `init-declarator`s
        eat()
        continue
      } else if (!isEaten() && current().asPunct() == Punctuators.SEMICOLON) {
        // Expected case; semi at the end of `declaration`
        eat()
        break
      } else {
        // Missing semicolon
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_AFTER
          formatArgs("declarator")
          column(colPastTheEnd(0))
        }
        break
      }
    }
    if (declaratorList.isEmpty()) parserDiagnostic {
      id = DiagnosticId.MISSING_DECLARATIONS
      errorOn(safeToken(0))
    }
    return declaratorList
  }
}
