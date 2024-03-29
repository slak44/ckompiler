package slak.ckompiler

import java.io.File

actual class FSPath(private val file: File) {
  actual constructor(inputPath: String) : this(File(inputPath))

  actual val isAbsolute: Boolean get() = file.isAbsolute
  actual val absolutePath: String get() = file.absolutePath
  actual val parentFile: FSPath get() = FSPath(file.parentFile)

  actual constructor(parent: FSPath, child: String) : this(File(parent.file, child))

  actual fun readText(): String = file.readText()

  actual fun exists(): Boolean = file.exists()
}
