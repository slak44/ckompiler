import mu.KLogger
import java.lang.RuntimeException
import DiagnosticKind.*

enum class DiagnosticId(val kind: DiagnosticKind, val messageFormat: String) {
  UNKNOWN(OTHER, ""),
  // Lexer
  INVALID_SUFFIX(ERROR, "Invalid suffix '%s' on %s constant"),
  MISSING_QUOTE(ERROR, "Missing terminating %c character"),
  NO_EXP_DIGITS(ERROR, "Exponent has no digits"),
  INVALID_DIGIT(ERROR, "Invalid digit '%s' in constant"),
  // Parser
  EMPTY_CHAR_CONSTANT(WARNING, "Empty character constant"),
  EXPECTED_EXPR(ERROR, "Expected expression"),
  EXPECTED_PRIMARY(ERROR, "Expected primary expression"),
  EXPECTED_EXTERNAL_DECL(ERROR, "Expected a declaration or function definition")
}

enum class DiagnosticKind(val text: String) {
  ERROR("error"), WARNING("warning"), OTHER("note")
}

typealias SourceFile = String

data class Diagnostic(val id: DiagnosticId,
                      val messageFormatArgs: List<Any>,
                      val sourceFile: SourceFile,
                      val sourceColumns: List<IntRange>,
                      val origin: String) {
  private val printed: String

  init {
    val msg = id.messageFormat.format(*messageFormatArgs.toTypedArray())
    // FIXME get data via separate function that takes the actual source text and the sourceColumns
    // FIXME calculate real line and col
    printed = "$sourceFile:FIXME:FIXME: ${id.kind.text}: $msg [${id.name}]"
    println(printed)
    // FIXME obtain the line with the error and print it
    // FIXME print a nice caret thing that shows the column within the line

    // FIXME if debugging, print the origin of the inspection as well
  }

  override fun toString(): String = "${javaClass.simpleName}[$printed]"
}

class DiagnosticBuilder {
  var id: DiagnosticId = DiagnosticId.UNKNOWN
  var sourceFile: SourceFile = "<unknown>"
  var origin: String = "<unknown>"
  var messageFormatArgs: List<Any> = listOf()
  private var sourceColumns = mutableListOf<IntRange>()

  fun column(col: Int) {
    sourceColumns.add(col until col)
  }

  fun columns(range: IntRange) {
    sourceColumns.add(range)
  }

  fun formatArgs(vararg args: Any) {
    messageFormatArgs = args.toList()
  }

  fun create() = Diagnostic(id, messageFormatArgs, sourceFile, sourceColumns, origin)
}

fun newDiagnostic(build: DiagnosticBuilder.() -> Unit): Diagnostic {
  val builder = DiagnosticBuilder()
  builder.build()
  return builder.create()
}

/**
 * Signals things that "can't" happen. Should never be thrown during normal execution, should not be
 * caught anywhere.
 */
class InternalCompilerError : RuntimeException {
  constructor(message: String, cause: Throwable) : super(message, cause)
  constructor(message: String) : super(message)
}

inline fun KLogger.throwICE(ice: InternalCompilerError, crossinline msg: () -> Any?) {
  error(ice) { msg() }
  throw ice
}

inline fun KLogger.throwICE(iceMessage: String, cause: Throwable, crossinline msg: () -> Any?) {
  throwICE(InternalCompilerError(iceMessage, cause), msg)
}

inline fun KLogger.throwICE(iceMessage: String, crossinline msg: () -> Any?) {
  throwICE(InternalCompilerError(iceMessage), msg)
}

