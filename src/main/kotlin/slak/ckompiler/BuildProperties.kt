package slak.ckompiler

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.util.Properties

object BuildProperties {
  private val logger: Logger = LogManager.getLogger("Properties")
  private const val propFileName = "ckompiler.properties"
  private val properties by lazy {
    val propsUrl = this::class.java.classLoader.getResource(propFileName)
    val prop = Properties()
    if (propsUrl == null) {
      logger.error("Bad configuration; $propFileName missing.")
      return@lazy prop
    }
    prop.load(propsUrl.openStream())
    return@lazy prop
  }

  private fun getProp(propName: String, default: String): String {
    val value = properties.getProperty(propName)
    if (value == null) {
      logger.error("Bad configuration; $propFileName does not have property '$propName'.")
      return default
    }
    return value
  }

  val version by lazy { getProp("version", "UNKNOWN_VERSION") }
  val includePath by lazy { getProp("include-path", "/usr/include") }
}
