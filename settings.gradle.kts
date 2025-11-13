pluginManagement {
  repositories {
      gradlePluginPortal()
      maven { url = uri("https://cache-redirector.jetbrains.com/dl.google.com/dl/android/maven2") }
      google()
      mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
  repositories {
      maven {
          url = uri("https://cache-redirector.jetbrains.com/dl.google.com/dl/android/maven2")
          metadataSources {
              mavenPom()
              artifact()
          }
      }
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
