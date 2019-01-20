package slak.ckompiler.parser

import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.*
import slak.ckompiler.throwICE
import java.util.*

interface IDeclarationParser {
  /**
   * Parses a declaration, including function declarations (prototypes).
   * @param validationRules extra checks to be performed on the [DeclarationSpecifier]
   * @return null if there is no declaration, or a [Declaration] otherwise
   */
  fun parseDeclaration(validationRules: SpecValidationRules): Declaration? {
    val (declSpec, firstDecl) = preParseDeclarator(validationRules)
    if (declSpec.isEmpty()) return null
    if (!firstDecl!!.isPresent) return null
    return parseDeclaration(declSpec, firstDecl.get())
  }

  /**
   * Parse a [DeclarationSpecifier] and the first [Declarator] that follows.
   * @param validationRules extra checks to be performed on the [DeclarationSpecifier]
   * @return if [DeclarationSpecifier.isEmpty] is true, the [Declarator] will be null, and if there
   * is no declarator, it will be [Optional.empty]
   */
  fun preParseDeclarator(
      validationRules: SpecValidationRules): Pair<DeclarationSpecifier, Optional<Declarator>?>

  /**
   * Parses a declaration where the [DeclarationSpecifier] and the first [Declarator] are already
   * parsed.
   * @see preParseDeclarator
   * @return the [Declaration]
   */
  fun parseDeclaration(declSpec: DeclarationSpecifier, declarator: Declarator?): Declaration

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

  override fun preParseDeclarator(
      validationRules: SpecValidationRules): Pair<DeclarationSpecifier, Optional<Declarator>?> {
    val declSpec = specParser.parseDeclSpecifiers(validationRules)
    if (isNotEaten() && current().asPunct() == Punctuators.SEMICOLON) {
      // This is the case where there is a semicolon after the DeclarationSpecifiers
      eat() // The ';'
      if (declSpec.canBeTag()) {
        // FIXME: actually do something with the "tag"
      } else {
        parserDiagnostic {
          id = DiagnosticId.MISSING_DECLARATIONS
          errorOn(safeToken(0))
        }
      }
      return Pair(declSpec, Optional.empty())
    }
    if (declSpec.canBeTag() && isEaten()) {
      return Pair(declSpec, Optional.empty())
    }
    val declarator = if (declSpec.isEmpty()) null else Optional.of(parseDeclarator(tokenCount))
    if (declarator != null && declarator.get() is FunctionDeclarator) {
      SpecValidationRules.FUNCTION_DECLARATION.validate(specParser, declSpec)
    }
    return Pair(declSpec, declarator)
  }

  override fun parseDeclaration(declSpec: DeclarationSpecifier,
                                declarator: Declarator?): Declaration {
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
   * void f(void);
   *        ^^^^
   * ```
   * C standard: A.2.2, 6.9.1.5
   * @return the list of [ParameterDeclaration]s, and whether or not the function is variadic
   */
  private fun parseParameterList(endIdx: Int):
      Pair<List<ParameterDeclaration>, Boolean> = tokenContext(endIdx) {
    // No parameters; this is not an error case
    if (isEaten()) return@tokenContext Pair(emptyList(), false)
    // The only thing between parens is "void" => no params
    if (it.size == 1 && it[0].asKeyword() == Keywords.VOID) {
      eat() // The 'void'
      return@tokenContext Pair(emptyList(), false)
    }
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
      val specs = specParser.parseDeclSpecifiers(SpecValidationRules.FUNCTION_PARAMETER)
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
      val paramEndIdx = if (commaIdx == -1) it.size else commaIdx
      val declarator = parseDeclarator(paramEndIdx)
      params += ParameterDeclaration(specs, declarator)
      // Add param name to current scope (which can be either block scope or
      // function prototype scope)
      declarator.name()?.let { name -> newIdentifier(name) }
      // Initializers are not allowed here, so catch them and error
      if (isNotEaten() && current().asPunct() == Punctuators.ASSIGN) {
        eat() // The '='
        val expr = parseExpr(paramEndIdx)
        parserDiagnostic {
          id = DiagnosticId.NO_DEFAULT_ARGS
          columns(expr?.tokenRange ?: rangeOne())
        }
      }
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

  /**
   * Parses pointers and their `type-qualifier`s.
   *
   * C standard: 6.7.6.1
   * @return a list, whose length is equal to the level of indirection in the declarator, and where
   * every element is the `type-qualifier-list` associated to that specific level of indirection
   */
  private fun parsePointer(endIdx: Int): List<TypeQualifierList> = tokenContext(endIdx) {
    if (isEaten() || current().asPunct() != Punctuators.STAR) return@tokenContext emptyList()
    val indirectionList = mutableListOf<List<Keyword>>()
    var currentIdx = 0
    while (isNotEaten() && current().asPunct() == Punctuators.STAR) {
      eat() // The '*'
      val qualsEnd = indexOfFirst { k -> k.asKeyword() !in SpecParser.typeQualifiers }
      // No type qualifiers
      if (qualsEnd == -1) continue
      // Get the quals as a list and add them to the big list
      // The first thing is the *, so drop that
      indirectionList += it.slice(currentIdx until qualsEnd).drop(1).map { k -> k as Keyword }
      eatUntil(qualsEnd)
      currentIdx = qualsEnd
    }
    return@tokenContext indirectionList
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
    val pointers = parsePointer(it.size)
    if (isEaten()) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_IDENT_OR_PAREN
        column(colPastTheEnd(0))
      }
      return@tokenContext errorDecl()
    }
    val directDecl = parseDirectDeclarator(it.size)
    directDecl.setIndirection(pointers)
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
      return errorExpr()
    }
    // Parse initializer-list
    if (current().asPunct() == Punctuators.LBRACKET) {
      TODO("parse initializer-list (A.2.2/6.7.9)")
    }
    // Simple expression
    // parseExpr should print out the diagnostic in case there is no expr here
    return parseExpr(tokenCount) ?: errorExpr()
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
    // FIXME typedef is to be handled specially, see 6.7.1.5
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
        // FIXME: bit field type must be _Bool, signed int, unsigned int (6.7.2.1.5)
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
    return declaratorList
  }
}
