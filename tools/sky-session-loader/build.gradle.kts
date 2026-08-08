plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("dev.pointtosky.tools.skysession.SkySessionLoaderCliKt")
}

kotlin {
    // Matches :core:astro-core, which this module compiles against.
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    // The whole point of this module: :core:astro-core is deliberately file-free, so the file->bytes
    // glue lives here and depends on it, never the other way round. No Android dependency.
    implementation(project(":core:astro-core"))
    implementation(kotlin("stdlib"))

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
