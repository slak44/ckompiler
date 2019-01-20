import java.net.URI

plugins {
  application
  kotlin("jvm") version "1.3.11"
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
  compile(kotlin("stdlib"))
  compile(group = "com.github.Kotlin", name = "kotlinx.cli", version = "-SNAPSHOT")
  compile(group = "org.slf4j", name = "slf4j-log4j12", version = "1.7.25")
  compile(group = "io.github.microutils", name = "kotlin-logging", version = "1.6.10")
  testCompile(kotlin("test-junit", "1.3.11"))
  testCompile(group = "junit", name = "junit", version = "4.11")
}
