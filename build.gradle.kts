import java.net.URI
import java.util.Properties

plugins {
  application
  kotlin("jvm") version "1.3.41"
  `maven-publish`
}

group = "ckompiler"
version = "SNAPSHOT3"

val includePath = "usr/include/ckompiler-$version"

application {
  mainClassName = "slak.ckompiler.MainKt"
  applicationName = "ckompiler"
  executableDir = "usr/bin"
  applicationDistribution.from(File(rootDir, "stdlib/include")).into(includePath)
}

tasks.installDist {
  val installPath = System.getenv("DESTDIR") ?: ""
  if (installPath.isNotBlank()) destinationDir = File(installPath)
}

repositories {
  mavenCentral()
  maven { url = URI("https://dl.bintray.com/orangy/maven") }
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation(kotlin("reflect"))
  implementation(group = "org.jetbrains.kotlinx", name = "kotlinx-cli-jvm",
      version = "0.1.0-dev-5") {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
  }
  implementation(group = "org.apache.logging.log4j", name = "log4j-api", version = "2.11.2")
  implementation(group = "org.apache.logging.log4j", name = "log4j-core", version = "2.11.2")
  implementation(group = "com.github.ajalt", name = "mordant", version = "1.2.0")
  testImplementation(group = "org.junit.jupiter", name = "junit-jupiter", version = "5.5.0")
  testImplementation(group = "org.apache.logging.log4j", name = "log4j-jul", version = "2.11.2")
  testImplementation(kotlin("test-junit"))
}

tasks.test {
  useJUnitPlatform()
  // Must be present at startup-ish time
  systemProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager")
  // Don't put this in junit-properties, because debugging tests in intellij triggers the timeout
  systemProperty("junit.jupiter.execution.timeout.default", "5s")
}

sourceSets {
  for (thing in listOf(main, test)) thing {
    resources.srcDir(File(buildDir, "resources"))
    resources.srcDir(File(projectDir, "stdlib"))
  }
}

tasks.create("makePropsFile") {
  doLast {
    val props = Properties()
    props["version"] = version
    props["include-path"] = "/$includePath"
    val res = File(buildDir, "resources")
    res.mkdirs()
    val writer = File(res, "ckompiler.properties").bufferedWriter()
    props.store(writer, null)
    writer.close()
  }
}
tasks.processResources.get().dependsOn("makePropsFile")
tasks.processTestResources.get().dependsOn("makePropsFile")

publishing {
  repositories {
    maven {
      name = "Github"
      url = uri("https://maven.pkg.github.com/slak44")
      credentials {
        username = "slak44"
        val file = File(projectDir, "publish-token")
        password = if (file.exists()) file.readText().trim() else ""
      }
    }
  }
  publications {
    register("mavenJava", MavenPublication::class) {
      from(components["java"])
      pom {
        url.set("https://github.com/slak44/ckompiler.git")
      }
    }
  }
}
