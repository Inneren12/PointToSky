plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "dev.pointtosky.wear.sensors"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdkWear.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = libs.versions.jvmTarget.get()
        allWarningsAsErrors = false
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
    baseline = file("detekt-baseline.xml")
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":core:logging"))
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.6")

    testImplementation(kotlin("test"))
}
