package dev.pointtosky.mobile.ar.camera

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `internalDebug`-only. Current schema version for [buildCamDiagnosticJson]. Bump whenever a field is
 * renamed, removed, or reinterpreted; a purely additive field may keep the same version.
 *
 * Bumped to `2` by the CAM-2c domain-consistency fix: `cam2c.frameTransform.framesWithUsableTransform`
 * was renamed to `framesWithSupportedTransformClass` (see that field's own KDoc for why the old name
 * overstated what the counter proves), and `domainConsistency`/`mappedSourceBoundsPx`/
 * `expectedBufferBoundsPx`/`consistencyReason` were added alongside it.
 */
const val CAM_DIAGNOSTIC_JSON_SCHEMA_VERSION: Int = 2

/** `internalDebug`-only. `explicitNulls = true` so every documented field is always present with either
 * a real value or a literal `null`. kotlinx.serialization's own number formatting never consults the
 * platform default [java.util.Locale]. */
val camDiagnosticJson: Json =
    Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
    }

private fun JsonObjectBuilder.putCam2b(cam2b: Cam2bDiagnosticSnapshot) {
    put("status", cam2b.status)
    put("waitingReason", cam2b.waitingReason)
    put("unavailableReason", cam2b.unavailableReason)
    put("intrinsicsMode", cam2b.intrinsicsMode)
    put("inputCount", cam2b.inputCount)
    put("visibleCount", cam2b.visibleCount)
}

private fun JsonObjectBuilder.putPublishedIntrinsics(published: PublishedIntrinsicsExportSnapshot) {
    put("publication", published.publication)
    put("fallbackReason", published.fallbackReason)
    put("source", published.source)
    put("reference", published.reference)
    put("referenceBufferWidthPx", published.referenceBufferWidthPx)
    put("referenceBufferHeightPx", published.referenceBufferHeightPx)
    put("quality", published.quality)
}

private fun JsonObjectBuilder.putMetadata(camera: CameraMetadataExportSnapshot) {
    put("pixelArrayWidthPx", camera.pixelArrayWidthPx)
    put("pixelArrayHeightPx", camera.pixelArrayHeightPx)
    put("activeArrayLeftPx", camera.activeArrayLeftPx)
    put("activeArrayTopPx", camera.activeArrayTopPx)
    put("activeArrayRightPx", camera.activeArrayRightPx)
    put("activeArrayBottomPx", camera.activeArrayBottomPx)
    put("preCorrectionActiveArrayLeftPx", camera.preCorrectionActiveArrayLeftPx)
    put("preCorrectionActiveArrayTopPx", camera.preCorrectionActiveArrayTopPx)
    put("preCorrectionActiveArrayRightPx", camera.preCorrectionActiveArrayRightPx)
    put("preCorrectionActiveArrayBottomPx", camera.preCorrectionActiveArrayBottomPx)
    put("sensorPhysicalWidthMm", camera.sensorPhysicalWidthMm)
    put("sensorPhysicalHeightMm", camera.sensorPhysicalHeightMm)
    put(
        "availableFocalLengthsMm",
        camera.availableFocalLengthsMm?.let { list -> buildJsonArray { list.forEach { add(it) } } } ?: JsonNull,
    )
}

private fun mappedBoundsJson(bounds: MappedBoundsExportSnapshot?): JsonElement {
    if (bounds == null) return JsonNull
    return buildJsonObject {
        put("leftPx", bounds.leftPx)
        put("topPx", bounds.topPx)
        put("rightPx", bounds.rightPx)
        put("bottomPx", bounds.bottomPx)
    }
}

private fun JsonObjectBuilder.putFrameTransform(frameTransform: FrameTransformExportSnapshot) {
    put("present", frameTransform.present)
    put(
        "matrix",
        frameTransform.matrix?.let { m -> buildJsonArray { m.forEach { add(it) } } } ?: JsonNull,
    )
    put("transformClass", frameTransform.transformClass)
    put("framesAnalyzed", frameTransform.framesAnalyzed)
    put("framesWithTransform", frameTransform.framesWithTransform)
    put("framesWithNullTransform", frameTransform.framesWithNullTransform)
    // CAM-2c domain-consistency fix: renamed from framesWithUsableTransform (schema v1 -> v2) - this
    // counts a structurally supported transform CLASS, never a semantically-checked "usable" transform;
    // see domainConsistency below for the separate semantic verdict.
    put("framesWithSupportedTransformClass", frameTransform.framesWithSupportedTransformClass)
    put("coordinatorFramesWaited", frameTransform.coordinatorFramesWaited)
    put("domainConsistency", frameTransform.domainConsistency)
    put("mappedSourceBoundsPx", mappedBoundsJson(frameTransform.mappedSourceBoundsPx))
    put("expectedBufferBoundsPx", mappedBoundsJson(frameTransform.expectedBufferBoundsPx))
    put("consistencyReason", frameTransform.consistencyReason)
}

private fun resolvedBufferKJson(resolvedBufferK: ResolvedBufferKExportSnapshot?): JsonElement {
    if (resolvedBufferK == null) return JsonNull
    return buildJsonObject {
        put("fxPx", resolvedBufferK.fxPx)
        put("fyPx", resolvedBufferK.fyPx)
        put("cxPx", resolvedBufferK.cxPx)
        put("cyPx", resolvedBufferK.cyPx)
    }
}

private fun cam2cJson(cam2c: Cam2cDiagnosticSnapshot): JsonElement =
    buildJsonObject {
        put("coordinatorState", cam2c.coordinatorState)
        put("attemptType", cam2c.attemptType)
        put("cameraId", cam2c.camera.cameraId)
        put("logicalMultiCamera", cam2c.camera.logicalMultiCamera)
        put(
            "physicalCameraIds",
            cam2c.camera.physicalCameraIds?.let { ids -> buildJsonArray { ids.forEach { add(it) } } } ?: JsonNull,
        )
        put("unsupportedTransformClass", cam2c.attemptTransformClass)
        put("invalidMetadataReason", cam2c.attemptInvalidMetadataReason)
        put("publishedIntrinsics", buildJsonObject { putPublishedIntrinsics(cam2c.publishedIntrinsics) })
        put("metadata", buildJsonObject { putMetadata(cam2c.camera) })
        put("frameTransform", buildJsonObject { putFrameTransform(cam2c.frameTransform) })
        put("resolvedBufferK", resolvedBufferKJson(cam2c.resolvedBufferK))
    }

private fun calibrationJson(calibration: CameraCalibrationExportSnapshot?): JsonElement {
    if (calibration == null) return JsonNull
    return buildJsonObject {
        put("activeArrayWidthPx", calibration.activeArrayWidthPx)
        put("activeArrayHeightPx", calibration.activeArrayHeightPx)
        put("activeArrayLeftPx", calibration.activeArrayLeftPx)
        put("activeArrayTopPx", calibration.activeArrayTopPx)
        put("activeArrayRightPx", calibration.activeArrayRightPx)
        put("activeArrayBottomPx", calibration.activeArrayBottomPx)
        put("pixelArrayWidthPx", calibration.pixelArrayWidthPx)
        put("pixelArrayHeightPx", calibration.pixelArrayHeightPx)
        put("sensorWidthMm", calibration.sensorWidthMm)
        put("sensorHeightMm", calibration.sensorHeightMm)
        put("focalLengthMm", calibration.focalLengthMm)
        put("activeFxPx", calibration.activeFxPx)
        put("activeFyPx", calibration.activeFyPx)
        put("activeCxPx", calibration.activeCxPx)
        put("activeCyPx", calibration.activeCyPx)
        put("principalPointBasis", calibration.principalPointBasis)
        put("focalDerivationBasis", calibration.focalDerivationBasis)
        put("cropLeftPx", calibration.cropLeftPx)
        put("cropTopPx", calibration.cropTopPx)
        put("cropRightPx", calibration.cropRightPx)
        put("cropBottomPx", calibration.cropBottomPx)
        put("bufferFxPx", calibration.bufferFxPx)
        put("bufferFyPx", calibration.bufferFyPx)
        put("bufferCxPx", calibration.bufferCxPx)
        put("bufferCyPx", calibration.bufferCyPx)
        put("quality", calibration.quality)
        put("sensorToBufferMappingSource", calibration.sensorToBufferMappingSource)
        put("transformClass", calibration.transformClass)
        put("skewDiagnosticReason", calibration.skewDiagnosticReason)
        put("cameraId", calibration.cameraId)
        put("isLogicalMultiCamera", calibration.isLogicalMultiCamera)
        put(
            "physicalCameraIds",
            calibration.physicalCameraIds?.let { ids -> buildJsonArray { ids.forEach { add(it) } } } ?: JsonNull,
        )
    }
}

private fun geometryJson(geometry: CameraGeometryExportSnapshot): JsonElement =
    buildJsonObject {
        put("category", geometry.category)
        put("bufferWidthPx", geometry.bufferWidthPx)
        put("bufferHeightPx", geometry.bufferHeightPx)
        put(
            "cropRect",
            geometry.cropRect?.let {
                buildJsonObject {
                    put("leftPx", it.leftPx)
                    put("topPx", it.topPx)
                    put("rightPx", it.rightPx)
                    put("bottomPx", it.bottomPx)
                }
            } ?: JsonNull,
        )
        put("rotationDegrees", geometry.rotationDegrees)
        put("viewportWidthPx", geometry.viewportWidthPx)
        put("viewportHeightPx", geometry.viewportHeightPx)
    }

/**
 * `internalDebug`-only. Builds the versioned, deterministic JSON export as a [JsonObject] - every field
 * name fixed and documented, every one of the sensor-to-buffer matrix's 9 values preserved as a JSON
 * number (never a formatted string), no star catalog contents, no image pixels. Consumes exclusively
 * the immutable [CamDiagnosticSnapshot] DTO tree. Nulls are always explicit rather than silently
 * omitted.
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
                put("statusTransitionCount", snapshot.geometry.statusTransitionCount)
                put("observedFrameCount", snapshot.geometry.observedFrameCount)
                put("readyBundleCount", snapshot.geometry.readyBundleCount)
            },
        )
        put("cam2b", buildJsonObject { putCam2b(snapshot.cam2b) })
        put("cam2c", cam2cJson(snapshot.cam2c))
        put("calibration", calibrationJson(snapshot.calibration))
        put("geometry", geometryJson(snapshot.geometry))
    }

/** `internalDebug`-only. [buildCamDiagnosticJsonElement], serialized to a `String` via [json]. */
fun buildCamDiagnosticJson(
    snapshot: CamDiagnosticSnapshot,
    liveness: CamDiagnosticLiveness,
    json: Json = camDiagnosticJson,
): String = json.encodeToString(JsonObject.serializer(), buildCamDiagnosticJsonElement(snapshot, liveness))
