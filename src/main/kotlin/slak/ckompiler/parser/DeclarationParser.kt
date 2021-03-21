package slak.ckompiler.parser

import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.Punctuator
import slak.ckompiler.lexer.Punctuators
import slak.ckompiler.lexer.asPunct
import slak.ckompiler.rangeTo
import java.util.*

interface IDeclarationParser : IDeclaratorParser {
  /**
   * Parses a declaration, including function declarations (prototypes).
   * @param validationRules extra checks to be performed on the [DeclarationSpecifier]
   * @return null if there is no declaration, a [Declaration] otherwise
   */
  fun parseDeclaration(validationRules: SpecValidationRules): Declaration? {
    val (declSpec, firstDecl) = preParseDeclarator(validationRules)
    if (declSpec.isBlank()) return null
    if (!firstDecl!!.isPresent) return Declaration(declSpec, emptyList())
    return parseDeclaration(declSpec, firstDecl.get())
  }

  /**
   * Parse a [DeclarationSpecifier] and the first [Declarator] that follows.
   * @param validationRules extra checks to be performed on the [DeclarationSpecifier]
   * @return if [DeclarationSpecifier.isBlank] is true, the [Declarator] will be null, and if there
   * is no declarator, it will be [Optional.empty]
   */
  fun preParseDeclarator(
      validationRules: SpecValidationRules
  ): Pair<DeclarationSpecifier, Optional<Declarator>?>

  /**
   * Parses a declaration where the [DeclarationSpecifier] and the first [Declarator] are already
   * parsed.
   * @see preParseDeclarator
   * @return the [Declaration]
   */
  fun parseDeclaration(declSpec: DeclarationSpecifier, declarator: Declarator?): Declaration
}

class DeclarationParser(parenMatcher: ParenMatcher, scopeHandler: ScopeHandler) :
    DeclaratorParser(parenMatcher, scopeHandler), IDeclarationParser {

  override fun preParseDeclarator(
      validationRules: SpecValidationRules
  ): Pair<DeclarationSpecifier, Optional<Declarator>?> {
    val declSpec = specParser.parseDeclSpecifiers(validationRules)
    if (isNotEaten() && current().asPunct() == Punctuators.SEMICOLON) {
      // This is the case where there is a semicolon after the DeclarationSpecifiers
      eat() // The ';'
      when {
        declSpec.isTag() -> {
          // Do nothing intentionally
        }
        declSpec.isTypedef() -> diagnostic {
          id = DiagnosticId.TYPEDEF_REQUIRES_NAME
          errorOn(declSpec)
        }
        else -> diagnostic {
          id = DiagnosticId.MISSING_DECLARATIONS
          errorOn(declSpec)
        }
      }
      return declSpec to Optional.empty()
    }
    if (declSpec.isTag() && isEaten()) return declSpec to Optional.empty()
    val declarator = if (declSpec.isBlank()) null else Optional.of(parseNamedDeclarator(tokenCount))
    if (declarator != null && declarator.get().isFunction()) {
      SpecValidationRules.FUNCTION_DECLARATION.validate(specParser, declSpec)
    }
    return declSpec to declarator
  }

  override fun parseDeclaration(
      declSpec: DeclarationSpecifier,
      declarator: Declarator?
  ): Declaration {
    val declList = parseInitDeclaratorList(declSpec, declarator)
    declList.forEach { checkArrayType(declSpec, it.first) }
    val start = if (declSpec.isBlank()) safeToken(0) else declSpec
    return Declaration(declSpec, declList).withRange(start..safeToken(-1))
  }

  /**
   * Adds declaration to scope.
   * @see IScopeHandler.newIdentifier
   */
  private fun addToScope(ds: DeclarationSpecifier, declarator: Declarator?) {
    if (declarator !is NamedDeclarator) return
    val identifier =
        if (ds.isTypedef()) TypedefName(ds, declarator)
        else TypedIdentifier(ds, declarator)
    newIdentifier(identifier)
  }

  /**
   * Parse an initializer for one of this declaration's declarators.
   * @see DeclaratorParser.parseInitializer
   */
  private fun parseDeclarationInitializer(
      expectedType: TypeName,
      ds: DeclarationSpecifier,
      endIdx: Int
  ): Initializer? {
    if (current().asPunct() != Punctuators.ASSIGN) return null
    val assignTok = current() as Punctuator
    eat() // Get rid of "="
    val initializer = parseInitializer(assignTok, expectedType, endIdx)
    if (!ds.isTypedef()) return initializer
    diagnostic {
      id = DiagnosticId.TYPEDEF_NO_INITIALIZER
      errorOn(assignTok..initializer)
    }
    return ErrorDeclInitializer(assignTok)
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
  private fun parseInitDeclaratorList(
      ds: DeclarationSpecifier,
      firstDecl: Declarator? = null
  ): List<Pair<Declarator, Initializer?>> {
    // This is the case where there are no declarators left for this function
    if (isNotEaten() && current().asPunct() == Punctuators.SEMICOLON) {
      eat()
      addToScope(ds, firstDecl)
      return listOfNotNull(firstDecl).map { it to null }
    }

    val declaratorList = mutableListOf<Pair<Declarator, Initializer?>>()

    fun processDeclarator(declarator: Declarator, endIdx: Int): Boolean {
      if (declarator is ErrorDeclarator) {
        val stopIdx = indexOfFirst(Punctuators.COMMA, Punctuators.SEMICOLON)
        if (stopIdx == -1) eatToSemi()
        else eatUntil(stopIdx)
      }
      addToScope(ds, declarator)
      if (isEaten()) {
        diagnostic {
          id = DiagnosticId.EXPECTED_SEMI_AFTER
          formatArgs("declarator")
          column(colPastTheEnd(0))
        }
        declaratorList += declarator to null
        return true
      }
      val expectedType = typeNameOf(ds, declarator)
      declaratorList += declarator to parseDeclarationInitializer(expectedType, ds, endIdx)
      if (isNotEaten() && current().asPunct() == Punctuators.COMMA) {
        // Expected case; there are chained `init-declarator`s
        eat()
      } else if (isNotEaten() && current().asPunct() == Punctuators.SEMICOLON) {
        // Expected case; semi at the end of `declaration`
        eat()
        return true
      } else {
        // Missing semicolon
        diagnostic {
          id = DiagnosticId.EXPECTED_SEMI_AFTER
          formatArgs("declarator")
          column(colPastTheEnd(0))
        }
        return true
      }
      return false
    }
    // If firstDecl is null, we act as if it was already processed
    var firstDeclUsed = firstDecl == null
    while (true) {
      val declEnd = firstOutsideParens(
          Punctuators.COMMA, Punctuators.LPAREN, Punctuators.RPAREN, stopAtSemi = true)
      val declarator = if (firstDeclUsed) {
        parseNamedDeclarator(declEnd)
      } else {
        firstDeclUsed = true
        firstDecl!!
      }
      val shouldBreak = processDeclarator(declarator, declEnd)
      if (shouldBreak) break
    }
    return declaratorList
  }
}
