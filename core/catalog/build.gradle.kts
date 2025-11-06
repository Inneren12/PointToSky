plugins {
  id("com.android.library") version "8.7.2"
  id("org.jetbrains.kotlin.android") version "2.0.20"
}

android {
  namespace = "dev.pointtosky.core.catalog"
  compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

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
    testImplementation("junit:junit:4.13.2")

    // runtime
    implementation(project(":core:astro"))
    // Logger участвует в публичных сигнатурах → нужен как api, чтобы тип был виден потребителям
    api(project(":core:logging"))

    // для CatalogDebugViewModel: ViewModel + Flow.update
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
  // logging types are exposed in public API → must be api
  api(project(":core:logging"))
}
