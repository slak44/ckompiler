package slak.ckompiler

actual class DiagnosticColors actual constructor(useColors: Boolean) {
  actual val green: (String) -> String = { if (useColors) "<span style=\"color: green\">$it</span>" else it }
  actual val brightRed: (String) -> String = { if (useColors) "<span style=\"color: darkred\">$it</span>" else it }
  actual val brightMagenta: (String) -> String = { if (useColors) "<span style=\"color: darkmagenta\">$it</span>" else it }
  actual val blue: (String) -> String = { if (useColors) "<span style=\"color: lightblue\">$it</span>" else it }
}

@JsModule("printj")
@JsNonModule
external object PrintJ {
  fun sprintf(vararg args: dynamic): String
}

actual fun String.format(vararg args: Any): String = PrintJ.sprintf(*args)
