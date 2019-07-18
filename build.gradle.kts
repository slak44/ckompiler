import java.net.URI

plugins {
  application
  kotlin("jvm") version "1.3.40"
}

group = "ckompiler"
version = "1.0-SNAPSHOT"

application {
  mainClassName = "slak.ckompiler.MainKt"
  applicationName = "ckompiler"
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
  testImplementation(kotlin("test-junit"))
}
