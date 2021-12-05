plugins {
  application
  kotlin("multiplatform") version "1.6.0"
  kotlin("plugin.serialization") version "1.6.0"
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

val makePropsFile: Task by tasks.creating {
  doLast {
    val json = "{ \"version\": \"$version\", \"include-path\": \"/$includePath\" }"
    val res = File(buildDir, "resources")
    res.mkdirs()
    File(res, "ckompiler.properties").writeText(json)
  }
}

tasks.installDist {
  val installPath = System.getenv("DESTDIR") ?: ""
  if (installPath.isNotBlank()) destinationDir = File(installPath)
}

kotlin {
  jvm {
    val main by compilations.getting {
      tasks.getByName(processResourcesTaskName).dependsOn(makePropsFile)
    }

    val test by compilations.getting {
      tasks.getByName(processResourcesTaskName).dependsOn(makePropsFile)
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
      }
    }
    binaries.executable()
  }

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(kotlin("stdlib-common"))
      }
    }

    val commonTest by getting {
      dependencies {
        implementation(kotlin("test-common"))
        implementation(kotlin("test-annotations-common"))
      }
    }

    val jvmMain by getting {
      kotlin.srcDir("src/main/kotlin")
      resources.srcDir("src/main/resources")
      resources.srcDir(File(buildDir, "resources"))
      resources.srcDir(File(projectDir, "stdlib"))

      dependsOn(commonMain)

      dependencies {
        implementation("org.jetbrains.kotlin:kotlin-stdlib:1.6.0")
        implementation("org.jetbrains.kotlin:kotlin-reflect:1.6.0")
        implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.7.2")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.1")
        implementation("org.apache.logging.log4j:log4j-api:2.11.2")
        implementation("org.apache.logging.log4j:log4j-core:2.11.2")
        implementation("com.github.ajalt:mordant:1.2.0")
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
