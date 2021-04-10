package slak.ckompiler.parser

import org.apache.logging.log4j.LogManager
import slak.ckompiler.*
import slak.ckompiler.lexer.*

interface TypeNameParser {
  /**
   * Parse a `type-name`.
   *
   * C standard: 6.7.7
   * @return null if no type name was found, the [TypeName] otherwise
   */
  fun parseTypeName(endIdx: Int): TypeName?
}

interface IDeclaratorParser : TypeNameParser {
  /**
   * Parses the declarators in a struct (`struct-declarator-list`).
   *
   * C standard: 6.7.2.1
   * @return the list of [StructMember]s
   */
  fun parseStructDeclaratorList(): List<StructMember>
}

/**
 * This class was created as a supertype for [DeclarationParser], but it _is_ a concrete class
 * by itself. Useful for avoiding [DeclarationParser] if it isn't needed.
 */
open class DeclaratorParser(parenMatcher: ParenMatcher, scopeHandler: ScopeHandler) :
    IDeclaratorParser,
    IDebugHandler by parenMatcher,
    ITokenHandler by parenMatcher,
    IParenMatcher by parenMatcher,
    IScopeHandler by scopeHandler {

  /**
   * There is a cyclic dependency between [DeclaratorParser] and [SpecParser]. We resolve it using
   * this property ([SpecParser] doesn't maintain its own state).
   */
  internal lateinit var specParser: SpecParser

  /** @see specParser */
  internal lateinit var expressionParser: ExpressionParser

  /** @see expressionParser */
  internal lateinit var constExprParser: ConstantExprParser

  override fun parseTypeName(endIdx: Int): TypeName? = tokenContext(endIdx) {
    val declSpec = specParser.parseDeclSpecifiers(SpecValidationRules.SPECIFIER_QUALIFIER)
    if (declSpec.isBlank()) return@tokenContext null
    val declarator = parseAbstractDeclarator(it.size, false)
    return@tokenContext typeNameOf(declSpec, declarator)
  }

  /**
   * This function allows an "empty" abstract declarator, because `type-name` contains an optional
   * `abstract-declarator`, and that is the primary use case for this function.
   *
   * Pass true to [allowName] to permit parsing an identifier as well, if it appears (will behave
   * like [parseNamedDeclarator], but with no errors if there is no such identifier).
   *
   * If [Declarator.isBlank] is true, the [ASTNode.range] is not attached.
   *
   * C standard: 6.7.7.0.1
   */
  private fun parseAbstractDeclarator(
      endIdx: Int,
      allowName: Boolean
  ): Declarator = tokenContext(endIdx) {
    if (it.isEmpty()) return@tokenContext AbstractDeclarator.blank()
    val startTok = current()
    val pointers = parsePointer(it.size)
    // Some pointers and nothing else is a valid abstract declarator
    if (isEaten()) {
      return@tokenContext AbstractDeclarator.base(pointers, emptyList())
          .withRange(startTok..safeToken(0))
    }
    // This fast path is here because parseNestedDeclarator thinks "()" is an error, and it isn't
    if (
      tokensLeft >= 2 &&
      current().asPunct() == Punctuators.LPAREN &&
      relative(1).asPunct() == Punctuators.RPAREN
    ) {
      return@tokenContext AbstractDeclarator.base(pointers, parseSuffixes(it.size))
          .withRange(startTok..safeToken(0))
    }
    val nested = parseNestedDeclarator<AbstractDeclarator>(allowName)
    // If it isn't nested stuff, it's probably suffixes
    if (nested == null || nested is ErrorNode) {
      if (allowName && isNotEaten() && current() is Identifier) {
        val name = IdentifierNode.from(current())
        eat() // The identifier token
        return@tokenContext NamedDeclarator.base(name, pointers, parseSuffixes(it.size))
            .withRange(startTok..safeToken(0))
      }
      return@tokenContext AbstractDeclarator.base(pointers, parseSuffixes(it.size))
          .withRange(startTok..safeToken(0))
    }
    val ind = nested.indirection + listOf(pointers)
    val suf = nested.suffixes + listOf(parseSuffixes(it.size))
    return@tokenContext if (allowName && nested is NamedDeclarator) {
      NamedDeclarator(nested.name, ind, suf)
    } else {
      AbstractDeclarator(ind, suf)
    }.withRange(startTok..safeToken(0))
  }

  /**
   * See `README.md` for more info.
   *
   * C standard: 6.7.6.0.1
   */
  protected fun parseNamedDeclarator(endIdx: Int): Declarator = tokenContext(endIdx) {
    fun emitDiagnostic(data: Any): ErrorDeclarator {
      diagnostic {
        id = DiagnosticId.EXPECTED_IDENT_OR_PAREN
        diagData(data)
      }
      return error()
    }
    if (it.isEmpty()) return@tokenContext emitDiagnostic(colPastTheEnd(0))
    val declStartTok = safeToken(0)
    val pointers = parsePointer(it.size)
    if (isEaten()) return@tokenContext emitDiagnostic(colPastTheEnd(0))
    val nested = parseNestedDeclarator<NamedDeclarator>(false)
    if (nested == null) {
      val nameTok = current() as? Identifier ?: return@tokenContext emitDiagnostic(safeToken(0))
      eat() // The identifier token
      val name = IdentifierNode.from(nameTok)
      return@tokenContext NamedDeclarator.base(name, pointers, parseSuffixes(it.size)).also { d ->
        val lastTok = d.suffixes.lastOrNull()?.lastOrNull() ?: nameTok
        d.withRange(declStartTok..lastTok)
      }
    }
    if (nested is ErrorNode) return@tokenContext error<ErrorDeclarator>()
    nested as NamedDeclarator
    val ind = nested.indirection + listOf(pointers)
    val suf = nested.suffixes + listOf(parseSuffixes(it.size))
    return@tokenContext NamedDeclarator(nested.name, ind, suf).also { d ->
      val lastTok = d.suffixes.lastOrNull()?.lastOrNull() ?: nested
      d.withRange(declStartTok..lastTok)
    }
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
  private inline fun <reified T : Declarator> parseNestedDeclarator(
      allowName: Boolean
  ): Declarator? {
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
      AbstractDeclarator::class -> parseAbstractDeclarator(end, allowName)
      else -> logger.throwICE("Bad declarator type for nested declarators") { "T: ${T::class}" }
    }
    if (declarator is ErrorNode) eatToSemi()
    else eat() // The ')'
    return declarator
  }

  /**
   * Parses pointers and their `type-qualifier`s.
   *
   * C standard: 6.7.6.1
   * @return a list, whose length is equal to the level of indirection in the declarator, and where
   * every element is the `type-qualifier-list` associated to that specific level of indirection
   */
  private fun parsePointer(endIdx: Int): Indirection = tokenContext(endIdx) {
    if (isEaten() || current().asPunct() != Punctuators.STAR) return@tokenContext emptyList()
    val indirectionList = mutableListOf<TypeQualifierList>()
    var currentIdx = 0
    while (isNotEaten() && current().asPunct() == Punctuators.STAR) {
      eat() // The '*'
      val qualsEnd = indexOfFirst { k -> k.asKeyword() !in SpecParser.typeQualifiers }
      val currentIndirectionEnd = if (qualsEnd == -1) tokenCount else qualsEnd
      // Get the quals as a list and add them to the big list
      // The first thing is the *, so drop that
      indirectionList += it
          .slice(currentIdx until currentIndirectionEnd)
          .drop(1)
          .map { k -> k as Keyword }
      eatUntil(currentIndirectionEnd)
      currentIdx = currentIndirectionEnd
      if (qualsEnd == -1) break
    }
    return@tokenContext indirectionList
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
    if (isEaten()) return@tokenContext ParameterTypeList(emptyList(), newScope())
    // The only thing between parens is "void" => no params
    if (it.size == 1 && it[0].asKeyword() == Keywords.VOID) {
      eat() // The 'void'
      return@tokenContext ParameterTypeList(emptyList(), newScope())
    }
    var isVariadic = false
    val params = mutableListOf<ParameterDeclaration>()
    val scope = newScope()
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
      // First thing is comma, complain
      if (current().asPunct() == Punctuators.COMMA) {
        eat() // The ','
        diagnostic {
          id = DiagnosticId.EXPECTED_PARAM_DECL
          errorOn(safeToken(0))
        }
        continue
      }
      val specs = specParser.parseDeclSpecifiers(SpecValidationRules.FUNCTION_PARAMETER)
      if (specs.isBlank()) {
        TODO("possible unimplemented grammar (old-style K&R functions?)")
      }
      val paramEndIdx = firstOutsideParens(
          Punctuators.COMMA, Punctuators.LPAREN, Punctuators.RPAREN, stopAtSemi = false
      )
      val declarator = parseAbstractDeclarator(paramEndIdx, true)
      if (declarator.isBlank()) declarator.withRange(specs..tokenAt(paramEndIdx - 1))
      params += ParameterDeclaration(specs, declarator).withRange(declarator)
      // Add param name to current scope (which can be either block scope or
      // function prototype scope). Ignore unnamed parameters.
      if (declarator is NamedDeclarator) scope.withScope {
        newIdentifier(TypedIdentifier(specs, declarator))
      }
      // Initializers are not allowed here, so catch them and error
      if (isNotEaten() && current().asPunct() == Punctuators.ASSIGN) {
        val assignTok = current() as Punctuator
        eat() // Get rid of "="
        val initializer = parseInitializer(assignTok, ErrorType, paramEndIdx)
        diagnostic {
          id = DiagnosticId.NO_DEFAULT_ARGS
          errorOn(assignTok..initializer)
        }
      }
      if (isNotEaten() && current().asPunct() == Punctuators.COMMA) {
        // Expected case; found comma that separates params
        eat()
        if (isNotEaten()) continue
        // Found comma, then list ended; complain
        diagnostic {
          id = DiagnosticId.EXPECTED_PARAM_DECL
          errorOn(safeToken(0))
        }
        break
      }
    }
    return@tokenContext ParameterTypeList(params, scope, isVariadic)
  }

  private fun indexOfDesignatorInTag(type: TagType, designator: DotDesignator): Int {
    return type.members!!.indexOfFirst { it.first.name == designator.identifier.name }
  }

  private fun parseArrayDesignator(): ArrayDesignator? {
    val lParen = current()
    val rParenIdx = findParenMatch(Punctuators.LSQPAREN, Punctuators.RSQPAREN)
    if (rParenIdx == -1) {
      eatToSemi()
      return null
    }
    eat() // The [

    val expr = expressionParser.parseExpr(rParenIdx) ?: error<ErrorExpression>()
    val (constExpr, diags) = constExprParser.evaluateExpr(expr)

    val rParen = current()
    eat() // The ]

    return if (diags.isEmpty() && constExpr is IntegerConstantNode) {
      ArrayDesignator(constExpr).withRange(lParen..rParen)
    } else {
      diagnostic {
        id = DiagnosticId.EXPR_NOT_CONSTANT_INT
        errorOn(lParen..rParen)
      }
      null
    }
  }

  /**
   * @return null on parse error, the designator otherwise
   */
  private fun parseDotDesignator(dot: Punctuator): DotDesignator? {
    if (isEaten()) {
      diagnostic {
        id = DiagnosticId.EXPECTED_DOT_DESIGNATOR
        errorOn(dot)
      }
      return null
    }

    val maybeIdent = current()
    if (maybeIdent !is Identifier) {
      diagnostic {
        id = DiagnosticId.EXPECTED_DOT_DESIGNATOR
        errorOn(dot..maybeIdent)
      }
      return null
    }
    eat() // The designator identifier

    return DotDesignator(IdentifierNode.from(maybeIdent)).withRange(dot..maybeIdent)
  }

  /**
   * Find the designated type, and its index list within the designated object type initialization order.
   *
   * The index list has the same length as the receiver designator list, and represents an index at each designation level.
   * Consider this example, which looks contrived, but can easily occur with named structs nested in other named structs:
   * ```
   * struct my_struct {
   *   int x;
   *   struct {
   *     int value1;
   *     int value2;
   *     struct {
   *       float final1[500];
   *       int final2;
   *     } value3;
   *   } y;
   * };
   * ```
   * For the designator list `.y.value3.final1[42]`, the index list is `[1, 2, 0, 42]`, and the type is `float`.
   */
  private fun List<Designator>.designatedTypeOf(type: TypeName): Pair<TypeName, List<Int>> {
    require(isNotEmpty()) { "Receiver designator list must not be empty" }
    require(type !is QualifiedType) { "Must call TypeName#unqualify before using this function" }

    val errorValue = ErrorType to this.map { 0 }
    val designator = first()

    fun maybeRecurse(foundType: TypeName, foundIdx: Int): Pair<TypeName, List<Int>> {
      return if (size == 1) {
        foundType to listOf(foundIdx)
      } else {
        val (finalType, indices) = drop(1).designatedTypeOf(foundType)
        finalType to (listOf(foundIdx) + indices)
      }
    }

    return when {
      type.isNotAllowedToDesignate() || type is ErrorType -> errorValue
      type.isScalar() -> {
        diagnostic {
          id = DiagnosticId.DESIGNATOR_FOR_SCALAR
          formatArgs(type)
          errorOn(designator)
        }
        errorValue
      }
      type is TagType -> when (designator) {
        is ArrayDesignator -> {
          diagnostic {
            id = DiagnosticId.ARRAY_DESIGNATOR_NON_ARRAY
            formatArgs(type)
            errorOn(designator)
          }
          errorValue
        }
        is DotDesignator -> {
          val idx = indexOfDesignatorInTag(type, designator)
          if (idx < 0) {
            diagnostic {
              id = DiagnosticId.DOT_DESIGNATOR_NO_FIELD
              formatArgs(designator, type)
              errorOn(designator)
            }
            errorValue
          } else {
            maybeRecurse(type.members!![idx].second, idx)
          }
        }
      }
      type is ArrayType -> when (designator) {
        is DotDesignator -> {
          diagnostic {
            id = DiagnosticId.DOT_DESIGNATOR_NON_TAG
            formatArgs(type)
            errorOn(designator)
          }
          errorValue
        }
        is ArrayDesignator -> {
          val index = designator.index.value
          when {
            index < 0 -> {
              diagnostic {
                id = DiagnosticId.ARRAY_DESIGNATOR_NEGATIVE
                formatArgs(index)
              }
              errorValue
            }
            type.size is ConstantArraySize && index > (type.size.size as IntegerConstantNode).value -> {
              diagnostic {
                id = DiagnosticId.ARRAY_DESIGNATOR_BOUNDS
                formatArgs(index, (type.size.size as IntegerConstantNode).value)
              }
              errorValue
            }
            else -> {
              maybeRecurse(type.elementType, index.toInt())
            }
          }
        }
      }
      else -> logger.throwICE("Unhandled type $type")
    }
  }

  /**
   * C standard: 6.7.9
   */
  private fun parseDesignatedInitializer(
      parentAssignTok: Punctuator,
      currentObjectType: TypeName,
      currentSubObjectIdx: Int
  ): DesignatedInitializer {
    if (currentObjectType.isNotAllowedToDesignate()) {
      val startTok = current()
      eatUntil(tokenCount)
      val errorDecl = ErrorDeclInitializer(parentAssignTok).withRange(startTok..safeToken(0))
      return DesignatedInitializer(null, errorDecl).withRange(errorDecl)
    }

    val designators = mutableListOf<Designator>()

    while (true) {
      if (isEaten()) break
      val tok = current() as? Punctuator
      val designator = when (tok?.asPunct()) {
        Punctuators.DOT -> {
          eat()
          parseDotDesignator(tok)
        }
        Punctuators.LSQPAREN -> {
          parseArrayDesignator()
        }
        else -> break
      }
      if (designator == null) {
        eatUntil(tokenCount)
        val errorDecl = ErrorDeclInitializer(parentAssignTok).withRange(tok..safeToken(0))
        return DesignatedInitializer(null, errorDecl).withRange(errorDecl)
      } else {
        designators += designator
      }
    }

    if (designators.isNotEmpty()) {
      val (designatedType, designationIndex) = designators.designatedTypeOf(currentObjectType)
      val designation = Designation(designators, designatedType, designationIndex).withRange(designators.first()..designators.last())

      if (isEaten() || current().asPunct() != Punctuators.ASSIGN) {
        diagnostic {
          id = DiagnosticId.EXPECTED_NEXT_DESIGNATOR
          colPastTheEnd(0)
        }
        eatUntil(tokenCount)
        return DesignatedInitializer(designation, ErrorDeclInitializer(parentAssignTok).withRange(designation)).withRange(designation)
      }
      val assignTok = current() as Punctuator
      eat()
      val initializer = parseInitializer(assignTok, designatedType, tokenCount)

      return DesignatedInitializer(designation, initializer).withRange(designation..initializer)
    }

    val typeToInit = when {
      currentObjectType is ArrayType -> currentObjectType.elementType
      currentObjectType is StructureType -> currentObjectType.members!!.getOrNull(currentSubObjectIdx)?.second ?: ErrorType
      currentObjectType is UnionType -> currentObjectType.members!!.first().second
      currentObjectType.isScalar() -> currentObjectType
      else -> logger.throwICE("Unhandled type $currentObjectType")
    }
    val initializer = parseInitializer(parentAssignTok, typeToInit, tokenCount)
    return DesignatedInitializer(null, initializer).withRange(initializer)
  }

  /**
   * C standard: 6.7.9.0.17
   */
  private fun parseInitializerList(
      parentAssignTok: Punctuator,
      currentObjectType: TypeName,
      endIdx: Int
  ): InitializerList = tokenContext(endIdx) {
    val initializers = mutableListOf<DesignatedInitializer>()
    var currentSubObjectIdx = 0
    val excessInitializers = mutableListOf<DesignatedInitializer>()

    while (isNotEaten()) {
      // TODO: this pretends commas can't appear in assignment expressions, or in array designated initializer constant expressions
      val initializerEndIdx = firstOutsideParens(Punctuators.COMMA, Punctuators.LBRACKET, Punctuators.RBRACKET, false)
      tokenContext(initializerEndIdx) {
        val di = parseDesignatedInitializer(parentAssignTok, currentObjectType, currentSubObjectIdx)
        if (di.designation == null) {
          if (
            currentObjectType is StructureType &&
            !currentObjectType.isValidMemberIdx(currentSubObjectIdx) &&
            di.initializer !is ErrorDeclInitializer
          ) {
            excessInitializers += di
          }
          currentSubObjectIdx++
        } else {
          currentSubObjectIdx = di.designation.designationIndices.first() + 1
        }
        initializers += di
      }
      if (isNotEaten() && current().asPunct() == Punctuators.COMMA) {
        eat()
      }
    }

    if (initializers.size > 1 && currentObjectType.isScalar()) {
      diagnostic {
        id = DiagnosticId.EXCESS_INITIALIZERS_SCALAR
        errorOn(initializers[1]..initializers.last())
      }
    }

    if (excessInitializers.isNotEmpty()) {
      diagnostic {
        id = DiagnosticId.EXCESS_INITIALIZERS
        errorOn(excessInitializers.first()..excessInitializers.last())
      }
    }

    return@tokenContext InitializerList(initializers, parentAssignTok)
  }

  protected fun parseInitializer(
      assignTok: Punctuator,
      initializerFor: TypeName,
      endIdx: Int
  ): Initializer {
    // Error case, no initializer here
    if (current().asPunct() == Punctuators.COMMA || current().asPunct() == Punctuators.SEMICOLON) {
      diagnostic {
        id = DiagnosticId.EXPECTED_EXPR
        errorOn(safeToken(0))
      }
      return ExpressionInitializer(error<ErrorExpression>(), assignTok)
    }
    // Parse initializer-list
    if (current().asPunct() == Punctuators.LBRACKET) {
      val lbracket = current()
      val rbracket = findParenMatch(Punctuators.LBRACKET, Punctuators.RBRACKET)
      eat() // The {
      if (rbracket == -1) {
        eatToSemi()
        return ErrorDeclInitializer(assignTok).withRange(lbracket..current())
      }
      val initList = parseInitializerList(assignTok, initializerFor, rbracket).withRange(lbracket..current())
      eat() // The }
      return initList
    }
    // Simple expression
    // parseExpr should print out the diagnostic in case there is no expr here
    val expr = expressionParser.parseExpr(endIdx) ?: error<ErrorExpression>()
    // Only do initializer type checking if the types are actually valid
    if (expr.type.unqualify() !is ErrorType && initializerFor.unqualify() !is ErrorType) {
      val (_, commonType) = BinaryOperators.ASSIGN.applyTo(initializerFor, expr.type)
      if (commonType is ErrorType) {
        diagnostic {
          id = DiagnosticId.INITIALIZER_TYPE_MISMATCH
          formatArgs(expr.type, initializerFor)
          errorOn(expr)
        }
      }
    }
    return ExpressionInitializer(convertToCommon(initializerFor, expr), assignTok)
  }

  /** C standard: 6.7.6.2 */
  private fun parseArrayTypeSize(endIdx: Int): ArrayTypeSize = tokenContext(endIdx) {
    if (it.isEmpty()) return@tokenContext NoSize
    fun parseTypeQualifierList(startIdx: Int): TypeQualifierList {
      val quals = it.drop(startIdx).takeWhile { k -> k.asKeyword() in SpecParser.typeQualifiers }
      eatUntil(startIdx + quals.size)
      return quals.map { k -> k as Keyword }
    }

    fun qualsAndStatic(quals: TypeQualifierList, staticKw: LexicalToken): ArrayTypeSize {
      if (isEaten()) {
        diagnostic {
          id = DiagnosticId.ARRAY_STATIC_NO_SIZE
          errorOn(staticKw)
        }
        return FunctionParameterConstantSize(quals, true, error<ErrorExpression>())
      }
      val expr = expressionParser.parseExpr(it.size) ?: error<ErrorExpression>()
      val (constExpr, diags) = constExprParser.evaluateExpr(expr)
      return if (diags.isEmpty() && constExpr !is ErrorExpression) {
        FunctionParameterConstantSize(quals, true, constExpr)
      } else {
        diagnostic {
          id = DiagnosticId.UNSUPPORTED_VLA
          errorOn(expr)
        }
        FunctionParameterSize(quals, true, expr)
      }
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
    val expr = expressionParser.parseExpr(it.size) ?: error<ErrorExpression>()
    val (constExpr, diags) = constExprParser.evaluateExpr(expr)
    if (diags.isEmpty() && constExpr !is ErrorExpression) {
      if (quals.isEmpty()) {
        return@tokenContext ConstantSize(constExpr)
      } else {
        return@tokenContext FunctionParameterConstantSize(quals, false, constExpr)
      }
    } else {
      diagnostic {
        id = DiagnosticId.UNSUPPORTED_VLA
        errorOn(expr)
      }
      if (quals.isEmpty()) {
        return@tokenContext ExpressionSize(expr)
      } else {
        return@tokenContext FunctionParameterSize(quals, false, expr)
      }
    }
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
        val bitWidthExpr = expressionParser.parseExpr(stopIdx)
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

  companion object {
    private val logger = LogManager.getLogger()
  }
}
