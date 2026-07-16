package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayState
import dev.pointtosky.mobile.ar.camera.prediction.name
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution as CoreCameraIntrinsicsResolution

/**
 * Current schema version for [buildCamDiagnosticJson] (diagnostic export/freeze fix §5). Bump this
 * whenever a field is renamed, removed, or reinterpreted; a purely additive field may keep the same
 * version. Every export always carries its own `schemaVersion`, so a later CAM-2d evidence-collection
 * pass can tell which shape an archived export used without guessing from field presence alone.
 */
const val CAM_DIAGNOSTIC_JSON_SCHEMA_VERSION: Int = 1

/**
 * The [Json] instance [buildCamDiagnosticJson] uses by default - `explicitNulls = true` so every
 * documented field is always present with either a real value or a literal `null` (never silently
 * omitted), and `prettyPrint = true` since this export is meant to be read by a human reviewer, not
 * parsed by a machine pipeline. kotlinx.serialization's own [kotlinx.serialization.json.JsonPrimitive]
 * number formatting never consults the platform default [java.util.Locale] (it uses Kotlin/JVM's
 * locale-independent `Number.toString()`), so no additional `Locale.ROOT` handling is needed here.
 */
val camDiagnosticJson: Json =
    Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
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

private fun referenceTypeName(reference: CameraIntrinsicsReference): String =
    when (reference) {
        is CameraIntrinsicsReference.AnalysisBuffer -> "AnalysisBuffer"
        CameraIntrinsicsReference.PhysicalSensor -> "PhysicalSensor"
        CameraIntrinsicsReference.Unspecified -> "Unspecified"
    }

private fun JsonObjectBuilder.putCam2b(state: PredictedStarOverlayState?) {
    val status =
        when (state) {
            null -> null
            PredictedStarOverlayState.Disabled -> "DISABLED"
            is PredictedStarOverlayState.Waiting -> "WAITING"
            is PredictedStarOverlayState.Unavailable -> "UNAVAILABLE"
            is PredictedStarOverlayState.Ready -> "READY"
        }
    put("status", status)
    put("waitingReason", (state as? PredictedStarOverlayState.Waiting)?.reason?.name)
    put("unavailableReason", (state as? PredictedStarOverlayState.Unavailable)?.reason?.name)
    val ready = state as? PredictedStarOverlayState.Ready
    put("intrinsicsMode", ready?.metadata?.intrinsicsMode?.name)
    put("inputCount", ready?.metadata?.inputCount)
    put("visibleCount", ready?.metadata?.visibleCount)
}

private fun JsonObjectBuilder.putPublishedIntrinsics(resolution: CoreCameraIntrinsicsResolution?) {
    val publication =
        when (resolution) {
            null -> null
            is CoreCameraIntrinsicsResolution.Resolved -> "Resolved"
            is CoreCameraIntrinsicsResolution.LegacyFallback -> "LegacyFallback"
        }
    put("publication", publication)
    put("fallbackReason", (resolution as? CoreCameraIntrinsicsResolution.LegacyFallback)?.reason)
    put("source", resolution?.intrinsics?.source?.name)
    val reference = resolution?.intrinsics?.reference
    put("reference", reference?.let { referenceTypeName(it) })
    put("referenceBufferWidthPx", (reference as? CameraIntrinsicsReference.AnalysisBuffer)?.widthPx)
    put("referenceBufferHeightPx", (reference as? CameraIntrinsicsReference.AnalysisBuffer)?.heightPx)
    put("quality", resolution?.intrinsics?.quality?.name)
}

private fun JsonObjectBuilder.putMetadata(snapshot: CameraCharacteristicsSnapshot?) {
    put("pixelArrayWidthPx", snapshot?.pixelArrayWidthPx)
    put("pixelArrayHeightPx", snapshot?.pixelArrayHeightPx)
    put("activeArrayLeftPx", snapshot?.activeArrayLeftPx)
    put("activeArrayTopPx", snapshot?.activeArrayTopPx)
    put("activeArrayRightPx", snapshot?.activeArrayRightPx)
    put("activeArrayBottomPx", snapshot?.activeArrayBottomPx)
    put("preCorrectionActiveArrayLeftPx", snapshot?.preCorrectionActiveArrayLeftPx)
    put("preCorrectionActiveArrayTopPx", snapshot?.preCorrectionActiveArrayTopPx)
    put("preCorrectionActiveArrayRightPx", snapshot?.preCorrectionActiveArrayRightPx)
    put("preCorrectionActiveArrayBottomPx", snapshot?.preCorrectionActiveArrayBottomPx)
    put("sensorPhysicalWidthMm", snapshot?.sensorPhysicalWidthMm?.toDouble())
    put("sensorPhysicalHeightMm", snapshot?.sensorPhysicalHeightMm?.toDouble())
    val focalLengths = snapshot?.availableFocalLengthsMm
    put(
        "availableFocalLengthsMm",
        focalLengths?.let { array -> buildJsonArray { array.forEach { add(it.toDouble()) } } } ?: JsonNull,
    )
}

private fun JsonObjectBuilder.putFrameTransform(state: CameraSessionIntrinsicsDiagnosticState?) {
    val counters = state?.frameCounters
    val transform = counters?.latestFrameTransform
    put("present", transform != null)
    val matrixArray =
        transform?.let { m ->
            buildJsonArray {
                add(m.m00)
                add(m.m01)
                add(m.m02)
                add(m.m10)
                add(m.m11)
                add(m.m12)
                add(m.m20)
                add(m.m21)
                add(m.m22)
            }
        }
    put("matrix", matrixArray ?: JsonNull)
    put("transformClass", counters?.latestFrameTransformClass?.name)
    put("framesAnalyzed", counters?.framesAnalyzed ?: 0L)
    put("framesWithTransform", counters?.framesWithTransform ?: 0L)
    put("framesWithNullTransform", counters?.framesWithNullTransform ?: 0L)
    put("framesWithUsableTransform", counters?.framesWithUsableTransform ?: 0L)
    put("coordinatorFramesWaited", counters?.coordinatorFramesWaited ?: 0)
}

/** `null` unless [attempt] is [AnalysisBufferIntrinsicsResolution.Resolved] - the resolved buffer `K`
 * only exists once the calibrated mapping actually succeeded. */
private fun resolvedBufferKJson(attempt: AnalysisBufferIntrinsicsResolution?): JsonElement {
    val resolved = attempt as? AnalysisBufferIntrinsicsResolution.Resolved ?: return JsonNull
    val diagnostics = resolved.diagnostics
    return buildJsonObject {
        put("fxPx", diagnostics.bufferFxPx)
        put("fyPx", diagnostics.bufferFyPx)
        put("cxPx", diagnostics.bufferCxPx)
        put("cyPx", diagnostics.bufferCyPx)
    }
}

private fun cam2cJson(state: CameraSessionIntrinsicsDiagnosticState?): JsonElement =
    buildJsonObject {
        val attempt = state?.analysisBufferAttempt
        val cameraSnapshot = state?.cameraCharacteristicsSnapshot
        put("coordinatorState", state?.coordinatorState?.name)
        put("attemptType", attemptTypeName(attempt))
        put("cameraId", cameraSnapshot?.cameraId)
        put("logicalMultiCamera", cameraSnapshot?.isLogicalMultiCamera)
        put(
            "physicalCameraIds",
            cameraSnapshot?.physicalCameraIds?.sorted()?.let { ids -> buildJsonArray { ids.forEach { add(it) } } }
                ?: JsonNull,
        )
        put(
            "unsupportedTransformClass",
            when (attempt) {
                is AnalysisBufferIntrinsicsResolution.UnsupportedSensorToBufferTransform -> attempt.transformClass.name
                is AnalysisBufferIntrinsicsResolution.RotationOwnershipUnproven -> attempt.transformClass.name
                else -> null
            },
        )
        put("invalidMetadataReason", (attempt as? AnalysisBufferIntrinsicsResolution.InvalidMetadata)?.reason)
        put("publishedIntrinsics", buildJsonObject { putPublishedIntrinsics(state?.publishedIntrinsicsResolution) })
        put("metadata", buildJsonObject { putMetadata(cameraSnapshot) })
        put("frameTransform", buildJsonObject { putFrameTransform(state) })
        put("resolvedBufferK", resolvedBufferKJson(attempt))
    }

/** Every [CameraCalibrationDiagnostics] field, verbatim - including all 9 sensor-to-buffer matrix
 * values are covered separately under `cam2c.frameTransform.matrix` (the per-frame transport this
 * calibration was actually computed from); this object carries the *resolved* crop/K numbers instead. */
private fun calibrationJson(diagnostics: CameraCalibrationDiagnostics?): JsonElement {
    if (diagnostics == null) return JsonNull
    return buildJsonObject {
        put("activeArrayWidthPx", diagnostics.activeArrayWidthPx)
        put("activeArrayHeightPx", diagnostics.activeArrayHeightPx)
        put("activeArrayLeftPx", diagnostics.activeArrayLeftPx)
        put("activeArrayTopPx", diagnostics.activeArrayTopPx)
        put("activeArrayRightPx", diagnostics.activeArrayRightPx)
        put("activeArrayBottomPx", diagnostics.activeArrayBottomPx)
        put("pixelArrayWidthPx", diagnostics.pixelArrayWidthPx)
        put("pixelArrayHeightPx", diagnostics.pixelArrayHeightPx)
        put("sensorWidthMm", diagnostics.sensorWidthMm)
        put("sensorHeightMm", diagnostics.sensorHeightMm)
        put("focalLengthMm", diagnostics.focalLengthMm)
        put("activeFxPx", diagnostics.activeFxPx)
        put("activeFyPx", diagnostics.activeFyPx)
        put("activeCxPx", diagnostics.activeCxPx)
        put("activeCyPx", diagnostics.activeCyPx)
        put("principalPointBasis", diagnostics.principalPointBasis)
        put("focalDerivationBasis", diagnostics.focalDerivationBasis)
        put("cropLeftPx", diagnostics.cropLeftPx)
        put("cropTopPx", diagnostics.cropTopPx)
        put("cropRightPx", diagnostics.cropRightPx)
        put("cropBottomPx", diagnostics.cropBottomPx)
        put("bufferFxPx", diagnostics.bufferFxPx)
        put("bufferFyPx", diagnostics.bufferFyPx)
        put("bufferCxPx", diagnostics.bufferCxPx)
        put("bufferCyPx", diagnostics.bufferCyPx)
        put("quality", diagnostics.quality.name)
        put("sensorToBufferMappingSource", diagnostics.sensorToBufferMappingSource)
        put("transformClass", diagnostics.transformClass.name)
        put("skewDiagnosticReason", diagnostics.skewDiagnosticReason)
        put("cameraId", diagnostics.cameraId)
        put("isLogicalMultiCamera", diagnostics.isLogicalMultiCamera)
        put(
            "physicalCameraIds",
            diagnostics.physicalCameraIds?.sorted()?.let { ids -> buildJsonArray { ids.forEach { add(it) } } } ?: JsonNull,
        )
    }
}

private fun geometryJson(snapshot: CamDiagnosticSnapshot): JsonElement =
    buildJsonObject {
        put("category", snapshot.cameraGeometryState?.category?.name)
        put("bufferWidthPx", snapshot.bufferWidthPx)
        put("bufferHeightPx", snapshot.bufferHeightPx)
        val crop = snapshot.cropRect
        put(
            "cropRect",
            crop?.let {
                buildJsonObject {
                    put("leftPx", it.leftPx)
                    put("topPx", it.topPx)
                    put("rightPx", it.rightPx)
                    put("bottomPx", it.bottomPx)
                }
            } ?: JsonNull,
        )
        put("rotationDegrees", snapshot.rotationDegrees)
        put("viewportWidthPx", snapshot.viewportWidthPx)
        put("viewportHeightPx", snapshot.viewportHeightPx)
    }

/**
 * Builds the versioned, deterministic JSON export (diagnostic export/freeze fix §5) as a
 * [JsonObject] - every field name fixed and documented above, every one of the sensor-to-buffer
 * matrix's 9 values preserved as JSON numbers (never a formatted string), no star catalog contents, no
 * image pixels. Nulls are always explicit (`camDiagnosticJson`'s `explicitNulls = true`) rather than
 * silently omitted, so a downstream reader can always distinguish "this field does not apply" from "this
 * export is from an older/incomplete schema".
 */
fun buildCamDiagnosticJsonElement(
    snapshot: CamDiagnosticSnapshot,
    liveness: CamDiagnosticLiveness,
): JsonObject =
    buildJsonObject {
        put("schemaVersion", CAM_DIAGNOSTIC_JSON_SCHEMA_VERSION)
        put("capturedAtEpochMillis", snapshot.capturedAtEpochMillis)
        put("sessionId", snapshot.sessionId)
        put("diagnostics", liveness.name)
        put(
            "session",
            buildJsonObject {
                put("statusTransitionCount", snapshot.cameraGeometryStatusTransitionCount)
                put("observedFrameCount", snapshot.cameraGeometryObservedFrameCount)
                put("readyBundleCount", snapshot.cameraGeometryReadyBundleCount)
            },
        )
        put("cam2b", buildJsonObject { putCam2b(snapshot.cam2bState) })
        put("cam2c", cam2cJson(snapshot.cameraIntrinsicsState))
        put("calibration", calibrationJson(snapshot.calibrationDiagnostics))
        put("geometry", geometryJson(snapshot))
    }

/** [buildCamDiagnosticJsonElement], serialized to a `String` via [json] (defaults to [camDiagnosticJson]). */
fun buildCamDiagnosticJson(
    snapshot: CamDiagnosticSnapshot,
    liveness: CamDiagnosticLiveness,
    json: Json = camDiagnosticJson,
): String = json.encodeToString(JsonObject.serializer(), buildCamDiagnosticJsonElement(snapshot, liveness))
