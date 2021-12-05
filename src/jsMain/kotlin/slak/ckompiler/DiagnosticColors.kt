package slak.ckompiler

actual class DiagnosticColors actual constructor(useColors: Boolean) {
  actual val green: (String) -> String
    get() = TODO("not implemented")
  actual val brightRed: (String) -> String
    get() = TODO("not implemented")
  actual val brightMagenta: (String) -> String
    get() = TODO("not implemented")
  actual val blue: (String) -> String
    get() = TODO("not implemented")
}

actual fun String.format(vararg args: Any): String {
  TODO("not implemented")
}
