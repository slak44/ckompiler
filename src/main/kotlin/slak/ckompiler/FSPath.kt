package slak.ckompiler

expect class FSPath(inputPath: String) {
  val isAbsolute: Boolean
  val absolutePath: String
  val parentFile: FSPath

  constructor(parent: FSPath, child: String)

  fun readText(): String
  fun exists(): Boolean
}
