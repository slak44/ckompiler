import java.util.*

plugins {
  application
  kotlin("jvm") version "1.5.21"
  `maven-publish`
  id("org.jetbrains.dokka") version "1.4.30"
}

group = "ckompiler"
version = "SNAPSHOT6"

val includePath = "usr/include/ckompiler-$version"

application {
  mainClass.set("slak.ckompiler.MainKt")
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
  maven { url = uri("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven") }
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation(kotlin("reflect"))
  implementation(group = "org.jetbrains.kotlinx", name = "kotlinx-html-jvm", version = "0.7.2")
  implementation(group = "org.apache.logging.log4j", name = "log4j-api", version = "2.11.2")
  implementation(group = "org.apache.logging.log4j", name = "log4j-core", version = "2.11.2")
  implementation(group = "com.github.ajalt", name = "mordant", version = "1.2.0")
  testImplementation(group = "org.junit.jupiter", name = "junit-jupiter", version = "5.5.0")
  testImplementation(group = "org.apache.logging.log4j", name = "log4j-jul", version = "2.11.2")
  testImplementation(kotlin("test-junit"))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().all {
  kotlinOptions {
    jvmTarget = "13"
  }
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
      url = uri("https://maven.pkg.github.com/slak44/ckompiler")
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
