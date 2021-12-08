package slak.ckompiler

@JsExport
actual class FSPath actual constructor(path: String) {
  actual val isAbsolute: Boolean
    get() = TODO("not implemented")
  actual val absolutePath: String
    get() = TODO("not implemented")
  actual val parentFile: FSPath
    get() = TODO("not implemented")

  actual fun readText(): String {
    TODO("not implemented")
  }

  actual fun exists(): Boolean {
    TODO("not implemented")
  }

  @JsName("FSPathChild")
  actual constructor(parent: FSPath, child: String) : this(TODO()) {
    TODO("not implemented")
  }
}
