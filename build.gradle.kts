import java.net.URI

plugins {
  application
  kotlin("jvm") version "1.3.20"
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
  implementation(group = "com.github.Kotlin", name = "kotlinx.cli", version = "-SNAPSHOT")
  implementation(group = "org.slf4j", name = "slf4j-log4j12", version = "1.7.25")
  implementation(group = "io.github.microutils", name = "kotlin-logging", version = "1.6.10")
  testImplementation(kotlin("test-junit", "1.3.11"))
  testImplementation(group = "junit", name = "junit", version = "4.11")
}
