package dev.pointtosky.core.astro.projection.camera

/**
 * How a [CameraSessionGeometry] knows whether its [CameraIntrinsics] are a real per-device
 * measurement or the legacy fixed-FOV fallback (CAM-1f).
 *
 * Mirrors the distinction CAM-1b's `dev.pointtosky.mobile.ar.camera.CameraIntrinsicsResolution`
 * already carries (`intrinsics` + `fallbackReason: String?`), but as a sealed hierarchy: a
 * fallback value can never be silently read as fully calibrated by a caller that forgets to check
 * a nullable field, because there is no field to forget — the type itself is one or the other.
 * `:mobile`'s resolver seam maps its own result into this type exactly once per bound camera
 * session (see `dev.pointtosky.mobile.ar.camera.SessionScopedCameraIntrinsicsResolver`); this
 * type carries no Android/CameraX dependency of its own.
 */
sealed interface CameraIntrinsicsResolution {
    val intrinsics: CameraIntrinsics

    /** Real per-device intrinsics. [CameraIntrinsics.source] is never [CameraIntrinsicsSource.LEGACY_FALLBACK]. */
    data class Resolved(
        override val intrinsics: CameraIntrinsics,
    ) : CameraIntrinsicsResolution {
        init {
            require(intrinsics.source != CameraIntrinsicsSource.LEGACY_FALLBACK) {
                "Resolved intrinsics must not carry LEGACY_FALLBACK source; use LegacyFallback instead"
            }
        }
    }

    /**
     * The explicit legacy fixed-FOV fallback. [CameraIntrinsics.source] is always
     * [CameraIntrinsicsSource.LEGACY_FALLBACK]; [reason] is a short, non-sensitive diagnostic
     * category (mirroring `dev.pointtosky.mobile.ar.camera.CameraIntrinsicsFallbackReason`), never
     * a raw exception message or device identifier.
     */
    data class LegacyFallback(
        override val intrinsics: CameraIntrinsics,
        val reason: String,
    ) : CameraIntrinsicsResolution {
        init {
            require(intrinsics.source == CameraIntrinsicsSource.LEGACY_FALLBACK) {
                "LegacyFallback intrinsics must carry LEGACY_FALLBACK source; was ${intrinsics.source}"
            }
            require(reason.isNotBlank()) { "reason must not be blank" }
        }
    }
}

/**
 * Whether a [CameraSessionGeometry] bundle is backed by real per-device calibration or the legacy
 * fallback (CAM-1f). Always derived from [CameraIntrinsicsResolution] — never guessed — so a
 * fallback bundle cannot be mislabeled as fully calibrated.
 */
enum class CameraGeometryQuality {
    CALIBRATED,
    LEGACY_INTRINSICS_FALLBACK,
}

/** [CameraGeometryQuality] implied by this resolution. */
val CameraIntrinsicsResolution.quality: CameraGeometryQuality
    get() =
        when (this) {
            is CameraIntrinsicsResolution.Resolved -> CameraGeometryQuality.CALIBRATED
            is CameraIntrinsicsResolution.LegacyFallback -> CameraGeometryQuality.LEGACY_INTRINSICS_FALLBACK
        }
