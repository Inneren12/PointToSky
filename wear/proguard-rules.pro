# Structured logging is handled by dev.pointtosky.core.logging. Do not strip android.util.Log
# or the custom logger — both remain available for diagnostics builds.
-keep class dev.pointtosky.core.logging.** { *; }
-keepclassmembers class dev.pointtosky.core.logging.** { *; }

# Avoid marking android.util.Log calls as side-effect free; they may be bridged into structured logs.
