import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.20"
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("dev.pointtosky.tools.ephemcli.EphemCliKt")
}

sourceSets {
    named("main") {
        kotlin.srcDir("../../core/astro/src/main/kotlin")
    }
}
