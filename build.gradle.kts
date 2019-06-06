import java.net.URI

plugins {
  application
  kotlin("jvm") version "1.3.21"
}

group = "ckompiler"
version = "1.0-SNAPSHOT"

application {
  mainClassName = "slak.ckompiler.MainKt"
  applicationName = "ckompiler"
}

repositories {
  mavenCentral()
  maven { url = URI("https://jitpack.io") }
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation(kotlin("reflect"))
  implementation(group = "com.github.Kotlin", name = "kotlinx.cli",
      version = "fd284ee94256a57b600b543670f2a07fc2f53820")
  implementation(group = "org.apache.logging.log4j", name = "log4j-api", version = "2.11.2")
  implementation(group = "org.apache.logging.log4j", name = "log4j-core", version = "2.11.2")
  implementation(group = "com.github.ajalt", name = "mordant", version = "1.2.0")
  testImplementation(kotlin("test-junit"))
  testImplementation(group = "junit", name = "junit", version = "4.11")
}
