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
  EXPECTED_EXTERNAL_DECL(ERROR, "Expected a declaration or function definition"),
  UNMATCHED_PAREN(ERROR, "Expected ')'"),
  MATCH_PAREN_TARGET(OTHER, "To match this '(")
}

enum class DiagnosticKind(val text: String) {
  ERROR("error"), WARNING("warning"), OTHER("note")
}

typealias SourceFileName = String

data class Diagnostic(val id: DiagnosticId,
                      val messageFormatArgs: List<Any>,
                      val sourceFileName: SourceFileName,
                      val sourceText: String,
                      val sourceColumns: List<IntRange>,
                      val origin: String) {
  private val printable: String by lazy {
    val (line, col) = if (sourceText.isNotEmpty() && sourceColumns.isNotEmpty()) {
      var currLine = 1
      var currLineStart = 0
      for ((idx, it) in sourceText.withIndex()) {
        if (it == '\n') {
          currLine++
          currLineStart = idx + 1
        }
        if (sourceColumns[0].start > idx) {
          break
        }
      }
      Pair(currLine.toString(), (sourceColumns[0].start - currLineStart).toString())
    } else {
      Pair("?", "?")
    }
    val msg = id.messageFormat.format(*messageFormatArgs.toTypedArray())
    return@lazy "$sourceFileName:$line:$col: ${id.kind.text}: $msg [$origin|${id.name}]"
    // FIXME add a nice caret thing that shows the column within the line
    // FIXME add tildes for the other sourceColumns
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
    sourceColumns.add(col until col)
  }

  fun columns(range: IntRange) {
    sourceColumns.add(range)
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

