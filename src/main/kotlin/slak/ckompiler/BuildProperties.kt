package slak.ckompiler

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager

@Serializable
private data class Properties(val version: String, @SerialName("include-path") val includePath: String)

object BuildProperties {
  private val logger = LogManager.getLogger()
  private const val propFileName = "ckompiler.properties"
  private val properties by lazy {
    val propsUrl = this::class.java.classLoader.getResource(propFileName)
    if (propsUrl == null) {
      logger.error("Bad configuration; $propFileName missing.")
      return@lazy Properties("UNKNOWN_VERSION", "/usr/include")
    }

    return@lazy Json.decodeFromString(Properties.serializer(), propsUrl.readText())
  }

  val version get() = properties.version
  val includePath get() = properties.includePath
}
