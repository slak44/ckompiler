package slak.ckompiler

const val stdlibDir = "/stdlib/include"

val fileSystem = BuildProperties.includeFiles!!.mapKeys { "$stdlibDir/${it.key}" }.toMutableMap()

@JsExport
actual class FSPath actual constructor(inputPath: String) {
  private val path = inputPath.removeSuffix("/")

  actual val isAbsolute: Boolean get() = true
  actual val absolutePath: String get() = path
  actual val parentFile: FSPath get() = FSPath(path.dropLastWhile { it != '/' })

  actual fun readText(): String {
    return fileSystem[path]!!
  }

  actual fun exists(): Boolean {
    return path in fileSystem
  }

  @JsName("FSPathChild")
  actual constructor(parent: FSPath, child: String) : this(parent.path + "/" + child)
}
