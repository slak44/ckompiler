import org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile

plugins {
  application
  kotlin("multiplatform") version "1.9.0"
  kotlin("plugin.serialization") version "1.9.0"
  `maven-publish`
  id("org.jetbrains.dokka") version "1.4.30"
}

val versionString = "SNAPSHOT6"
group = "ckompiler"
version = versionString

val jvmIncludePath = "usr/include/ckompiler-$version"

application {
  mainClass.set("slak.ckompiler.MainKt")
  applicationName = "ckompiler"
  executableDir = "usr/bin"
  applicationDistribution.from(File(rootDir, "stdlib/include")).into(jvmIncludePath)
}

tasks.named<JavaExec>("run") {
  dependsOn(tasks.getByName("jvmProcessResources"))

  classpath += objects.fileCollection().from(
      tasks.named("compileKotlinJvm"),
      configurations.named("jvmRuntimeClasspath")
  )
}

tasks.installDist {
  val installPath = System.getenv("DESTDIR") ?: ""
  if (installPath.isNotBlank()) destinationDir = File(installPath)
}

tasks.withType<Jar> {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
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
  abstract var version: String

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

fun registerPropsTask(name: String, buildOutputDir: File, dependency: String): TaskProvider<PropsFileJsTask> {
  return tasks.register<PropsFileJsTask>(name) {
    dependsOn(dependency, "jsProcessResources")

    version = versionString
    includeFolder = File(buildOutputDir, "include")
    output = File(buildOutputDir, "ckompiler.json")
  }
}

val jsProductionExecBuildDir = buildPath(buildDir, "dist", "js", "productionExecutable")
val jsProductionLibBuildDir = buildPath(buildDir, "dist", "js", "productionLibrary")

val jsBrowserProductionExecutablePropsFile = registerPropsTask(
    "jsBrowserProductionExecutablePropsFile",
    jsProductionExecBuildDir,
    "jsBrowserProductionWebpack"
)

val jsBrowserProductionLibraryPropsFile = registerPropsTask(
    "jsBrowserProductionLibraryPropsFile",
    jsProductionLibBuildDir,
    "jsBrowserProductionLibraryDistribution"
)

val fixESImport: Task by tasks.creating {
  doLast {
    val jsModule = File(jsProductionLibBuildDir, "ckompiler.mjs")
    val propsFileImport = "import * as ckompilerJson from './ckompiler.json';\n"
    val fixedImport = propsFileImport + jsModule.readText()
        .replace(Regex("import (\\S+) from 'printj';"), "import { vsprintf as $1 } from 'printj';")

    jsModule.writeText(fixedImport)
  }
}

val copyLibraryNodeModules: Task by tasks.creating {
  doLast {
    copy {
      from(buildPath(buildDir, "js"))
      into(jsProductionLibBuildDir)
      include("node_modules/**")
    }
  }
}

fun createFixDefinitionsTask(targetDir: File): NamedDomainObjectContainerCreatingDelegateProvider<Task> {
  return tasks.creating {
    doLast {
      val tsDefinitions = buildPath(buildDir, "js", "packages", "ckompiler", "kotlin", "ckompiler.d.ts")
      val interfaceCompanion = Regex("interface(.*?)\\{(.*?)static get Companion\\(\\): \\{.*?\\};(.*?)\\}", RegexOption.DOT_MATCHES_ALL)
      val newContents = tsDefinitions.readText()
          .replace(interfaceCompanion, "interface$1{$2 $3}")

      val destinationDefinitions = File(targetDir, "ckompiler.d.ts")
      destinationDefinitions.writeText(newContents)
    }
  }
}

val fixLibraryDefinitionsFile: Task by createFixDefinitionsTask(jsProductionLibBuildDir)
val fixBinaryDefinitionsFile: Task by createFixDefinitionsTask(jsProductionExecBuildDir)

kotlin {
  jvm {
    val jvmJar by tasks.getting(Jar::class) {
      doFirst {
        manifest {
          attributes["Main-Class"] = "slak.ckompiler.MainKt"
          attributes["Class-Path"] = configurations.getByName("jvmRuntimeClasspath").map {
            if (it.isDirectory || it.name.endsWith(".jar")) it else zipTree(it)
          }.joinToString(" ")
        }
      }
    }

    val main by compilations.getting {
      tasks.getByName("jvmProcessResources") {
        dependsOn(tasks.getByName("processResources"))
        dependsOn(tasks.getByName("processTestResources"))
        finalizedBy(makePropsFileJvm)
      }
    }

    val test by compilations.getting {
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
      }
    }
  }

  js(IR) {
    useEsModules()

    compilations["main"].packageJson {
      customField("module", "ckompiler.mjs")
      customField("peerDependencies", dependencies)
    }

    tasks.withType<KotlinJsCompile>().configureEach {
      kotlinOptions {
        useEsClasses = true
      }
    }

    binaries.library()
    binaries.executable()

    browser()

    generateTypeScriptDefinitions()

    tasks.getByName("jsProcessResources") {
      dependsOn(tasks.getByName("processResources"))
      dependsOn(tasks.getByName("processTestResources"))
    }

    tasks.getByName("jsProductionLibraryCompileSync") {
      finalizedBy(jsBrowserProductionLibraryPropsFile)
      finalizedBy(fixESImport)
      finalizedBy(copyLibraryNodeModules)
      finalizedBy(fixLibraryDefinitionsFile)
    }

    tasks.getByName("jsProductionExecutableCompileSync") {
      finalizedBy(jsBrowserProductionExecutablePropsFile)
      finalizedBy(fixBinaryDefinitionsFile)
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
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
        implementation("org.jetbrains.kotlinx:kotlinx-html:0.9.1")
        implementation("io.github.oshai:kotlin-logging:5.1.0")
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
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.6.0")
        implementation("com.github.ajalt:mordant:1.2.0")
        implementation("org.apache.logging.log4j:log4j-core:2.20.0")
        implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.20.0")
        implementation("io.github.oshai:kotlin-logging-jvm:5.1.0")
      }
    }

    val jvmTest by getting {
      kotlin.srcDir("src/test/kotlin")
      resources.srcDir("src/test/resources")

      dependsOn(jvmMain)
      dependsOn(commonTest)

      dependencies {
        implementation("org.junit.jupiter:junit-jupiter:5.10.0")
        implementation("org.apache.logging.log4j:log4j-jul:2.20.0")
        implementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.0")
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
