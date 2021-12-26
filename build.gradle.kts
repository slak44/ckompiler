plugins {
  application
  kotlin("multiplatform") version "1.6.0"
  kotlin("plugin.serialization") version "1.6.0"
  `maven-publish`
  id("org.jetbrains.dokka") version "1.4.30"
}

group = "ckompiler"
version = "SNAPSHOT6"

val jvmIncludePath = "usr/include/ckompiler-$version"

application {
  mainClass.set("slak.ckompiler.MainKt")
  applicationName = "ckompiler"
  executableDir = "usr/bin"
  applicationDistribution.from(File(rootDir, "stdlib/include")).into(jvmIncludePath)
}

tasks.installDist {
  val installPath = System.getenv("DESTDIR") ?: ""
  if (installPath.isNotBlank()) destinationDir = File(installPath)
}

fun buildPath(file: File, vararg segments: String): File {
  return File(file, segments.joinToString(System.getProperty("file.separator")))
}

val makePropsFileJvm: Task by tasks.creating {
  doLast {
    val propsFileContents = "{ \"version\": \"$version\", \"include-path\": \"/$jvmIncludePath\", \"include-files\": null }"
    val res = File(buildDir, "resources")
    res.mkdirs()
    File(res, "ckompiler.json").writeText(propsFileContents)
  }
}

@CacheableTask
abstract class PropsFileJsTask : DefaultTask() {
  @get:InputDirectory
  @get:SkipWhenEmpty
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract var includeFolder: File

  @get:OutputFile
  abstract var output: File

  @get:Input
  abstract var version: Int

  @TaskAction
  fun writePropsFile() {
    val filesMap = includeFolder.listFiles()?.joinToString(",", "{", "}") { file ->
      "\"${file.name}\": \"${jsonEscape(file.readText())}\""
    } ?: "{}"

    val propsFileContents = "{ \"version\": \"$version\", \"include-path\": \"/assets/stdlib\", \"include-files\": $filesMap }"

    output.writeText(propsFileContents)
  }

  private fun jsonEscape(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
        .replace("\r", "\\r")
        .replace("\b", "\\b")
        .replace("\u000c", "\\f")
  }
}

val makePropsFileJs = "makePropsFileJs"
tasks.register<PropsFileJsTask>(makePropsFileJs) {
  dependsOn("jsProductionExecutableCompileSync")
  dependsOn("processTestResources")
  this.version = version
  val res = buildPath(buildDir, "js", "packages", "ckompiler", "kotlin")
  includeFolder = File(res, "include")
  output = File(res, "ckompiler.json")
}

val setNoCheck: Task by tasks.creating {
  doLast {
    val tsDefinitions = buildPath(buildDir, "js", "packages", "ckompiler", "kotlin", "ckompiler.d.ts")
    val newContents = "// @ts-nocheck\n\n" + tsDefinitions.readText()
    tsDefinitions.writeText(newContents)
  }
}

kotlin {
  jvm {
    val main by compilations.getting {
      kotlinOptions {
        jvmTarget = "11"
      }

      tasks.getByName("jvmProcessResources") {
        dependsOn(tasks.getByName("processResources"))
        dependsOn(tasks.getByName("processTestResources"))
        finalizedBy(makePropsFileJvm)
      }
    }

    val test by compilations.getting {
      kotlinOptions {
        jvmTarget = "11"
      }

      tasks.getByName("jvmTestProcessResources") {
        dependsOn(tasks.getByName("processResources"))
        dependsOn(tasks.getByName("processTestResources"))
        finalizedBy(makePropsFileJvm)
      }
    }

    testRuns.getByName("test") {
      executionTask {
        useJUnitPlatform()
        // Must be present at startup-ish time
        systemProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager")
        // Don't put this in junit-properties, because debugging tests in intellij triggers the timeout
        systemProperty("junit.jupiter.execution.timeout.default", "5s")
      }
    }
  }

  js(IR) {
    browser {
      webpackTask {
        finalizedBy(setNoCheck)
      }
    }
    binaries.executable()

    tasks.getByName("jsProcessResources") {
      dependsOn(tasks.getByName("processResources"))
      dependsOn(tasks.getByName("processTestResources"))
      finalizedBy(makePropsFileJs)
    }
  }

  sourceSets {
    val commonMain by getting {
      kotlin.srcDir("src/main/kotlin")
      resources.srcDir("src/main/resources")
      resources.srcDir(File(buildDir, "resources"))
      resources.srcDir(File(projectDir, "stdlib"))

      dependencies {
        implementation(kotlin("stdlib-common"))
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.1")
        implementation("org.jetbrains.kotlinx:kotlinx-html:0.7.2")
        implementation("io.github.microutils:kotlin-logging:2.0.2")
      }
    }

    val commonTest by getting {
      dependencies {
        implementation(kotlin("test-common"))
        implementation(kotlin("test-annotations-common"))
      }
    }

    val jvmMain by getting {
      dependsOn(commonMain)

      dependencies {
        implementation(kotlin("stdlib-jdk8"))
        implementation("com.github.ajalt:mordant:1.2.0")
        implementation("org.apache.logging.log4j:log4j-slf4j18-impl:2.17.0")
      }
    }

    val jvmTest by getting {
      kotlin.srcDir("src/test/kotlin")
      resources.srcDir("src/test/resources")

      dependsOn(jvmMain)
      dependsOn(commonTest)

      dependencies {
        implementation("org.junit.jupiter:junit-jupiter:5.5.0")
        implementation("org.apache.logging.log4j:log4j-jul:2.11.2")
        implementation("org.jetbrains.kotlin:kotlin-test-junit:1.6.0")
      }
    }

    val jsMain by getting {
      dependsOn(commonMain)
      languageSettings.optIn("kotlin.js.ExperimentalJsExport")

      dependencies {
        implementation(npm("printj", "1.2.2"))
      }
    }

    all {
      languageSettings.optIn("kotlin.js.ExperimentalJsExport")
    }
  }
}

repositories {
  mavenCentral()
  maven { url = uri("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven") }
}

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
      from(components["kotlin"])
      pom {
        url.set("https://github.com/slak44/ckompiler.git")
      }
    }
  }
}
