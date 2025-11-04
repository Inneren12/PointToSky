plugins {
  id("org.jetbrains.kotlin.jvm") version "2.0.20"
  application
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}

application {
  mainClass.set("dev.pointtosky.tools.catalog.PackerMainKt")
}

dependencies {
  implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.20")
  testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

tasks.test {
  useJUnitPlatform()
}

kotlin {
  jvmToolchain(21)
}
