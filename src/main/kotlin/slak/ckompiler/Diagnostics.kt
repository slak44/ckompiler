package slak.ckompiler

import io.github.oshai.kotlinlogging.KLogger
import slak.ckompiler.DiagnosticKind.*
import kotlin.js.JsExport
import kotlin.math.min

expect class DiagnosticColors(useColors: Boolean) {
  val green: (String) -> String
  val brightRed: (String) -> String
  val brightMagenta: (String) -> String
  val blue: (String) -> String
}

expect fun String.format(vararg args: Any): String

// FIXME: learn from "http://blog.llvm.org/2010/04/amazing-feats-of-clang-error-recovery.html"
@JsExport
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
  PRAGMA_IGNORED(WARNING, "Pragma ignored"),

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
  EXPECTED_COLON_AFTER(ERROR, "Expected ':' after '%s'"),
  EXPECTED_SEMI_IN_FOR(ERROR, "Expected ';' in 'for' statement specifier"),
  UNEXPECTED_IN_FOR(ERROR, "Unexpected token in 'for' statement specifier"),
  EXPECTED_IDENT_OR_PAREN(ERROR, "Expected identifier or '('"),
  EXPECTED_STATEMENT(ERROR, "Expected statement"),
  EXPECTED_RPAREN_AFTER_VARIADIC(ERROR, "Expected ')' after variadic '...'"),
  EXPECTED_PARAM_DECL(ERROR, "Expected parameter declarator"),
  EXPECTED_ENUM_INIT(ERROR, "Expected '= constant-expression' or end of enumerator list"),
  UNEXPECTED_SWITCH_LABEL(ERROR, "Unexpected '%s' label outside switch statement"),
  TRANSLATION_UNIT_NEEDS_DECL(WARNING,
      "ISO C requires a translation unit to contain at least one declaration"),
  ARRAY_SIZE_MISSING(ERROR, "Array size missing for '%s'"),
  ARRAY_STATIC_NO_SIZE(ERROR, "'static' may not be used without an array size"),
  UNSUPPORTED_VLA(ERROR, "Variable length arrays are not supported by this implementation"),
  PARAM_NAME_OMITTED(ERROR, "Parameter name omitted for type '%s'"),
  FOR_INIT_NON_LOCAL(ERROR, "Declaration of non-local variable in 'for' loop"),
  EXPR_NOT_CONSTANT(ERROR, "Expression is not a constant expression"),
  EXPR_NOT_CONSTANT_INT(ERROR, "Expression is not an integer constant expression"),
  FUN_CALL_ARG_COUNT(ERROR, "Too %s arguments to function call, expected '%d', got '%d'"),
  FUN_CALL_ARG_COUNT_VAR(ERROR,
      "Too few arguments to function call, expected at least '%d', got '%d'"),
  CONTINUE_OUTSIDE_LOOP(ERROR, "'continue' statement found outside loop"),
  BREAK_OUTSIDE_LOOP_SWITCH(ERROR, "'break' statement found outside loop or switch"),
  EXPECTED_DOT_DESIGNATOR(ERROR, "Expected a designator of the form '.field' in this initializer list"),
  EXPECTED_NEXT_DESIGNATOR(ERROR, "Expected '=' or another designator"),

  // Type system
  CALL_OBJECT_TYPE(ERROR, "Called object type '%s' is not a function or function pointer"),
  INVALID_ARGUMENT_UNARY(ERROR, "Invalid argument type '%s' to unary operator '%s'"),
  INVALID_INC_DEC_ARGUMENT(ERROR, "Cannot %s value of type '%s'"),
  INVALID_MOD_LVALUE_INC_DEC(ERROR, "Expression is not a modifiable lvalue"),
  INVALID_RET_TYPE(ERROR, "Function cannot return %s type '%s'"),
  VOID_RETURNS_VALUE(ERROR, "Void function '%s' returns value"),
  NON_VOID_RETURNS_NOTHING(ERROR, "Non-void function '%s' doesn't return a value"),
  DONT_RETURN_VOID_EXPR(WARNING, "Void function '%s' should not return void expression"),
  RET_TYPE_MISMATCH(ERROR, "Function return type '%s' incompatible with returned type '%s'"),
  INVALID_ARR_TYPE(ERROR, "'%s' declared as array of functions of type '%s'"),
  INVALID_ARGS_BINARY(ERROR, "Invalid operands to binary operator '%s': '%s' and '%s'"),
  INVALID_ARGS_TERNARY(ERROR, "Incompatible operand types to ?: operator ('%s' and '%s')"),
  INVALID_SUBSCRIPTED(ERROR,
      "Subscripted value is not a pointer to a complete object type (array or pointer)"),
  SUBSCRIPT_OF_FUNCTION(ERROR, "Subscript of (pointer to) function type '%s'"),
  SUBSCRIPT_NOT_INTEGRAL(ERROR, "Array subscript is not an integral type"),
  SUBSCRIPT_TYPE_CHAR(WARNING, "Array subscript is of type '%s'"),
  INVALID_CAST_TYPE(ERROR, "Conversion to non-scalar type '%s' requested"),
  POINTER_FLOAT_CAST(ERROR,
      "Cannot convert between floating point type '%s' and pointer type '%s'"),
  ILLEGAL_CAST_ASSIGNMENT(ERROR, "Assignment to cast is illegal, lvalue casts are not supported"),
  EXPRESSION_NOT_ASSIGNABLE(ERROR, "Expression is not assignable"),
  CONSTANT_NOT_ASSIGNABLE(ERROR, "Cannot assign to a constant"),
  CONST_QUALIFIED_NOT_ASSIGNABLE(ERROR,
      "Assignment to %s '%s' with const-qualified type '%s'"),
  ARRAY_OF_INCOMPLETE(ERROR, "Array has incomplete element type '%s'"),
  SIZEOF_TYPENAME_PARENS(ERROR, "Expected parentheses around type name in sizeof expression"),
  SIZEOF_ON_BITFIELD(ERROR, "Sizeof applied to a bit-field"),
  SIZEOF_ON_INCOMPLETE(WARNING, "Sizeof applied to incomplete type '%s'"),
  SIZEOF_ON_FUNCTION(WARNING, "Sizeof applied to function type '%s'"),
  ADDRESS_OF_REGISTER(ERROR, "Taking address of register variable '%s'"),
  ADDRESS_OF_BITFIELD(ERROR, "Taking address of bit-field"),
  ADDRESS_REQUIRES_LVALUE(ERROR, "Taking address of rvalue of type '%s'"),
  SWITCH_COND_IS_BOOL(WARNING, "Switch condition has boolean type"),
  VARIABLE_TYPE_INCOMPLETE(ERROR, "Declaring variable of incomplete type '%s'"),
  MEMBER_REFERENCE_NOT_PTR(ERROR, "Member reference type '%s' is not a pointer"),
  MEMBER_BASE_NOT_TAG(ERROR, "Attempt to access member '%s' in non-struct non-union type '%s'"),
  MEMBER_NAME_NOT_FOUND(ERROR, "No such member '%s' in '%s'"),
  DESIGNATOR_FOR_SCALAR(ERROR, "Designator found in initializer for scalar type '%s'"),
  EXCESS_INITIALIZERS_SCALAR(WARNING, "Excess initializers in scalar initializer list"),
  EXCESS_INITIALIZERS(WARNING, "Excess initializers in tag initializer list"),
  EXCESS_INITIALIZERS_ARRAY(WARNING, "Excess initializers in array initializer list"),
  EXCESS_INITIALIZER_SIZE(WARNING, "Initializer array value has size %d which is longer than target array size %d"),
  ARRAY_DESIGNATOR_NON_ARRAY(ERROR, "Array designator cannot initialize non-array type '%s'"),
  DOT_DESIGNATOR_NON_TAG(ERROR, "Field designator cannot initialize non-struct, non-union type '%s'"),
  DOT_DESIGNATOR_NO_FIELD(ERROR, "Field designator '%s' does not refer to any field in type '%s'"),
  INITIALIZER_TYPE_MISMATCH(ERROR, "Expression of type '%s' cannot initialize type '%s'"),
  ARRAY_DESIGNATOR_NEGATIVE(ERROR, "Array designator index %d is negative"),
  ARRAY_DESIGNATOR_BOUNDS(ERROR, "Array designator index %d is out of bounds for this array (%d)"),
  INITIALIZER_OVERRIDES_PRIOR(WARNING, "Initializer overrides prior initialization of this subobject"),
  PRIOR_INITIALIZER(OTHER, "Previous initialization is here"),

  // Declaration Specifier issues
  DUPLICATE_DECL_SPEC(WARNING, "Duplicate '%s' declaration specifier"),
  INCOMPATIBLE_DECL_SPEC(ERROR, "Cannot combine with previous '%s' declaration specifier"),

  // clang puts up a warning; the standard says we error
  MISSING_TYPE_SPEC(ERROR, "Type specifier missing"),

  // Implementations are allowed to not support complex numbers
  UNSUPPORTED_COMPLEX(ERROR, "_Complex is not supported by this implementation"),
  TYPE_NOT_SIGNED(ERROR, "'%s' cannot be signed or unsigned"),
  ANON_TAG_MUST_DEFINE(ERROR, "Declaration of anonymous '%s' must be a definition"),
  ENUM_IS_EMPTY(ERROR, "Empty enum not allowed"),
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
  USE_ENUM_UNDEFINED(ERROR, "Cannot use enum '%s' without previous definition"),

  // Analysis
  UNREACHABLE_CODE(WARNING, "Code will never be executed"),
  UNSEQUENCED_MODS(WARNING, "Multiple unsequenced modifications to '%s'"),
  CONTROL_END_OF_NON_VOID(WARNING,
      "Control flow reaches end of function '%s' with non-void return type '%s'")
}

@JsExport
enum class DiagnosticKind(val text: String) {
  ERROR("error"), WARNING("warning"), OTHER("note")
}

typealias SourceFileName = String

/**
 * Marks an object that corresponds to a range of indices in the program source.
 */
@JsExport
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
    override val expandedFrom: SourcedRange?,
) : SourcedRange

private fun combineSources(
    combinedRange: IntRange,
    src1: SourcedRange,
    src2: SourcedRange,
): SourcedRange = when {
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

fun List<Diagnostic>.errors() = filter { it.id.kind == ERROR }

@JsExport
data class SourceColumnData(val line: Int, val column: Int, val lineText: String)

@JsExport
@Suppress("MemberVisibilityCanBePrivate") // JS-exported
data class Diagnostic(
    val id: DiagnosticId,
    val messageFormatArgs: List<Any>,
    val sourceColumns: List<SourcedRange>,
    val origin: String,
) {
  val caret: SourcedRange get() = sourceColumns[0]

  fun dataFor(col: SourcedRange): SourceColumnData {
    if (col.sourceText!!.isEmpty()) return SourceColumnData(1, 0, "")
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
    return SourceColumnData(currLine, col.range.first - currLineStart, currLineText)
  }

  val formattedMessage get() = id.messageFormat.format(*messageFormatArgs.toTypedArray())

  val printable: String by lazy {
    val color = DiagnosticColors(useColors)
    val (line, col, lineText) =
        if (sourceColumns.isNotEmpty()) dataFor(caret)
        else SourceColumnData(-1, -1, "???")
    val kindText = when (id.kind) {
      ERROR -> color.brightRed
      WARNING -> color.brightMagenta
      OTHER -> color.blue
    }("${id.kind.text}:")
    val srcFileName = if (sourceColumns.isNotEmpty()) caret.sourceFileName else "<unknown>"
    val firstLine = "$srcFileName:$line:$col: $kindText $formattedMessage [$origin|${id.name}]"
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
        .filter { it.second.line == line }
        .map {
          val startIdx = it.second.column.coerceAtLeast(0)
          startIdx until (startIdx + it.first.length()).coerceAtLeast(1)
        }
        .fold(originalCaretLine) { caretLine, it ->
          caretLine.replaceRange(it, "~".repeat(it.length()))
        }
    val expandedDiags = sourceColumns
        .asSequence()
        .filter { it.expandedFrom != null }
        .map {
          Diagnostic(
              DiagnosticId.EXPANDED_FROM,
              listOf(it.expandedName!!),
              listOf(it.expandedFrom!!),
              origin
          )
        }
        .joinToString("\n") { it.printable }
        .let { if (it.isNotEmpty()) "\n$it" else it }
    return@lazy "$firstLine\n$lineText\n${color.green(caretLine)}$expandedDiags"
  }

  fun print() = println(printable)

  override fun toString(): String = "${this::class.simpleName}[\n$printable]"

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
@JsExport
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
@JsExport
class DebugHandler(
    private val diagSource: String,
    private val srcFileName: SourceFileName,
    private val srcText: String,
) : IDebugHandler {
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

fun KLogger.throwICE(iceMessage: String): Nothing {
  val ice = InternalCompilerError(iceMessage)
  error(ice) {}
  throw ice
}

fun KLogger.throwICE(ice: InternalCompilerError, msg: () -> Any?): Nothing {
  error(ice, msg)
  throw ice
}

fun KLogger.throwICE(iceMessage: String, cause: Throwable, msg: () -> Any?): Nothing {
  throwICE(InternalCompilerError(iceMessage, cause), msg)
}

fun KLogger.throwICE(iceMessage: String, msg: () -> Any?): Nothing {
  throwICE(InternalCompilerError(iceMessage), msg)
}
