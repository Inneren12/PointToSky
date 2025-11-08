# Keep kotlinx.serialization generated classes and @Serializable models
-keep @kotlinx.serialization.Serializable class * { *; }
-keep class **$$serializer { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers enum * {
    @kotlinx.serialization.SerialName <fields>;
}
-dontwarn kotlinx.serialization.**

# Compose tooling (debug previews)
-keep class androidx.compose.ui.tooling.** { *; }
-keep class androidx.compose.ui.tooling.preview.** { *; }

# Data Layer entry points
-keep class dev.pointtosky.mobile.datalayer.** extends com.google.android.gms.wearable.WearableListenerService { *; }
-keep class dev.pointtosky.wear.datalayer.** extends com.google.android.gms.wearable.WearableListenerService { *; }

# Public API surface from core modules
-keep class dev.pointtosky.core.astro.** { *; }
-keep class dev.pointtosky.core.catalog.** { *; }
