package slak.ckompiler

actual fun readResource(resourcePath: String): String? {
  return Int.Companion::class.java.classLoader.getResource(resourcePath)?.readText()
}

actual fun regexEscape(part: String): String = part.map { "\\" + it }.joinToString("")
