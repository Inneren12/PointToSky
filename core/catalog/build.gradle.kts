plugins {
  id("com.android.library") version "8.7.2"
  id("org.jetbrains.kotlin.android") version "2.0.20"
}

android {
  namespace = "dev.pointtosky.core.catalog"
  compileSdk = 35

  defaultConfig {
    minSdk = 26
    consumerProguardFiles("consumer-rules.pro")
  }
  testOptions {
    unitTests.all {
      it.systemProperty(
        "catalog.bin.dir",
        project.findProperty("catalog.bin.dir")?.toString() ?: ""
      )
    }
  }
  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
    }
    debug {
      isMinifyEnabled = false
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }
}

dependencies {
  // Явные зависимости, без Version Catalog
  implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.20")
  // Пока ничего больше — модуль пустой (bootstrap)
  testImplementation("junit:junit:4.13.2")
  implementation(project(":core:astro"))
  implementation(project(":core:logging"))
}
