plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    // SKY-1 session-log codec (skylog/SkySessionLogCodec.kt) only. Runtime artifact, no compiler
    // plugin: the codec hand-builds JsonObjects exactly like core/logging's LogEvent.toJsonLine does,
    // so no @Serializable code generation is involved. Already on :mobile's runtime classpath via
    // :core:common, so this adds no new artifact to the app.
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    // Bind kotlin.test to the JUnit 5 platform
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
