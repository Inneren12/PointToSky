plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.pointtosky.core.time"
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

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
