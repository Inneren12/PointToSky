package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraGeometryQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryResult
import dev.pointtosky.core.astro.projection.camera.CropScaleTransform
import dev.pointtosky.core.astro.projection.camera.GeometryRejectionReason
import dev.pointtosky.core.astro.projection.camera.IntrinsicsUnavailableReason
import dev.pointtosky.core.astro.projection.camera.PixelPoint
import dev.pointtosky.core.astro.projection.camera.RotationUnavailableReason
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Stable, loggable/displayable classification of a [CameraSessionGeometryResult] (CAM-1g) — one
 * value per distinct reason the CAM-1c–1f pipeline can report, so a physical-device validation
 * session can tell *which* non-ready status is blocking readiness instead of a single collapsed
 * "loading" state. Never derived from the sealed result's own `toString()` — see
 * [toDiagnosticCategory].
 */
enum class CameraGeometryDiagnosticCategory {
    MISSING_FRAME,
    INVALID_VIEWPORT,
    INTRINSICS_PENDING,
    ROTATION_NO_SAMPLES,
    ROTATION_OUTSIDE_TOLERANCE,
    ROTATION_CLOCK_MISMATCH,
    PAIRING_FRAME_MISMATCH,
    PAIRING_DELTA_MISMATCH,
    PAIRING_OUTSIDE_CONFIGURED_TOLERANCE,
    CROP_SCALE_CONSTRUCTION_FAILED,
    READY_CALIBRATED,
    READY_LEGACY_FALLBACK,
    DISPOSED,
}

/**
 * Pure diagnostic center-probe result (CAM-1g §7) — see [computeCameraGeometryCenterProbe]. Bounded
 * scalars only, no matrix, no pixel-plane data.
 */
data class CameraGeometryCenterProbeSnapshot(
    val viewportCenterXPx: Double,
    val viewportCenterYPx: Double,
    val imagePointXPx: Double,
    val imagePointYPx: Double,
    val roundTripErrorPx: Double,
)

/**
 * Bounded, immutable debug-only snapshot of one [CameraSessionGeometryResult] (CAM-1g §3). Carries
 * no matrix, no pixel-plane data, no historical list, no raw exception text, and no device/camera
 * hardware identifier — only the plain scalar fields needed to judge "is this bundle plausible" on
 * a physical device. Every category other than [CameraGeometryDiagnosticCategory.READY_CALIBRATED]/
 * [CameraGeometryDiagnosticCategory.READY_LEGACY_FALLBACK] leaves the geometry fields `null` rather
 * than reconstructing a value the sealed result does not actually carry for that variant — see
 * [toDiagnosticSnapshot].
 */
data class CameraGeometryDiagnosticSnapshot(
    val category: CameraGeometryDiagnosticCategory,
    val quality: CameraGeometryQuality?,
    val frameTimestampNanos: Long?,
    val bufferWidthPx: Int?,
    val bufferHeightPx: Int?,
    val cropLeftPx: Int?,
    val cropTopPx: Int?,
    val cropRightPx: Int?,
    val cropBottomPx: Int?,
    val rotationDegrees: Int?,
    val viewportWidthPx: Int?,
    val viewportHeightPx: Int?,
    val pairDeltaNanos: Long?,
    val intrinsicsSource: CameraIntrinsicsSource?,
    val horizontalFovDeg: Double?,
    val verticalFovDeg: Double?,
    val uniformScale: Double?,
    val displayOffsetX: Double?,
    val displayOffsetY: Double?,
    val centerProbe: CameraGeometryCenterProbeSnapshot?,
)

/**
 * The [CameraGeometryDiagnosticCategory] this result maps to. [CameraSessionGeometryResult.Ready]
 * splits on [CameraSessionGeometryResult.Ready.quality] rather than reusing
 * [CameraGeometryQuality]'s own name, since the diagnostic category is one flat enum covering every
 * sealed variant, not just `Ready`.
 */
fun CameraSessionGeometryResult.toDiagnosticCategory(): CameraGeometryDiagnosticCategory =
    when (this) {
        is CameraSessionGeometryResult.Ready ->
            when (quality) {
                CameraGeometryQuality.CALIBRATED -> CameraGeometryDiagnosticCategory.READY_CALIBRATED
                CameraGeometryQuality.LEGACY_INTRINSICS_FALLBACK -> CameraGeometryDiagnosticCategory.READY_LEGACY_FALLBACK
            }

        is CameraSessionGeometryResult.MissingFrame -> CameraGeometryDiagnosticCategory.MISSING_FRAME
        is CameraSessionGeometryResult.InvalidViewport -> CameraGeometryDiagnosticCategory.INVALID_VIEWPORT

        is CameraSessionGeometryResult.RotationUnavailable ->
            when (reason) {
                RotationUnavailableReason.NO_SAMPLES -> CameraGeometryDiagnosticCategory.ROTATION_NO_SAMPLES
                RotationUnavailableReason.OUTSIDE_TOLERANCE -> CameraGeometryDiagnosticCategory.ROTATION_OUTSIDE_TOLERANCE
                RotationUnavailableReason.CLOCK_MISMATCH_SUSPECTED -> CameraGeometryDiagnosticCategory.ROTATION_CLOCK_MISMATCH
            }

        is CameraSessionGeometryResult.IntrinsicsUnavailable ->
            when (reason) {
                IntrinsicsUnavailableReason.PENDING -> CameraGeometryDiagnosticCategory.INTRINSICS_PENDING
            }

        is CameraSessionGeometryResult.GeometryRejected ->
            when (reason) {
                GeometryRejectionReason.PAIRING_FRAME_MISMATCH -> CameraGeometryDiagnosticCategory.PAIRING_FRAME_MISMATCH
                GeometryRejectionReason.PAIRING_DELTA_MISMATCH -> CameraGeometryDiagnosticCategory.PAIRING_DELTA_MISMATCH
                GeometryRejectionReason.PAIRING_OUTSIDE_CONFIGURED_TOLERANCE ->
                    CameraGeometryDiagnosticCategory.PAIRING_OUTSIDE_CONFIGURED_TOLERANCE
                GeometryRejectionReason.CROP_SCALE_CONSTRUCTION_FAILED ->
                    CameraGeometryDiagnosticCategory.CROP_SCALE_CONSTRUCTION_FAILED
            }

        CameraSessionGeometryResult.Disposed -> CameraGeometryDiagnosticCategory.DISPOSED
    }

/**
 * Pure mapper (CAM-1g §3): builds a [CameraGeometryDiagnosticSnapshot] entirely from this
 * [CameraSessionGeometryResult] — no Android dependency, no additional state read, no pixels, no
 * matrix. Geometry fields (buffer/crop/rotation/scale/offset/FOV/center-probe) are populated only
 * for [CameraSessionGeometryResult.Ready], the only variant that actually carries a
 * `CameraSessionGeometry`. [CameraSessionGeometryResult.MissingFrame]'s last-known viewport and
 * [CameraSessionGeometryResult.InvalidViewport]'s raw width/height are reported directly since those
 * two variants do carry viewport information; every other non-`Ready` variant reports only its
 * [category].
 */
fun CameraSessionGeometryResult.toDiagnosticSnapshot(): CameraGeometryDiagnosticSnapshot {
    val category = toDiagnosticCategory()
    return when (this) {
        is CameraSessionGeometryResult.Ready -> {
            val transform = geometry.cropScaleTransform
            val crop = transform.sourceCrop
            val intrinsics = geometry.intrinsics.intrinsics
            CameraGeometryDiagnosticSnapshot(
                category = category,
                quality = quality,
                frameTimestampNanos = geometry.frame.timestampNanos,
                bufferWidthPx = geometry.frame.bufferWidthPx,
                bufferHeightPx = geometry.frame.bufferHeightPx,
                cropLeftPx = crop.left.roundToInt(),
                cropTopPx = crop.top.roundToInt(),
                cropRightPx = crop.right.roundToInt(),
                cropBottomPx = crop.bottom.roundToInt(),
                rotationDegrees = geometry.frame.rotationDegrees,
                viewportWidthPx = geometry.viewportSize.width.roundToInt(),
                viewportHeightPx = geometry.viewportSize.height.roundToInt(),
                pairDeltaNanos = geometry.frameRotationDeltaNanos,
                intrinsicsSource = intrinsics.source,
                horizontalFovDeg = intrinsics.horizontalFovDeg,
                verticalFovDeg = intrinsics.verticalFovDeg,
                uniformScale = transform.uniformScale,
                displayOffsetX = transform.displayOffsetX,
                displayOffsetY = transform.displayOffsetY,
                centerProbe = computeCameraGeometryCenterProbe(transform),
            )
        }

        is CameraSessionGeometryResult.MissingFrame ->
            emptyDiagnosticSnapshot(category).copy(
                viewportWidthPx = viewportSize?.width?.roundToInt(),
                viewportHeightPx = viewportSize?.height?.roundToInt(),
            )

        is CameraSessionGeometryResult.InvalidViewport ->
            emptyDiagnosticSnapshot(category).copy(viewportWidthPx = widthPx, viewportHeightPx = heightPx)

        is CameraSessionGeometryResult.RotationUnavailable,
        is CameraSessionGeometryResult.IntrinsicsUnavailable,
        is CameraSessionGeometryResult.GeometryRejected,
        CameraSessionGeometryResult.Disposed,
        -> emptyDiagnosticSnapshot(category)
    }
}

private fun emptyDiagnosticSnapshot(category: CameraGeometryDiagnosticCategory) =
    CameraGeometryDiagnosticSnapshot(
        category = category,
        quality = null,
        frameTimestampNanos = null,
        bufferWidthPx = null,
        bufferHeightPx = null,
        cropLeftPx = null,
        cropTopPx = null,
        cropRightPx = null,
        cropBottomPx = null,
        rotationDegrees = null,
        viewportWidthPx = null,
        viewportHeightPx = null,
        pairDeltaNanos = null,
        intrinsicsSource = null,
        horizontalFovDeg = null,
        verticalFovDeg = null,
        uniformScale = null,
        displayOffsetX = null,
        displayOffsetY = null,
        centerProbe = null,
    )

/**
 * Pure diagnostic center-probe (CAM-1g §7): maps the viewport center through
 * [CropScaleTransform.displayToImage], then that image point back through
 * [CropScaleTransform.imageToDisplay], and reports the round-trip error in display pixels. Values
 * are never clamped and the error is computed at full precision before any display rounding — only
 * the returned scalars are ever formatted for display. This checks the *published* transform's own
 * mathematical self-consistency only; it is not proof of Preview/ImageAnalysis pixel alignment (see
 * `docs/camera_coordinate_calibration_contract.md` §9.10).
 */
fun computeCameraGeometryCenterProbe(transform: CropScaleTransform): CameraGeometryCenterProbeSnapshot {
    val viewportCenter =
        PixelPoint(
            x = transform.viewportSize.width / 2.0,
            y = transform.viewportSize.height / 2.0,
        )
    val imagePoint = transform.displayToImage(viewportCenter)
    val displayRoundTrip = transform.imageToDisplay(imagePoint)
    val roundTripErrorPx =
        hypot(
            displayRoundTrip.x - viewportCenter.x,
            displayRoundTrip.y - viewportCenter.y,
        )
    return CameraGeometryCenterProbeSnapshot(
        viewportCenterXPx = viewportCenter.x,
        viewportCenterYPx = viewportCenter.y,
        imagePointXPx = imagePoint.x,
        imagePointYPx = imagePoint.y,
        roundTripErrorPx = roundTripErrorPx,
    )
}

/**
 * Local, non-persistent, in-process session counter (CAM-1g §9) — proves that leaving and
 * re-entering AR creates a fresh owner rather than reusing a disposed one. Never a device
 * identifier, never persisted, never uploaded.
 */
private val nextDebugSessionIdCounter = AtomicLong(0)

fun nextDebugSessionId(): Long = nextDebugSessionIdCounter.incrementAndGet()
