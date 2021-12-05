package slak.ckompiler

actual fun readResource(resourcePath: String): String? {
  return Int.Companion::class.java.classLoader.getResource(resourcePath)?.readText()
}
