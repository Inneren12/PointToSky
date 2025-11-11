plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "dev.pointtosky.core.astro"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdkMobile.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = libs.versions.jvmTarget.get()
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = false
        checkReleaseBuilds = true
        baseline = file("lint-baseline.xml")
    }
}

kotlin {
    jvmToolchain(17)
}

detekt {
    buildUponDefaultConfig = true
    autoCorrect = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

ktlint {
    android.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}

dependencies {
    implementation(project(":core:time"))
    testImplementation(kotlin("test"))
    // Bind kotlin.test to the JUnit 5 platform
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    // Allow legacy JUnit4 tests to continue running on the JUnit Platform
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.kotlinx.serialization.json)
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.6")
    testImplementation(project(":core:catalog"))
}
