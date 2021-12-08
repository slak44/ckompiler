package slak.ckompiler

actual fun readResource(resourcePath: String): String? {
  if (resourcePath == BuildProperties.propFileName) {
    return JSON.stringify(js("require(\"./ckompiler.json\")"))
  } else {
    return null
  }
}

actual fun regexEscape(part: String): String {
  return js("""part.replace(/[.*+?^${'$'}{}()|[\]\\]/g, '\\${'$'}&')""") as String
}
