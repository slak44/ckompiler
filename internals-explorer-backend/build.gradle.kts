import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("org.springframework.boot") version "3.0.5"
  id("io.spring.dependency-management") version "1.1.0"
  id("org.liquibase.gradle") version "2.0.4"
  kotlin("jvm") version "1.7.22"
  kotlin("plugin.spring") version "1.7.22"
  kotlin("plugin.jpa") version "1.7.22"
}

group = "slak"
version = "0.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_17

configurations {
  compileOnly {
    extendsFrom(configurations.annotationProcessor.get())
  }

  liquibase {
    activities.register("main")
    runList = "main"
  }

  liquibaseRuntime {
    extendsFrom(runtimeOnly.get())
  }
}

tasks {
  diffChangeLog {
    dependsOn += compileJava
  }
  diff {
    dependsOn += compileJava
  }
  generateChangelog {
    dependsOn += compileJava
  }
}

repositories {
  mavenCentral()
}

dependencies {
  liquibaseRuntime("org.liquibase:liquibase-core:4.18.0")
  liquibaseRuntime("org.postgresql:postgresql:42.5.1")
  liquibaseRuntime("org.liquibase.ext:liquibase-hibernate6:4.18.0")
  liquibaseRuntime(sourceSets.getByName("main").compileClasspath)
  liquibaseRuntime(sourceSets.getByName("main").runtimeClasspath)
  liquibaseRuntime(sourceSets.getByName("main").output)
  implementation("org.liquibase:liquibase-core")

  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
  implementation("org.jetbrains.kotlin:kotlin-reflect")
  developmentOnly("org.springframework.boot:spring-boot-devtools")
  runtimeOnly("org.postgresql:postgresql")
  testRuntimeOnly("com.h2database:h2")
  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.security:spring-security-test")
}

tasks.withType<KotlinCompile> {
  kotlinOptions {
    freeCompilerArgs = listOf("-Xjsr305=strict")
    jvmTarget = "17"
  }
}

tasks.withType<Test> {
  useJUnitPlatform()
}
