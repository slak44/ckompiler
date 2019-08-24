package slak.ckompiler.lexer

import slak.ckompiler.BuildProperties
import java.io.File

data class IncludePaths(val general: List<File>, val system: List<File>, val users: List<File>) {

  /**
   * Don't consider the current directory for includes.
   */
  var includeBarrier = false

  /**
   * Look for the given header name in the inclusion paths.
   *
   * C standard: 6.10.2
   *
   * @param parent the source file that included the [headerName]'s parent directory
   */
  fun search(headerName: String, parent: File, isSystem: Boolean): File? {
    if (!includeBarrier) {
      val candidate = File(parent, headerName)
      if (candidate.exists()) return candidate
    }
    for (searchPath in if (isSystem) system + general else users + general + system) {
      val candidate = File(searchPath, headerName)
      if (candidate.exists()) return candidate
    }
    return null
  }

  operator fun plus(other: IncludePaths): IncludePaths {
    val inc = IncludePaths(general + other.general, system + other.system, users + other.users)
    inc.includeBarrier = includeBarrier || other.includeBarrier
    return inc
  }

  companion object {
    val defaultPaths = IncludePaths(
        general = emptyList(),
        system = listOf(File(BuildProperties.includePath)),
        users = emptyList()
    )
  }
}
