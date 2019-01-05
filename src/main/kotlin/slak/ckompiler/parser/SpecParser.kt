package slak.ckompiler.parser

import slak.ckompiler.DiagnosticId
import slak.ckompiler.lexer.Keyword
import slak.ckompiler.lexer.Keywords
import slak.ckompiler.parser.TypeSpecifier.*
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
    CHAR -> if (isSigned) SIGNED_CHAR else UNSIGNED_CHAR
    SHORT -> if (isSigned) SIGNED_SHORT else UNSIGNED_SHORT
    INT -> if (isSigned) SIGNED_INT else UNSIGNED_INT
    LONG -> if (isSigned) SIGNED_LONG else UNSIGNED_LONG
    LONG_LONG -> if (isSigned) SIGNED_LONG_LONG else UNSIGNED_LONG_LONG
    SIGNED, SIGNED_CHAR, SIGNED_SHORT, SIGNED_INT, SIGNED_LONG, SIGNED_LONG_LONG -> {
      if (isSigned) diagDuplicate(debug)
      else diagIncompat(this, debug)
      this
    }
    UNSIGNED, UNSIGNED_CHAR, UNSIGNED_SHORT, UNSIGNED_INT, UNSIGNED_LONG, UNSIGNED_LONG_LONG -> {
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
   * This function takes a [TypeSpecifier], and changes it based on the next keyword. Works great
   * with [List.fold].
   *
   * Examples:
   * 1. [TypeSpecifier.CHAR] + [Keywords.UNSIGNED] => [TypeSpecifier.UNSIGNED_CHAR]
   * 2. [TypeSpecifier.DOUBLE] + [Keywords.LONG] => [TypeSpecifier.LONG_DOUBLE]
   * 3. [TypeSpecifier.SIGNED_LONG] + [Keywords.LONG] => [TypeSpecifier.SIGNED_LONG_LONG]
   * 4. etc.
   */
  private infix fun TypeSpecifier.combineWith(next: Keyword): TypeSpecifier {
    if (next.value == Keywords.SIGNED || next.value == Keywords.UNSIGNED) {
      return this.signSpec(next.value == Keywords.SIGNED, next)
    }
    when (this) {
      LONG -> when (next.value) {
        Keywords.DOUBLE -> return LONG_DOUBLE
        Keywords.LONG -> return LONG_LONG
        else -> diagIncompat(this, next)
      }
      SIGNED_LONG -> when (next.value) {
        Keywords.LONG -> return SIGNED_LONG_LONG
        else -> diagIncompat(this, next)
      }
      UNSIGNED_LONG -> when (next.value) {
        Keywords.LONG -> return UNSIGNED_LONG_LONG
        else -> diagIncompat(this, next)
      }
      DOUBLE -> when (next.value) {
        Keywords.LONG -> return LONG_DOUBLE
        else -> diagIncompat(this, next)
      }
      SIGNED -> when (next.value) {
        Keywords.CHAR -> return SIGNED_CHAR
        Keywords.SHORT -> return SIGNED_SHORT
        Keywords.INT -> return SIGNED_INT
        Keywords.LONG -> return SIGNED_LONG
        else -> diagNotSigned(this, next)
      }
      UNSIGNED -> when (next.value) {
        Keywords.CHAR -> return UNSIGNED_CHAR
        Keywords.SHORT -> return UNSIGNED_SHORT
        Keywords.INT -> return UNSIGNED_INT
        Keywords.LONG -> return UNSIGNED_LONG
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
  private fun parseTypeSpecifier(typeSpec: List<Keyword>): TypeSpecifier {
    if (typeSpec.isEmpty()) {
      logger.throwICE("Empty list of type specs") { typeSpec }
    }

    val init = when (typeSpec[0].value) {
      Keywords.SIGNED -> SIGNED
      Keywords.UNSIGNED -> UNSIGNED
      Keywords.VOID -> VOID
      Keywords.BOOL -> BOOL
      Keywords.CHAR -> CHAR
      Keywords.SHORT -> SHORT
      Keywords.INT -> INT
      Keywords.LONG -> LONG
      Keywords.FLOAT -> FLOAT
      Keywords.DOUBLE -> DOUBLE
      else -> logger.throwICE("Bad keyword interpreted as type specifier") { typeSpec[0] }
    }

    return typeSpec.drop(1).fold(init) { type, nextKeyword -> type combineWith nextKeyword }
  }

  override fun parseDeclSpecifiers(): DeclarationSpecifier {
    val contextEnd = indexOfFirst { it !is Keyword }.let { if (it == -1) tokenCount else it }
    return tokenContext(contextEnd) {
      val storageSpecs = mutableListOf<Keyword>()
      val typeSpecs = mutableListOf<Keyword>()
      val typeQuals = mutableListOf<Keyword>()
      val funSpecs = mutableListOf<Keyword>()
      it.takeWhile { tok ->
        when ((tok as Keyword).value) {
          Keywords.COMPLEX -> parserDiagnostic {
            id = DiagnosticId.UNSUPPORTED_COMPLEX
            errorOn(safeToken(0))
          }
          Keywords.TYPEDEF -> logger.throwICE("Typedef not implemented") { this }
          in storageClassSpecifier -> storageSpecs.add(tok)
          in typeSpecifier -> typeSpecs.add(tok)
          in typeQualifier -> typeQuals.add(tok)
          in funSpecifier -> funSpecs.add(tok)
          else -> return@takeWhile false
        }
        eat()
        return@takeWhile true
      }
      val ts: TypeSpecifier?
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
      val isEmpty = storageSpecs.isEmpty() && funSpecs.isEmpty() &&
          typeQuals.isEmpty() && typeSpecs.isEmpty()
      DeclarationSpecifier(
          storageClassSpecs = storageSpecs,
          functionSpecs = funSpecs,
          typeSpecifiers = typeSpecs,
          typeQualifiers = typeQuals,
          typeSpec = ts,
          range = if (isEmpty) null else tokenAt(0) until safeToken(0)
      )
    }
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
