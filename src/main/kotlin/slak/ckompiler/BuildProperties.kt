package slak.ckompiler

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.oshai.kotlinlogging.KotlinLogging

@Serializable
private data class Properties(
    val version: String,
    @SerialName("include-path") val includePath: String,
    @SerialName("include-files") val includeFiles: Map<String, String>?,
)

object BuildProperties {
  private val logger = KotlinLogging.logger {}

  const val propFileName = "ckompiler.json"

  private val properties by lazy {
    val propsText = readResource(propFileName)
    if (propsText == null) {
      logger.error { "Bad configuration; $propFileName missing." }
      return@lazy Properties("UNKNOWN_VERSION", "/usr/include", null)
    }

    return@lazy Json.decodeFromString(Properties.serializer(), propsText)
  }

  val version get() = properties.version
  val includePath get() = properties.includePath
  val includeFiles get() = properties.includeFiles
}
