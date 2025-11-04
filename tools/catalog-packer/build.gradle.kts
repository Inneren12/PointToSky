import org.gradle.api.tasks.compile.JavaCompile

plugins {
  id("org.jetbrains.kotlin.jvm") version "2.0.20"
  application
}

application {
  mainClass.set("dev.pointtosky.tools.catalog.PackerMainKt")
}

dependencies {
  implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.20")
  implementation(libs.kotlin.csv)

  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
  useJUnitPlatform()
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

tasks.withType<JavaCompile>().configureEach {
  sourceCompatibility = "17"
  targetCompatibility = "17"
}
