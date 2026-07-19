import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Project
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

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

// Detekt: временно исключаем большой Compose-файл, который крэшит анализ на JDK 21.
tasks.withType<Detekt>().configureEach {
    exclude("**/dev/pointtosky/mobile/ar/ArScreen.kt")
}

android {
    namespace = "dev.pointtosky.mobile"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "dev.pointtosky.mobile"
        minSdk =
            libs.versions.minSdkMobile
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
        jniLibs {
            // не стричь эти .so → предупреждение “Unable to strip …” уйдёт
            keepDebugSymbols +=
                listOf(
                    "**/libandroidx.graphics.path.so",
                    "**/libdatastore_shared_counter.so",
                    "**/libimage_processing_util_jni.so",
                )
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

tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs all Debug unit tests for every flavor."
    dependsOn("testInternalDebugUnitTest", "testPublicDebugUnitTest")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:astro-core"))
    implementation(project(":core:astro"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    testImplementation(kotlin("test"))           // kotlin.test + JUnit binding
    testImplementation(kotlin("reflect"))         // KVisibility checks in the resolution-seam boundary tests
    testImplementation("org.json:json:20240303") // real org.json for JVM unit tests (avoids android.jar stub)
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

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

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
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.play.services)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // AndroidX Test — one coherent generation, centrally managed via the version catalog
    // (was scattered across three conflicting hard-coded versions per artifact; the mismatch
    // left androidx.test.espresso:espresso-core resolving to the stale 3.5.0 pulled in
    // transitively by Compose's ui-test-android, which is incompatible with newer platform
    // InputManager APIs — see docs/validation/cam_2b_device_validation.md).
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)

    // Compose UI tests — через BOM, без явной версии у артефактов
    // (debugImplementation ui-test-manifest is already declared above, next to compose.ui.tooling)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    androidTestImplementation(libs.kotlinx.coroutines.test)
}

licenseReport {
    generateHtmlReport = true
    generateJsonReport = true
    copyHtmlReportToAssets = false
    copyJsonReportToAssets = false
}

// --- Force detekt to run on JDK 17 (workaround for detekt 1.23.x + JDK 21 crash) ---
run {
    val toolchains = extensions.getByType<JavaToolchainService>()

    // Принудительно используем JDK 17 для всех detekt tasks
    tasks.withType<Detekt>().configureEach {
        jvmTarget = "17"

        // КРИТИЧНО: установите jdkHome
        jdkHome.set(
            toolchains
                .launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(17))
                }.map { it.metadata.installationPath },
        )
    }
}

// --- Detekt stabilizer: отключаем variant-таски, мягкий локально/строгий в CI, без внутренней параллели ---
plugins.withId("io.gitlab.arturbosch.detekt") {
    val strict = System.getenv("CI") == "true" || project.hasProperty("strict")

    // Расширение detekt: выключаем внутренний параллелизм (PSI race), оставляем базовые флаги
    extensions.configure<DetektExtension>("detekt") {
        parallel = false
        autoCorrect = false
        // на случай отсутствия явной инициализации где-то ещё — безопасно продублировать:
        buildUponDefaultConfig = true
        allRules = false
    }

    // Отключаем variant-специфичные таски (InternalDebug/PublicRelease и т.п.)
    tasks
        .matching {
            it.name.startsWith("detekt") && it.name !in setOf("detekt", "detektMain", "detektTest")
        }.configureEach {
            enabled = false
        }

    // Общие настройки задач detektMain/Test: режим фейла и порядок после автоформатирования
    tasks.withType<Detekt>().configureEach {
        ignoreFailures = !strict
        mustRunAfter("ktlintFormat")
    }

    // Включаем детект в check и, если есть, подтягиваем ktlintCheck
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn("detektMain", "detektTest")
        runCatching { dependsOn("ktlintCheck") }
    }
}
// --- end Detekt stabilizer ---
