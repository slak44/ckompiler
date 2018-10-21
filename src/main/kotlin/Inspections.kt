enum class InspectionId(val kind: InspectionKind, val messageFormat: String) {
  UNKNOWN(InspectionKind.OTHER, ""),
  INVALID_SUFFIX(InspectionKind.ERROR, "Invalid suffix '%s' on integer constant"),
  MISSING_QUOTE(InspectionKind.ERROR, "Missing terminating %c character"),
}

enum class InspectionKind(val text: String) {
  ERROR("error"), WARNING("warning"), OTHER("note")
}

typealias SourceFile = String

data class Inspection(val id: InspectionId,
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

class InspectionBuilder {
  var id: InspectionId = InspectionId.UNKNOWN
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

  fun create() = Inspection(id, sourceFile, sourceColumns, origin, messageFormatArgs)
}

fun newInspection(build: InspectionBuilder.() -> Unit): Inspection {
  val builder = InspectionBuilder()
  builder.build()
  return builder.create()
}
