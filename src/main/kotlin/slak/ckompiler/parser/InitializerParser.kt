package slak.ckompiler.parser

import org.apache.logging.log4j.LogManager
import slak.ckompiler.*
import slak.ckompiler.lexer.Identifier
import slak.ckompiler.lexer.Punctuator
import slak.ckompiler.lexer.Punctuators
import slak.ckompiler.lexer.asPunct

interface IInitializerParser {
  /**
   * Parses what the standard calls an `initializer`, including initializer lists.
   *
   * [assignTok] refers to the assignment for which this initializer is on the rhs.
   *
   * [initializerFor] is the type of the lhs that is being initialized. Importantly, the returned initializer may not have the same type.
   * This is either because the initializer will _determine_ the type (`int x[] = { 1, 2 };`) or because the standard says it's different
   * (`char list[2] = "xyzabcd";`, where the string is longer than the array).
   *
   * C standard: 6.7.9
   */
  fun parseInitializer(assignTok: Punctuator, initializerFor: TypeName, endIdx: Int): Initializer
}

class InitializerParser(parenMatcher: ParenMatcher, scopeHandler: ScopeHandler, constantExprParser: ConstantExprParser) :
    ITokenHandler by parenMatcher,
    IParenMatcher by parenMatcher,
    IScopeHandler by scopeHandler,
    IExpressionParser by constantExprParser,
    IConstantExprParser by constantExprParser,
    IInitializerParser {

  private fun indexOfDesignatorInTag(type: TagType, designator: DotDesignator): Int {
    return type.members!!.indexOfFirst { it.first.name == designator.identifier.name }
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
  private fun List<Designator>.designatedTypeOf(type: TypeName): DesignationKey {
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
            type.size is ConstantArraySize && index > type.size.asValue -> {
              diagnostic {
                id = DiagnosticId.ARRAY_DESIGNATOR_BOUNDS
                formatArgs(index, type.size.asValue)
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

  private fun parseArrayDesignator(): ArrayDesignator? {
    val lParen = current()
    val rParenIdx = findParenMatch(Punctuators.LSQPAREN, Punctuators.RSQPAREN)
    if (rParenIdx == -1) {
      eatToSemi()
      return null
    }
    eat() // The [

    val expr = parseExpr(rParenIdx) ?: error<ErrorExpression>()
    val (constExpr, diags) = evaluateExpr(expr)

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
    val designatedInitializer = DesignatedInitializer(null, initializer).withRange(initializer)
    designatedInitializer.resolvedDesignation = typeToInit to listOf(currentSubObjectIdx)
    return designatedInitializer
  }

  private fun isArrayIndexInExcess(currentObjectType: TypeName, currentSubObjectIdx: Int): Boolean {
    return currentObjectType is ArrayType &&
        currentObjectType.size is ConstantArraySize &&
        currentObjectType.size.size is IntegerConstantNode &&
        currentSubObjectIdx > currentObjectType.size.asValue
  }

  /**
   * C standard: 6.7.9
   */
  private fun parseInitializerList(
      parentAssignTok: Punctuator,
      currentObjectType: TypeName,
      endIdx: Int
  ): InitializerList = tokenContext(endIdx) {
    val initializers = mutableListOf<DesignatedInitializer>()
    var currentSubObjectIdx = 0
    val excessInitializers = mutableListOf<DesignatedInitializer>()
    val encounteredDesignations = mutableMapOf<DesignationKey, DesignatedInitializer>()

    fun checkAlreadyEncountered(latest: DesignatedInitializer) {
      val key = if (latest.designation != null) {
        latest.designation.designatedType to latest.designation.designationIndices
      } else {
        // The resolvedDesignation might not be set on some errored initializers
        latest.resolvedDesignation ?: return
      }

      if (key in encounteredDesignations || (currentObjectType is UnionType && initializers.isNotEmpty())) {
        diagnostic {
          id = DiagnosticId.INITIALIZER_OVERRIDES_PRIOR
          errorOn(latest.initializer)
        }
        diagnostic {
          id = DiagnosticId.PRIOR_INITIALIZER
          if (currentObjectType is UnionType) {
            errorOn(encounteredDesignations.values.last().initializer)
          } else {
            errorOn(encounteredDesignations.getValue(key).initializer)
          }
        }
      } else {
        encounteredDesignations[key] = latest
      }
    }

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
        if (isArrayIndexInExcess(currentObjectType, currentSubObjectIdx)) {
          excessInitializers += di
        }
        checkAlreadyEncountered(di)
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
        id = if (currentObjectType is ArrayType) DiagnosticId.EXCESS_INITIALIZERS_ARRAY else DiagnosticId.EXCESS_INITIALIZERS
        errorOn(excessInitializers.first()..excessInitializers.last())
      }
    }

    return@tokenContext InitializerList(initializers, parentAssignTok, currentSubObjectIdx)
  }

  override fun parseInitializer(assignTok: Punctuator, initializerFor: TypeName, endIdx: Int): Initializer {
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
    val expr = parseExpr(endIdx) ?: error<ErrorExpression>()
    // Only do initializer type checking if the types are actually valid
    if (expr.type.unqualify() !is ErrorType && initializerFor.unqualify() !is ErrorType) {
      val rhs = expr.type
      val (_, commonType) = BinaryOperators.ASSIGN.applyTo(initializerFor, rhs)
      if (commonType is ErrorType) {
        diagnostic {
          id = DiagnosticId.INITIALIZER_TYPE_MISMATCH
          formatArgs(rhs, initializerFor)
          errorOn(expr)
        }
      } else if (initializerFor is ArrayType && rhs is ArrayType) {
        if (initializerFor.size is ConstantArraySize && rhs.size is ConstantArraySize && rhs.size.asValue > initializerFor.size.asValue) {
          diagnostic {
            id = DiagnosticId.EXCESS_INITIALIZER_SIZE
            formatArgs(rhs.size.asValue, initializerFor.size.asValue)
            errorOn(expr)
          }
        }
      }
    }
    return ExpressionInitializer(convertToCommon(initializerFor, expr), assignTok)
  }

  companion object {
    private val logger = LogManager.getLogger()
  }
}
