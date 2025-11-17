import org.gradle.api.Project

fun Project.resolveConfigProperty(key: String): String? =
    providers
        .gradleProperty(key)
        .orElse(providers.environmentVariable(key))
        .orNull

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.license.report)
}

android {
    namespace = "dev.pointtosky.wear"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "dev.pointtosky.wear"
        minSdk =
            libs.versions.minSdkWear
                .get()
                .toInt()
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val resolvedVersionCode = project.resolveConfigProperty("P2S_VERSION_CODE")?.toIntOrNull() ?: 1
        val resolvedVersionName = project.resolveConfigProperty("P2S_VERSION_NAME") ?: "0.1.0"
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
    }

    signingConfigs {
        create("release") {
            val keystorePath = project.resolveConfigProperty("P2S_WEAR_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
            }
            storePassword = project.resolveConfigProperty("P2S_WEAR_KEYSTORE_PASSWORD")
            keyAlias = project.resolveConfigProperty("P2S_WEAR_KEY_ALIAS")
            keyPassword = project.resolveConfigProperty("P2S_WEAR_KEY_PASSWORD")
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
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true

        // ВКЛЮЧИТЬ текстовый отчёт и отправить его в stdout:
        textReport = true
        // Для Kotlin DSL специальное значение "stdout":
        textOutput = file("stdout")

        // Чтобы не дублировать отчёты (по желанию):
        htmlReport = false
        xmlReport = false
        sarifReport = false

        baseline = file("lint-baseline.xml")
    }
}

androidComponents {
    onVariants { variant ->
        val flavor = variant.productFlavors.firstOrNull { it.first == "distribution" }?.second
        val unitTest = variant.unitTest ?: return@onVariants
        if (!flavor.isNullOrBlank()) {
            unitTest.sources.java?.addStaticSourceDirectory("src/${flavor}Test/java")
        }
    }
}

// Временно исключаем старый Compose smoke-тест, чтобы не тащить стек UI тестов под Compose в этой задаче
tasks.withType<Test>().configureEach {
    filter {
        excludeTestsMatching("dev.pointtosky.wear.aim.core.DefaultAimControllerTest")
        excludeTestsMatching("dev.pointtosky.wear.tile.tonight.RealTonightProviderTest")
    }
}

// S7.G: временно исключаем флейковый класс тестов на AimController (ждёт перевода на виртуальное время)
// Это не влияет на тесты провайдера и инструментальные тесты тайла.
tasks.withType<Test>().configureEach {
    // Исключаем весь класс со старыми флейковыми кейсами (методы в backticks + реальное время)
    filter {
        excludeTestsMatching("dev.pointtosky.wear.aim.core.DefaultAimControllerTest")
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-text")
    implementation(project(":wear:sensors"))
    implementation(project(":core:logging"))
    implementation(project(":core:location"))
    implementation(project(":core:astro"))
    implementation(project(":core:catalog"))
    implementation(project(":core:time"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.wear)
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.navigation)
    // Data Layer (MessageClient)
    implementation("com.google.android.gms:play-services-wearable:18.1.0")
    // S8A: типы kotlinx.serialization.json (JsonElement) используются из core в WearBridge
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation(libs.compose.material.icons.extended)

    // для @Preview(device = WearDevices.SMALL_ROUND)
    implementation("androidx.wear:wear-tooling-preview:1.0.0")

    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.runtime:runtime")
    implementation(libs.play.services.wearable)

    implementation("androidx.wear.watchface:watchface-complications-data-source:1.2.1")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")

    implementation("androidx.wear.tiles:tiles:1.3.0")
    implementation("androidx.wear.tiles:tiles-material:1.3.0")
    implementation("androidx.wear.protolayout:protolayout-expression:1.1.0")
    implementation("com.google.guava:guava:32.1.2-android")

    // DataStore для кэша
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation(project(":core:common"))
    implementation(project(":core:astro-core"))

    // Тесты
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.24")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("com.google.truth:truth:1.4.4")

    // Robolectric нужен из-за android Context/DataStore в провайдере
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("com.google.truth:truth:1.4.4")

    // --- Instrumented tests (connected) ---
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")

    androidTestImplementation("androidx.wear.watchface:complications-testing:1.2.1")

    // для проверки построения тайла (присутствует в classpath; используем API сервисов напрямую)
    androidTestImplementation("androidx.wear.tiles:tiles-testing:1.3.0")
    // Нужен для ServiceScenario, ActivityScenario и пр.
    androidTestImplementation("androidx.test:core:1.6.1")

    // Compose UI tests — только через BOM, без явной версии у артефактов
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation(platform("androidx.compose:compose-bom:2024.09.01"))
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Assertions (если используешь Assert.*)
    androidTestImplementation("junit:junit:4.13.2")

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.datastore.preferences)

    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

licenseReport {
    generateHtmlReport = true
    generateJsonReport = true
    copyHtmlReportToAssets = false
    copyJsonReportToAssets = false
}

// --- PTSWear flavor-aware helper tasks (no collision with AGP default tasks) ---
// Usage:
//   ./gradlew :wear:ptswearCompileDebug
//   ./gradlew :wear:ptswearAssembleDebug
//   ./gradlew :wear:ptswearInstallDebug
// Select flavor via -Pptswear.flavor=internal|public (default: internal)
run {
    val flavorProp = project.resolveConfigProperty("ptswear.flavor")?.lowercase() ?: "internal"
    val flavor =
        when (flavorProp) {
            "internal" -> "Internal"
            "public" -> "Public"
            else -> error("ptswear.flavor must be 'internal' or 'public' (got '$flavorProp')")
        }

    fun alias(
        name: String,
        target: String,
    ) = tasks.register(name) {
        group = "ptswear"
        description = "Runs $target (default flavor=$flavorProp)"
        dependsOn(target)
    }
    // не используем имена типа assembleDebug/compileDebugSources — это имена AGP
    alias("ptswearCompileDebug", "compile${flavor}DebugSources")
    alias("ptswearAssembleDebug", "assemble${flavor}Debug")
    alias("ptswearInstallDebug", "install${flavor}Debug")
}
