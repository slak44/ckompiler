package slak.ckompiler

import mu.KLogger
import mu.KotlinLogging
import slak.ckompiler.DiagnosticKind.*
import slak.ckompiler.lexer.Token
import kotlin.math.max
import kotlin.math.min

enum class DiagnosticId(val kind: DiagnosticKind, val messageFormat: String) {
  UNKNOWN(OTHER, ""),

  // Lexer
  INVALID_SUFFIX(ERROR, "Invalid suffix '%s' on %s constant"),
  MISSING_QUOTE(ERROR, "Missing terminating %c character"),
  NO_EXP_DIGITS(ERROR, "Exponent has no digits"),
  INVALID_DIGIT(ERROR, "Invalid digit '%s' in constant"),
  UNFINISHED_COMMENT(ERROR, "Unterminated /* comment"),

  // Parser
  // FIXME: this should be a warning printed by the preprocessor
  EMPTY_CHAR_CONSTANT(ERROR, "Empty character constant"),
  EXPECTED_EXPR(ERROR, "Expected expression"),
  EXPECTED_PRIMARY(ERROR, "Expected primary expression"),
  EXPECTED_EXTERNAL_DECL(ERROR, "Expected a declaration or a function definition"),
  EXPECTED_IDENT(ERROR, "Expected identifier"),
  UNMATCHED_PAREN(ERROR, "Expected '%s'"),
  MATCH_PAREN_TARGET(OTHER, "To match this '%s'"),
  EXPECTED_DECL(ERROR, "Expected declarator"),
  EXPECTED_SEMI_AFTER(ERROR, "Expected ';' after %s"),
  EXPECTED_LPAREN_AFTER(ERROR, "Expected '(' after %s"),
  EXPECTED_SEMI_IN_FOR(ERROR, "Expected ';' in 'for' statement specifier"),
  UNEXPECTED_IN_FOR(ERROR, "Unexpected token in 'for' statement specifier"),
  EXPECTED_IDENT_OR_PAREN(ERROR, "Expected identifier or '('"),
  EXPECTED_STATEMENT(ERROR, "Expected statement"),
  EXPECTED_RPAREN_AFTER_VARIADIC(ERROR, "Expected ')' after variadic '...'"),
  TRANSLATION_UNIT_NEEDS_DECL(WARNING, """
    ISO C requires a translation unit to contain at least one declaration
  """.trimIndent()),

  // Declaration Specifier issues
  DUPLICATE_DECL_SPEC(WARNING, "Duplicate '%s' declaration specifier"),
  INCOMPATIBLE_DECL_SPEC(ERROR, "Cannot combine with previous '%s' declaration specifier"),
  // clang puts up a warning; the standard says we error
  MISSING_TYPE_SPEC(ERROR, "Type specifier missing"),
  // Implementations are allowed to not support complex numbers
  UNSUPPORTED_COMPLEX(ERROR, "_Complex is not supported by this implementation"),
  TYPE_NOT_SIGNED(ERROR, "'%s' cannot be signed or unsigned"),
  ANON_STRUCT_MUST_DEFINE(ERROR, "Declaration of anonymous struct must be a definition"),
  SPEC_NOT_ALLOWED(ERROR, "Type name does not allow %s to be specified"),
  MISSING_DECLARATIONS(WARNING, "Declaration does not declare anything"),
  PARAM_BEFORE_VARIADIC(ERROR, "ISO C requires a named parameter before '...'"),
  ILLEGAL_STORAGE_CLASS(ERROR, "Illegal storage class '%s' on %s"),
  NO_DEFAULT_ARGS(ERROR, "C does not support default arguments"),
  TYPEDEF_NO_INITIALIZER(ERROR, "Illegal initializer (only variables can be initialized)"),
  TYPEDEF_REQUIRES_NAME(WARNING, "typedef requires a name"),

  // Scope issues
  REDEFINITION(ERROR, "Redefinition of '%s'"),
  REDEFINITION_LABEL(ERROR, "Redefinition of label '%s'"),
  REDEFINITION_TYPEDEF(ERROR, "typedef redefinition with different types ('%s' vs '%s')"),
  REDEFINITION_PREVIOUS(OTHER, "Previous definition is here"),
  TAG_MISMATCH(ERROR, "Use of '%s' with tag type that does not match previous declaration"),
  TAG_MISMATCH_PREVIOUS(OTHER, "Previous use is here"),
  USE_UNDECLARED(ERROR, "Use of undeclared identifier '%s'"),
}

enum class DiagnosticKind(val text: String) {
  ERROR("error"), WARNING("warning"), OTHER("note")
}

typealias SourceFileName = String

fun IntRange.length() = endInclusive + 1 - start

data class Diagnostic(val id: DiagnosticId,
                      val messageFormatArgs: List<Any>,
                      val sourceFileName: SourceFileName,
                      val sourceText: String,
                      val sourceColumns: List<IntRange>,
                      val origin: String) {
  val caret: IntRange get() = sourceColumns[0]

  /**
   * @returns (line, col, lineText) of the [col] in the [sourceText]
   */
  fun errorOf(
      col: IntRange?): Triple<Int, Int, String> = if (sourceText.isNotEmpty() && col != null) {
    var currLine = 1
    var currLineStart = 0
    var currLineText = ""
    sourceText
    for ((idx, it) in sourceText.withIndex()) {
      if (it == '\n') {
        currLineText = sourceText.slice(currLineStart until idx)
        if (col.start in currLineStart..idx) {
          break
        }
        currLine++
        currLineStart = idx + 1
      }
      if (idx == sourceText.length - 1) {
        currLineText = sourceText.slice(currLineStart until sourceText.length)
      }
    }
    Triple(currLine, col.start - currLineStart, currLineText)
  } else {
    Triple(-1, -1, "???")
  }

  private val printable: String by lazy {
    val (line, col, lineText) = errorOf(if (sourceColumns.isNotEmpty()) caret else null)
    val msg = id.messageFormat.format(*messageFormatArgs.toTypedArray())
    val spacesCount = max(col, 0)
    val tildeCount = min(
        max(caret.length() - 1, 0), // Size of provided range (caret eats one)
        max(lineText.length - spacesCount + 1, 0) // Size of spaces + 1 for the caret
    )
    val firstLine = "$sourceFileName:$line:$col: ${id.kind.text}: $msg [$origin|${id.name}]"
    val caretLine = " ".repeat(spacesCount) + '^' + "~".repeat(tildeCount)
    // FIXME add tildes for the other sourceColumns
    return@lazy "$firstLine\n$lineText\n$caretLine"
  }

  fun print() = println(printable)

  override fun toString(): String = "${javaClass.simpleName}[$printable]"
}

class DiagnosticBuilder {
  var id: DiagnosticId = DiagnosticId.UNKNOWN
  var sourceFileName: SourceFileName = "<unknown>"
  var sourceText: String = ""
  var origin: String = "<unknown>"
  var messageFormatArgs: List<Any> = listOf()
  private var sourceColumns = mutableListOf<IntRange>()

  fun column(col: Int) {
    sourceColumns.add(col..col)
  }

  fun columns(range: IntRange) {
    sourceColumns.add(range)
  }

  fun errorOn(token: Token) {
    sourceColumns.add(token.range)
  }

  fun formatArgs(vararg args: Any) {
    messageFormatArgs = args.toList()
  }

  fun create() =
      Diagnostic(id, messageFormatArgs, sourceFileName, sourceText, sourceColumns, origin)
}

fun createDiagnostic(build: DiagnosticBuilder.() -> Unit): Diagnostic {
  val builder = DiagnosticBuilder()
  builder.build()
  return builder.create()
}

/**
 * Useful for delegation.
 * @see DebugHandler
 */
interface IDebugHandler {
  val logger: KLogger
  val diags: List<Diagnostic>
  fun diagnostic(build: DiagnosticBuilder.() -> Unit)
}

/**
 * This class handles [Diagnostic]s and logging for a particular source
 * (eg [slak.ckompiler.parser.Parser]). It is intended for use with delegation via [IDebugHandler].
 */
class DebugHandler(private val diagSource: String,
                   private val srcFileName: SourceFileName,
                   private val srcText: String) : IDebugHandler {
  override val logger: KLogger get() = KotlinLogging.logger(diagSource)
  override val diags = mutableListOf<Diagnostic>()

  override fun diagnostic(build: DiagnosticBuilder.() -> Unit) {
    diags += createDiagnostic {
      sourceFileName = srcFileName
      sourceText = srcText
      origin = diagSource
      this.build()
    }
  }
}

/**
 * Signals things that "can't" happen. Should never be thrown during normal execution, should not be
 * caught anywhere.
 */
class InternalCompilerError : RuntimeException {
  constructor(message: String, cause: Throwable) : super(message, cause)
  constructor(message: String) : super(message)
}

inline fun KLogger.throwICE(ice: InternalCompilerError, crossinline msg: () -> Any?): Nothing {
  error(ice) { msg() }
  throw ice
}

inline fun KLogger.throwICE(iceMessage: String,
                            cause: Throwable, crossinline msg: () -> Any?): Nothing {
  throwICE(InternalCompilerError(iceMessage, cause), msg)
}

inline fun KLogger.throwICE(iceMessage: String, crossinline msg: () -> Any?): Nothing {
  throwICE(InternalCompilerError(iceMessage), msg)
}

