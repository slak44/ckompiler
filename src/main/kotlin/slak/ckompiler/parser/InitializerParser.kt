package slak.ckompiler.parser

import org.apache.logging.log4j.LogManager
import slak.ckompiler.*
import slak.ckompiler.lexer.Identifier
import slak.ckompiler.lexer.Punctuator
import slak.ckompiler.lexer.Punctuators
import slak.ckompiler.lexer.asPunct
import kotlin.math.max

private data class InitializerListContext(
    val parentAssignTok: Punctuator,
    val currentObjectType: TypeName,
    val initializers: List<DesignatedInitializer>,
    val designatedIndices: MutableList<Int> = mutableListOf(0),
    var maxRootIndex: Int = 0,
    val excessInitializers: MutableList<DesignatedInitializer> = mutableListOf(),
    val encounteredDesignations: MutableMap<DesignationKey, DesignatedInitializer> = mutableMapOf()
)

private fun IDebugHandler.validateExprInitializer(expr: Expression, initializerFor: TypeName): TypeName {
  // Only do initializer type checking if the types are actually valid
  if (expr.type.unqualify() is ErrorType || initializerFor.unqualify() is ErrorType) return ErrorType

  val rhs = expr.type
  val (_, commonType) = BinaryOperators.ASSIGN.applyTo(initializerFor, rhs)
  if (commonType is ErrorType) {
    diagnostic {
      id = DiagnosticId.INITIALIZER_TYPE_MISMATCH
      formatArgs(rhs, initializerFor)
      errorOn(expr)
    }
    return ErrorType
  }

  if (
    initializerFor is ArrayType && rhs is ArrayType &&
    initializerFor.size is ConstantArraySize && rhs.size is ConstantArraySize &&
    rhs.size.asValue > initializerFor.size.asValue
  ) {
    diagnostic {
      id = DiagnosticId.EXCESS_INITIALIZER_SIZE
      formatArgs(rhs.size.asValue, initializerFor.size.asValue)
      errorOn(expr)
    }
  }

  return commonType
}

private fun IDebugHandler.convertToInitializer(expr: Expression, assignTok: Punctuator, initializerFor: TypeName): ExpressionInitializer {
  val commonType = validateExprInitializer(expr, initializerFor)
  return ExpressionInitializer(convertToCommon(commonType, expr), assignTok)
}

/**
 * Intermediate union result type `Initializer | Expression`.
 *
 * @see InitializerParser.parseInitializerInternal
 */
private sealed class InternalInitializer {
  data class Actual(val initializer: Initializer) : InternalInitializer()
  data class Expr(val expr: Expression) : InternalInitializer()
}

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

  /**
   * Given pre-computed designation indices, find the type name that is referred to. This differs from the other overload, which reads
   * parsed [Designator]s.
   *
   * @see designatedTypeOf
   */
  private fun DesignationIndices.designatedTypeOf(currentObjectType: TypeName): TypeName {
    var workingType = currentObjectType
    for (indexTier in this) {
      workingType = when {
        workingType is ArrayType -> workingType.elementType
        workingType is StructureType -> workingType.members!!.getOrNull(indexTier)?.second ?: ErrorType
        workingType is UnionType -> workingType.members!!.first().second
        workingType.isScalar() -> workingType
        else -> logger.throwICE("Unhandled type $workingType")
      }
    }
    return workingType
  }

  /**
   * Advances the current indices to the next thing to be initialized. Basically, next item for array, next element for struct,
   * next in parent for union.
   *
   * C standard: 6.7.9.0.17, 6.7.9.0.20, note 149
   */
  private fun InitializerListContext.advanceIndices(itemType: TypeName) {
    val lastIdx = designatedIndices.removeLast()
    val parentType = designatedIndices.designatedTypeOf(currentObjectType)

    if (designatedIndices.isEmpty() || parentType.isNotAllowedToDesignate() || parentType.isScalar()) {
      designatedIndices += (lastIdx + 1)
      return
    }

    when (parentType) {
      is UnionType -> {
        // For unions, the next subobject is never inside the union
        // Instead, it is the next subobject of the union's parent type
        // This is identical to the "last element in structure" case below
        advanceIndices(parentType)
      }
      is StructureType -> {
        if (lastIdx == parentType.members!!.size - 1) {
          // Last item in structure, don't add next index at this level
          advanceIndices(parentType)
        } else {
          // There are items left to initialize in structure, increment index at current level
          designatedIndices += (lastIdx + 1)
        }
      }
      is ArrayType -> when (parentType.size) {
        is NoSize -> {
          // For these, we can initialize items as long as there are initializers available
          designatedIndices += (lastIdx + 1)
        }
        is ConstantArraySize -> {
          if (lastIdx >= parentType.size.asValue) {
            // Finished initializing this array, move on
            advanceIndices(parentType)
          } else {
            // Next array item
            designatedIndices += (lastIdx + 1)
          }
        }
        else -> logger.throwICE("Non constant, non empty array size can't be inside aggregate initializer")
      }
      else -> logger.throwICE("Unreachable, scalar should not get here")
    }
  }

  private tailrec fun InitializerListContext.trySubObjectInitialization(expr: Expression, initializerFor: TypeName): TypeName? {
    val rhs = expr.type
    val (_, commonType) = BinaryOperators.ASSIGN.applyTo(initializerFor, rhs)
    if (commonType !is ErrorType) {
      return commonType
    }

    if (initializerFor is ArrayType || initializerFor is StructureType || initializerFor is UnionType) {
      val nestedType = listOf(designatedIndices.last()).designatedTypeOf(initializerFor)
      designatedIndices += 0
      return trySubObjectInitialization(expr, nestedType)
    }

    return null
  }

  private fun InitializerListContext.unwrapInternal(
      internalInitializer: InternalInitializer,
      designatedType: TypeName,
      assignTok: Punctuator,
  ): Initializer {
    return when (internalInitializer) {
      is InternalInitializer.Actual -> internalInitializer.initializer
      is InternalInitializer.Expr -> {
        val maybeType = trySubObjectInitialization(internalInitializer.expr, designatedType)
        // If no subobject can be initialized with this value, use the designated type itself, which will produce a diagnostic
        convertToInitializer(internalInitializer.expr, assignTok, maybeType ?: designatedType)
      }
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

    if (diags.isNotEmpty()) {
      eatUntil(tokenCount)
      return null
    }

    if (expr.type is ErrorType) {
      return null
    }

    return if (constExpr is IntegerConstantNode) {
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
   * Parse a `designator-list` from the standard.
   *
   * This is a list of [Designator]s, not a [Designation], as it is not dependent on types.
   *
   * An empty list => no designators were found.
   *
   * C standard: 6.7.9
   *
   * @return null on parse error and diagnostic printed, the list otherwise
   */
  private fun parseDesignatorList(): List<Designator>? {
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
        // This is the case where one of the designators returned null and created a diagnostic
        return null
      } else {
        designators += designator
      }
    }

    return designators
  }

  private fun errorDesignatedInitializer(parentAssignTok: Punctuator): DesignatedInitializer {
    val startTok = safeToken(0)
    eatUntil(tokenCount)
    val errorDecl = ErrorDeclInitializer(parentAssignTok).withRange(startTok..safeToken(0))
    return DesignatedInitializer(null, errorDecl).withRange(errorDecl)
  }

  private fun isArrayIndexInExcess(maybeArrayType: TypeName, currentSubObjectIdx: Int): Boolean {
    return maybeArrayType is ArrayType &&
        maybeArrayType.size is ConstantArraySize &&
        maybeArrayType.size.size is IntegerConstantNode &&
        currentSubObjectIdx >= maybeArrayType.size.asValue
  }

  private fun isStructIndexInExcess(di: DesignatedInitializer, maybeStructType: TypeName, currentSubObjectIdx: Int): Boolean {
    return di.designation == null &&
        maybeStructType is StructureType &&
        !maybeStructType.isValidMemberIdx(currentSubObjectIdx) &&
        di.initializer !is ErrorDeclInitializer
  }

  private fun isIndexInExcess(di: DesignatedInitializer, currentObjectType: TypeName, currentSubObjectIdx: Int): Boolean {
    return isStructIndexInExcess(di, currentObjectType, currentSubObjectIdx) || isArrayIndexInExcess(currentObjectType, currentSubObjectIdx)
  }

  /**
   * Emits a warning if there are 2 initializers (designated or not) for the same object/subobject.
   */
  private fun InitializerListContext.checkAlreadyEncountered(latest: DesignatedInitializer) {
    val key = if (latest.designation != null) {
      // Make copy of list, as it is mutable
      latest.designation.designatedType to latest.designation.designationIndices.toList()
    } else {
      // The resolvedDesignation might not be set on some errored initializers
      latest.resolvedDesignation ?: return
    }

    if (key.first is ErrorType) return

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

  /**
   * Emits some warnings for excess initializers after an [InitializerList]'s contents were parsed.
   */
  private fun InitializerListContext.checkExcessInitializers() {
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
  }

  private fun InitializerListContext.parseExplicitlyDesignated(designators: List<Designator>): DesignatedInitializer {
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
    val internalInitializer = parseInitializerInternal(assignTok, designatedType, tokenCount)

    // Move indices to the explicitly designated type
    designatedIndices.clear()
    designatedIndices += designation.designationIndices
    maxRootIndex = max(maxRootIndex, designatedIndices[0])
    if (designation.designatedType !is ErrorType) {
      advanceIndices(designation.designatedType)
    }

    val initializer = unwrapInternal(internalInitializer, designatedType, assignTok)

    return DesignatedInitializer(designation, initializer).withRange(designation..initializer)
  }

  private fun InitializerListContext.parseImplicitlyDesignated(): DesignatedInitializer {
    val typeToInit = designatedIndices.designatedTypeOf(currentObjectType)
    val internalInitializer = parseInitializerInternal(parentAssignTok, typeToInit, tokenCount)

    val initializer = unwrapInternal(internalInitializer, typeToInit, parentAssignTok)

    val designatedInitializer = DesignatedInitializer(null, initializer).withRange(initializer)
    designatedInitializer.resolvedDesignation = designatedIndices.designatedTypeOf(currentObjectType) to designatedIndices.toList()

    maxRootIndex = max(maxRootIndex, designatedIndices[0])

    if (isIndexInExcess(designatedInitializer, currentObjectType, designatedIndices[0])) excessInitializers += designatedInitializer
    if (designatedInitializer.resolvedDesignation != null && designatedInitializer.resolvedDesignation!!.first !is ErrorType) {
      advanceIndices(designatedInitializer.resolvedDesignation!!.first)
    }

    return designatedInitializer
  }

  /**
   * C standard: 6.7.9
   */
  private fun InitializerListContext.parseDesignatedInitializer(
      initializerEndIdx: Int
  ): DesignatedInitializer = tokenContext(initializerEndIdx) {
    if (currentObjectType.isNotAllowedToDesignate()) {
      return@tokenContext errorDesignatedInitializer(parentAssignTok)
    }

    val designators = parseDesignatorList() ?: return@tokenContext errorDesignatedInitializer(parentAssignTok)

    val designatedInitializer = if (designators.isNotEmpty()) {
      parseExplicitlyDesignated(designators)
    } else {
      parseImplicitlyDesignated()
    }

    checkAlreadyEncountered(designatedInitializer)

    return@tokenContext designatedInitializer
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
    val context = InitializerListContext(parentAssignTok, currentObjectType, initializers)

    while (isNotEaten()) {
      // TODO: this pretends commas can't appear in assignment expressions, or in array designated initializer constant expressions
      val initializerEndIdx = firstOutsideParens(Punctuators.COMMA, Punctuators.LBRACKET, Punctuators.RBRACKET, false)
      initializers += context.parseDesignatedInitializer(initializerEndIdx)

      // Trailing commas are allowed in initializer lists
      if (isNotEaten() && current().asPunct() == Punctuators.COMMA) {
        eat()
      }
    }

    context.checkExcessInitializers()

    return@tokenContext InitializerList(initializers, context.parentAssignTok, context.maxRootIndex)
  }

  private fun parseInitializerInternal(assignTok: Punctuator, initializerFor: TypeName, endIdx: Int): InternalInitializer {
    // Error case, no initializer here
    if (current().asPunct() == Punctuators.COMMA || current().asPunct() == Punctuators.SEMICOLON) {
      diagnostic {
        id = DiagnosticId.EXPECTED_EXPR
        errorOn(safeToken(0))
      }
      return InternalInitializer.Actual(ExpressionInitializer(error<ErrorExpression>(), assignTok))
    }
    // Parse initializer-list
    if (current().asPunct() == Punctuators.LBRACKET) {
      val lbracket = current()
      val rbracket = findParenMatch(Punctuators.LBRACKET, Punctuators.RBRACKET)
      eat() // The {
      if (rbracket == -1) {
        eatToSemi()
        return InternalInitializer.Actual(ErrorDeclInitializer(assignTok).withRange(lbracket..current()))
      }
      val initList = parseInitializerList(assignTok, initializerFor, rbracket).withRange(lbracket..current())
      eat() // The }
      return InternalInitializer.Actual(initList)
    }
    // Simple expression
    // parseExpr should print out the diagnostic in case there is no expr here
    val expr = parseExpr(endIdx) ?: error<ErrorExpression>()
    return InternalInitializer.Expr(expr)
  }

  override fun parseInitializer(assignTok: Punctuator, initializerFor: TypeName, endIdx: Int): Initializer {
    return when (val init = parseInitializerInternal(assignTok, initializerFor, endIdx)) {
      is InternalInitializer.Actual -> init.initializer
      is InternalInitializer.Expr -> convertToInitializer(init.expr, assignTok, initializerFor)
    }
  }

  companion object {
    private val logger = LogManager.getLogger()
  }
}
