plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget()

    sourceSets {
        val commonMain by getting
        val commonTest by getting
    }

    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

android {
    namespace = "dev.pointtosky.core.common"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdkMobile.get().toInt()
    }
}
