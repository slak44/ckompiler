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

  /**
   * Parses the declarators in a struct (`struct-declarator-list`).
   *
   * C standard: 6.7.2.1
   * @return the list of [StructDeclarator]s
   */
  fun parseStructDeclaratorList(): List<StructDeclarator>
}

class DeclarationParser(scopeHandler: ScopeHandler, expressionParser: ExpressionParser) :
    IDeclarationParser,
    IDebugHandler by scopeHandler,
    ITokenHandler by expressionParser,
    IScopeHandler by scopeHandler,
    IParenMatcher by expressionParser,
    IExpressionParser by expressionParser {

  /**
   * There is a cyclic dependency between [DeclarationParser] and [SpecParser]. We resolve it using
   * this property ([SpecParser] doesn't maintain its own state).
   */
  internal lateinit var specParser: SpecParser

  override fun parseDeclaration(): Declaration? {
    val declSpec = specParser.parseDeclSpecifiers()
    if (declSpec.isEmpty()) return null
    val declRange = declSpec.range!!.start until safeToken(0).startIdx
    if (declSpec.canBeTag() && isNotEaten() && current().asPunct() == Punctuators.SEMICOLON) {
      // This is the case where there is a semicolon after the DeclarationSpecifiers
      eat()
      // FIXME: actually do something with the "tag"
      return Declaration(declSpec, emptyList()).withRange(declRange)
    }
    if (declSpec.canBeTag() && isEaten()) {
      return Declaration(declSpec, emptyList()).withRange(declRange)
    }
    return Declaration(declSpec, parseInitDeclaratorList()).withRange(declRange)
  }

  override fun preParseDeclarator(): Pair<DeclarationSpecifier, Declarator?> {
    val declSpec = specParser.parseDeclSpecifiers()
    if (declSpec.canBeTag() && isNotEaten() && current().asPunct() == Punctuators.SEMICOLON) {
      // This is the case where there is a semicolon after the DeclarationSpecifiers
      eat()
      // FIXME: actually do something with the "tag"
      return Pair(declSpec, null)
    }
    if (declSpec.canBeTag() && isEaten()) {
      return Pair(declSpec, null)
    }
    return Pair(declSpec, if (declSpec.isEmpty()) null else parseDeclarator(tokenCount))
  }

  override fun parseDeclaration(declSpec: DeclarationSpecifier,
                                declarator: Declarator): Declaration {
    val d = Declaration(declSpec, parseInitDeclaratorList(declarator))
    val start = if (declSpec.isEmpty()) safeToken(0).startIdx else declSpec.range!!.start
    return d.withRange(start until safeToken(0).startIdx)
  }

  private fun errorDecl() = ErrorDeclarator().withRange(rangeOne())

  /**
   * Parses the params in a function declaration (`parameter-type-list`).
   * Examples of what it parses:
   * ```
   * void f(int a, int x);
   *        ^^^^^^^^^^^^
   * void g();
   *        (here this function gets nothing to parse, and returns an empty list)
   * ```
   * C standard: A.2.2
   * @return the list of [ParameterDeclaration]s, and whether or not the function is variadic
   */
  private fun parseParameterList(endIdx: Int):
      Pair<List<ParameterDeclaration>, Boolean> = tokenContext(endIdx) {
    // No parameters; this is not an error case
    if (isEaten()) return@tokenContext Pair(emptyList(), false)
    var isVariadic = false
    val params = mutableListOf<ParameterDeclaration>()
    while (isNotEaten()) {
      // Variadic functions
      if (current().asPunct() == Punctuators.DOTS) {
        eat()
        isVariadic = true
        if (params.isEmpty()) {
          parserDiagnostic {
            id = DiagnosticId.PARAM_BEFORE_VARIADIC
            errorOn(safeToken(0))
          }
        }
        break
      }
      val specs = specParser.parseDeclSpecifiers()
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
      params += ParameterDeclaration(specs, declarator)
      // Add param name to current scope (which can be either block scope or
      // function prototype scope)
      declarator.name()?.let { name -> newIdentifier(name) }
      if (isNotEaten() && current().asPunct() == Punctuators.COMMA) {
        // Expected case; found comma that separates params
        eat()
      }
    }
    return@tokenContext Pair(params, isVariadic)
  }

  /**
   * This function parses a `declarator` nested in a `direct-declarator`.
   *
   * C standard: 6.7.6.1
   */
  private fun parseNestedDeclarator(): Declarator? {
    if (current().asPunct() != Punctuators.LPAREN) return null
    val end = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
    if (end == -1) return errorDecl()
    // If the declarator slice will be empty, error out
    if (end - 1 == 0) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_DECL
        errorOn(safeToken(1))
      }
      eatToSemi()
      return errorDecl()
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
      val lparen = safeToken(0)
      val rparenIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
      eat() // Get rid of "("
      // FIXME: we can return something better than an ErrorNode (have the ident)
      if (rparenIdx == -1) {
        // FIXME: maybe we should eat stuff here?
        errorDecl()
      } else scoped {
        val (paramList, variadic) = parseParameterList(rparenIdx)
        // Bad params, usually happens if there are other args after '...'
        if (current().asPunct() != Punctuators.RPAREN) {
          parserDiagnostic {
            id = DiagnosticId.UNMATCHED_PAREN
            formatArgs(Punctuators.RPAREN.s)
            errorOn(tokenAt(rparenIdx))
          }
          parserDiagnostic {
            id = DiagnosticId.MATCH_PAREN_TARGET
            formatArgs(Punctuators.LPAREN.s)
            errorOn(lparen)
          }
          eatUntil(rparenIdx)
        }
        eat() // Get rid of ")"
        FunctionDeclarator(
            declarator = primary, params = paramList, scope = this, variadic = variadic)
            .withRange(primary.tokenRange.start until tokenAt(rparenIdx).startIdx)
      }
    }
    current().asPunct() == Punctuators.LSQPAREN -> {
      val end = findParenMatch(Punctuators.LSQPAREN, Punctuators.RSQPAREN)
      if (end == -1) {
        errorDecl()
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
    val name = NameDeclarator(IdentifierNode(id.name).withRange(rangeOne())).withRange(rangeOne())
    eat() // The identifier token
    return name
  }

  /** C standard: A.2.2, 6.7 */
  private fun parseDirectDeclarator(endIdx: Int): Declarator = tokenContext(endIdx) {
    val primaryDecl = parseNameDeclarator() ?: parseNestedDeclarator()
    if (primaryDecl == null) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_IDENT_OR_PAREN
        errorOn(safeToken(0))
      }
      return@tokenContext errorDecl()
    }
    return@tokenContext parseDirectDeclaratorSuffixes(primaryDecl)
  }

  /** C standard: 6.7.6.1 */
  private fun parseDeclarator(endIdx: Int): Declarator = tokenContext(endIdx) {
    if (it.isEmpty()) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_IDENT_OR_PAREN
        column(colPastTheEnd(0))
      }
      return@tokenContext errorDecl()
    }
    // FIXME: missing pointer parsing
    val directDecl = parseDirectDeclarator(it.size)
    if (isNotEaten()) {
      // FIXME: this should likely be an error (it is way to noisy)
//      logger.warn { "parseDirectDeclarator did not eat all of its tokens" }
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
   * @return the list of comma-separated declarators
   */
  private fun parseInitDeclaratorList(firstDecl: Declarator? = null): List<Declarator> {
    // This is the case where there are no declarators left for this function
    if (isNotEaten() && current().asPunct() == Punctuators.SEMICOLON) {
      eat()
      firstDecl?.name()?.let { newIdentifier(it) }
      return listOfNotNull(firstDecl)
    }
    // FIXME typedef is to be handled specially, see 6.7.1 paragraph 5
    val declaratorList = mutableListOf<Declarator>()
    // If firstDecl is null, we act as if it was already processed
    var firstDeclUsed = firstDecl == null
    while (true) {
      val declarator = (if (firstDeclUsed) {
        parseDeclarator(tokenCount)
      } else {
        firstDeclUsed = true
        firstDecl!!
      })
      declarator.name()?.let { newIdentifier(it) }
      if (declarator is ErrorDeclarator) {
        val parenEndIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
        if (parenEndIdx == -1) {
          TODO("handle error case where there is an unmatched paren in the initializer")
        }
        val stopIdx = indexOfFirst {
          it.asPunct() == Punctuators.COMMA || it.asPunct() == Punctuators.SEMICOLON
        }
        if (stopIdx != -1) eatUntil(stopIdx)
      }
      if (isEaten()) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_AFTER
          formatArgs("declarator")
          column(colPastTheEnd(0))
        }
        declaratorList += declarator
        break
      }
      declaratorList += if (current().asPunct() == Punctuators.ASSIGN) {
        val d = InitDeclarator(declarator, parseInitializer())
        d.withRange(declarator.tokenRange between d.initializer.tokenRange)
        d
      } else {
        declarator
      }
      if (isNotEaten() && current().asPunct() == Punctuators.COMMA) {
        // Expected case; there are chained `init-declarator`s
        eat()
        continue
      } else if (isNotEaten() && current().asPunct() == Punctuators.SEMICOLON) {
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

  override fun parseStructDeclaratorList(): List<StructDeclarator> {
    val declaratorList = mutableListOf<StructDeclarator>()
    declLoop@ while (true) {
      val declarator = parseDeclarator(tokenCount)
      // FIXME: bitfield width decls without actual declarators are allowed, and should be handled
      if (declarator is ErrorDeclarator) {
        val parenEndIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
        if (parenEndIdx == -1) {
          TODO("handle error case where there is an unmatched paren in the decl")
        }
        val stopIdx = indexOfFirst {
          it.asPunct() == Punctuators.COMMA || it.asPunct() == Punctuators.SEMICOLON
        }
        eatUntil(stopIdx)
      }
      if (isEaten()) {
        parserDiagnostic {
          id = DiagnosticId.EXPECTED_SEMI_AFTER
          formatArgs("struct declarator list")
          column(colPastTheEnd(0))
        }
        declaratorList += StructDeclarator(declarator, null)
        break@declLoop
      }
      declaratorList += if (current().asPunct() == Punctuators.COLON) {
        // Has bit width
        eat()
        val stopIdx = indexOfFirst {
          it.asPunct() == Punctuators.COMMA || it.asPunct() == Punctuators.SEMICOLON
        }
        val bitWidthExpr = parseExpr(stopIdx)
        // FIXME: expr MUST be a constant expression
        StructDeclarator(declarator, bitWidthExpr)
      } else {
        // No bit width
        StructDeclarator(declarator, null)
      }
      when {
        current().asPunct() == Punctuators.COMMA -> {
          // Expected case; there are chained `struct-declarator`s
          eat()
          continue@declLoop
        }
        current().asPunct() == Punctuators.SEMICOLON -> {
          // Expected case; semi at the end of the `struct-declaration`
          eat()
          break@declLoop
        }
        else -> {
          // Missing semicolon
          parserDiagnostic {
            id = DiagnosticId.EXPECTED_SEMI_AFTER
            formatArgs("struct declarator list")
            column(colPastTheEnd(0))
          }
          break@declLoop
        }
      }
    }
    if (declaratorList.isEmpty()) parserDiagnostic {
      id = DiagnosticId.MISSING_DECLARATIONS
      errorOn(safeToken(0))
    }
    return declaratorList
  }
}
