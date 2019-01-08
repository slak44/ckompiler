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

  private fun diagIncompat(original: TypeSpecifier, last: Keyword) = parserDiagnostic {
    id = DiagnosticId.INCOMPATIBLE_DECL_SPEC
    formatArgs(original.toString())
    errorOn(last)
  }

  private fun diagNotSigned(original: TypeSpecifier, signed: Keyword) = parserDiagnostic {
    id = DiagnosticId.TYPE_NOT_SIGNED
    formatArgs(original.toString())
    errorOn(signed)
  }

  /**
   * Used in [combineWith] when the next keyword is either [Keywords.SIGNED] or [Keywords.UNSIGNED].
   * If it encounters an error, the diagnostic is issued and the value of [this] is returned.
   */
  private fun TypeSpecifier.signSpec(isSigned: Boolean, debug: Keyword) = when (this) {
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
   * This function takes a [TypeSpecifier], and changes it based on the next keyword.
   *
   * Examples:
   * 1. [Char] + [Unsigned] => [UnsignedChar]
   * 2. [Double] + [Keywords.LONG] => [LongDouble]
   * 3. [SignedLong] + [Keywords.LONG] => [SignedLongLong]
   * 4. null + [Keywords.INT] => [IntType]
   * 4. etc.
   */
  private infix fun TypeSpecifier?.combineWith(next: Keyword): TypeSpecifier {
    if (this == null) return when (next.value) {
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
      else -> logger.throwICE("Bad keyword interpreted as type specifier") { next }
    }
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

  override fun parseDeclSpecifiers(): DeclarationSpecifier {
    val storageSpecs = mutableListOf<Keyword>()
    val typeQuals = mutableListOf<Keyword>()
    val funSpecs = mutableListOf<Keyword>()
    var typeSpecifier: TypeSpecifier? = null

    specLoop@ while (true) {
      val tok = current() as? Keyword ?: break@specLoop
      when (tok.value) {
        Keywords.COMPLEX -> parserDiagnostic {
          id = DiagnosticId.UNSUPPORTED_COMPLEX
          errorOn(safeToken(0))
        }
        Keywords.TYPEDEF -> logger.throwICE("Typedef not implemented") { this }
        in typeSpecifiers -> typeSpecifier = typeSpecifier combineWith tok
        in storageClassSpecifiers -> storageSpecs.add(tok)
        in typeQualifiers -> typeQuals.add(tok)
        in funSpecifiers -> funSpecs.add(tok)
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
    val isEmpty =
        storageSpecs.isEmpty() && funSpecs.isEmpty() && typeQuals.isEmpty() && typeSpecifier == null

    return DeclarationSpecifier(
        storageClassSpecs = storageSpecs,
        functionSpecs = funSpecs,
        typeQualifiers = typeQuals,
        typeSpec = typeSpecifier,
        range = if (isEmpty) null else tokenAt(0) until safeToken(0)
    )
  }

  companion object {
    private val storageClassSpecifiers =
        listOf(Keywords.EXTERN, Keywords.STATIC, Keywords.AUTO, Keywords.REGISTER)
    private val typeSpecifiers = listOf(Keywords.VOID, Keywords.CHAR, Keywords.SHORT, Keywords.INT,
        Keywords.LONG, Keywords.FLOAT, Keywords.DOUBLE, Keywords.SIGNED, Keywords.UNSIGNED,
        Keywords.BOOL, Keywords.COMPLEX)
    private val typeQualifiers =
        listOf(Keywords.CONST, Keywords.RESTRICT, Keywords.VOLATILE, Keywords.ATOMIC)
    private val funSpecifiers = listOf(Keywords.NORETURN, Keywords.INLINE)
  }
}
