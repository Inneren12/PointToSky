plugins {
    id("com.android.application") version "8.7.2" apply false
    id("com.android.library") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.20" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.6" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0" apply false
}

// Агрегатор: подтягиваем задачи из подключённых модулей динамически
// build.gradle.kts (root)
val staticCheck = tasks.register("staticCheck")

subprojects {
    plugins.withId("io.gitlab.arturbosch.detekt") {
        dependencies {
            add("detektPlugins", "io.gitlab.arturbosch.detekt:detekt-formatting:1.23.6")
        }
    }
}

subprojects {
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
