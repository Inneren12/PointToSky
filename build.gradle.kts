import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.JavaVersion
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.gradle.api.Project

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}

// Агрегатор: подтягиваем задачи из подключённых модулей динамически
// build.gradle.kts (root)
val staticCheck = tasks.register("staticCheck")

val strict: Boolean = System.getenv("CI") == "true" || project.hasProperty("strict")
fun Project.configureStaticAnalysis(isAndroidModule: Boolean, strictMode: Boolean) {
    val configuredKey = "staticAnalysisConfigured"
    val androidKey = "staticAnalysisAndroid"
    val javaToolchains = extensions.getByType<JavaToolchainService>()
    val currentJavaVersion = JavaVersion.current().majorVersion.toInt()

    if (!extensions.extraProperties.has(configuredKey)) {
        extensions.extraProperties[configuredKey] = true
        extensions.extraProperties[androidKey] = isAndroidModule

        plugins.apply("org.jlleitschuh.gradle.ktlint")
        plugins.apply("io.gitlab.arturbosch.detekt")

        extensions.configure<KtlintExtension> {
            android.set(isAndroidModule)
            ignoreFailures.set(!strictMode)
            filter {
                exclude("**/build/**")
                exclude("**/generated/**")
            }
        }

        extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            allRules = false
            autoCorrect = false
            parallel = true
            config.setFrom(files("$rootDir/config/detekt.yml"))
            baseline = file("$rootDir/config/detekt-baseline.xml")
        }

        tasks.matching { it.name == "detekt" }.configureEach { mustRunAfter("ktlintFormat") }
        tasks.matching { it.name == "check" }.configureEach { dependsOn("ktlintCheck", "detekt") }
        tasks.withType<Detekt>().configureEach {
            ignoreFailures = !strictMode
            jvmTarget = currentJavaVersion.toString()
            val launcher = javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(currentJavaVersion))
            }.get()
            jdkHome.set(launcher.metadata.installationPath.asFile)
        }
    } else if (isAndroidModule && !(extensions.extraProperties[androidKey] as Boolean)) {
        extensions.extraProperties[androidKey] = true
        extensions.configure<KtlintExtension> { android.set(true) }
    }
}

subprojects {
    pluginManager.withPlugin("com.android.application") {
        configureStaticAnalysis(isAndroidModule = true, strictMode = strict)
    }
    pluginManager.withPlugin("com.android.library") {
        configureStaticAnalysis(isAndroidModule = true, strictMode = strict)
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        configureStaticAnalysis(isAndroidModule = false, strictMode = strict)
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        val isAndroidModule = plugins.hasPlugin("com.android.application") || plugins.hasPlugin("com.android.library")
        configureStaticAnalysis(isAndroidModule = isAndroidModule, strictMode = strict)
    }
}

subprojects {

    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        // Берём версии из каталога "libs"
        val libsCatalog = rootProject.extensions
            .getByType(VersionCatalogsExtension::class.java)
            .named("libs")
        val ktlintEngine = libsCatalog.findVersion("ktlintEngine").get().toString()
        val strict = System.getenv("CI") == "true" || project.hasProperty("strict")

        // Конфигурация плагина: движок 1.x, Android-мод включён, фейлить только в CI
        extensions.configure(KtlintExtension::class.java) {
            version.set(ktlintEngine)
            android.set(true)
            ignoreFailures.set(!strict)
            filter {
                exclude("**/build/**")
                exclude("**/generated/**")
            }
        }

        // Чтобы проверки реально выполнялись в сборке
        tasks.matching { it.name == "check" }.configureEach {
            dependsOn("ktlintCheck")
        }
    }

    plugins.withId("io.gitlab.arturbosch.detekt") {
        staticCheck.configure { dependsOn(tasks.named("detekt")) }
    }
    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        staticCheck.configure { dependsOn(tasks.named("ktlintCheck")) }
    }
    plugins.withId("com.android.application") {
        staticCheck.configure {
            tasks.matching { it.name == "lintPublicDebug" }.forEach { dependsOn(it) }
        }
    }
    plugins.withId("com.android.library") {
        staticCheck.configure {
            tasks.matching { it.name.startsWith("lint") && it.name.endsWith("Debug") }
                .forEach { dependsOn(it) }
        }
    }
}


tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

tasks.register("astroTest") {
    dependsOn(":core:astro:test")
}

tasks.register("ephemCli") {
    dependsOn(":tools:ephem-cli:installDist")
}
