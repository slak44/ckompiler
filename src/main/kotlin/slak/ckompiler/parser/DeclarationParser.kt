package slak.ckompiler.parser

import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler
import slak.ckompiler.lexer.*
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
    if (!firstDecl!!.isPresent) return Declaration(declSpec, emptyList())
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
    ILexicalTokenHandler by expressionParser,
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
    if (declSpec.isTag() && !(declSpec.typeSpec as TagSpecifier).isAnonymous) {
      createTag(declSpec.typeSpec)
    }
    if (isNotEaten() && current().asPunct() == Punctuators.SEMICOLON) {
      // This is the case where there is a semicolon after the DeclarationSpecifiers
      eat() // The ';'
      when {
        declSpec.isTag() -> { /* Do nothing intentionally */ }
        declSpec.isTypedef() -> diagnostic {
          id = DiagnosticId.TYPEDEF_REQUIRES_NAME
          columns(declSpec.range!!)
        }
        else -> diagnostic {
          id = DiagnosticId.MISSING_DECLARATIONS
          columns(declSpec.range!!)
        }
      }
      return declSpec to Optional.empty()
    }
    if (declSpec.isTag() && isEaten()) return declSpec to Optional.empty()
    val declarator = if (declSpec.isEmpty()) null else Optional.of(parseDeclarator(tokenCount))
    if (declarator != null && declarator.get() is FunctionDeclarator) {
      SpecValidationRules.FUNCTION_DECLARATION.validate(specParser, declSpec)
    }
    return declSpec to declarator
  }

  override fun parseDeclaration(declSpec: DeclarationSpecifier,
                                declarator: Declarator?): Declaration {
    val d = Declaration(declSpec, parseInitDeclaratorList(declSpec, declarator))
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
   * @return the [ParameterTypeList]
   */
  private fun parseParameterList(endIdx: Int): ParameterTypeList = tokenContext(endIdx) {
    // No parameters; this is not an error case
    if (isEaten()) return@tokenContext ParameterTypeList(emptyList(), false)
    // The only thing between parens is "void" => no params
    if (it.size == 1 && it[0].asKeyword() == Keywords.VOID) {
      eat() // The 'void'
      return@tokenContext ParameterTypeList(emptyList(), false)
    }
    var isVariadic = false
    val params = mutableListOf<ParameterDeclaration>()
    while (isNotEaten()) {
      // Variadic functions
      if (current().asPunct() == Punctuators.DOTS) {
        eat() // The '...'
        isVariadic = true
        if (params.isEmpty()) {
          diagnostic {
            id = DiagnosticId.PARAM_BEFORE_VARIADIC
            errorOn(safeToken(0))
          }
        }
        // There can be nothing after the variadic dots
        if (isNotEaten()) {
          diagnostic {
            id = DiagnosticId.EXPECTED_RPAREN_AFTER_VARIADIC
            errorOn(safeToken(0))
          }
          // Eat the ", other_stuff" inside the function call: `f(stuff, ..., other_stuff)`
          while (isNotEaten()) eat()
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
      val commaIdx = indexOfFirst(Punctuators.COMMA)
      val paramEndIdx = if (commaIdx == -1) it.size else commaIdx
      val declarator = parseDeclarator(paramEndIdx)
      params += ParameterDeclaration(specs, declarator)
      // Add param name to current scope (which can be either block scope or
      // function prototype scope)
      declarator.name()?.let { name -> newIdentifier(name) }
      // Initializers are not allowed here, so catch them and error
      if (isNotEaten() && current().asPunct() == Punctuators.ASSIGN) {
        val assignTok = current()
        val initializer = parseInitializer(paramEndIdx)
        diagnostic {
          id = DiagnosticId.NO_DEFAULT_ARGS
          columns(assignTok..initializer)
        }
      }
      if (isNotEaten() && current().asPunct() == Punctuators.COMMA) {
        // Expected case; found comma that separates params
        eat()
      }
    }
    return@tokenContext ParameterTypeList(params, isVariadic)
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
      diagnostic {
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

  /** C standard: 6.7.6.2 */
  private fun parseArrayTypeSize(endIdx: Int): ArrayTypeSize = tokenContext(endIdx) {
    if (it.isEmpty()) return@tokenContext NoSize
    fun parseTypeQualifierList(startIdx: Int): TypeQualifierList {
      val quals = it.drop(startIdx).takeWhile { k -> k.asKeyword() in SpecParser.typeQualifiers }
      eatUntil(startIdx + quals.size)
      return quals.map { k -> k as Keyword }
    }
    fun qualsAndStatic(quals: TypeQualifierList, staticKw: LexicalToken): FunctionParameterSize {
      if (isEaten()) {
        diagnostic {
          id = DiagnosticId.ARRAY_STATIC_NO_SIZE
          errorOn(staticKw)
        }
        return FunctionParameterSize(quals, true, errorExpr())
      }
      return FunctionParameterSize(quals, true, parseExpr(it.size) ?: errorExpr())
    }
    if (current().asKeyword() == Keywords.STATIC) {
      val staticKw = current()
      eat() // The 'static' keyword
      val quals = parseTypeQualifierList(1)
      return@tokenContext qualsAndStatic(quals, staticKw)
    }
    val quals = parseTypeQualifierList(0)
    if (current().asKeyword() == Keywords.STATIC) {
      val staticKw = current()
      eat() // The 'static' keyword
      return@tokenContext qualsAndStatic(quals, staticKw)
    }
    if (current().asPunct() == Punctuators.STAR) {
      val star = current()
      eat() // The VLA '*'
      diagnostic {
        id = DiagnosticId.UNSUPPORTED_VLA
        errorOn(star)
      }
      return@tokenContext UnconfinedVariableSize(quals, star as Punctuator)
    }
    if (isEaten()) {
      return@tokenContext FunctionParameterSize(quals, false, null)
    }
    val expr = parseExpr(it.size) ?: errorExpr()
    if (quals.isEmpty()) {
      return@tokenContext ExpressionSize(expr)
    } else {
      return@tokenContext FunctionParameterSize(quals, false, expr)
    }
  }

  /**
   * This function parses the things that can come after a `direct-declarator`, but only one.
   * For example, in `int v[4][5];` the `[4]` and the `[5]` will be parsed one at a time.
   *
   * C standard: 6.7.6.1, A.2.2
   * @return the suffix with the declarator, or null if there is no suffix
   * @see parseArrayTypeSize
   * @see parseParameterList
   */
  private fun parseDirectDeclaratorSuffix(primary: Declarator): Declarator? = when {
    isEaten() -> null
    current().asPunct() == Punctuators.LPAREN -> {
      val lParen = safeToken(0)
      val rParenIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
      eat() // Get rid of "("
      if (rParenIdx == -1) {
        eatToSemi()
        errorDecl()
      } else scoped {
        val ptl = parseParameterList(rParenIdx)
        // Bad params, usually happens if there are other args after '...'
        if (current().asPunct() != Punctuators.RPAREN) {
          diagnostic {
            id = DiagnosticId.UNMATCHED_PAREN
            formatArgs(Punctuators.RPAREN.s)
            errorOn(tokenAt(rParenIdx))
          }
          diagnostic {
            id = DiagnosticId.MATCH_PAREN_TARGET
            formatArgs(Punctuators.LPAREN.s)
            errorOn(lParen)
          }
          eatUntil(rParenIdx)
        }
        eat() // Get rid of ")"
        FunctionDeclarator(declarator = primary, ptl = ptl, scope = this)
            .withRange(primary.tokenRange.start until tokenAt(rParenIdx).startIdx)
      }
    }
    current().asPunct() == Punctuators.LSQPAREN -> {
      val lSqParen = safeToken(0)
      val rSqParenIdx = findParenMatch(Punctuators.LSQPAREN, Punctuators.RSQPAREN)
      eat() // The '['
      if (rSqParenIdx == -1) {
        eatToSemi()
        errorDecl()
      } else {
        val arraySize = parseArrayTypeSize(rSqParenIdx)
        // Extraneous stuff in array size
        if (current().asPunct() != Punctuators.RSQPAREN) {
          diagnostic {
            id = DiagnosticId.UNMATCHED_PAREN
            formatArgs(Punctuators.RSQPAREN.s)
            errorOn(tokenAt(rSqParenIdx))
          }
          diagnostic {
            id = DiagnosticId.MATCH_PAREN_TARGET
            formatArgs(Punctuators.LSQPAREN.s)
            errorOn(lSqParen)
          }
          eatUntil(rSqParenIdx)
        }
        eat() // Eat ']'
        // FIXME: not all array sizes are allowed everywhere
        ArrayDeclarator(primary, arraySize)
      }
    }
    else -> null
  }

  /** C standard: 6.7.6.1 */
  private fun parseNameDeclarator(): NameDeclarator? {
    val id = current() as? Identifier ?: return null
    val name = NameDeclarator.from(id)
    eat() // The identifier token
    return name
  }

  /** C standard: A.2.2, 6.7 */
  private fun parseDirectDeclarator(endIdx: Int): Declarator = tokenContext(endIdx) {
    val primaryDecl = parseNameDeclarator() ?: parseNestedDeclarator()
    if (primaryDecl == null) {
      diagnostic {
        id = DiagnosticId.EXPECTED_IDENT_OR_PAREN
        errorOn(safeToken(0))
      }
      return@tokenContext errorDecl()
    }
    var declarator: Declarator = primaryDecl
    while (isNotEaten()) {
      declarator = parseDirectDeclaratorSuffix(declarator) ?: break
    }
    return@tokenContext declarator
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
    val indirectionList = mutableListOf<TypeQualifierList>()
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
      diagnostic {
        id = DiagnosticId.EXPECTED_IDENT_OR_PAREN
        column(colPastTheEnd(0))
      }
      return@tokenContext errorDecl()
    }
    val pointers = parsePointer(it.size)
    if (isEaten()) {
      diagnostic {
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
  private fun parseInitializer(endIdx: Int): Expression {
    eat() // Get rid of "="
    // Error case, no initializer here
    if (current().asPunct() == Punctuators.COMMA || current().asPunct() == Punctuators.SEMICOLON) {
      diagnostic {
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
    return parseExpr(endIdx) ?: errorExpr()
  }

  /**
   * Parse a `init-declarator-list`, a part of a `declaration`.
   *
   * C standard: A.2.2
   * @param ds the declaration's [DeclarationSpecifier]
   * @param firstDecl if not null, this will be treated as the first declarator in the list that was
   * pre-parsed
   * @return the list of comma-separated declarators
   */
  private fun parseInitDeclaratorList(ds: DeclarationSpecifier,
                                      firstDecl: Declarator? = null): List<Declarator> {
    fun addDeclaratorToScope(declarator: Declarator?) {
      if (declarator == null) return
      if (ds.isTypedef()) {
        // Add typedef to scope
        createTypedef(TypedefName(ds, declarator.indirection, declarator.name()!!))
      } else {
        // Add ident to scope
        declarator.name()?.let { newIdentifier(it) }
      }
    }
    // This is the case where there are no declarators left for this function
    if (isNotEaten() && current().asPunct() == Punctuators.SEMICOLON) {
      eat()
      addDeclaratorToScope(firstDecl)
      return listOfNotNull(firstDecl)
    }
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
      if (declarator is ErrorDeclarator) {
        val parenEndIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
        if (parenEndIdx == -1) {
          TODO("handle error case where there is an unmatched paren in the initializer")
        }
        val stopIdx = indexOfFirst(Punctuators.COMMA, Punctuators.SEMICOLON)
        if (stopIdx != -1) eatUntil(stopIdx)
      }
      addDeclaratorToScope(declarator)
      if (isEaten()) {
        diagnostic {
          id = DiagnosticId.EXPECTED_SEMI_AFTER
          formatArgs("declarator")
          column(colPastTheEnd(0))
        }
        declaratorList += declarator
        break
      }
      declaratorList += if (current().asPunct() == Punctuators.ASSIGN) {
        val assignTok = current()
        val initializer = parseInitializer(tokenCount)
        if (ds.isTypedef()) {
          diagnostic {
            id = DiagnosticId.TYPEDEF_NO_INITIALIZER
            columns(assignTok..initializer)
          }
          declarator
        } else {
          InitDeclarator(declarator, initializer).withRange(declarator..initializer)
        }
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
        diagnostic {
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
        val stopIdx = indexOfFirst(Punctuators.COMMA, Punctuators.SEMICOLON)
        eatUntil(stopIdx)
      }
      if (isEaten()) {
        diagnostic {
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
        val stopIdx = indexOfFirst(Punctuators.COMMA, Punctuators.SEMICOLON)
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
          diagnostic {
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
