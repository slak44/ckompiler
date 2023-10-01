package slak.ckompiler

actual class DiagnosticColors actual constructor(useColors: Boolean) {
  actual val green: (String) -> String = { if (useColors) "<span style=\"color: lightgreen\">$it</span>" else it }
  actual val brightRed: (String) -> String = { if (useColors) "<span style=\"color: red\">$it</span>" else it }
  actual val brightMagenta: (String) -> String = { if (useColors) "<span style=\"color: magenta\">$it</span>" else it }
  actual val blue: (String) -> String = { if (useColors) "<span style=\"color: lightblue\">$it</span>" else it }
}

@JsModule("printj")
external fun vsprintf(format: String, vararg args: dynamic): String

actual fun String.format(vararg args: Any): String = vsprintf(this, args)
