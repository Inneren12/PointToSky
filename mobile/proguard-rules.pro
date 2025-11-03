# Preserve structured logging implementation so diagnostics continue to work in release builds.
-keep class dev.pointtosky.core.logging.** { *; }
-keepclassmembers class dev.pointtosky.core.logging.** { *; }

# Do not mark android.util.Log methods as side-effect free — diagnostics can rely on logcat.
# (We intentionally omit any -assumenosideeffects directives here.)
