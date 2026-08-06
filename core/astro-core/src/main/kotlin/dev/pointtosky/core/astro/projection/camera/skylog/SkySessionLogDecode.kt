package dev.pointtosky.core.astro.projection.camera.skylog

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarClassification
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The decode half of the SKY-1 JSONL codec — the inverse of [encodeSkySessionHeaderLine] and
 * [encodeSkyFrameLine], split into its own file because encoding and decoding a schema this wide are
 * two independently readable concerns.
 *
 * Every function here may throw [IllegalArgumentException] for a missing or malformed field (see
 * [requiredString] and friends). That is deliberate and local: [parseSkySessionLogLine] is the only
 * caller, and it converts every such throw into a [SkySessionLogLine.Unreadable] — so a damaged line
 * is data the reader reports, never an exception a consumer has to catch.
 *
 * The `"pinhole"` and `"quaternion"` fields are **not** read back: both are derived on write. See
 * [SkySessionLog]'s "two kinds of fields".
 */

internal fun decodeHeader(obj: JsonObject): SkySessionLogHeader {
    val alignment = obj.obj("clockAlignment") ?: throw IllegalArgumentException("missing \"clockAlignment\"")
    val intrinsics = obj.obj("intrinsics") ?: throw IllegalArgumentException("missing \"intrinsics\"")
    return SkySessionLogHeader(
        sessionId = obj.requiredString("sessionId"),
        startedAtEpochMillis = obj.requiredLong("startedAtEpochMillis"),
        bufferWidthPx = obj.requiredInt("bufferWidthPx"),
        bufferHeightPx = obj.requiredInt("bufferHeightPx"),
        intrinsics = decodeIntrinsics(intrinsics),
        clockAlignment =
            SkyClockAlignment(
                frameClock = alignment.requiredEnum("frameClock", SkyClock.entries),
                poseClock = alignment.requiredEnum("poseClock", SkyClock.entries),
                poseToFrameOffsetNanos = alignment.long("poseToFrameOffsetNanos"),
            ),
        maxPairDeltaNanos = obj.requiredLong("maxPairDeltaNanos"),
        clockMismatchThresholdNanos = obj.requiredLong("clockMismatchThresholdNanos"),
        schemaVersion = obj.int("schemaVersion") ?: SKY_SESSION_LOG_SCHEMA_VERSION,
        lumaFormat = obj.enum("lumaFormat", SkyLumaFormat.entries) ?: SkyLumaFormat.RAW_Y8,
        deviceModel = obj.string("deviceModel"),
        cameraId = obj.string("cameraId"),
        physicalCameraIds = obj.stringList("physicalCameraIds"),
        calibration = obj.obj("calibration")?.let { decodeCalibration(it) },
        notes = obj.string("notes"),
    )
}

internal fun decodeIntrinsics(obj: JsonObject): SkyIntrinsicsRecord =
    SkyIntrinsicsRecord(
        horizontalFovDeg = obj.requiredDouble("horizontalFovDeg"),
        verticalFovDeg = obj.requiredDouble("verticalFovDeg"),
        source = obj.requiredEnum("source", CameraIntrinsicsSource.entries),
        referenceKind = obj.requiredEnum("referenceKind", SkyIntrinsicsReferenceKind.entries),
        referenceWidthPx = obj.int("referenceWidthPx"),
        referenceHeightPx = obj.int("referenceHeightPx"),
        quality = obj.enum("quality", CameraIntrinsicsQuality.entries),
        focalLengthMm = obj.double("focalLengthMm"),
        sensorWidthMm = obj.double("sensorWidthMm"),
        sensorHeightMm = obj.double("sensorHeightMm"),
        principalPointXPx = obj.double("principalPointXPx"),
        principalPointYPx = obj.double("principalPointYPx"),
        axisSwapped = obj.boolean("axisSwapped") ?: false,
        negateXInput = obj.boolean("negateXInput") ?: false,
        negateYInput = obj.boolean("negateYInput") ?: false,
        legacyFallbackReason = obj.string("legacyFallbackReason"),
        // "pinhole" is deliberately not read back: it is derived on write. See SkyPinholeRecord.
        pinhole = null,
    )

internal fun decodeCalibration(obj: JsonObject): SkyCalibrationRecord =
    SkyCalibrationRecord(
        activeArrayWidthPx = obj.requiredInt("activeArrayWidthPx"),
        activeArrayHeightPx = obj.requiredInt("activeArrayHeightPx"),
        activeArrayLeftPx = obj.requiredDouble("activeArrayLeftPx"),
        activeArrayTopPx = obj.requiredDouble("activeArrayTopPx"),
        activeArrayRightPx = obj.requiredDouble("activeArrayRightPx"),
        activeArrayBottomPx = obj.requiredDouble("activeArrayBottomPx"),
        sensorWidthMm = obj.requiredDouble("sensorWidthMm"),
        sensorHeightMm = obj.requiredDouble("sensorHeightMm"),
        focalLengthMm = obj.requiredDouble("focalLengthMm"),
        activeFxPx = obj.requiredDouble("activeFxPx"),
        activeFyPx = obj.requiredDouble("activeFyPx"),
        activeCxPx = obj.requiredDouble("activeCxPx"),
        activeCyPx = obj.requiredDouble("activeCyPx"),
        bufferFxPx = obj.requiredDouble("bufferFxPx"),
        bufferFyPx = obj.requiredDouble("bufferFyPx"),
        bufferCxPx = obj.requiredDouble("bufferCxPx"),
        bufferCyPx = obj.requiredDouble("bufferCyPx"),
        quality = obj.requiredString("quality"),
        sensorToBufferMappingSource = obj.requiredString("sensorToBufferMappingSource"),
        transformClass = obj.requiredString("transformClass"),
    )

internal fun decodeFrame(obj: JsonObject): SkyFrameRecord {
    val frameObj = obj.obj("frame") ?: throw IllegalArgumentException("missing \"frame\"")
    val lumaObj = obj.obj("luma") ?: throw IllegalArgumentException("missing \"luma\"")
    val poseObj = obj.obj("pose") ?: throw IllegalArgumentException("missing \"pose\"")
    return SkyFrameRecord(
        sequence = obj.requiredLong("seq"),
        capturedAtEpochMillis = obj.requiredLong("capturedAtEpochMillis"),
        frame = decodeFrameMetadata(frameObj),
        viewportWidthPx = obj.requiredInt("viewportWidthPx"),
        viewportHeightPx = obj.requiredInt("viewportHeightPx"),
        luma =
            SkyLumaReference(
                path = lumaObj.requiredString("path"),
                format = lumaObj.enum("format", SkyLumaFormat.entries) ?: SkyLumaFormat.RAW_Y8,
                widthPx = lumaObj.requiredInt("widthPx"),
                heightPx = lumaObj.requiredInt("heightPx"),
                rowStridePx = lumaObj.requiredInt("rowStridePx"),
                byteLength = lumaObj.requiredLong("byteLength"),
            ),
        pose =
            SkyPoseSample(
                timestampNanos = poseObj.requiredLong("timestampNanos"),
                rotationMatrix = poseObj.requiredDoubleList("rotationMatrix"),
                frameToPoseDeltaNanos = poseObj.requiredLong("frameToPoseDeltaNanos"),
            ),
        observer =
            obj.obj("observer")?.let {
                SkyObserverContext(
                    latitudeDeg = it.requiredDouble("latitudeDeg"),
                    longitudeDeg = it.requiredDouble("longitudeDeg"),
                    utcEpochMillis = it.requiredLong("utcEpochMillis"),
                    horizontalAccuracyM = it.double("horizontalAccuracyM"),
                    magneticDeclinationDeg = it.double("magneticDeclinationDeg"),
                )
            },
        exposure =
            obj.obj("exposure")?.let {
                SkyExposureSample(
                    exposureTimeNanos = it.long("exposureTimeNanos"),
                    sensitivityIso = it.int("sensitivityIso"),
                    frameDurationNanos = it.long("frameDurationNanos"),
                    aeMode = it.string("aeMode"),
                    awbMode = it.string("awbMode"),
                    sensorTimestampNanos = it.long("sensorTimestampNanos"),
                )
            },
        predictedStars =
            (obj["predictedStars"] as? JsonArray)?.map { entry ->
                val star =
                    entry as? JsonObject ?: throw IllegalArgumentException("predictedStars entry is not an object")
                SkyPredictedStar(
                    catalogIndex = star.requiredInt("id"),
                    rightAscensionRad = star.requiredDouble("raRad"),
                    declinationRad = star.requiredDouble("decRad"),
                    magnitude = star.double("mag"),
                    classification = star.requiredEnum("classification", PredictedStarClassification.entries),
                    imageXPx = star.double("xPx"),
                    imageYPx = star.double("yPx"),
                    displayXPx = star.double("displayXPx"),
                    displayYPx = star.double("displayYPx"),
                )
            } ?: emptyList(),
    )
}

/** A row-major 3x3 sensor-to-buffer matrix, flattened. */
private const val MATRIX_3X3_SIZE = 9

internal fun decodeFrameMetadata(obj: JsonObject): CameraFrameMetadata {
    val crop = obj.obj("cropRect")
    val sensorToBuffer =
        obj.takeIf { it["sensorToBufferTransform"] != null }?.let {
            val v = it.requiredDoubleList("sensorToBufferTransform")
            require(v.size == MATRIX_3X3_SIZE) {
                "sensorToBufferTransform must have $MATRIX_3X3_SIZE elements; was ${v.size}"
            }
            SensorToBufferMatrix3(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8])
        }
    return CameraFrameMetadata(
        timestampNanos = obj.requiredLong("timestampNanos"),
        bufferWidthPx = obj.requiredInt("bufferWidthPx"),
        bufferHeightPx = obj.requiredInt("bufferHeightPx"),
        rotationDegrees = obj.requiredInt("rotationDegrees"),
        cropRectLeftPx = crop?.requiredInt("leftPx"),
        cropRectTopPx = crop?.requiredInt("topPx"),
        cropRectRightPx = crop?.requiredInt("rightPx"),
        cropRectBottomPx = crop?.requiredInt("bottomPx"),
        sensorToBufferTransform = sensorToBuffer,
    )
}
