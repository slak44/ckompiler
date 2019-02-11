package slak.ckompiler.parser

import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler
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
   * @return the list of [StructMember]s
   */
  fun parseStructDeclaratorList(): List<StructMember>
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
        declSpec.isTag() -> { /* Do nothing intentionally */
        }
        declSpec.isTypedef() -> diagnostic {
          id = DiagnosticId.TYPEDEF_REQUIRES_NAME
          columns(declSpec.tokenRange)
        }
        else -> diagnostic {
          id = DiagnosticId.MISSING_DECLARATIONS
          columns(declSpec.tokenRange)
        }
      }
      return declSpec to Optional.empty()
    }
    if (declSpec.isTag() && isEaten()) return declSpec to Optional.empty()
    val declarator = if (declSpec.isEmpty()) null else Optional.of(parseNamedDeclarator(tokenCount))
    if (declarator != null && declarator.get().isFunction()) {
      SpecValidationRules.FUNCTION_DECLARATION.validate(specParser, declSpec)
    }
    return declSpec to declarator
  }

  override fun parseDeclaration(declSpec: DeclarationSpecifier,
                                declarator: Declarator?): Declaration {
    val d = Declaration(declSpec, parseInitDeclaratorList(declSpec, declarator))
    val start = if (declSpec.isEmpty()) safeToken(0).startIdx else declSpec.tokenRange.start
    return d.withRange(start until safeToken(0).startIdx)
  }

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
    if (isEaten()) return@tokenContext ParameterTypeList(emptyList())
    // The only thing between parens is "void" => no params
    if (it.size == 1 && it[0].asKeyword() == Keywords.VOID) {
      eat() // The 'void'
      return@tokenContext ParameterTypeList(emptyList())
    }
    var isVariadic = false
    val params = mutableListOf<ParameterDeclaration>()
    val scope = LexicalScope()
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
      // Once found, parseNamedDeclarator handles parsing the param and eating it
      val parenEndIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
      if (parenEndIdx == -1) {
        TODO("handle error case where there is an unmatched paren in the parameter list")
      }
      val commaIdx = indexOfFirst(Punctuators.COMMA)
      val paramEndIdx = if (commaIdx == -1) it.size else commaIdx
      // FIXME: abstract declarator allowed here
      val declarator = parseNamedDeclarator(paramEndIdx)
      params += ParameterDeclaration(specs, declarator)
      // Add param name to current scope (which can be either block scope or
      // function prototype scope)
      scope.withScope {
        newIdentifier(TypedIdentifier(declarator.name.name, typeNameOf(specs, declarator))
            .withRange(declarator.name.tokenRange))
      }
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
    return@tokenContext ParameterTypeList(params, scope, isVariadic)
  }

  /**
   * This function parses a `declarator` nested in a `direct-declarator`, or an
   * `abstract-declarator` nested in a `direct-abstract-declarator`.
   *
   * This function produces a diagnostic if the nested context is empty.
   *
   * C standard: 6.7.6.0.1
   * @return a [NamedDeclarator], [ErrorDeclarator] on error, null if there is no nesting
   */
  private inline fun <reified T : Declarator> parseNestedDeclarator(): Declarator? {
    if (current().asPunct() != Punctuators.LPAREN) return null
    val end = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
    if (end == -1) return error<ErrorDeclarator>()
    // If the declarator slice will be empty, error out
    if (end - 1 == 0) {
      diagnostic {
        id = DiagnosticId.EXPECTED_DECL
        errorOn(safeToken(1))
      }
      eatToSemi()
      return error<ErrorDeclarator>()
    }
    eat() // The '('
    val declarator = when (T::class) {
      NamedDeclarator::class -> parseNamedDeclarator(end)
      AbstractDeclarator::class -> parseAbstractDeclarator(end)
      else -> logger.throwICE("Bad declarator type for nested declarators") { "T: ${T::class}" }
    }
    if (declarator is ErrorNode) eatToSemi()
    else eat() // The ')'
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
        return FunctionParameterSize(quals, true, error<ErrorExpression>())
      }
      return FunctionParameterSize(quals, true, parseExpr(it.size) ?: error<ErrorExpression>())
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
    val expr = parseExpr(it.size) ?: error<ErrorExpression>()
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
   * C standard: 6.7.6.0.1, A.2.2
   * @return the [DeclaratorSuffix], or null if there is no suffix
   * @see parseArrayTypeSize
   * @see parseParameterList
   */
  private fun parseDirectDeclaratorSuffix(): DeclaratorSuffix? = when {
    isEaten() -> null
    current().asPunct() == Punctuators.LPAREN -> {
      val lParen = safeToken(0)
      val rParenIdx = findParenMatch(Punctuators.LPAREN, Punctuators.RPAREN)
      eat() // Get rid of "("
      if (rParenIdx == -1) {
        eatToSemi()
        error<ErrorSuffix>()
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
        ptl.withRange(lParen..tokenAt(rParenIdx))
      }
    }
    current().asPunct() == Punctuators.LSQPAREN -> {
      val lSqParen = safeToken(0)
      val rSqParenIdx = findParenMatch(Punctuators.LSQPAREN, Punctuators.RSQPAREN)
      eat() // The '['
      if (rSqParenIdx == -1) {
        eatToSemi()
        error<ErrorSuffix>()
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
        arraySize.withRange(lSqParen..tokenAt(rSqParenIdx))
      }
    }
    else -> null
  }

  /** C standard: A.2.2, 6.7 */
  private fun parseSuffixes(endIdx: Int): List<DeclaratorSuffix> = tokenContext(endIdx) {
    val suffixes = mutableListOf<DeclaratorSuffix>()
    while (isNotEaten()) {
      suffixes += parseDirectDeclaratorSuffix() ?: break
    }
    return@tokenContext suffixes
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

  /** C standard: 6.7.6.0.1 */
  private fun parseNamedDeclarator(endIdx: Int): Declarator = tokenContext(endIdx) {
    fun emitDiagnostic(data: Any): ErrorDeclarator {
      diagnostic {
        id = DiagnosticId.EXPECTED_IDENT_OR_PAREN
        diagData(data)
      }
      return error()
    }
    if (it.isEmpty()) return@tokenContext emitDiagnostic(colPastTheEnd(0))
    val pointers = parsePointer(it.size)
    if (isEaten()) return@tokenContext emitDiagnostic(colPastTheEnd(0))
    val nested = parseNestedDeclarator<NamedDeclarator>()
    if (nested == null) {
      val nameTok = current() as? Identifier ?: return@tokenContext emitDiagnostic(safeToken(0))
      eat() // The identifier token
      val name = IdentifierNode.from(nameTok)
      return@tokenContext NamedDeclarator(name, pointers, parseSuffixes(it.size))
    }
    if (nested is ErrorNode) return@tokenContext error<ErrorDeclarator>()
    nested as NamedDeclarator
    return@tokenContext NamedDeclarator(nested.name,
        pointers + nested.indirection, nested.suffixes + parseSuffixes(it.size))
  }

  /** C standard: 6.7.7.0.1 */
  private fun parseAbstractDeclarator(endIdx: Int): Declarator = tokenContext(endIdx) {
    // This function allows an "empty" abstract declarator, because `type-name` contains an
    // optional `abstract-declarator`, and that is the primary (only?) use case for this function
    if (it.isEmpty()) return@tokenContext AbstractDeclarator(emptyList(), emptyList())
    val pointers = parsePointer(it.size)
    // Some pointers and nothing else is a valid abstract declarator
    if (isEaten()) return@tokenContext AbstractDeclarator(pointers, emptyList())
    if (current().asPunct() == Punctuators.LPAREN && relative(1).asPunct() == Punctuators.RPAREN) {
      return@tokenContext AbstractDeclarator(pointers, parseSuffixes(it.size))
    }
    val nested = parseNestedDeclarator<AbstractDeclarator>()
    // If it isn't nested stuff, it's probably suffixes
    if (nested == null || nested is ErrorNode) {
      return@tokenContext AbstractDeclarator(pointers, parseSuffixes(it.size))
    }
    nested as AbstractDeclarator
    return@tokenContext AbstractDeclarator(
        pointers + nested.indirection, nested.suffixes + parseSuffixes(it.size))
  }

  // FIXME: return type will change with the initializer list
  private fun parseInitializer(endIdx: Int): ExpressionInitializer {
    eat() // Get rid of "="
    // Error case, no initializer here
    if (current().asPunct() == Punctuators.COMMA || current().asPunct() == Punctuators.SEMICOLON) {
      diagnostic {
        id = DiagnosticId.EXPECTED_EXPR
        errorOn(safeToken(0))
      }
      return ExpressionInitializer.from(error<ErrorExpression>())
    }
    // Parse initializer-list
    if (current().asPunct() == Punctuators.LBRACKET) {
      TODO("parse initializer-list (A.2.2/6.7.9)")
    }
    // Simple expression
    // parseExpr should print out the diagnostic in case there is no expr here
    return ExpressionInitializer.from(parseExpr(endIdx) ?: error<ErrorExpression>())
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
  private fun parseInitDeclaratorList(ds: DeclarationSpecifier, firstDecl: Declarator? = null):
      List<Pair<Declarator, Initializer?>> {
    fun addDeclaratorToScope(declarator: Declarator?) {
      if (declarator == null) return
      if (declarator is ErrorNode) return
      if (ds.isTypedef()) {
        // Add typedef to scope
        newIdentifier(TypedefName(ds, declarator.indirection, declarator.name))
      } else {
        // Add ident to scope
        newIdentifier(TypedIdentifier(declarator.name.name, typeNameOf(ds, declarator))
            .withRange(declarator.name.tokenRange))
      }
    }
    // This is the case where there are no declarators left for this function
    if (isNotEaten() && current().asPunct() == Punctuators.SEMICOLON) {
      eat()
      addDeclaratorToScope(firstDecl)
      return listOfNotNull(firstDecl).map { it to null }
    }
    val declaratorList = mutableListOf<Pair<Declarator, Initializer?>>()
    // If firstDecl is null, we act as if it was already processed
    var firstDeclUsed = firstDecl == null
    while (true) {
      val declarator = (if (firstDeclUsed) {
        parseNamedDeclarator(tokenCount)
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
        declaratorList += declarator to null
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
          declarator to null
        } else {
          declarator to initializer
        }
      } else {
        declarator to null
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

  override fun parseStructDeclaratorList(): List<StructMember> {
    val declaratorList = mutableListOf<StructMember>()
    declLoop@ while (true) {
      val declarator = parseNamedDeclarator(tokenCount)
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
        declaratorList += StructMember(declarator, null)
        break@declLoop
      }
      declaratorList += if (current().asPunct() == Punctuators.COLON) {
        // Has bit width
        eat()
        val stopIdx = indexOfFirst(Punctuators.COMMA, Punctuators.SEMICOLON)
        val bitWidthExpr = parseExpr(stopIdx)
        // FIXME: bit field type must be _Bool, signed int, unsigned int (6.7.2.1.5)
        // FIXME: expr MUST be a constant expression
        StructMember(declarator, bitWidthExpr)
      } else {
        // No bit width
        StructMember(declarator, null)
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
