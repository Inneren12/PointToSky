import org.gradle.api.Project

fun Project.resolveConfigProperty(key: String): String? = providers.gradleProperty(key)
    .orElse(providers.environmentVariable(key))
    .orNull

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.license.report)
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "dev.pointtosky.mobile"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.pointtosky.mobile"
        minSdk = libs.versions.minSdkMobile.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val resolvedVersionCode = project.resolveConfigProperty("P2S_VERSION_CODE")?.toIntOrNull() ?: 1
        val resolvedVersionName = project.resolveConfigProperty("P2S_VERSION_NAME") ?: "0.1.0"
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
    }

    signingConfigs {
        create("release") {
            val keystorePath = project.resolveConfigProperty("P2S_MOBILE_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
            }
            storePassword = project.resolveConfigProperty("P2S_MOBILE_KEYSTORE_PASSWORD")
            keyAlias = project.resolveConfigProperty("P2S_MOBILE_KEY_ALIAS")
            keyPassword = project.resolveConfigProperty("P2S_MOBILE_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("internal") {
            dimension = "distribution"
            applicationIdSuffix = ".int"
        }
        create("public") {
            dimension = "distribution"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = libs.versions.jvmTarget.get()
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // не стричь эти .so → предупреждение “Unable to strip …” уйдёт
            keepDebugSymbols += listOf(
                "**/libandroidx.graphics.path.so",
                "**/libdatastore_shared_counter.so",
                "**/libimage_processing_util_jni.so",
                )
        }
    }
    lint {
        // S9: варнинги не валят сборку, критичные чиним
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
// AndroidX Test для инструментальных тестов (ServiceScenario и пр.)
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:core-ktx:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.6")

    implementation(platform(libs.compose.bom))

    // Если используете BOM в других модулях — подключите и здесь:
    implementation(platform(libs.compose.bom))

    // S8A/S8C: JsonElement и кодек (используем в DemoAimTargets)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // KeyboardOptions / KeyboardType
    implementation("androidx.compose.ui:ui-text")
    // Material3 виджеты (OutlinedTextField, Button, Card и т.д.)
    implementation("androidx.compose.material3:material3")
    // Compose интеграция Activity
    implementation("androidx.activity:activity-compose")
    // collectAsStateWithLifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-compose")
    // viewModel(...) из androidx.lifecycle.viewmodel.compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.compose.material3)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.runtime:runtime")
    implementation(libs.compose.ui.tooling.preview)

    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    implementation(libs.androidx.datastore.preferences)

    implementation(project(":core:common"))
    implementation(project(":core:catalog"))
    implementation(project(":core:location"))
    implementation(project(":core:astro"))
    implementation(project(":core:time"))
    implementation(project(":core:logging"))

    // Приём сообщений от часов
    implementation("com.google.android.gms:play-services-wearable:18.1.0")

    implementation(libs.play.services.wearable)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")

    // Compose UI tests — через BOM, без явной версии у артефактов
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Манифест для Compose-тестов подключаем только в debug
    debugImplementation(platform("androidx.compose:compose-bom:2024.09.01"))
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

licenseReport {
    generateHtmlReport = true
    generateJsonReport = true
    copyHtmlReportToAssets = false
    copyJsonReportToAssets = false
}
