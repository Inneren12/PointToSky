package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.mobile.BuildConfig

/** The `productFlavors { create("internal") { ... } }` name from `mobile/build.gradle.kts`. */
internal const val INTERNAL_DISTRIBUTION_FLAVOR = "internal"

/**
 * CAM-1g debug-only visibility gate for the camera-geometry diagnostic overlay (§2). The overlay
 * must never appear in a release build or in the public-distribution flavor — only the
 * internal-distribution debug variant ("internalDebug") the physical-device validation checklist
 * actually runs against. [isDiagnosticsEnabled] is a plain pure function so the gate logic itself is
 * unit-tested without needing to swap `BuildConfig` fields in a test.
 */
object CameraGeometryDiagnosticsGate {
    val isEnabled: Boolean = isDiagnosticsEnabled(debug = BuildConfig.DEBUG, flavor = BuildConfig.FLAVOR)
}

internal fun isDiagnosticsEnabled(
    debug: Boolean,
    flavor: String,
): Boolean = debug && flavor == INTERNAL_DISTRIBUTION_FLAVOR
