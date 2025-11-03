# Keep structured logging infrastructure intact. These classes provide runtime logging hooks
# and must not be optimized out even in release builds.
-keep class dev.pointtosky.core.logging.** { *; }
-keepclassmembers class dev.pointtosky.core.logging.** { *; }

# Intentionally do NOT mark logging APIs as side-effect free. Persisting logs is observable.
# (See LoggerInitializer for more context.)
