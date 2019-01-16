package slak.ckompiler.parser

import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.*
import slak.ckompiler.throwICE

interface ISpecParser {
  /** C standard: A.2.2, 6.7 */
  fun parseDeclSpecifiers(): DeclarationSpecifier
}

class SpecParser(declarationParser: DeclarationParser) :
    ISpecParser,
    IDebugHandler by declarationParser,
    ITokenHandler by declarationParser,
    IParenMatcher by declarationParser,
    IDeclarationParser by declarationParser {

  private fun diagDuplicate(last: Keyword) = parserDiagnostic {
    id = DiagnosticId.DUPLICATE_DECL_SPEC
    formatArgs(last.value.keyword)
    errorOn(last)
  }

  private fun diagIncompat(original: String, last: Keyword) = parserDiagnostic {
    id = DiagnosticId.INCOMPATIBLE_DECL_SPEC
    formatArgs(original)
    errorOn(last)
  }

  private fun diagNotSigned(original: String, signed: Keyword) = parserDiagnostic {
    id = DiagnosticId.TYPE_NOT_SIGNED
    formatArgs(original)
    errorOn(signed)
  }

  /**
   * Used in [combineWith] when the next keyword is either [Keywords.SIGNED] or [Keywords.UNSIGNED].
   * If it encounters an error, the diagnostic is issued and the value of [this] is returned.
   */
  private fun TypeSpecifier.signSpec(isSigned: Boolean, debug: Keyword) = when (this) {
    is Char -> if (isSigned) SignedChar(this.first) else UnsignedChar(this.first)
    is Short -> if (isSigned) SignedShort(this.first) else UnsignedShort(this.first)
    is IntType -> if (isSigned) SignedInt(this.first) else UnsignedInt(this.first)
    is LongType -> if (isSigned) SignedLong(this.first) else UnsignedLong(this.first)
    is LongLong -> if (isSigned) SignedLongLong(this.first) else UnsignedLongLong(this.first)
    is Signed, is SignedChar, is SignedShort, is SignedInt, is SignedLong, is SignedLongLong -> {
      if (isSigned) diagDuplicate(debug)
      else diagIncompat(this.toString(), debug)
      this
    }
    is Unsigned, is UnsignedChar, is UnsignedShort, is UnsignedInt,
    is UnsignedLong, is UnsignedLongLong -> {
      if (!isSigned) diagDuplicate(debug)
      else diagIncompat(this.toString(), debug)
      this
    }
    else -> {
      diagNotSigned(this.toString(), debug)
      this
    }
  }

  /**
   * This function takes a [TypeSpecifier], and changes it based on the next keyword.
   *
   * Examples:
   * 1. [Char] + [Keywords.UNSIGNED] => [UnsignedChar]
   * 2. [Double] + [Keywords.LONG] => [LongDouble]
   * 3. [SignedLong] + [Keywords.LONG] => [SignedLongLong]
   * 4. null + [Keywords.INT] => [IntType]
   * 4. etc.
   */
  private infix fun TypeSpecifier?.combineWith(next: Keyword): TypeSpecifier {
    if (this == null) return when (next.value) {
      Keywords.SIGNED -> Signed(next)
      Keywords.UNSIGNED -> Unsigned(next)
      Keywords.VOID -> VoidType(next)
      Keywords.BOOL -> Bool(next)
      Keywords.CHAR -> Char(next)
      Keywords.SHORT -> Short(next)
      Keywords.INT -> IntType(next)
      Keywords.LONG -> LongType(next)
      Keywords.FLOAT -> FloatType(next)
      Keywords.DOUBLE -> DoubleType(next)
      else -> logger.throwICE("Bad keyword interpreted as type specifier") { next }
    }
    if (next.value == Keywords.SIGNED || next.value == Keywords.UNSIGNED) {
      return this.signSpec(next.value == Keywords.SIGNED, next)
    }
    when (this) {
      is LongType -> when (next.value) {
        Keywords.DOUBLE -> return LongDouble(this.first)
        Keywords.LONG -> return LongLong(this.first)
        else -> diagIncompat(this.toString(), next)
      }
      is SignedLong -> when (next.value) {
        Keywords.LONG -> return SignedLongLong(this.first)
        else -> diagIncompat(this.toString(), next)
      }
      is UnsignedLong -> when (next.value) {
        Keywords.LONG -> return UnsignedLongLong(this.first)
        else -> diagIncompat(this.toString(), next)
      }
      is DoubleType -> when (next.value) {
        Keywords.LONG -> return LongDouble(this.first)
        else -> diagIncompat(this.toString(), next)
      }
      is Signed -> when (next.value) {
        Keywords.CHAR -> return SignedChar(this.first)
        Keywords.SHORT -> return SignedShort(this.first)
        Keywords.INT -> return SignedInt(this.first)
        Keywords.LONG -> return SignedLong(this.first)
        else -> diagNotSigned(next.value.keyword, this.first)
      }
      is Unsigned -> when (next.value) {
        Keywords.CHAR -> return UnsignedChar(this.first)
        Keywords.SHORT -> return UnsignedShort(this.first)
        Keywords.INT -> return UnsignedInt(this.first)
        Keywords.LONG -> return UnsignedLong(this.first)
        else -> diagNotSigned(next.value.keyword, this.first)
      }
      else -> diagIncompat(this.toString(), next)
    }
    return this
  }

  /**
   * Parses `specifier-qualifer-list` (like [DeclarationSpecifier], but without function specs and
   * storage specs).
   *
   * C standard: 6.7.2.1
   */
  private fun parseSpecifierQualifierSpec(): DeclarationSpecifier {
    val specifierQualifier = parseDeclSpecifiers()
    if (specifierQualifier.functionSpecs.isNotEmpty()) parserDiagnostic {
      id = DiagnosticId.SPEC_NOT_ALLOWED
      formatArgs("function specifier")
      errorOn(specifierQualifier.functionSpecs.first())
    }
    if (specifierQualifier.storageClass != null ||
        specifierQualifier.threadLocal != null) parserDiagnostic {
      id = DiagnosticId.SPEC_NOT_ALLOWED
      formatArgs("storage specifier")
      val errTok = specifierQualifier.threadLocal ?: specifierQualifier.storageClass
      errorOn(errTok!!)
    }
    return specifierQualifier
  }

  /**
   * Parses `struct-or-union-specifier`.
   *
   * C standard: 6.7.2.1
   */
  private fun parseStructUnion(): TypeSpecifier? {
    val startTok = current()
    val isUnion = current().asKeyword() == Keywords.UNION
    eat() // struct or union
    val name = (current() as? Identifier)?.let {
      eat()
      IdentifierNode(it.name)
    }
    if (current().asPunct() != Punctuators.LBRACKET) {
      // This is the case where it's just a type specifier
      // Like "struct person p;"
      // This means `name` can't be null, because the declaration of an anonymous struct must
      // be a definition, and since we have no bracket, it isn't one
      if (name == null) {
        parserDiagnostic {
          id = DiagnosticId.ANON_STRUCT_MUST_DEFINE
          errorOn(startTok)
        }
        return null
      }
      return if (isUnion) UnionNameSpecifier(name) else StructNameSpecifier(name)
    }
    val endIdx = findParenMatch(Punctuators.LBRACKET, Punctuators.RBRACKET, stopAtSemi = false)
    eat() // The {
    val declarations = mutableListOf<Declaration>()
    tokenContext(endIdx) {
      while (isNotEaten()) {
        val spec = parseSpecifierQualifierSpec()
        if (spec.isEmpty()) {
          continue
        }
        if (isNotEaten() && current().asPunct() == Punctuators.SEMICOLON) {
          eat() // The ';'
          if (spec.canBeTag()) {
            declarations += Declaration(spec, emptyList())
          } else {
            parserDiagnostic {
              id = DiagnosticId.MISSING_DECLARATIONS
              errorOn(safeToken(0))
            }
          }
        } else {
          declarations += Declaration(spec, parseStructDeclaratorList())
        }
      }
    }
    eat() // The }
    if (isEaten()) {
      parserDiagnostic {
        id = DiagnosticId.EXPECTED_SEMI_AFTER
        formatArgs(if (isUnion) "union" else "struct")
        column(colPastTheEnd(0))
      }
    }
    return if (isUnion) UnionDefinition(name, declarations)
    else StructDefinition(name, declarations)
  }

  /**
   * Creates diagnostics for duplicates, and removes the duplicates from the list.
   */
  private fun removeDuplicates(specList: MutableList<Keyword>) {
    for (spec in specList) {
      val iter = specList.iterator()
      for (otherSpec in iter) {
        if (spec !== otherSpec && spec.value == otherSpec.value) {
          diagDuplicate(otherSpec)
          iter.remove()
        }
      }
    }
  }

  /**
   * C standard: 6.7.1.2
   * @return the [Keywords.THREAD_LOCAL] keyword and the storage class that was found (if any)
   */
  private fun validateStorageClass(storageSpecs: List<Keyword>): Pair<Keyword?, Keyword?> {
    var threadLocal: Keyword? = null
    var storageClass: Keyword? = null
    for (spec in storageSpecs) {
      if (spec.value == Keywords.THREAD_LOCAL) {
        if (storageClass == null ||
            storageClass.value == Keywords.STATIC || storageClass.value == Keywords.EXTERN) {
          threadLocal = spec
        } else {
          diagIncompat(storageClass.value.keyword, spec)
        }
        continue
      }
      if (storageClass != null) {
        diagIncompat(storageClass.value.keyword, spec)
        continue
      }
      if (threadLocal != null && spec.value != Keywords.STATIC && spec.value != Keywords.EXTERN) {
        diagIncompat(Keywords.THREAD_LOCAL.keyword, spec)
        continue
      }
      storageClass = spec
    }
    return threadLocal to storageClass
  }

  override fun parseDeclSpecifiers(): DeclarationSpecifier {
    val startTok = current()

    val storageSpecs = mutableListOf<Keyword>()
    val typeQuals = mutableListOf<Keyword>()
    val funSpecs = mutableListOf<Keyword>()
    var typeSpecifier: TypeSpecifier? = null

    specLoop@ while (isNotEaten()) {
      val tok = current() as? Keyword ?: break@specLoop
      when (tok.value) {
        Keywords.COMPLEX -> parserDiagnostic {
          id = DiagnosticId.UNSUPPORTED_COMPLEX
          errorOn(safeToken(0))
        }
        Keywords.STRUCT, Keywords.UNION -> {
          if (typeSpecifier != null) diagIncompat(typeSpecifier.toString(), tok)
          parseStructUnion()?.let { typeSpecifier = it }
          // The function deals with eating, so the eat() below should be skipped
          continue@specLoop
        }
        in typeSpecifiers -> typeSpecifier = typeSpecifier combineWith tok
        in storageClassSpecifiers -> storageSpecs += tok
        in typeQualifiers -> typeQuals += tok
        in funSpecifiers -> funSpecs += tok
        else -> break@specLoop
      }
      eat()
    }
    // We found declaration specifiers, so this *is* a declarator, but there are no type specs
    if ((storageSpecs.isNotEmpty() || typeQuals.isNotEmpty() || funSpecs.isNotEmpty()) &&
        typeSpecifier == null) {
      parserDiagnostic {
        id = DiagnosticId.MISSING_TYPE_SPEC
        errorOn(safeToken(0))
      }
    }

    removeDuplicates(storageSpecs)
    removeDuplicates(typeQuals)
    removeDuplicates(funSpecs)

    val (threadLocal, storageClass) = validateStorageClass(storageSpecs)

    val isEmpty =
        storageSpecs.isEmpty() && funSpecs.isEmpty() && typeQuals.isEmpty() && typeSpecifier == null

    return DeclarationSpecifier(
        storageClass = storageClass,
        threadLocal = threadLocal,
        functionSpecs = funSpecs,
        typeQualifiers = typeQuals,
        typeSpec = typeSpecifier,
        range = if (isEmpty) null else startTok until safeToken(0)
    )
  }

  companion object {
    private val storageClassSpecifiers = listOf(Keywords.TYPEDEF, Keywords.EXTERN, Keywords.STATIC,
        Keywords.AUTO, Keywords.REGISTER, Keywords.THREAD_LOCAL)
    private val typeSpecifiers = listOf(Keywords.VOID, Keywords.CHAR, Keywords.SHORT, Keywords.INT,
        Keywords.LONG, Keywords.FLOAT, Keywords.DOUBLE, Keywords.SIGNED, Keywords.UNSIGNED,
        Keywords.BOOL, Keywords.COMPLEX)
    private val typeQualifiers =
        listOf(Keywords.CONST, Keywords.RESTRICT, Keywords.VOLATILE, Keywords.ATOMIC)
    private val funSpecifiers = listOf(Keywords.NORETURN, Keywords.INLINE)
  }
}
