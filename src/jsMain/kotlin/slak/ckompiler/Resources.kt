package slak.ckompiler

external val ckompilerJson: dynamic

actual fun readResource(resourcePath: String): String? {
  if (resourcePath == BuildProperties.propFileName) {
    return JSON.stringify(ckompilerJson.default)
  } else {
    return null
  }
}

actual fun regexEscape(part: String): String {
  return js("""part.replace(/[.*+?^${'$'}{}()|[\]\\]/g, '\\${'$'}&')""") as String
}
