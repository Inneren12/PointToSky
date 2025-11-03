pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PointToSky"
include(":wear")
include(":wear:sensors")
include(":mobile")
include(":core:common")
include(":core:logging")
include(":core:location")
include(":core:time")
