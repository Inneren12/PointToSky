package dev.pointtosky.core.astro.projection.camera.skylog

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.TimedRotationSample
import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarClassification
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * SKY-1: the pure, Android-independent data model of a **sky-track session log** — a per-line JSONL
 * stream where one line is one analyzed camera frame, self-contained enough for an offline star
 * detector to be developed against it without a device.
 *
 * ## What this is not
 * This is a *capture and replay* contract, not a detector and not a diagnostics snapshot. Nothing
 * here detects stars, matches them, or corrects a pose. It is deliberately a **separate stream** from
 * both the CAM-2c `FrameContent` dot-grid track (see `FrameContentCorrespondenceSnapshot`) and the
 * app-wide `dev.pointtosky.core.logging` event log: the serialization *style* is shared with the
 * latter (hand-built `JsonObject`s, one object per line — see [encodeSkyFrameLine]), the streams
 * never are.
 *
 * ## Why one line per frame instead of one snapshot per session
 * The existing exports (`CamDiagnosticSnapshot`, `FrameContentCorrespondenceSnapshot`) capture a
 * single moment's metadata. A detector needs a *time series*: every frame's pixels, the pose that
 * frame was shot at, and what the existing predictor thought should be visible in it. A one-object
 * -per-line stream is appendable during capture, truncation-tolerant on read (a half-written final
 * line costs one frame, not the session — see [parseSkySessionLog]), and streamable offline.
 *
 * ## Two kinds of fields
 * Most fields are **authoritative**: parsed back verbatim, and the replay path ([replaySkySessionLog])
 * reconstructs its inputs from them alone. A few are **derived-on-write**: computed from the
 * authoritative fields when the line is encoded, emitted for the benefit of an offline consumer that
 * would otherwise have to re-derive them, and *ignored on parse* (see [SkyPoseSample.quaternion] and
 * [SkyIntrinsicsRecord.pinhole]). Storing a derived value as parseable state would let a hand-edited
 * or truncated log carry a quaternion that disagrees with its own rotation matrix, with no way to say
 * which one the math should believe. Derived-on-write has no such failure mode: the authoritative
 * field is always the answer.
 */
const val SKY_SESSION_LOG_SCHEMA_VERSION: Int = 1

/**
 * Every [SkySessionLogHeader.schemaVersion] this build knows how to read.
 *
 * A header carrying anything else is [SkySessionLogLine.UnsupportedSchema] — never silently coerced
 * to [SKY_SESSION_LOG_SCHEMA_VERSION]. A log written by a newer build may have reinterpreted a field
 * this build still recognizes by name, so "parse it anyway and hope" is exactly the failure mode a
 * version number exists to prevent. Widening this set is the deliberate act of teaching this build to
 * read that version.
 */
val SUPPORTED_SKY_SESSION_LOG_SCHEMA_VERSIONS: Set<Int> = setOf(1)

/**
 * How one frame's pixels are stored on disk beside the log.
 *
 * Only [RAW_Y8] exists today, and the choice is deliberate: star detection needs intensity, not
 * color, and `ImageProxy.planes[0]` already *is* an 8-bit intensity plane, so writing it verbatim is
 * both lossless and free — no encoder, no color-space decision, no dependency. Offline it is one
 * `numpy.fromfile(path, dtype=np.uint8).reshape(heightPx, rowStridePx)[:, :widthPx]` away from an
 * array. A grayscale PNG would cost an encoder on the capture side and buy nothing a detector can
 * use; full RGB would cost ~3x the bytes for chroma no star detector reads.
 */
enum class SkyLumaFormat {
    /** Packed 8-bit luma, one byte per pixel, [SkyLumaReference.rowStridePx] bytes per row. */
    RAW_Y8,
}

/**
 * Which monotonic clock a nanosecond timestamp is expressed on. Never assumed — a log that cannot
 * say is explicitly [UNKNOWN], and [SkyClockAlignment] then refuses to align rather than guessing an
 * offset of zero.
 */
enum class SkyClock {
    /** `ImageProxy.imageInfo.timestamp` / Camera2 `CaptureResult.SENSOR_TIMESTAMP`. */
    CAMERA_SENSOR_NANOS,

    /** `android.hardware.SensorEvent.timestamp`, as read in `RotationFrame.kt`. */
    SENSOR_EVENT_NANOS,

    /** The producing code could not establish which clock this timestamp came from. */
    UNKNOWN,
}

/**
 * The session-level relationship between the pose clock and the frame clock.
 *
 * On most Android devices `SensorEvent.timestamp` and `ImageProxy.imageInfo.timestamp` are both
 * `SystemClock.elapsedRealtimeNanos`-based and directly comparable, which is exactly the assumption
 * CAM-1d's `pairFrameToNearestRotation` already makes (and why it has a
 * `ClockMismatchSuspected` outcome for when the assumption fails). This type records that assumption
 * explicitly per session instead of leaving it implicit:
 *
 *  - [poseToFrameOffsetNanos] present → it is **always** applied, even when the two clock enums
 *    agree, so a session that measured a real offset can record it.
 *  - absent and the two clocks are equal and not [SkyClock.UNKNOWN] → the offset is `0` because the
 *    timestamps are on the same clock, not because zero was assumed.
 *  - absent and the clocks differ (or either is [SkyClock.UNKNOWN]) → **no alignment is possible**;
 *    [alignPoseTimestampToFrameClock] returns `null` and the replay skips the frame rather than
 *    comparing two incomparable numbers.
 *
 * @property poseToFrameOffsetNanos nanoseconds to **add** to a pose timestamp to express it on the
 *   frame clock.
 */
data class SkyClockAlignment(
    val frameClock: SkyClock,
    val poseClock: SkyClock,
    val poseToFrameOffsetNanos: Long? = null,
)

/**
 * [poseTimestampNanos] expressed on [alignment]'s frame clock, or `null` when the two clocks cannot
 * be related (see [SkyClockAlignment]). Saturating rather than wrapping on overflow, matching
 * `overflowSafeDeltaNanos`' own convention.
 */
fun alignPoseTimestampToFrameClock(
    poseTimestampNanos: Long,
    alignment: SkyClockAlignment,
): Long? {
    val offset =
        alignment.poseToFrameOffsetNanos
            ?: when {
                alignment.frameClock == SkyClock.UNKNOWN || alignment.poseClock == SkyClock.UNKNOWN -> return null
                alignment.frameClock == alignment.poseClock -> 0L
                else -> return null
            }
    val sum = poseTimestampNanos + offset
    // Overflow iff both operands share a sign that the result does not.
    val overflowed = ((poseTimestampNanos xor sum) and (offset xor sum)) < 0L
    return when {
        !overflowed -> sum
        offset > 0L -> Long.MAX_VALUE
        else -> Long.MIN_VALUE
    }
}

/**
 * A unit quaternion, scalar-last in field order but named explicitly so no caller has to guess the
 * convention: `(x, y, z)` is the vector part, [w] the scalar part. Always **derived on write** from
 * [SkyPoseSample.rotationMatrix] (see [SkySessionLog]'s "two kinds of fields"), never parsed back.
 */
data class SkyQuaternion(
    val x: Double,
    val y: Double,
    val z: Double,
    val w: Double,
)

/**
 * The unit quaternion equivalent to the row-major 3x3 rotation matrix [m] (9 elements). Shepperd's
 * method: pick the largest of the four possible divisors so the square root is never taken of a
 * near-zero number. Sign is canonicalized to `w >= 0`, so the same rotation always encodes the same
 * way (`q` and `-q` are the same rotation).
 */
internal fun quaternionFromRotationMatrix(m: List<Double>): SkyQuaternion {
    requireUsableRotationMatrix(m)
    val trace = m[0] + m[4] + m[8]
    val q =
        when {
            trace > 0.0 -> {
                val s = sqrt(trace + 1.0) * 2.0
                SkyQuaternion(x = (m[7] - m[5]) / s, y = (m[2] - m[6]) / s, z = (m[3] - m[1]) / s, w = 0.25 * s)
            }

            m[0] > m[4] && m[0] > m[8] -> {
                val s = sqrt((1.0 + m[0] - m[4] - m[8]).coerceAtLeast(0.0)) * 2.0
                SkyQuaternion(x = 0.25 * s, y = (m[1] + m[3]) / s, z = (m[2] + m[6]) / s, w = (m[7] - m[5]) / s)
            }

            m[4] > m[8] -> {
                val s = sqrt((1.0 + m[4] - m[0] - m[8]).coerceAtLeast(0.0)) * 2.0
                SkyQuaternion(x = (m[1] + m[3]) / s, y = 0.25 * s, z = (m[5] + m[7]) / s, w = (m[2] - m[6]) / s)
            }

            else -> {
                val s = sqrt((1.0 + m[8] - m[0] - m[4]).coerceAtLeast(0.0)) * 2.0
                SkyQuaternion(x = (m[2] + m[6]) / s, y = (m[5] + m[7]) / s, z = 0.25 * s, w = (m[3] - m[1]) / s)
            }
        }
    // Defence in depth. requireUsableRotationMatrix above already rules out every input that could
    // divide by zero or take a root of a negative, and the coerceAtLeast guards make the roots total -
    // but a NaN reaching a log line silently poisons every offline consumer downstream, so the output
    // is checked rather than assumed.
    require(q.x.isFinite() && q.y.isFinite() && q.z.isFinite() && q.w.isFinite()) {
        "quaternion derivation produced a non-finite value from $m"
    }
    return if (q.w < 0.0) SkyQuaternion(-q.x, -q.y, -q.z, -q.w) else q
}

internal const val ROTATION_MATRIX_SIZE: Int = 9

/**
 * How far a stored matrix may drift from exact orthonormality before it is refused.
 *
 * `1e-3` is deliberately generous. The real values come from `SensorManager.getRotationMatrixFromVector`
 * followed by `remapCoordinateSystem`, both computed in `Float`, so ~1e-6 of accumulated error is
 * normal and must not be rejected. The purpose of this check is not to police float noise: it is to
 * refuse a matrix that cannot represent a rotation at all — an all-zero array, a scaled or sheared
 * matrix, a reflection (determinant near -1), a partially-overwritten buffer — before
 * [quaternionFromRotationMatrix] turns it into a plausible-looking quaternion and a replay projects
 * stars through it.
 */
internal const val ROTATION_MATRIX_ORTHONORMAL_TOLERANCE: Double = 1e-3

/**
 * Rejects [m] unless it can represent a usable rotation: nine finite elements, rows and columns of
 * unit length, mutually orthogonal rows, and a determinant near `+1` (not `-1`, which is a
 * reflection and would silently mirror every projected star).
 *
 * @throws IllegalArgumentException with a specific reason. [parseSkySessionLogLine] converts it into
 *   a [SkySessionLogLine.Unreadable], so a malformed line is reported rather than thrown.
 */
internal fun requireUsableRotationMatrix(m: List<Double>) {
    require(m.size == ROTATION_MATRIX_SIZE) {
        "rotationMatrix must have $ROTATION_MATRIX_SIZE elements; was ${m.size}"
    }
    require(m.all { it.isFinite() }) { "rotationMatrix elements must all be finite; was $m" }

    val tolerance = ROTATION_MATRIX_ORTHONORMAL_TOLERANCE
    val rows = listOf(0, 3, 6).map { listOf(m[it], m[it + 1], m[it + 2]) }
    val columns = listOf(0, 1, 2).map { listOf(m[it], m[it + 3], m[it + 6]) }

    (rows + columns).forEachIndexed { index, vector ->
        val norm = sqrt(vector.sumOf { it * it })
        require(abs(norm - 1.0) <= tolerance) {
            "rotationMatrix ${if (index < 3) "row" else "column"} ${index % 3} must be unit length " +
                "within $tolerance; was $norm"
        }
    }
    for (a in 0 until 2) {
        for (b in a + 1 until 3) {
            val dot = (0 until 3).sumOf { rows[a][it] * rows[b][it] }
            require(abs(dot) <= tolerance) {
                "rotationMatrix rows $a and $b must be orthogonal within $tolerance; dot was $dot"
            }
        }
    }

    val determinant =
        m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])
    require(abs(determinant - 1.0) <= tolerance) {
        "rotationMatrix determinant must be +1 within $tolerance (a reflection or scaling is not a " +
            "usable rotation); was $determinant"
    }
}

/**
 * One device-pose sample, paired to one frame.
 *
 * [rotationMatrix] is the **authoritative** field and is exactly what the math consumes: the
 * display-remapped, row-major device-to-world matrix `RotationFrame.kt` publishes (and therefore the
 * matrix `projectStars` reads through `CameraSessionGeometry.pairedRotation`). It is a `List<Double>`
 * rather than a `FloatArray` on purpose — this is a value type that must compare by content, which
 * `FloatArray` does not.
 *
 * It is **not** the raw `TYPE_ROTATION_VECTOR` quaternion: `RotationFrame.kt` applies
 * `SensorManager.remapCoordinateSystem` for the current display rotation before publishing, and the
 * whole point of the log is that a replay reproduces what the device actually computed. [quaternion]
 * is the same rotation in quaternion form, derived on write for offline convenience.
 *
 * @property timestampNanos on [SkySessionLogHeader.clockAlignment]'s `poseClock`, raw and unaligned —
 *   the alignment is applied at read time so a log never bakes in an offset a later analysis
 *   disagrees with.
 * @property frameToPoseDeltaNanos `frame.timestampNanos - pose.timestampNanos` as the capturing
 *   device computed it, in raw clock units. Recorded rather than re-derived so a log can be audited
 *   for pairing drift even if the alignment is later corrected.
 */
data class SkyPoseSample(
    val timestampNanos: Long,
    val rotationMatrix: List<Double>,
    val frameToPoseDeltaNanos: Long,
) {
    init {
        require(timestampNanos >= 0L) { "timestampNanos must be non-negative; was $timestampNanos" }
        // Validated here, at construction, rather than at quaternion-derivation or replay time: a pose
        // that cannot represent a rotation must never reach a log line or a projection in the first
        // place. See requireUsableRotationMatrix for what "usable" means and why the tolerance is what
        // it is.
        requireUsableRotationMatrix(rotationMatrix)
    }

    /** Derived on write, ignored on parse. See [SkySessionLog]'s "two kinds of fields". */
    val quaternion: SkyQuaternion get() = quaternionFromRotationMatrix(rotationMatrix)

    /** This pose as the `TimedRotationSample` the CAM-1d/CAM-1f math consumes, on the frame clock. */
    fun toTimedRotationSample(alignedTimestampNanos: Long): TimedRotationSample =
        TimedRotationSample(
            timestampNanos = alignedTimestampNanos,
            rotationMatrix = FloatArray(ROTATION_MATRIX_SIZE) { rotationMatrix[it].toFloat() },
        )
}

/**
 * Where and when the frame was shot, in the exact terms `StarProjectionContext` needs.
 *
 * Degrees rather than radians because a log is read by humans and by tools that speak degrees;
 * [toStarProjectionContext] does the conversion once, at replay time.
 *
 * [magneticDeclinationDeg] is nullable and a `null` is **never** silently treated as `0.0` — see
 * `StarProjectionContext`'s own KDoc for why "treat magnetic north as true north" and "declination is
 * known to be zero" must not be conflated. A frame whose declination was unavailable replays as
 * [SkyFrameReplaySkipReason.MAGNETIC_DECLINATION_UNAVAILABLE], not as an uncorrected projection
 * masquerading as a corrected one.
 */
data class SkyObserverContext(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val utcEpochMillis: Long,
    val horizontalAccuracyM: Double? = null,
    val magneticDeclinationDeg: Double? = null,
) {
    init {
        require(latitudeDeg.isFinite()) { "latitudeDeg must be finite; was $latitudeDeg" }
        require(longitudeDeg.isFinite()) { "longitudeDeg must be finite; was $longitudeDeg" }
        require(latitudeDeg in -90.0..90.0) { "latitudeDeg must be in [-90, 90]; was $latitudeDeg" }
        require(horizontalAccuracyM == null || (horizontalAccuracyM.isFinite() && horizontalAccuracyM >= 0.0)) {
            "horizontalAccuracyM must be finite and non-negative when present; was $horizontalAccuracyM"
        }
        require(magneticDeclinationDeg == null || magneticDeclinationDeg.isFinite()) {
            "magneticDeclinationDeg must be finite when present; was $magneticDeclinationDeg"
        }
    }
}

/**
 * Camera2 exposure state for one frame, read from `CaptureResult` (see
 * `dev.pointtosky.mobile.ar.camera.SkyExposureSampleStore` in `:mobile`). Every field is nullable
 * because every one of them is an optional `CaptureResult` key the device may not report; a `null`
 * means "the device did not tell us", never "zero".
 *
 * [sensorTimestampNanos] is `CaptureResult.SENSOR_TIMESTAMP`, the same value as
 * `ImageProxy.imageInfo.timestamp` for the same frame. It is what makes this sample *provably* the
 * one belonging to [SkyFrameRecord.frame] rather than merely the most recent one seen.
 */
data class SkyExposureSample(
    val exposureTimeNanos: Long? = null,
    val sensitivityIso: Int? = null,
    val frameDurationNanos: Long? = null,
    val aeMode: String? = null,
    val awbMode: String? = null,
    val sensorTimestampNanos: Long? = null,
)

/**
 * One predicted star for one frame: what `projectStars` said should be at which pixel, which is
 * exactly what a future detector compares its own findings against.
 *
 * [rightAscensionRad]/[declinationRad] are the projector's *input*, carried alongside its output on
 * purpose: without them the log records an answer with no question, and the replay could not re-run
 * the projection without also shipping the star catalog. With them, one JSONL line is a complete,
 * self-contained projection test case.
 *
 * [imageXPx]/[imageYPx] are full-analysis-buffer pixels (`PredictedStarProjection.imagePoint`) — the
 * same space [SkyLumaReference] stores, so a detection found in the luma file is directly comparable.
 * [displayXPx]/[displayYPx] are the viewport-space equivalents. All four are `null` for a star behind
 * the camera, which is a normal outcome, not an error.
 */
data class SkyPredictedStar(
    val catalogIndex: Int,
    val rightAscensionRad: Double,
    val declinationRad: Double,
    val magnitude: Double? = null,
    val classification: PredictedStarClassification,
    val imageXPx: Double? = null,
    val imageYPx: Double? = null,
    val displayXPx: Double? = null,
    val displayYPx: Double? = null,
) {
    init {
        require(catalogIndex >= 0) { "catalogIndex must be non-negative; was $catalogIndex" }
        require(rightAscensionRad.isFinite()) { "rightAscensionRad must be finite; was $rightAscensionRad" }
        require(declinationRad.isFinite()) { "declinationRad must be finite; was $declinationRad" }
        require(magnitude == null || magnitude.isFinite()) { "magnitude must be finite when present; was $magnitude" }
        // A NaN pixel coordinate would compare unequal to itself and silently poison every residual a
        // detector computes against it, so absence and non-finiteness are kept distinct: absent is a
        // star behind the camera, non-finite is a malformed line.
        requireFiniteIfPresent(imageXPx, "imageXPx")
        requireFiniteIfPresent(imageYPx, "imageYPx")
        requireFiniteIfPresent(displayXPx, "displayXPx")
        requireFiniteIfPresent(displayYPx, "displayYPx")
    }
}

/** @throws IllegalArgumentException when [value] is present and not finite. */
internal fun requireFiniteIfPresent(
    value: Double?,
    name: String,
) {
    require(value == null || value.isFinite()) { "$name must be finite when present; was $value" }
}

/**
 * Where this frame's pixels live and how to read them.
 *
 * [path] is **relative to the directory holding the log file**, never absolute: a session directory
 * must stay readable after being copied off the device, and an absolute `/data/user/0/...` path also
 * leaks the app's private storage layout into a file meant to be shared.
 *
 * [byteLength] is checked against the geometry rather than trusted: a stored buffer must be at least
 * `rowStridePx * (heightPx - 1) + widthPx` bytes or the reference describes a file that cannot hold
 * the image it claims — the same bound `LumaBuffer` enforces in `:mobile`.
 */
data class SkyLumaReference(
    val path: String,
    val format: SkyLumaFormat,
    val widthPx: Int,
    val heightPx: Int,
    val rowStridePx: Int,
    val byteLength: Long,
) {
    init {
        require(path.isNotBlank()) { "path must not be blank" }
        require(!path.startsWith("/")) { "path must be relative to the log directory; was $path" }
        require(widthPx > 0) { "widthPx must be positive; was $widthPx" }
        require(heightPx > 0) { "heightPx must be positive; was $heightPx" }
        require(rowStridePx >= widthPx) { "rowStridePx ($rowStridePx) must be >= widthPx ($widthPx)" }
        require(byteLength >= minimumByteLength) {
            "byteLength ($byteLength) is too small for ${widthPx}x$heightPx stride=$rowStridePx " +
                "(needs at least $minimumByteLength)"
        }
    }

    /** The smallest file that can hold this geometry: full rows for all but the last, then one row of pixels. */
    val minimumByteLength: Long
        get() = rowStridePx.toLong() * (heightPx - 1L) + widthPx.toLong()
}

/**
 * The pixel-space pinhole coefficients implied by a session's intrinsics and buffer size. **Derived
 * on write, ignored on parse** — the authoritative values are [SkyIntrinsicsRecord]'s FOV degrees,
 * from which `PinholeProjectionModel.forGeometry` derives exactly these numbers at replay time. They
 * are emitted because an offline detector that only wants to un-project a pixel should not have to
 * reimplement that derivation.
 */
data class SkyPinholeRecord(
    val fxPx: Double,
    val fyPx: Double,
    val cxPx: Double,
    val cyPx: Double,
) {
    init {
        require(fxPx.isFinite() && fyPx.isFinite() && cxPx.isFinite() && cyPx.isFinite()) {
            "pinhole coefficients must all be finite; was fx=$fxPx fy=$fyPx cx=$cxPx cy=$cyPx"
        }
    }
}

/**
 * The session's resolved-or-fallback intrinsics, flattened to plain values and rich enough to
 * reconstruct a `CameraIntrinsicsResolution` exactly (see [toCameraIntrinsicsResolution]).
 *
 * [referenceWidthPx]/[referenceHeightPx] carry `CameraIntrinsicsReference.AnalysisBuffer`'s recorded
 * dimensions. They matter more than they look: `projectStars` rejects a whole batch when they do not
 * *exactly* match the frame's buffer, and reproducing that rejection offline is the point of
 * recording them rather than re-deriving them from the frame.
 */
data class SkyIntrinsicsRecord(
    val horizontalFovDeg: Double,
    val verticalFovDeg: Double,
    val source: CameraIntrinsicsSource,
    val referenceKind: SkyIntrinsicsReferenceKind,
    val referenceWidthPx: Int? = null,
    val referenceHeightPx: Int? = null,
    val quality: CameraIntrinsicsQuality? = null,
    val focalLengthMm: Double? = null,
    val sensorWidthMm: Double? = null,
    val sensorHeightMm: Double? = null,
    val principalPointXPx: Double? = null,
    val principalPointYPx: Double? = null,
    val axisSwapped: Boolean = false,
    val negateXInput: Boolean = false,
    val negateYInput: Boolean = false,
    val legacyFallbackReason: String? = null,
    val pinhole: SkyPinholeRecord? = null,
) {
    init {
        // The FOV pair drives every pixel focal length downstream; a non-finite one would produce an
        // fx/fy of NaN and put every projected star at a NaN pixel without any single field looking
        // wrong on its own.
        require(horizontalFovDeg.isFinite()) { "horizontalFovDeg must be finite; was $horizontalFovDeg" }
        require(verticalFovDeg.isFinite()) { "verticalFovDeg must be finite; was $verticalFovDeg" }
        requireFiniteIfPresent(focalLengthMm, "focalLengthMm")
        requireFiniteIfPresent(sensorWidthMm, "sensorWidthMm")
        requireFiniteIfPresent(sensorHeightMm, "sensorHeightMm")
        requireFiniteIfPresent(principalPointXPx, "principalPointXPx")
        requireFiniteIfPresent(principalPointYPx, "principalPointYPx")
        require((source == CameraIntrinsicsSource.LEGACY_FALLBACK) == (legacyFallbackReason != null)) {
            "legacyFallbackReason must be present exactly when source is LEGACY_FALLBACK; was source=$source, " +
                "reason=$legacyFallbackReason"
        }
        if (referenceKind == SkyIntrinsicsReferenceKind.ANALYSIS_BUFFER) {
            require(referenceWidthPx != null && referenceHeightPx != null) {
                "ANALYSIS_BUFFER reference requires referenceWidthPx/referenceHeightPx"
            }
        }
    }
}

/** [CameraIntrinsicsReference]'s three variants as a flat, log-friendly discriminator. */
enum class SkyIntrinsicsReferenceKind {
    ANALYSIS_BUFFER,
    PHYSICAL_SENSOR,
    UNSPECIFIED,
}

/** Rebuilds the exact `CameraIntrinsicsResolution` this record was flattened from. */
fun SkyIntrinsicsRecord.toCameraIntrinsicsResolution(): CameraIntrinsicsResolution {
    val reference =
        when (referenceKind) {
            SkyIntrinsicsReferenceKind.ANALYSIS_BUFFER ->
                CameraIntrinsicsReference.AnalysisBuffer(
                    widthPx = requireNotNull(referenceWidthPx),
                    heightPx = requireNotNull(referenceHeightPx),
                )

            SkyIntrinsicsReferenceKind.PHYSICAL_SENSOR -> CameraIntrinsicsReference.PhysicalSensor
            SkyIntrinsicsReferenceKind.UNSPECIFIED -> CameraIntrinsicsReference.Unspecified
        }
    val intrinsics =
        CameraIntrinsics(
            horizontalFovDeg = horizontalFovDeg,
            verticalFovDeg = verticalFovDeg,
            focalLengthMm = focalLengthMm,
            sensorWidthMm = sensorWidthMm,
            sensorHeightMm = sensorHeightMm,
            principalPointXPx = principalPointXPx,
            principalPointYPx = principalPointYPx,
            source = source,
            reference = reference,
            quality = quality,
            axisSwapped = axisSwapped,
            negateXInput = negateXInput,
            negateYInput = negateYInput,
        )
    val fallbackReason = legacyFallbackReason
    return if (fallbackReason != null) {
        CameraIntrinsicsResolution.LegacyFallback(intrinsics, fallbackReason)
    } else {
        CameraIntrinsicsResolution.Resolved(intrinsics)
    }
}

/** Flattens a `CameraIntrinsicsResolution` into its log form. [pinhole] is the caller's to supply. */
fun CameraIntrinsicsResolution.toSkyIntrinsicsRecord(pinhole: SkyPinholeRecord? = null): SkyIntrinsicsRecord {
    val intrinsics = this.intrinsics
    val reference = intrinsics.reference
    return SkyIntrinsicsRecord(
        horizontalFovDeg = intrinsics.horizontalFovDeg,
        verticalFovDeg = intrinsics.verticalFovDeg,
        source = intrinsics.source,
        referenceKind =
            when (reference) {
                is CameraIntrinsicsReference.AnalysisBuffer -> SkyIntrinsicsReferenceKind.ANALYSIS_BUFFER
                is CameraIntrinsicsReference.PhysicalSensor -> SkyIntrinsicsReferenceKind.PHYSICAL_SENSOR
                is CameraIntrinsicsReference.Unspecified -> SkyIntrinsicsReferenceKind.UNSPECIFIED
            },
        referenceWidthPx = (reference as? CameraIntrinsicsReference.AnalysisBuffer)?.widthPx,
        referenceHeightPx = (reference as? CameraIntrinsicsReference.AnalysisBuffer)?.heightPx,
        quality = intrinsics.quality,
        focalLengthMm = intrinsics.focalLengthMm,
        sensorWidthMm = intrinsics.sensorWidthMm,
        sensorHeightMm = intrinsics.sensorHeightMm,
        principalPointXPx = intrinsics.principalPointXPx,
        principalPointYPx = intrinsics.principalPointYPx,
        axisSwapped = intrinsics.axisSwapped,
        negateXInput = intrinsics.negateXInput,
        negateYInput = intrinsics.negateYInput,
        legacyFallbackReason = (this as? CameraIntrinsicsResolution.LegacyFallback)?.reason,
        pinhole = pinhole,
    )
}

/**
 * The device's own per-camera calibration numbers, mirroring the geometrically meaningful part of
 * `dev.pointtosky.mobile.ar.camera.CameraCalibrationExportSnapshot` (`internalDebug`-only in
 * `:mobile`, and so unreachable from this pure module — hence a parallel plain-value type rather than
 * a shared one). Recorded once per session for an offline consumer that wants to reason in
 * active-array pixels rather than buffer pixels.
 *
 * **Lens distortion is absent, deliberately and honestly.** Camera2's `LENS_DISTORTION` /
 * `LENS_RADIAL_DISTORTION` characteristics are not read anywhere in this codebase today, so there is
 * no distortion model to record; an always-`null` field would only imply one exists and was empty.
 * When a distortion read is added, it belongs here as a new, additive field with a schema bump.
 */
data class SkyCalibrationRecord(
    val activeArrayWidthPx: Int,
    val activeArrayHeightPx: Int,
    val activeArrayLeftPx: Double,
    val activeArrayTopPx: Double,
    val activeArrayRightPx: Double,
    val activeArrayBottomPx: Double,
    val sensorWidthMm: Double,
    val sensorHeightMm: Double,
    val focalLengthMm: Double,
    val activeFxPx: Double,
    val activeFyPx: Double,
    val activeCxPx: Double,
    val activeCyPx: Double,
    val bufferFxPx: Double,
    val bufferFyPx: Double,
    val bufferCxPx: Double,
    val bufferCyPx: Double,
    val quality: String,
    val sensorToBufferMappingSource: String,
    val transformClass: String,
) {
    init {
        // Every numeric field here is read by an offline consumer reasoning in active-array pixels. One
        // non-finite value among twenty is exactly the kind of thing that surfaces later as an
        // inexplicable detector bias rather than as an obvious parse failure, so all of them are
        // checked at construction.
        val numerics =
            mapOf(
                "activeArrayLeftPx" to activeArrayLeftPx,
                "activeArrayTopPx" to activeArrayTopPx,
                "activeArrayRightPx" to activeArrayRightPx,
                "activeArrayBottomPx" to activeArrayBottomPx,
                "sensorWidthMm" to sensorWidthMm,
                "sensorHeightMm" to sensorHeightMm,
                "focalLengthMm" to focalLengthMm,
                "activeFxPx" to activeFxPx,
                "activeFyPx" to activeFyPx,
                "activeCxPx" to activeCxPx,
                "activeCyPx" to activeCyPx,
                "bufferFxPx" to bufferFxPx,
                "bufferFyPx" to bufferFyPx,
                "bufferCxPx" to bufferCxPx,
                "bufferCyPx" to bufferCyPx,
            )
        numerics.forEach { (name, value) -> require(value.isFinite()) { "$name must be finite; was $value" } }
        require(activeArrayWidthPx > 0 && activeArrayHeightPx > 0) {
            "active array must be positively sized; was ${activeArrayWidthPx}x$activeArrayHeightPx"
        }
    }
}

/**
 * The session header — the first line of every sky session log, written once.
 *
 * [maxPairDeltaNanos] and [clockMismatchThresholdNanos] are the tolerances the *capturing* session
 * actually paired with. Replay reuses them ([replaySkySessionFrame]) instead of defaulting, so an
 * offline run reproduces the device's own accept/reject decisions rather than a different set made
 * with library defaults.
 *
 * [bufferWidthPx]/[bufferHeightPx] are the session's expected analysis-buffer size. A frame is free
 * to disagree (CameraX may renegotiate); the frame's own [CameraFrameMetadata] always wins, and the
 * header value is documentation of intent.
 */
data class SkySessionLogHeader(
    val sessionId: String,
    val startedAtEpochMillis: Long,
    val bufferWidthPx: Int,
    val bufferHeightPx: Int,
    val intrinsics: SkyIntrinsicsRecord,
    val clockAlignment: SkyClockAlignment,
    val maxPairDeltaNanos: Long,
    val clockMismatchThresholdNanos: Long,
    val schemaVersion: Int = SKY_SESSION_LOG_SCHEMA_VERSION,
    val lumaFormat: SkyLumaFormat = SkyLumaFormat.RAW_Y8,
    val deviceModel: String? = null,
    val cameraId: String? = null,
    val physicalCameraIds: List<String> = emptyList(),
    val calibration: SkyCalibrationRecord? = null,
    val notes: String? = null,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(schemaVersion > 0) { "schemaVersion must be positive; was $schemaVersion" }
        require(bufferWidthPx > 0) { "bufferWidthPx must be positive; was $bufferWidthPx" }
        require(bufferHeightPx > 0) { "bufferHeightPx must be positive; was $bufferHeightPx" }
        require(maxPairDeltaNanos >= 0L) { "maxPairDeltaNanos must be non-negative; was $maxPairDeltaNanos" }
        require(clockMismatchThresholdNanos >= maxPairDeltaNanos) {
            "clockMismatchThresholdNanos ($clockMismatchThresholdNanos) must be >= maxPairDeltaNanos " +
                "($maxPairDeltaNanos)"
        }
    }
}

/**
 * One analyzed frame: pixels, pose, place, exposure, and predictions — everything needed to develop a
 * detector against this frame alone.
 *
 * [frame] is the CAM-1c `CameraFrameMetadata` verbatim rather than a parallel copy of its fields:
 * that type is already pure, already validated, and already what every downstream math entry point
 * accepts, so re-modelling it here would only create two things to keep in sync.
 *
 * [viewportWidthPx]/[viewportHeightPx] are recorded because `createCameraSessionGeometry` needs them
 * and they are *not* derivable from the buffer — the display-space half of every prediction depends
 * on them.
 */
data class SkyFrameRecord(
    val sequence: Long,
    val capturedAtEpochMillis: Long,
    val frame: CameraFrameMetadata,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val luma: SkyLumaReference,
    val pose: SkyPoseSample,
    val observer: SkyObserverContext? = null,
    val exposure: SkyExposureSample? = null,
    val predictedStars: List<SkyPredictedStar> = emptyList(),
) {
    init {
        require(sequence >= 0L) { "sequence must be non-negative; was $sequence" }
        require(viewportWidthPx > 0) { "viewportWidthPx must be positive; was $viewportWidthPx" }
        require(viewportHeightPx > 0) { "viewportHeightPx must be positive; was $viewportHeightPx" }
        require(luma.widthPx == frame.bufferWidthPx && luma.heightPx == frame.bufferHeightPx) {
            "luma geometry (${luma.widthPx}x${luma.heightPx}) must match the frame buffer " +
                "(${frame.bufferWidthPx}x${frame.bufferHeightPx})"
        }
    }
}
