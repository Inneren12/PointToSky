# Keep structured logging infrastructure intact in release builds.
-keep class dev.pointtosky.core.logging.** { *; }
-keepclassmembers class dev.pointtosky.core.logging.** { *; }

# Do not declare android.util.Log calls as side-effect free.
# They are sometimes bridged into the structured logging subsystem.
