pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
  repositories {
    google()
    mavenCentral()
  }
}


rootProject.name = "PointToSky"

// Bootstrap S5
include(
  ":wear",
  ":wear:benchmark",
  ":wear:sensors",
  ":mobile",
  ":core:common",
  ":core:logging",
  ":core:location",
  ":core:astro",
  ":core:time",
  ":tools:ephem-cli",
  ":core:catalog",
  ":tools:catalog-packer"
)
