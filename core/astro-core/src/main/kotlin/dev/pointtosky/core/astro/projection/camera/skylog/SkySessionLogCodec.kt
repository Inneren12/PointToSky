package dev.pointtosky.core.astro.projection.camera.skylog

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarClassification
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SKY-1: the JSONL codec for a sky session log. One JSON object per line, no pretty-printing —
 * `prettyPrint` would break the one-line-per-record invariant the whole format rests on.
 *
 * Hand-built [JsonObject]s rather than `@Serializable` codegen, matching
 * `dev.pointtosky.core.logging.LogEvent.toJsonLine` and `CamDiagnosticSnapshotJson` (this codebase's
 * two existing JSON emitters). The reason is the same one those have: the model types carry
 * `init`-block invariants and private constructors that a generated serializer would have to be
 * taught to respect, and the wire names must be able to differ from the Kotlin field names without a
 * `@SerialName` on every property.
 *
 * `explicitNulls = false` on write: an absent optional field is simply absent, keeping lines short
 * across thousands of frames. Readers must therefore treat "missing" and "null" identically, which
 * every accessor below does.
 */
val skySessionLogJson: Json =
    Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

private const val KIND_SESSION = "session"
private const val KIND_FRAME = "frame"
private const val KEY_KIND = "kind"

/**
 * One decoded line of a sky session log. [Unreadable] is a first-class outcome, not an exception: a
 * capture interrupted mid-write (battery, crash, the user walking away) leaves a truncated final
 * line, and losing that one frame must never cost the thousands before it.
 */
sealed interface SkySessionLogLine {
    data class Header(
        val header: SkySessionLogHeader,
    ) : SkySessionLogLine

    data class Frame(
        val record: SkyFrameRecord,
    ) : SkySessionLogLine

    /**
     * @property reason a short, non-sensitive category plus the failing detail. Never the raw line
     *   content — a log line carries a location fix, and an error string is the one part of a parse
     *   result most likely to be pasted into a bug report.
     */
    data class Unreadable(
        val lineNumber: Int,
        val reason: String,
    ) : SkySessionLogLine

    /**
     * A syntactically valid session header naming a [schemaVersion] outside
     * [SUPPORTED_SKY_SESSION_LOG_SCHEMA_VERSIONS].
     *
     * Distinct from [Unreadable] on purpose: the line parsed fine, and the file is very likely a real
     * session log — this build simply must not interpret it. Reporting it as merely "unreadable" would
     * invite the natural next step of parsing it anyway, which is exactly what a version number
     * exists to prevent.
     *
     * @property schemaVersion the version the header claimed, `null` when the field was absent or not
     *   an integer at all.
     */
    data class UnsupportedSchema(
        val lineNumber: Int,
        val schemaVersion: Int?,
    ) : SkySessionLogLine
}

/**
 * A whole parsed log.
 *
 * [header] is nullable because a log truncated before its first flush, or one whose header line
 * failed to parse or named an unsupported schema, still yields whatever frames survived.
 *
 * ## Why [orphanFrames] is separate from [records]
 * A frame line that appears **before** any valid header does not belong to [header] and must never be
 * replayed as though it did. It was written under intrinsics, a camera id, and pairing tolerances
 * this document has no record of; attributing a later header's calibration to it would produce
 * plausible, wrong numbers with nothing to flag them. So such frames are kept — they still carry real
 * pixels and a real pose — but in their own list, and [replaySkySessionLog] never touches them.
 *
 * [records] therefore contains exactly the frames that followed the accepted header, in file order.
 */
data class SkySessionLogDocument(
    val header: SkySessionLogHeader?,
    val records: List<SkyFrameRecord>,
    val unreadable: List<SkySessionLogLine.Unreadable>,
    val orphanFrames: List<SkyFrameRecord> = emptyList(),
    val unsupportedSchema: List<SkySessionLogLine.UnsupportedSchema> = emptyList(),
)

// ---------------------------------------------------------------------------------------------
// Encoding
// ---------------------------------------------------------------------------------------------

/** The header line: always the first line of the file, written exactly once per session. */
fun encodeSkySessionHeaderLine(header: SkySessionLogHeader): String {
    val element =
        buildJsonObject {
            put(KEY_KIND, KIND_SESSION)
            put("schemaVersion", header.schemaVersion)
            put("sessionId", header.sessionId)
            put("startedAtEpochMillis", header.startedAtEpochMillis)
            header.deviceModel?.let { put("deviceModel", it) }
            header.cameraId?.let { put("cameraId", it) }
            if (header.physicalCameraIds.isNotEmpty()) {
                put("physicalCameraIds", buildJsonArray { header.physicalCameraIds.forEach { add(it) } })
            }
            put("bufferWidthPx", header.bufferWidthPx)
            put("bufferHeightPx", header.bufferHeightPx)
            put("lumaFormat", header.lumaFormat.name)
            put("maxPairDeltaNanos", header.maxPairDeltaNanos)
            put("clockMismatchThresholdNanos", header.clockMismatchThresholdNanos)
            put("clockAlignment", encodeClockAlignment(header.clockAlignment))
            put("intrinsics", encodeIntrinsics(header.intrinsics))
            header.calibration?.let { put("calibration", encodeCalibration(it)) }
            header.notes?.let { put("notes", it) }
        }
    return skySessionLogJson.encodeToString(JsonObject.serializer(), element)
}

/** One frame line. Self-contained: pixels reference, pose, observer, exposure, predictions. */
fun encodeSkyFrameLine(record: SkyFrameRecord): String {
    val element =
        buildJsonObject {
            put(KEY_KIND, KIND_FRAME)
            put("seq", record.sequence)
            put("capturedAtEpochMillis", record.capturedAtEpochMillis)
            put("frame", encodeFrameMetadata(record.frame))
            put("viewportWidthPx", record.viewportWidthPx)
            put("viewportHeightPx", record.viewportHeightPx)
            put("luma", encodeLuma(record.luma))
            put("pose", encodePose(record.pose))
            record.observer?.let { put("observer", encodeObserver(it)) }
            record.exposure?.let { put("exposure", encodeExposure(it)) }
            put(
                "predictedStars",
                buildJsonArray { record.predictedStars.forEach { add(encodePredictedStar(it)) } },
            )
        }
    return skySessionLogJson.encodeToString(JsonObject.serializer(), element)
}

private fun encodeClockAlignment(alignment: SkyClockAlignment): JsonObject =
    buildJsonObject {
        put("frameClock", alignment.frameClock.name)
        put("poseClock", alignment.poseClock.name)
        // Required since schema v2. Without it a reader cannot tell a proven-comparable session from a
        // session that merely assumed one, which is the whole reason the field exists.
        put("relationship", alignment.relationship.name)
        alignment.poseToFrameOffsetNanos?.let { put("poseToFrameOffsetNanos", it) }
    }

private fun encodeIntrinsics(intrinsics: SkyIntrinsicsRecord): JsonObject =
    buildJsonObject {
        put("horizontalFovDeg", intrinsics.horizontalFovDeg)
        put("verticalFovDeg", intrinsics.verticalFovDeg)
        put("source", intrinsics.source.name)
        put("referenceKind", intrinsics.referenceKind.name)
        intrinsics.referenceWidthPx?.let { put("referenceWidthPx", it) }
        intrinsics.referenceHeightPx?.let { put("referenceHeightPx", it) }
        intrinsics.quality?.let { put("quality", it.name) }
        intrinsics.focalLengthMm?.let { put("focalLengthMm", it) }
        intrinsics.sensorWidthMm?.let { put("sensorWidthMm", it) }
        intrinsics.sensorHeightMm?.let { put("sensorHeightMm", it) }
        intrinsics.principalPointXPx?.let { put("principalPointXPx", it) }
        intrinsics.principalPointYPx?.let { put("principalPointYPx", it) }
        put("axisSwapped", intrinsics.axisSwapped)
        put("negateXInput", intrinsics.negateXInput)
        put("negateYInput", intrinsics.negateYInput)
        intrinsics.legacyFallbackReason?.let { put("legacyFallbackReason", it) }
        // Derived on write, ignored on parse - see SkyPinholeRecord's KDoc.
        intrinsics.pinhole?.let { pinhole ->
            put(
                "pinhole",
                buildJsonObject {
                    put("fxPx", pinhole.fxPx)
                    put("fyPx", pinhole.fyPx)
                    put("cxPx", pinhole.cxPx)
                    put("cyPx", pinhole.cyPx)
                },
            )
        }
    }

private fun encodeCalibration(calibration: SkyCalibrationRecord): JsonObject =
    buildJsonObject {
        put("activeArrayWidthPx", calibration.activeArrayWidthPx)
        put("activeArrayHeightPx", calibration.activeArrayHeightPx)
        put("activeArrayLeftPx", calibration.activeArrayLeftPx)
        put("activeArrayTopPx", calibration.activeArrayTopPx)
        put("activeArrayRightPx", calibration.activeArrayRightPx)
        put("activeArrayBottomPx", calibration.activeArrayBottomPx)
        put("sensorWidthMm", calibration.sensorWidthMm)
        put("sensorHeightMm", calibration.sensorHeightMm)
        put("focalLengthMm", calibration.focalLengthMm)
        put("activeFxPx", calibration.activeFxPx)
        put("activeFyPx", calibration.activeFyPx)
        put("activeCxPx", calibration.activeCxPx)
        put("activeCyPx", calibration.activeCyPx)
        put("bufferFxPx", calibration.bufferFxPx)
        put("bufferFyPx", calibration.bufferFyPx)
        put("bufferCxPx", calibration.bufferCxPx)
        put("bufferCyPx", calibration.bufferCyPx)
        put("quality", calibration.quality)
        put("sensorToBufferMappingSource", calibration.sensorToBufferMappingSource)
        put("transformClass", calibration.transformClass)
    }

private fun encodeFrameMetadata(frame: CameraFrameMetadata): JsonObject =
    buildJsonObject {
        put("timestampNanos", frame.timestampNanos)
        put("bufferWidthPx", frame.bufferWidthPx)
        put("bufferHeightPx", frame.bufferHeightPx)
        put("rotationDegrees", frame.rotationDegrees)
        if (frame.cropRectLeftPx != null) {
            put(
                "cropRect",
                buildJsonObject {
                    put("leftPx", frame.cropRectLeftPx)
                    put("topPx", frame.cropRectTopPx)
                    put("rightPx", frame.cropRectRightPx)
                    put("bottomPx", frame.cropRectBottomPx)
                },
            )
        }
        frame.sensorToBufferTransform?.let { matrix ->
            put(
                "sensorToBufferTransform",
                buildJsonArray {
                    listOf(
                        matrix.m00,
                        matrix.m01,
                        matrix.m02,
                        matrix.m10,
                        matrix.m11,
                        matrix.m12,
                        matrix.m20,
                        matrix.m21,
                        matrix.m22,
                    ).forEach { add(it) }
                },
            )
        }
    }

private fun encodeLuma(luma: SkyLumaReference): JsonObject =
    buildJsonObject {
        put("path", luma.path)
        put("format", luma.format.name)
        put("widthPx", luma.widthPx)
        put("heightPx", luma.heightPx)
        put("rowStridePx", luma.rowStridePx)
        put("byteLength", luma.byteLength)
    }

private fun encodePose(pose: SkyPoseSample): JsonObject =
    buildJsonObject {
        put("timestampNanos", pose.timestampNanos)
        put("frameToPoseRawDeltaNanos", pose.frameToPoseRawDeltaNanos)
        put("rotationMatrix", buildJsonArray { pose.rotationMatrix.forEach { add(it) } })
        // Derived on write, ignored on parse - see SkyPoseSample.quaternion's KDoc.
        val quaternion = pose.quaternion
        put(
            "quaternion",
            buildJsonObject {
                put("x", quaternion.x)
                put("y", quaternion.y)
                put("z", quaternion.z)
                put("w", quaternion.w)
            },
        )
    }

private fun encodeObserver(observer: SkyObserverContext): JsonObject =
    buildJsonObject {
        put("latitudeDeg", observer.latitudeDeg)
        put("longitudeDeg", observer.longitudeDeg)
        put("utcEpochMillis", observer.utcEpochMillis)
        observer.horizontalAccuracyM?.let { put("horizontalAccuracyM", it) }
        observer.magneticDeclinationDeg?.let { put("magneticDeclinationDeg", it) }
    }

private fun encodeExposure(exposure: SkyExposureSample): JsonObject =
    buildJsonObject {
        exposure.exposureTimeNanos?.let { put("exposureTimeNanos", it) }
        exposure.sensitivityIso?.let { put("sensitivityIso", it) }
        exposure.frameDurationNanos?.let { put("frameDurationNanos", it) }
        exposure.aeMode?.let { put("aeMode", it) }
        exposure.awbMode?.let { put("awbMode", it) }
        exposure.sensorTimestampNanos?.let { put("sensorTimestampNanos", it) }
    }

private fun encodePredictedStar(star: SkyPredictedStar): JsonObject =
    buildJsonObject {
        put("id", star.catalogIndex)
        star.magnitude?.let { put("mag", it) }
        put("raRad", star.rightAscensionRad)
        put("decRad", star.declinationRad)
        put("classification", star.classification.name)
        star.imageXPx?.let { put("xPx", it) }
        star.imageYPx?.let { put("yPx", it) }
        star.displayXPx?.let { put("displayXPx", it) }
        star.displayYPx?.let { put("displayYPx", it) }
    }

// ---------------------------------------------------------------------------------------------
// Decoding
// ---------------------------------------------------------------------------------------------

/**
 * Parses one line. A blank line is [SkySessionLogLine.Unreadable] rather than silently skipped —
 * a blank line in the middle of a JSONL stream means something went wrong during the write, and
 * hiding it would hide that.
 *
 * Never throws: malformed JSON, a missing required field, and an invariant a model type's `init`
 * rejects all become [SkySessionLogLine.Unreadable]. Parsing a log the device wrote is an expected
 * runtime activity, not a programmer contract, and one bad line must not abort the read.
 */
fun parseSkySessionLogLine(
    line: String,
    lineNumber: Int,
): SkySessionLogLine {
    if (line.isBlank()) return SkySessionLogLine.Unreadable(lineNumber, "blank line")
    val element =
        try {
            skySessionLogJson.parseToJsonElement(line)
        } catch (e: IllegalArgumentException) {
            return SkySessionLogLine.Unreadable(lineNumber, "malformed JSON: ${e.javaClass.simpleName}")
        }
    val obj = element as? JsonObject ?: return SkySessionLogLine.Unreadable(lineNumber, "line is not a JSON object")
    return when (val kind = obj.string(KEY_KIND)) {
        KIND_SESSION -> parseHeaderLine(obj, lineNumber)

        KIND_FRAME ->
            runCatchingLine(lineNumber) { SkySessionLogLine.Frame(decodeFrame(obj)) }

        null -> SkySessionLogLine.Unreadable(lineNumber, "missing \"$KEY_KIND\"")
        else -> SkySessionLogLine.Unreadable(lineNumber, "unknown \"$KEY_KIND\": $kind")
    }
}

/**
 * Reads the `schemaVersion` gate before decoding anything else.
 *
 * The version is **required and must be an integer**. A missing, non-integer, zero, or negative
 * version is [SkySessionLogLine.UnsupportedSchema] with `schemaVersion = null`/the offending value —
 * never coerced to [SKY_SESSION_LOG_SCHEMA_VERSION]. Defaulting would let a header written before the
 * field existed, or by a future build that reinterpreted a field this one still recognizes by name,
 * decode into numbers that look right and are not.
 */
private fun parseHeaderLine(
    obj: JsonObject,
    lineNumber: Int,
): SkySessionLogLine {
    val declared = obj.int("schemaVersion")
    if (declared == null || declared !in SUPPORTED_SKY_SESSION_LOG_SCHEMA_VERSIONS) {
        return SkySessionLogLine.UnsupportedSchema(lineNumber, declared)
    }
    return runCatchingLine(lineNumber) { SkySessionLogLine.Header(decodeHeader(obj)) }
}

/**
 * Parses a whole log.
 *
 * The **first** supported header line wins: a session log has exactly one, and a second means two
 * sessions were concatenated — recorded as unreadable rather than silently replacing the intrinsics
 * every earlier frame was captured under.
 *
 * Frames read **before** that header land in [SkySessionLogDocument.orphanFrames], never in
 * [SkySessionLogDocument.records]; see that type's KDoc for why attributing them to a later header
 * would be worse than dropping them.
 */
fun parseSkySessionLog(lines: Sequence<String>): SkySessionLogDocument {
    var header: SkySessionLogHeader? = null
    val records = mutableListOf<SkyFrameRecord>()
    val orphanFrames = mutableListOf<SkyFrameRecord>()
    val unreadable = mutableListOf<SkySessionLogLine.Unreadable>()
    val unsupportedSchema = mutableListOf<SkySessionLogLine.UnsupportedSchema>()
    lines.forEachIndexed { index, line ->
        val lineNumber = index + 1
        when (val parsed = parseSkySessionLogLine(line, lineNumber)) {
            is SkySessionLogLine.Header ->
                if (header == null) {
                    header = parsed.header
                } else {
                    unreadable += SkySessionLogLine.Unreadable(lineNumber, "duplicate session header")
                }

            is SkySessionLogLine.Frame ->
                if (header == null) orphanFrames += parsed.record else records += parsed.record

            is SkySessionLogLine.Unreadable -> unreadable += parsed
            is SkySessionLogLine.UnsupportedSchema -> unsupportedSchema += parsed
        }
    }
    return SkySessionLogDocument(
        header = header,
        records = records,
        unreadable = unreadable,
        orphanFrames = orphanFrames,
        unsupportedSchema = unsupportedSchema,
    )
}

/**
 * Convenience overload for a whole log held in memory. Exactly one trailing line terminator is
 * stripped first, so a well-formed file ending in a newline does not report a phantom blank final
 * line; any *other* blank line is still reported, because a gap mid-stream is real evidence about the
 * write that produced it.
 */
fun parseSkySessionLog(text: String): SkySessionLogDocument {
    if (text.isEmpty()) {
        return SkySessionLogDocument(header = null, records = emptyList(), unreadable = emptyList())
    }
    return parseSkySessionLog(text.removeSuffix("\n").removeSuffix("\r").lineSequence())
}

private inline fun runCatchingLine(
    lineNumber: Int,
    block: () -> SkySessionLogLine,
): SkySessionLogLine =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        // Covers both SkySessionLogDecode.kt's own require()s and every model type's init block.
        SkySessionLogLine.Unreadable(lineNumber, e.message ?: e.javaClass.simpleName)
    } catch (e: NoSuchElementException) {
        SkySessionLogLine.Unreadable(lineNumber, e.message ?: e.javaClass.simpleName)
    } catch (e: IllegalStateException) {
        // A `check()` anywhere in the decoded model tree. Same reasoning as IllegalArgumentException:
        // reading a file the device wrote is an expected runtime activity, so nothing here may escape
        // as an exception a consumer has to catch.
        SkySessionLogLine.Unreadable(lineNumber, e.message ?: e.javaClass.simpleName)
    } catch (e: NumberFormatException) {
        SkySessionLogLine.Unreadable(lineNumber, "malformed number: ${e.javaClass.simpleName}")
    } catch (e: IndexOutOfBoundsException) {
        SkySessionLogLine.Unreadable(lineNumber, "truncated array: ${e.javaClass.simpleName}")
    }
