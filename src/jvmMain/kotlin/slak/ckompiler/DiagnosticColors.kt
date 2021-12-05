package slak.ckompiler

import com.github.ajalt.mordant.TermColors

actual class DiagnosticColors actual constructor(useColors: Boolean) {
  private val colors = TermColors(if (useColors) TermColors.Level.TRUECOLOR else TermColors.Level.NONE)

  actual val green: (String) -> String = colors.green
  actual val brightRed: (String) -> String = colors.brightRed
  actual val brightMagenta: (String) -> String = colors.brightMagenta
  actual val blue: (String) -> String = colors.blue
}

actual fun String.format(vararg args: Any): String = String.format(this, *args)
