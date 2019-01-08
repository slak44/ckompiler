package slak.ckompiler.parser

import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.Keyword
import slak.ckompiler.lexer.Keywords
import slak.ckompiler.throwICE

interface ISpecParser {
  /** C standard: A.2.2, 6.7 */
  fun parseDeclSpecifiers(): DeclarationSpecifier
}

class SpecParser(tokenHandler: TokenHandler) :
    ISpecParser,
    IDebugHandler by tokenHandler,
    ITokenHandler by tokenHandler {

  private fun diagDuplicate(last: Keyword) = parserDiagnostic {
    id = DiagnosticId.DUPLICATE_DECL_SPEC
    formatArgs(last.value)
    errorOn(last)
  }

  private fun diagIncompat(original: BasicTypeSpecifier, last: Keyword) = parserDiagnostic {
    id = DiagnosticId.INCOMPATIBLE_DECL_SPEC
    formatArgs(original.toString())
    errorOn(last)
  }

  private fun diagNotSigned(original: BasicTypeSpecifier, signed: Keyword) = parserDiagnostic {
    id = DiagnosticId.TYPE_NOT_SIGNED
    formatArgs(original.toString())
    errorOn(signed)
  }

  /**
   * Used in [combineWith] when the next keyword is either [Keywords.SIGNED] or [Keywords.UNSIGNED].
   * If it encounters an error, the diagnostic is issued and the value of [this] is returned.
   */
  private fun BasicTypeSpecifier.signSpec(isSigned: Boolean, debug: Keyword) = when (this) {
    Char -> if (isSigned) SignedChar else UnsignedChar
    Short -> if (isSigned) SignedShort else UnsignedShort
    IntType -> if (isSigned) SignedInt else UnsignedInt
    LongType -> if (isSigned) SignedLong else UnsignedLong
    LongLong -> if (isSigned) SignedLongLong else UnsignedLongLong
    Signed, SignedChar, SignedShort, SignedInt, SignedLong, SignedLongLong -> {
      if (isSigned) diagDuplicate(debug)
      else diagIncompat(this, debug)
      this
    }
    Unsigned, UnsignedChar, UnsignedShort, UnsignedInt, UnsignedLong, UnsignedLongLong-> {
      if (!isSigned) diagDuplicate(debug)
      else diagIncompat(this, debug)
      this
    }
    else -> {
      diagNotSigned(this, debug)
      this
    }
  }

  /**
   * This function takes a [BasicTypeSpecifier], and changes it based on the next keyword.
   * Works great with [List.fold].
   *
   * Examples:
   * 1. [Char] + [Unsigned] => [UnsignedChar]
   * 2. [Double] + [Keywords.LONG] => [LongDouble]
   * 3. [SignedLong] + [Keywords.LONG] => [SignedLongLong]
   * 4. etc.
   */
  private infix fun BasicTypeSpecifier.combineWith(next: Keyword): BasicTypeSpecifier {
    if (next.value == Keywords.SIGNED || next.value == Keywords.UNSIGNED) {
      return this.signSpec(next.value == Keywords.SIGNED, next)
    }
    when (this) {
      LongType -> when (next.value) {
        Keywords.DOUBLE -> return LongDouble
        Keywords.LONG -> return LongLong
        else -> diagIncompat(this, next)
      }
      SignedLong -> when (next.value) {
        Keywords.LONG -> return SignedLongLong
        else -> diagIncompat(this, next)
      }
      UnsignedLong -> when (next.value) {
        Keywords.LONG -> return UnsignedLongLong
        else -> diagIncompat(this, next)
      }
      DoubleType -> when (next.value) {
        Keywords.LONG -> return LongDouble
        else -> diagIncompat(this, next)
      }
      Signed -> when (next.value) {
        Keywords.CHAR -> return SignedChar
        Keywords.SHORT -> return SignedShort
        Keywords.INT -> return SignedInt
        Keywords.LONG -> return SignedLong
        else -> diagNotSigned(this, next)
      }
      Unsigned -> when (next.value) {
        Keywords.CHAR -> return UnsignedChar
        Keywords.SHORT -> return UnsignedShort
        Keywords.INT -> return UnsignedInt
        Keywords.LONG -> return UnsignedLong
        else -> diagNotSigned(this, next)
      }
      else -> diagIncompat(this, next)
    }
    return this
  }

  /**
   * FIXME missing type specifiers (A.2.2/6.7.2):
   * 1. atomic-type-specifier (6.7.2.4)
   * 2. struct-or-union-specifier (6.7.2.1)
   * 3. enum-specifier (6.7.2.2)
   * 4. typedef-name (6.7.8)
   */
  private fun parseTypeSpecifier(typeSpec: List<Keyword>): BasicTypeSpecifier {
    if (typeSpec.isEmpty()) {
      logger.throwICE("Empty list of type specs") { typeSpec }
    }

    val init = when (typeSpec[0].value) {
      Keywords.SIGNED -> Signed
      Keywords.UNSIGNED -> Unsigned
      Keywords.VOID -> VoidType
      Keywords.BOOL -> Bool
      Keywords.CHAR -> Char
      Keywords.SHORT -> Short
      Keywords.INT -> IntType
      Keywords.LONG -> LongType
      Keywords.FLOAT -> FloatType
      Keywords.DOUBLE -> DoubleType
      else -> logger.throwICE("Bad keyword interpreted as type specifier") { typeSpec[0] }
    }

    return typeSpec.drop(1).fold(init) { type, nextKeyword -> type combineWith nextKeyword }
  }

  override fun parseDeclSpecifiers(): DeclarationSpecifier {
    val storageSpecs = mutableListOf<Keyword>()
    val typeSpecs = mutableListOf<Keyword>()
    val typeQuals = mutableListOf<Keyword>()
    val funSpecs = mutableListOf<Keyword>()
    specLoop@ while (true) {
      val tok = current() as? Keyword ?: break@specLoop
      when (tok.value) {
        Keywords.COMPLEX -> parserDiagnostic {
          id = DiagnosticId.UNSUPPORTED_COMPLEX
          errorOn(safeToken(0))
        }
        Keywords.TYPEDEF -> logger.throwICE("Typedef not implemented") { this }
        in storageClassSpecifier -> storageSpecs.add(tok)
        in typeSpecifier -> typeSpecs.add(tok)
        in typeQualifier -> typeQuals.add(tok)
        in funSpecifier -> funSpecs.add(tok)
        else -> break@specLoop
      }
      eat()
    }
    val ts: BasicTypeSpecifier?
    if ((storageSpecs.isNotEmpty() || typeQuals.isNotEmpty() || funSpecs.isNotEmpty()) &&
        typeSpecs.isEmpty()) {
      // We found declaration specifiers, so this *is* a declarator, but there are no type specs
      parserDiagnostic {
        id = DiagnosticId.MISSING_TYPE_SPEC
        errorOn(safeToken(0))
      }
      ts = null
    } else if (typeSpecs.isEmpty()) {
      // This is the case where DeclarationSpecifier.isEmpty is true, so no declarator found
      ts = null
    } else {
      ts = parseTypeSpecifier(typeSpecs)
    }
    val isEmpty =
        storageSpecs.isEmpty() && funSpecs.isEmpty() && typeQuals.isEmpty() && typeSpecs.isEmpty()

    return DeclarationSpecifier(
        storageClassSpecs = storageSpecs,
        functionSpecs = funSpecs,
        typeSpecifiers = typeSpecs,
        typeQualifiers = typeQuals,
        typeSpec = ts,
        range = if (isEmpty) null else tokenAt(0) until safeToken(0)
    )
  }

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
}
