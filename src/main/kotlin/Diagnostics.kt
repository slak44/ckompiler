import mu.KLogger
import java.lang.RuntimeException

enum class DiagnosticId(val kind: DiagnosticKind, val messageFormat: String) {
  UNKNOWN(DiagnosticKind.OTHER, ""),
  INVALID_SUFFIX(DiagnosticKind.ERROR, "Invalid suffix '%s' on %s constant"),
  MISSING_QUOTE(DiagnosticKind.ERROR, "Missing terminating %c character"),
  INVALID_DIGIT(DiagnosticKind.ERROR, "Invalid digit '%s' in constant")
}

enum class DiagnosticKind(val text: String) {
  ERROR("error"), WARNING("warning"), OTHER("note")
}

typealias SourceFile = String

data class Diagnostic(val id: DiagnosticId,
                      val sourceFile: SourceFile,
                      val sourceColumns: List<IntRange>,
                      val origin: String,
                      val messageFormatArgs: List<Any>) {
  init {
    val msg = id.messageFormat.format(*messageFormatArgs.toTypedArray())
    // FIXME get data via separate function that takes the actual source text and the sourceColumns
    // FIXME calculate real line and col
    println("$sourceFile:FIXME:FIXME: ${id.kind.text}: $msg [${id.name}]")
    // FIXME obtain the line with the error and print it
    // FIXME print a nice caret thing that shows the column within the line

    // FIXME if debugging, print the origin of the inspection as well
  }
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

  fun create() = Diagnostic(id, sourceFile, sourceColumns, origin, messageFormatArgs)
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

