package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayState
import dev.pointtosky.mobile.ar.camera.prediction.name
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution as CoreCameraIntrinsicsResolution

/**
 * `internalDebug`-only. Bounded, active-array-local crop rectangle (pixels), a plain value.
 */
data class CamDiagnosticCropRect(
    val leftPx: Int,
    val topPx: Int,
    val rightPx: Int,
    val bottomPx: Int,
)

/**
 * `internalDebug`-only. CAM-2b's status/reason scalars only (architecture fix §2) - never
 * [PredictedStarOverlayState] itself, never its `points`/`summary` (catalog-derived overlay payload).
 */
data class Cam2bDiagnosticSnapshot(
    val status: String?,
    val waitingReason: String?,
    val unavailableReason: String?,
    val intrinsicsMode: String?,
    val inputCount: Int?,
    val visibleCount: Int?,
)

/**
 * `internalDebug`-only. Raw Camera2 metadata, deep-copied into a read-only export DTO at capture time
 * (architecture fix §2) - [physicalCameraIds] is a freshly sorted `List`, never the original
 * (possibly mutable) `Set`; [availableFocalLengthsMm] is a freshly built `List<Double>`, never the
 * original (definitely mutable) `FloatArray`. Note Kotlin's `List` is a read-only *interface*, not an
 * absolute immutability guarantee on the JVM - what this deep-copy actually rules out is retaining any
 * reference to a caller-owned mutable collection, so mutating the caller's own original array/set after
 * capture can never affect this snapshot (see `CamDiagnosticSnapshotTest`'s mutation-regression tests).
 */
data class CameraMetadataExportSnapshot(
    val cameraId: String?,
    val logicalMultiCamera: Boolean?,
    val physicalCameraIds: List<String>?,
    val pixelArrayWidthPx: Int?,
    val pixelArrayHeightPx: Int?,
    val activeArrayLeftPx: Int?,
    val activeArrayTopPx: Int?,
    val activeArrayRightPx: Int?,
    val activeArrayBottomPx: Int?,
    val preCorrectionActiveArrayLeftPx: Int?,
    val preCorrectionActiveArrayTopPx: Int?,
    val preCorrectionActiveArrayRightPx: Int?,
    val preCorrectionActiveArrayBottomPx: Int?,
    val sensorPhysicalWidthMm: Double?,
    val sensorPhysicalHeightMm: Double?,
    val availableFocalLengthsMm: List<Double>?,
)

/**
 * `internalDebug`-only. A rectangle exported from a [SensorToBufferDomainBounds] - a plain value, never
 * that richer diagnostic type itself.
 */
data class MappedBoundsExportSnapshot(
    val leftPx: Double,
    val topPx: Double,
    val rightPx: Double,
    val bottomPx: Double,
)

/**
 * `internalDebug`-only. The latest frame's transform transport plus running counters
 * (architecture fix §2) - [matrix] is a freshly built 9-element `List<Double>` (`m00`..`m22`, in that
 * order), never the original [dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3] value.
 *
 * @property framesWithSupportedTransformClass (renamed from `framesWithUsableTransform`) the subset of
 *   frames whose transform classified as a **structurally** supported
 *   [dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass] — never a claim that the
 *   transform's own numbers match any particular source-domain hypothesis; see
 *   [wholeActiveArrayHypothesisVerdict] for that separate, explicitly-scoped question.
 * @property sourceDomainBasis the
 *   [SourceDomainBasis] name that
 *   [wholeActiveArrayHypothesisVerdict] was tested against — always
 *   `"ASSUMED_WHOLE_ACTIVE_ARRAY_LOCAL"` as of this codebase, `null` only when no transform is present
 *   at all (no assessment was attempted). Carried explicitly so a reader never has to assume which
 *   hypothesis a verdict describes.
 * @property wholeActiveArrayHypothesisVerdict the latest frame's
 *   [WholeActiveArrayHypothesisVerdict] name, or `null` only
 *   when no transform is present at all. When a transform *is* present but the active-array/buffer
 *   dimensions needed to test the hypothesis are missing, this is still non-`null` — a typed
 *   `SOURCE_METADATA_UNAVAILABLE`/`BUFFER_METADATA_UNAVAILABLE` verdict, never a silent `null`. This is
 *   never a general validity/usability verdict on the transform itself — see that enum's own KDoc.
 * @property mappedAssumedSourceBoundsPx the latest frame's *assumed* source domain (the whole active
 *   array, under [sourceDomainBasis]'s hypothesis), mapped through its own transform - see
 *   [assessWholeActiveArrayMappingHypothesis].
 * @property expectedBufferBoundsPx the analysis buffer's own `[0,0]`-`[width,height]` rectangle, the
 *   value [mappedAssumedSourceBoundsPx] is compared against. Present whenever a valid buffer size is
 *   known, even when [mappedAssumedSourceBoundsPx] itself is `null`.
 * @property hypothesisReason a short, human-readable explanation of [wholeActiveArrayHypothesisVerdict],
 *   never a claim that the transform itself is invalid, unusable, or broken.
 */
data class FrameTransformExportSnapshot(
    val present: Boolean,
    val matrix: List<Double>?,
    val transformClass: String?,
    val framesAnalyzed: Long,
    val framesWithTransform: Long,
    val framesWithNullTransform: Long,
    val framesWithSupportedTransformClass: Long,
    val coordinatorFramesWaited: Int,
    val sourceDomainBasis: String? = null,
    val wholeActiveArrayHypothesisVerdict: String? = null,
    val mappedAssumedSourceBoundsPx: MappedBoundsExportSnapshot? = null,
    val expectedBufferBoundsPx: MappedBoundsExportSnapshot? = null,
    val hypothesisReason: String? = null,
)

/** `internalDebug`-only. What CAM-1b/CAM-2c actually published, as plain strings/scalars. */
data class PublishedIntrinsicsExportSnapshot(
    val publication: String?,
    val fallbackReason: String?,
    val source: String?,
    val reference: String?,
    val referenceBufferWidthPx: Int?,
    val referenceBufferHeightPx: Int?,
    val quality: String?,
)

/** `internalDebug`-only. The resolved buffer `K`, only present when CAM-2c's attempt actually succeeded. */
data class ResolvedBufferKExportSnapshot(
    val fxPx: Double,
    val fyPx: Double,
    val cxPx: Double,
    val cyPx: Double,
)

/**
 * `internalDebug`-only. CAM-2c's full picture, as plain values (architecture fix §2) - never
 * [CameraSessionIntrinsicsDiagnosticState], [AnalysisBufferIntrinsicsResolution], or
 * [CoreCameraIntrinsicsResolution] themselves.
 *
 * @property attemptType the typed [AnalysisBufferIntrinsicsResolution] variant's simple name (e.g.
 *   `"UnsupportedLogicalMultiCameraMapping"`), or `null` when no attempt was made yet.
 * @property attemptTransformClass only set for `UnsupportedSensorToBufferTransform`/
 *   `RotationOwnershipUnproven` - the rejected [dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass] name.
 * @property attemptInvalidMetadataReason only set for `InvalidMetadata` - its short reason code.
 */
data class Cam2cDiagnosticSnapshot(
    val coordinatorState: String?,
    val attemptType: String?,
    val attemptTransformClass: String?,
    val attemptInvalidMetadataReason: String?,
    val camera: CameraMetadataExportSnapshot,
    val frameTransform: FrameTransformExportSnapshot,
    val publishedIntrinsics: PublishedIntrinsicsExportSnapshot,
    val resolvedBufferK: ResolvedBufferKExportSnapshot?,
)

/**
 * `internalDebug`-only. CAM-1g's geometry state, as plain values (architecture fix §2) - never
 * [CameraGeometryDiagnosticSnapshot] itself.
 */
data class CameraGeometryExportSnapshot(
    val category: String?,
    val statusTransitionCount: Int,
    val observedFrameCount: Long,
    val readyBundleCount: Long,
    val bufferWidthPx: Int?,
    val bufferHeightPx: Int?,
    val cropRect: CamDiagnosticCropRect?,
    val rotationDegrees: Int?,
    val viewportWidthPx: Int?,
    val viewportHeightPx: Int?,
)

/**
 * `internalDebug`-only. Every [CameraCalibrationDiagnostics] field, as plain values (architecture fix
 * §2) - never [CameraCalibrationDiagnostics] itself; [physicalCameraIds] is a freshly sorted `List`.
 */
data class CameraCalibrationExportSnapshot(
    val activeArrayWidthPx: Int,
    val activeArrayHeightPx: Int,
    val activeArrayLeftPx: Double,
    val activeArrayTopPx: Double,
    val activeArrayRightPx: Double,
    val activeArrayBottomPx: Double,
    val pixelArrayWidthPx: Int?,
    val pixelArrayHeightPx: Int?,
    val sensorWidthMm: Double,
    val sensorHeightMm: Double,
    val focalLengthMm: Double,
    val activeFxPx: Double,
    val activeFyPx: Double,
    val activeCxPx: Double,
    val activeCyPx: Double,
    val principalPointBasis: String,
    val focalDerivationBasis: String,
    val cropLeftPx: Double,
    val cropTopPx: Double,
    val cropRightPx: Double,
    val cropBottomPx: Double,
    val bufferFxPx: Double,
    val bufferFyPx: Double,
    val bufferCxPx: Double,
    val bufferCyPx: Double,
    val quality: String,
    val sensorToBufferMappingSource: String,
    val transformClass: String,
    val skewDiagnosticReason: String?,
    val cameraId: String?,
    val isLogicalMultiCamera: Boolean,
    val physicalCameraIds: List<String>?,
)

/**
 * `internalDebug`-only. A bounded, **deep-copied, read-only export DTO tree** capturing one CAM
 * diagnostics moment (architecture fix §2) - every field, at every level of nesting, is a plain value
 * (`String`, `Int`, `Long`, `Double`, `Boolean`, a read-only `List` of one of those, or a nested `data
 * class` built from the same). No field anywhere in this tree is a runtime provider/coordinator
 * reference, a mutable array, a mutable-backed collection, a
 * [dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayState] (or its `points`/`summary`
 * catalog-derived payload), a [CameraSessionIntrinsicsDiagnosticState], a [CameraCharacteristicsSnapshot],
 * or a [CameraCalibrationDiagnostics]. See [captureCamDiagnosticSnapshot]'s own KDoc for the
 * deep-copy/normalization every field is built through exactly once, at capture time.
 *
 * Note Kotlin's `List` is a read-only *interface*, not a JVM-enforced immutability guarantee - nothing
 * here prevents an unsafe downcast to `MutableList` from mutating a backing array in principle. What
 * this type actually guarantees is that no field is, or ever wraps, a reference to a caller-owned
 * mutable collection: every `List` here was freshly built at capture time from already-copied elements
 * (see [captureCamDiagnosticSnapshot]), so mutating the *original* source array/set/state afterward can
 * never change an already-captured [CamDiagnosticSnapshot].
 */
data class CamDiagnosticSnapshot(
    val capturedAtEpochMillis: Long,
    val sessionId: Long,
    val cam2b: Cam2bDiagnosticSnapshot,
    val cam2c: Cam2cDiagnosticSnapshot,
    val geometry: CameraGeometryExportSnapshot,
    val calibration: CameraCalibrationExportSnapshot?,
)

private fun cam2bDiagnosticSnapshot(state: PredictedStarOverlayState?): Cam2bDiagnosticSnapshot =
    when (state) {
        null ->
            Cam2bDiagnosticSnapshot(
                status = null,
                waitingReason = null,
                unavailableReason = null,
                intrinsicsMode = null,
                inputCount = null,
                visibleCount = null,
            )
        PredictedStarOverlayState.Disabled ->
            Cam2bDiagnosticSnapshot(
                status = "DISABLED",
                waitingReason = null,
                unavailableReason = null,
                intrinsicsMode = null,
                inputCount = null,
                visibleCount = null,
            )
        is PredictedStarOverlayState.Waiting ->
            Cam2bDiagnosticSnapshot(
                status = "WAITING",
                waitingReason = state.reason.name,
                unavailableReason = null,
                intrinsicsMode = null,
                inputCount = null,
                visibleCount = null,
            )
        is PredictedStarOverlayState.Unavailable ->
            Cam2bDiagnosticSnapshot(
                status = "UNAVAILABLE",
                waitingReason = null,
                unavailableReason = state.reason.name,
                intrinsicsMode = null,
                inputCount = null,
                visibleCount = null,
            )
        is PredictedStarOverlayState.Ready ->
            // Only diagnostic scalars from `state.metadata` are read here - `state.points` (the
            // catalog-derived overlay payload) and `state.summary` are never touched, so neither can
            // ever end up in the captured snapshot (architecture fix §2).
            Cam2bDiagnosticSnapshot(
                status = "READY",
                waitingReason = null,
                unavailableReason = null,
                intrinsicsMode = state.metadata.intrinsicsMode.name,
                inputCount = state.metadata.inputCount,
                visibleCount = state.metadata.visibleCount,
            )
    }

private fun cameraMetadataExportSnapshot(snapshot: CameraCharacteristicsSnapshot?): CameraMetadataExportSnapshot =
    CameraMetadataExportSnapshot(
        cameraId = snapshot?.cameraId,
        logicalMultiCamera = snapshot?.isLogicalMultiCamera,
        // Deep-copy/normalize (architecture fix §2): a fresh, sorted List - never the original Set
        // reference, whose backing implementation is not guaranteed immutable.
        physicalCameraIds = snapshot?.physicalCameraIds?.toList()?.sorted(),
        pixelArrayWidthPx = snapshot?.pixelArrayWidthPx,
        pixelArrayHeightPx = snapshot?.pixelArrayHeightPx,
        activeArrayLeftPx = snapshot?.activeArrayLeftPx,
        activeArrayTopPx = snapshot?.activeArrayTopPx,
        activeArrayRightPx = snapshot?.activeArrayRightPx,
        activeArrayBottomPx = snapshot?.activeArrayBottomPx,
        preCorrectionActiveArrayLeftPx = snapshot?.preCorrectionActiveArrayLeftPx,
        preCorrectionActiveArrayTopPx = snapshot?.preCorrectionActiveArrayTopPx,
        preCorrectionActiveArrayRightPx = snapshot?.preCorrectionActiveArrayRightPx,
        preCorrectionActiveArrayBottomPx = snapshot?.preCorrectionActiveArrayBottomPx,
        sensorPhysicalWidthMm = snapshot?.sensorPhysicalWidthMm?.toDouble(),
        sensorPhysicalHeightMm = snapshot?.sensorPhysicalHeightMm?.toDouble(),
        // Deep-copy/normalize: a fresh List<Double> - never the original FloatArray reference, which is
        // mutable by construction (a caller retaining that array could otherwise mutate this "snapshot").
        availableFocalLengthsMm = snapshot?.availableFocalLengthsMm?.map { it.toDouble() },
    )

/** `internalDebug`-only. [SensorToBufferDomainBounds] deep-copied into a plain [MappedBoundsExportSnapshot]. */
private fun mappedBoundsExportSnapshot(bounds: SensorToBufferDomainBounds?): MappedBoundsExportSnapshot? =
    bounds?.let { MappedBoundsExportSnapshot(it.leftPx, it.topPx, it.rightPx, it.bottomPx) }

/**
 * `internalDebug`-only. Computes the latest frame's
 * [WholeActiveArrayMappingAssessment] - tests exactly the
 * one, explicitly-named hypothesis that the matrix's source domain is the *complete*
 * `SENSOR_INFO_ACTIVE_ARRAY_SIZE`-local rectangle (from [characteristics]'s own reported width/height),
 * against CAM-1g's own currently-tracked `ImageAnalysis` buffer width/height (from
 * [geometryBufferWidthPx]/[geometryBufferHeightPx] - deliberately CAM-1g's, not any CAM-2c-resolved
 * value, since this assessment must still be computable even when CAM-2c never resolves, e.g. the real
 * Pixel 9 `UnsupportedLogicalMultiCameraMapping` case). `null` only when no transform is present at all
 * — when a transform *is* present but the active-array/buffer dimensions are missing, a typed
 * unavailable verdict is returned instead of `null` (see
 * [WholeActiveArrayHypothesisVerdict.SOURCE_METADATA_UNAVAILABLE]/
 * `BUFFER_METADATA_UNAVAILABLE`).
 */
private fun frameTransformExportSnapshot(
    counters: CameraSessionIntrinsicsFrameCounters?,
    characteristics: CameraCharacteristicsSnapshot?,
    geometryBufferWidthPx: Int?,
    geometryBufferHeightPx: Int?,
): FrameTransformExportSnapshot {
    val transform = counters?.latestFrameTransform
    val activeArrayWidthPx =
        if (characteristics?.activeArrayLeftPx != null && characteristics.activeArrayRightPx != null) {
            characteristics.activeArrayRightPx - characteristics.activeArrayLeftPx
        } else {
            null
        }
    val activeArrayHeightPx =
        if (characteristics?.activeArrayTopPx != null && characteristics.activeArrayBottomPx != null) {
            characteristics.activeArrayBottomPx - characteristics.activeArrayTopPx
        } else {
            null
        }
    val assessment =
        transform?.let {
            assessWholeActiveArrayMappingHypothesis(
                matrix = it,
                sourceWidthPx = activeArrayWidthPx,
                sourceHeightPx = activeArrayHeightPx,
                bufferWidthPx = geometryBufferWidthPx,
                bufferHeightPx = geometryBufferHeightPx,
            )
        }
    return FrameTransformExportSnapshot(
        present = transform != null,
        // Deep-copy/normalize: a fresh 9-element List<Double> - never the SensorToBufferMatrix3 value
        // itself (harmless to retain since it is already immutable, but formatters must consume only
        // this export DTO, never a runtime/core domain type - see this file's own KDoc).
        matrix = transform?.let { listOf(it.m00, it.m01, it.m02, it.m10, it.m11, it.m12, it.m20, it.m21, it.m22) },
        transformClass = counters?.latestFrameTransformClass?.name,
        framesAnalyzed = counters?.framesAnalyzed ?: 0L,
        framesWithTransform = counters?.framesWithTransform ?: 0L,
        framesWithNullTransform = counters?.framesWithNullTransform ?: 0L,
        framesWithSupportedTransformClass = counters?.framesWithUsableTransform ?: 0L,
        coordinatorFramesWaited = counters?.coordinatorFramesWaited ?: 0,
        sourceDomainBasis = assessment?.sourceDomainBasis?.name,
        wholeActiveArrayHypothesisVerdict = assessment?.verdict?.name,
        mappedAssumedSourceBoundsPx = mappedBoundsExportSnapshot(assessment?.mappedAssumedSourceBoundsPx),
        expectedBufferBoundsPx = mappedBoundsExportSnapshot(assessment?.expectedBufferBoundsPx),
        hypothesisReason = assessment?.reason,
    )
}

private fun referenceTypeName(reference: CameraIntrinsicsReference): String =
    when (reference) {
        is CameraIntrinsicsReference.AnalysisBuffer -> "AnalysisBuffer"
        CameraIntrinsicsReference.PhysicalSensor -> "PhysicalSensor"
        CameraIntrinsicsReference.Unspecified -> "Unspecified"
    }

private fun publishedIntrinsicsExportSnapshot(resolution: CoreCameraIntrinsicsResolution?): PublishedIntrinsicsExportSnapshot {
    val reference = resolution?.intrinsics?.reference
    return PublishedIntrinsicsExportSnapshot(
        publication =
            when (resolution) {
                null -> null
                is CoreCameraIntrinsicsResolution.Resolved -> "Resolved"
                is CoreCameraIntrinsicsResolution.LegacyFallback -> "LegacyFallback"
            },
        fallbackReason = (resolution as? CoreCameraIntrinsicsResolution.LegacyFallback)?.reason,
        source = resolution?.intrinsics?.source?.name,
        reference = reference?.let { referenceTypeName(it) },
        referenceBufferWidthPx = (reference as? CameraIntrinsicsReference.AnalysisBuffer)?.widthPx,
        referenceBufferHeightPx = (reference as? CameraIntrinsicsReference.AnalysisBuffer)?.heightPx,
        quality = resolution?.intrinsics?.quality?.name,
    )
}

private fun attemptTypeName(attempt: AnalysisBufferIntrinsicsResolution?): String? =
    when (attempt) {
        null -> null
        is AnalysisBufferIntrinsicsResolution.Resolved -> "Resolved"
        AnalysisBufferIntrinsicsResolution.MissingActiveArray -> "MissingActiveArray"
        AnalysisBufferIntrinsicsResolution.MissingPhysicalSensorSize -> "MissingPhysicalSensorSize"
        AnalysisBufferIntrinsicsResolution.MissingPixelArraySize -> "MissingPixelArraySize"
        AnalysisBufferIntrinsicsResolution.MissingFocalLength -> "MissingFocalLength"
        AnalysisBufferIntrinsicsResolution.MissingSensorToBufferTransform -> "MissingSensorToBufferTransform"
        is AnalysisBufferIntrinsicsResolution.UnsupportedSensorToBufferTransform -> "UnsupportedSensorToBufferTransform"
        is AnalysisBufferIntrinsicsResolution.RotationOwnershipUnproven -> "RotationOwnershipUnproven"
        is AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping -> "UnsupportedLogicalMultiCameraMapping"
        is AnalysisBufferIntrinsicsResolution.InvalidMetadata -> "InvalidMetadata"
    }

private fun resolvedBufferKExportSnapshot(attempt: AnalysisBufferIntrinsicsResolution?): ResolvedBufferKExportSnapshot? {
    val resolved = attempt as? AnalysisBufferIntrinsicsResolution.Resolved ?: return null
    val d = resolved.diagnostics
    return ResolvedBufferKExportSnapshot(fxPx = d.bufferFxPx, fyPx = d.bufferFyPx, cxPx = d.bufferCxPx, cyPx = d.bufferCyPx)
}

private fun cam2cDiagnosticSnapshot(
    state: CameraSessionIntrinsicsDiagnosticState?,
    geometryBufferWidthPx: Int?,
    geometryBufferHeightPx: Int?,
): Cam2cDiagnosticSnapshot {
    val attempt = state?.analysisBufferAttempt
    return Cam2cDiagnosticSnapshot(
        coordinatorState = state?.coordinatorState?.name,
        attemptType = attemptTypeName(attempt),
        attemptTransformClass =
            when (attempt) {
                is AnalysisBufferIntrinsicsResolution.UnsupportedSensorToBufferTransform -> attempt.transformClass.name
                is AnalysisBufferIntrinsicsResolution.RotationOwnershipUnproven -> attempt.transformClass.name
                else -> null
            },
        attemptInvalidMetadataReason = (attempt as? AnalysisBufferIntrinsicsResolution.InvalidMetadata)?.reason,
        camera = cameraMetadataExportSnapshot(state?.cameraCharacteristicsSnapshot),
        frameTransform =
            frameTransformExportSnapshot(
                state?.frameCounters,
                state?.cameraCharacteristicsSnapshot,
                geometryBufferWidthPx,
                geometryBufferHeightPx,
            ),
        publishedIntrinsics = publishedIntrinsicsExportSnapshot(state?.publishedIntrinsicsResolution),
        resolvedBufferK = resolvedBufferKExportSnapshot(attempt),
    )
}

private fun cameraGeometryExportSnapshot(
    snapshot: CameraGeometryDiagnosticSnapshot?,
    statusTransitionCount: Int,
    observedFrameCount: Long,
    readyBundleCount: Long,
): CameraGeometryExportSnapshot {
    val cropLeft = snapshot?.cropLeftPx
    val cropTop = snapshot?.cropTopPx
    val cropRight = snapshot?.cropRightPx
    val cropBottom = snapshot?.cropBottomPx
    val cropRect =
        if (cropLeft != null && cropTop != null && cropRight != null && cropBottom != null) {
            CamDiagnosticCropRect(leftPx = cropLeft, topPx = cropTop, rightPx = cropRight, bottomPx = cropBottom)
        } else {
            null
        }
    return CameraGeometryExportSnapshot(
        category = snapshot?.category?.name,
        statusTransitionCount = statusTransitionCount,
        observedFrameCount = observedFrameCount,
        readyBundleCount = readyBundleCount,
        bufferWidthPx = snapshot?.bufferWidthPx,
        bufferHeightPx = snapshot?.bufferHeightPx,
        cropRect = cropRect,
        rotationDegrees = snapshot?.rotationDegrees,
        viewportWidthPx = snapshot?.viewportWidthPx,
        viewportHeightPx = snapshot?.viewportHeightPx,
    )
}

private fun cameraCalibrationExportSnapshot(diagnostics: CameraCalibrationDiagnostics?): CameraCalibrationExportSnapshot? {
    if (diagnostics == null) return null
    return CameraCalibrationExportSnapshot(
        activeArrayWidthPx = diagnostics.activeArrayWidthPx,
        activeArrayHeightPx = diagnostics.activeArrayHeightPx,
        activeArrayLeftPx = diagnostics.activeArrayLeftPx,
        activeArrayTopPx = diagnostics.activeArrayTopPx,
        activeArrayRightPx = diagnostics.activeArrayRightPx,
        activeArrayBottomPx = diagnostics.activeArrayBottomPx,
        pixelArrayWidthPx = diagnostics.pixelArrayWidthPx,
        pixelArrayHeightPx = diagnostics.pixelArrayHeightPx,
        sensorWidthMm = diagnostics.sensorWidthMm,
        sensorHeightMm = diagnostics.sensorHeightMm,
        focalLengthMm = diagnostics.focalLengthMm,
        activeFxPx = diagnostics.activeFxPx,
        activeFyPx = diagnostics.activeFyPx,
        activeCxPx = diagnostics.activeCxPx,
        activeCyPx = diagnostics.activeCyPx,
        principalPointBasis = diagnostics.principalPointBasis,
        focalDerivationBasis = diagnostics.focalDerivationBasis,
        cropLeftPx = diagnostics.cropLeftPx,
        cropTopPx = diagnostics.cropTopPx,
        cropRightPx = diagnostics.cropRightPx,
        cropBottomPx = diagnostics.cropBottomPx,
        bufferFxPx = diagnostics.bufferFxPx,
        bufferFyPx = diagnostics.bufferFyPx,
        bufferCxPx = diagnostics.bufferCxPx,
        bufferCyPx = diagnostics.bufferCyPx,
        quality = diagnostics.quality.name,
        sensorToBufferMappingSource = diagnostics.sensorToBufferMappingSource,
        transformClass = diagnostics.transformClass.name,
        skewDiagnosticReason = diagnostics.skewDiagnosticReason,
        cameraId = diagnostics.cameraId,
        isLogicalMultiCamera = diagnostics.isLogicalMultiCamera,
        // Deep-copy/normalize: a fresh, sorted List - never the original Set reference.
        physicalCameraIds = diagnostics.physicalCameraIds?.toList()?.sorted(),
    )
}

/**
 * `internalDebug`-only. Pure mapper (architecture fix §2): the **one** place a runtime
 * [PredictedStarOverlayState]/[CameraGeometryDiagnosticSnapshot]/[CameraSessionIntrinsicsDiagnosticState]/
 * [CameraCalibrationDiagnostics] is ever read to build a [CamDiagnosticSnapshot]. Every field is
 * deep-copied/normalized here, once: `Set`s become freshly sorted `List`s
 * (`source?.toList()?.sorted()`), `FloatArray`s become freshly built `List<Double>`s
 * (`source?.map(Float::toDouble)`), and the 3x3 sensor-to-buffer matrix becomes a freshly built
 * 9-element `List<Double>`. No reference to any runtime state object is ever stored in the returned
 * value - mutating the caller's own original array/set/state afterward can never change what this
 * function already returned (see `CamDiagnosticSnapshotTest`'s mutation-regression tests).
 *
 * [capturedAtEpochMillis] is a parameter, not `System.currentTimeMillis()` called here - this function
 * itself does not read the system clock, keeping it a pure, deterministic mapper.
 */
fun captureCamDiagnosticSnapshot(
    capturedAtEpochMillis: Long,
    sessionId: Long,
    cam2bState: PredictedStarOverlayState?,
    cameraGeometryState: CameraGeometryDiagnosticSnapshot?,
    cameraGeometryStatusTransitionCount: Int,
    cameraGeometryObservedFrameCount: Long,
    cameraGeometryReadyBundleCount: Long,
    cameraIntrinsicsState: CameraSessionIntrinsicsDiagnosticState?,
    calibrationDiagnostics: CameraCalibrationDiagnostics?,
): CamDiagnosticSnapshot =
    CamDiagnosticSnapshot(
        capturedAtEpochMillis = capturedAtEpochMillis,
        sessionId = sessionId,
        cam2b = cam2bDiagnosticSnapshot(cam2bState),
        cam2c = cam2cDiagnosticSnapshot(cameraIntrinsicsState, cameraGeometryState?.bufferWidthPx, cameraGeometryState?.bufferHeightPx),
        geometry =
            cameraGeometryExportSnapshot(
                cameraGeometryState,
                cameraGeometryStatusTransitionCount,
                cameraGeometryObservedFrameCount,
                cameraGeometryReadyBundleCount,
            ),
        calibration = cameraCalibrationExportSnapshot(calibrationDiagnostics),
    )

/** `internalDebug`-only convenience overload taking a [CamDiagnosticsExportInput] directly. */
fun captureCamDiagnosticSnapshot(
    input: CamDiagnosticsExportInput,
    capturedAtEpochMillis: Long,
): CamDiagnosticSnapshot =
    captureCamDiagnosticSnapshot(
        capturedAtEpochMillis = capturedAtEpochMillis,
        sessionId = input.sessionId,
        cam2bState = input.cam2bState,
        cameraGeometryState = input.cameraGeometryState,
        cameraGeometryStatusTransitionCount = input.cameraGeometryStatusTransitionCount,
        cameraGeometryObservedFrameCount = input.cameraGeometryObservedFrameCount,
        cameraGeometryReadyBundleCount = input.cameraGeometryReadyBundleCount,
        cameraIntrinsicsState = input.cameraIntrinsicsState,
        calibrationDiagnostics = input.calibrationDiagnostics,
    )
