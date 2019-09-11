package slak.ckompiler

import com.github.ajalt.mordant.TermColors
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.message.ObjectMessage
import slak.ckompiler.DiagnosticKind.*
import kotlin.math.min

// FIXME: learn from "http://blog.llvm.org/2010/04/amazing-feats-of-clang-error-recovery.html"
enum class DiagnosticId(val kind: DiagnosticKind, val messageFormat: String) {
  UNKNOWN(OTHER, ""),

  EXPANDED_FROM(OTHER, "Expanded from macro '%s'"),

  // CLI
  BAD_CLI_OPTION(ERROR, "Unrecognized command line option '%s'"),
  FILE_IS_DIRECTORY(ERROR, "File is a directory: '%s'"),
  NO_INPUT_FILES(ERROR, "No input files"),
  MULTIPLE_FILES_PARTIAL(ERROR,
      "Cannot specify explicit output file when generating multiple outputs"),
  CFG_NO_SUCH_FUNCTION(ERROR, "Function '%s' not found"),

  // Preprocessor/Lexer
  TRIGRAPH_IGNORED(WARNING, "Trigraph ignored"),
  TRIGRAPH_PROCESSED(WARNING, "Trigraph converted to '%s' character"),
  EXPECTED_HEADER_NAME(ERROR, "Expected \"FILENAME\" or <FILENAME>"),
  EXPECTED_H_Q_CHAR_SEQUENCE(ERROR, "Expected %cFILENAME%c"),
  EMPTY_CHAR_CONSTANT(ERROR, "Empty character constant"),
  INVALID_SUFFIX(ERROR, "Invalid suffix '%s' on %s constant"),
  MISSING_QUOTE(ERROR, "Missing terminating %c character"),
  NO_EXP_DIGITS(ERROR, "Exponent has no digits"),
  UNFINISHED_COMMENT(ERROR, "Unterminated /* comment"),
  INVALID_PP_DIRECTIVE(ERROR, "Invalid preprocessing directive '%s'"),
  PP_ERROR_DIRECTIVE(ERROR, "%s"),
  MACRO_NAME_MISSING(ERROR, "Macro name missing"),
  MACRO_NAME_NOT_IDENT(ERROR, "Macro name must be an identifier"),
  // clang puts up a warning; the standard says we error
  MACRO_REDEFINITION(WARNING, "'%s' macro redefined"),
  EXTRA_TOKENS_DIRECTIVE(WARNING, "Extra tokens at end of %s directive"),
  FILE_NOT_FOUND(ERROR, "File not found: '%s'"),
  UNTERMINATED_CONDITIONAL(ERROR, "Unterminated conditional directive"),
  ELSE_NOT_LAST(ERROR, "%s after #else"),
  DIRECTIVE_WITHOUT_IF(ERROR, "%s without #if"),
  ELIF_NO_CONDITION(ERROR, "#elif condition missing"),
  INVALID_LITERAL_IN_PP(ERROR, "Invalid %s literal in preprocessor expression"),
  NOT_DEFINED_IS_0(WARNING, "'%s' is not defined, evaluates to 0"),

  // Parser
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
  TRANSLATION_UNIT_NEEDS_DECL(WARNING,
      "ISO C requires a translation unit to contain at least one declaration"),
  ARRAY_SIZE_MISSING(ERROR, "Array size missing for '%s'"),
  ARRAY_STATIC_NO_SIZE(ERROR, "'static' may not be used without an array size"),
  UNSUPPORTED_VLA(ERROR, "Variable length arrays are not supported by this implementation"),
  PARAM_NAME_OMITTED(ERROR, "Parameter name omitted for type '%s'"),
  FOR_INIT_NON_LOCAL(ERROR, "Declaration of non-local variable in 'for' loop"),
  EXPR_NOT_CONSTANT(ERROR, "Expression is not a constant expression"),
  FUN_CALL_ARG_COUNT(ERROR, "Too %s arguments to function call, expected '%d', got '%d'"),
  FUN_CALL_ARG_COUNT_VAR(ERROR,
      "Too few arguments to function call, expected at least '%d', got '%d'"),

  // Type system
  CALL_OBJECT_TYPE(ERROR, "Called object type '%s' is not a function or function pointer"),
  INVALID_ARGUMENT_UNARY(ERROR, "Invalid argument type '%s' to unary operator '%s'"),
  INVALID_INC_DEC_ARGUMENT(ERROR, "Cannot %s value of type '%s'"),
  INVALID_RET_TYPE(ERROR, "Function cannot return %s type '%s'"),
  INVALID_ARR_TYPE(ERROR, "'%s' declared as array of functions of type '%s'"),
  INVALID_ARGS_BINARY(ERROR, "Invalid operands to binary operator '%s': '%s' and '%s'"),
  INVALID_ARGS_TERNARY(ERROR, "Incompatible operand types to ?: operator ('%s' and '%s')"),
  INVALID_SUBSCRIPTED(ERROR,
      "Subscripted value is not a pointer to a complete object type (array or pointer)"),
  SUBSCRIPT_OF_FUNCTION(ERROR, "Subscript of (pointer to) function type '%s'"),
  SUBSCRIPT_NOT_INTEGRAL(ERROR, "Array subscript is not an integral type"),
  INVALID_CAST_TYPE(ERROR, "Conversion to non-scalar type '%s' requested"),
  POINTER_FLOAT_CAST(ERROR,
      "Cannot convert between floating point type '%s' and pointer type '%s'"),
  ILLEGAL_CAST_ASSIGNMENT(ERROR, "Assignment to cast is illegal, lvalue casts are not supported"),
  EXPRESSION_NOT_ASSIGNABLE(ERROR, "Expression is not assignable"),
  CONSTANT_NOT_ASSIGNABLE(ERROR, "Cannot assign to a constant"),
  ARRAY_OF_INCOMPLETE(ERROR, "Array has incomplete element type '%s'"),
  SIZEOF_ON_BITFIELD(ERROR, "Sizeof applied to a bitfield"),
  SIZEOF_ON_INCOMPLETE(WARNING, "Sizeof applied to incomplete type '%s'"),
  SIZEOF_ON_FUNCTION(WARNING, "Sizeof applied to function type '%s'"),

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
  FUNC_DEF_HAS_TYPEDEF(ERROR, "Function definition declared 'typedef'"),

  // Scope issues
  REDEFINITION(ERROR, "Redefinition of '%s'"),
  REDEFINITION_LABEL(ERROR, "Redefinition of label '%s'"),
  REDEFINITION_TYPEDEF(ERROR, "typedef redefinition with different types ('%s' vs '%s')"),
  REDEFINITION_OTHER_SYM(ERROR, "Redefinition of '%s' as different kind of symbol (was %s, is %s)"),
  REDEFINITION_PREVIOUS(OTHER, "Previous definition is here"),
  TAG_MISMATCH(ERROR, "Use of '%s' with tag type that does not match previous declaration"),
  TAG_MISMATCH_PREVIOUS(OTHER, "Previous use is here"),
  USE_UNDECLARED(ERROR, "Use of undeclared identifier '%s'"),
  UNEXPECTED_TYPEDEF_USE(ERROR,
      "Unexpected type name '%s' (aka '%s'); expected primary expression"),

  // Analysis
  UNREACHABLE_CODE(WARNING, "Code will never be executed"),
  UNSEQUENCED_MODS(WARNING, "Multiple unsequenced modifications to '%s'"),
  CONTROL_END_OF_NON_VOID(WARNING,
      "Control flow reaches end of function '%s' with non-void return type '%s'")
}

enum class DiagnosticKind(val text: String) {
  ERROR("error"), WARNING("warning"), OTHER("note")
}

typealias SourceFileName = String

/**
 * Marks an object that corresponds to a range of indices in the program source.
 */
interface SourcedRange : ClosedRange<Int> {
  /**
   * Name of the translation unit's file. Has other values for test classes. Can be null if unknown.
   */
  val sourceFileName: SourceFileName?
  /**
   * Reference to the source text this ranges is sourced from. Can be null if unknown.
   */
  val sourceText: String?
  /**
   * Actual range inside [sourceText].
   */
  val range: IntRange
  /**
   * Name of the macro this range was expanded from. (null if not macro-expanded)
   */
  val expandedName: String?
  /**
   * For macro-expanded ranges, the range in the macro. (null if not macro-expanded)
   * For example:
   * ```
   * #define ASD 123 +
   * int a = ASD;
   * ```
   * This creates a diagnostic on the `+` token.
   * Its `range` will be on the line of `a`'s declaration, on `ASD`.
   * This property will be on the define's line, at the `+`'s actual position.
   */
  val expandedFrom: SourcedRange?

  override val endInclusive: Int get() = range.last
  override val start: Int get() = range.first

  fun cloneSource(): SourcedRange =
      ClonedSourcedRange(sourceFileName, sourceText, range, expandedName, expandedFrom)
}

fun ClosedRange<Int>.length() = endInclusive + 1 - start

private data class ClonedSourcedRange(
    override val sourceFileName: SourceFileName?,
    override val sourceText: String?,
    override val range: IntRange,
    override val expandedName: String?,
    override val expandedFrom: SourcedRange?
) : SourcedRange

private fun combineSources(combinedRange: IntRange,
                           src1: SourcedRange,
                           src2: SourcedRange): SourcedRange = when {
  src1.sourceFileName == null -> {
    ClonedSourcedRange(
        src2.sourceFileName, src2.sourceText, combinedRange, src2.expandedName, src2.expandedFrom)
  }
  src2.sourceFileName == null || src1.sourceFileName == src2.sourceFileName -> {
    ClonedSourcedRange(
        src1.sourceFileName, src1.sourceText, combinedRange, src1.expandedName, src1.expandedFrom)
  }
  else -> throw IllegalArgumentException(
      "Trying to combine disjoint sources (${src1.sourceFileName} vs ${src2.sourceFileName})")
}

infix fun SourcedRange.until(other: SourcedRange): SourcedRange {
  val range = range.first until other.range.first
  return combineSources(range, this, other)
}

operator fun SourcedRange.rangeTo(other: SourcedRange): SourcedRange {
  val range = range.first..other.range.last
  return combineSources(range, this, other)
}

data class Diagnostic(
    val id: DiagnosticId,
    val messageFormatArgs: List<Any>,
    val sourceColumns: List<SourcedRange>,
    val origin: String
) {
  private val caret: SourcedRange get() = sourceColumns[0]

  internal fun dataFor(col: SourcedRange): Triple<Int, Int, String> {
    if (col.sourceText!!.isEmpty()) return Triple(1, 0, "")
    var currLine = 1
    var currLineStart = 0
    var currLineText = ""
    for ((idx, it) in col.sourceText!!.withIndex()) {
      if (it == '\n') {
        currLineText = col.sourceText!!.slice(currLineStart until idx)
        if (col.range.first in currLineStart..idx) {
          break
        }
        currLine++
        currLineStart = idx + 1
      }
      if (idx == col.sourceText!!.length - 1) {
        currLineText = col.sourceText!!.slice(currLineStart until col.sourceText!!.length)
      }
    }
    return Triple(currLine, col.range.first - currLineStart, currLineText)
  }

  private val printable: String by lazy {
    val color = TermColors(if (useColors) TermColors.Level.TRUECOLOR else TermColors.Level.NONE)
    val (line, col, lineText) =
        if (sourceColumns.isNotEmpty()) dataFor(caret)
        else Triple(-1, -1, "???")
    // Yes, it makes a copy, but interacting with String.format is basically impossible otherwise
    @SuppressWarnings("SpreadOperator")
    val msg = id.messageFormat.format(*messageFormatArgs.toTypedArray())
    val kindText = when (id.kind) {
      ERROR -> color.brightRed
      WARNING -> color.brightMagenta
      OTHER -> color.blue
    }("${id.kind.text}:")
    val srcFileName = if (sourceColumns.isNotEmpty()) caret.sourceFileName else "<unknown>"
    val firstLine = "$srcFileName:$line:$col: $kindText $msg [$origin|${id.name}]"
    // Special case where the file is empty
    if (sourceColumns.isEmpty() || caret.sourceText!!.isEmpty()) return@lazy firstLine
    val spacesCount = col.coerceAtLeast(0)
    val tildeCount = min(
        (caret.length() - 1).coerceAtLeast(0), // Size of provided range (caret eats one)
        (lineText.length - spacesCount + 1).coerceAtLeast(0) // Size of spaces + 1 for the caret
    )
    val spacesLeftAfterCaret = (lineText.length - spacesCount - tildeCount - 1).coerceAtLeast(0)
    val originalCaretLine =
        " ".repeat(spacesCount) + '^' + "~".repeat(tildeCount) + " ".repeat(spacesLeftAfterCaret)
    val caretLine = sourceColumns
        .asSequence()
        .drop(1)
        .map { it to dataFor(it) }
        // FIXME: add tildes for the sourceColumns on different lines
        .filter { it.second.first == line }
        .map {
          val startIdx = it.second.second.coerceAtLeast(0)
          startIdx until (startIdx + it.first.length()).coerceAtLeast(1)
        }
        .fold(originalCaretLine) { caretLine, it ->
          caretLine.replaceRange(it, "~".repeat(it.length()))
        }
    val expandedDiags = sourceColumns
        .asSequence()
        .filter { it.expandedFrom != null }
        .map { Diagnostic(
            DiagnosticId.EXPANDED_FROM,
            listOf(it.expandedName!!),
            listOf(it.expandedFrom!!),
            origin
        ) }
        .joinToString("\n") { it.printable }
        .let { if (it.isNotEmpty()) "\n$it" else it }
    return@lazy "$firstLine\n$lineText\n${color.green(caretLine)}$expandedDiags"
  }

  fun print() = println(printable)

  override fun toString(): String = "${javaClass.simpleName}[\n$printable]"

  companion object {
    // FIXME: this really should not be global
    var useColors: Boolean = true
  }
}

class DiagnosticBuilder {
  var id: DiagnosticId = DiagnosticId.UNKNOWN
  var sourceFileName: SourceFileName = "<unknown>"
  var sourceText: String = ""
  var origin: String = "<unknown>"
  private var messageFormatArgs: List<Any> = listOf()
  private var sourceColumns = mutableListOf<SourcedRange>()

  fun column(col: Int) {
    sourceColumns.add(ClonedSourcedRange(sourceFileName, sourceText, col..col, null, null))
  }

  fun columns(range: IntRange) {
    sourceColumns.add(ClonedSourcedRange(sourceFileName, sourceText, range, null, null))
  }

  fun errorOn(obj: SourcedRange) {
    require(obj.sourceFileName != null || obj.sourceText != null) { "SourcedRange source is null" }
    sourceColumns.add(obj)
  }

  inline fun <reified T> diagData(data: T) = when (data) {
    is IntRange -> columns(data)
    is Int -> column(data)
    is SourcedRange -> errorOn(data)
    else -> throw IllegalArgumentException("T must be Int, IntRange, or SourcedRange")
  }

  fun formatArgs(vararg args: Any) {
    messageFormatArgs = args.toList()
  }

  fun create() = Diagnostic(id, messageFormatArgs, sourceColumns, origin)
}

/**
 * Useful for delegation.
 * @see DebugHandler
 */
interface IDebugHandler {
  val diags: List<Diagnostic>
  fun createDiagnostic(build: DiagnosticBuilder.() -> Unit): Diagnostic
  fun diagnostic(build: DiagnosticBuilder.() -> Unit)

  // FIXME: use this in preprocessor
  fun includeNestedDiags(diags: List<Diagnostic>)
}

/**
 * This class handles [Diagnostic]s for a particular source (eg [slak.ckompiler.parser.Parser]).
 * It is intended for use with delegation via [IDebugHandler].
 */
class DebugHandler(private val diagSource: String,
                   private val srcFileName: SourceFileName,
                   private val srcText: String) : IDebugHandler {
  override val diags = mutableListOf<Diagnostic>()

  override fun includeNestedDiags(diags: List<Diagnostic>) {
    this.diags += diags
  }

  override fun diagnostic(build: DiagnosticBuilder.() -> Unit) {
    diags += createDiagnostic(build)
  }

  override fun createDiagnostic(build: DiagnosticBuilder.() -> Unit): Diagnostic {
    val builder = DiagnosticBuilder()
    builder.sourceFileName = srcFileName
    builder.sourceText = srcText
    builder.origin = diagSource
    builder.build()
    return builder.create()
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

inline fun Logger.error(t: Throwable, crossinline msg: () -> Any?) {
  error({ msg().toObjectMessage() }, t)
}

fun Any?.toObjectMessage() = ObjectMessage(this)

fun Logger.throwICE(iceMessage: String): Nothing {
  val ice = InternalCompilerError(iceMessage)
  error(ice)
  throw ice
}

inline fun Logger.throwICE(ice: InternalCompilerError, crossinline msg: () -> Any?): Nothing {
  error(ice, msg)
  throw ice
}

inline fun Logger.throwICE(iceMessage: String,
                           cause: Throwable, crossinline msg: () -> Any?): Nothing {
  throwICE(InternalCompilerError(iceMessage, cause), msg)
}

inline fun Logger.throwICE(iceMessage: String, crossinline msg: () -> Any?): Nothing {
  throwICE(InternalCompilerError(iceMessage), msg)
}
