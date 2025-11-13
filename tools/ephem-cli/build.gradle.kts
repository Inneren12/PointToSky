
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("dev.pointtosky.tools.ephemcli.EphemCliKt")
}

dependencies {
    implementation(project(":core:astro"))
    implementation(kotlin("stdlib"))
}
