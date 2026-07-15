package dev.pointtosky.core.astro.projection.camera.prediction

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryResult
import dev.pointtosky.core.astro.projection.camera.TimedRotationSample
import dev.pointtosky.core.astro.projection.camera.TimestampSyncConfig
import dev.pointtosky.core.astro.projection.camera.legacyFallbackCameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.pairFrameToNearestRotation
import dev.pointtosky.core.astro.projection.camera.createCameraSessionGeometry
import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.astro.units.wrapDeg0To360
import java.time.Instant
import kotlin.math.sqrt
import kotlin.test.assertIs

/**
 * Literal, hand-derived row-major `FloatArray(9)` rotation matrices ([TimedRotationSample]-shaped:
 * device→world), each representing a device held **upright** (`+Y = world Up`) and pointed at a
 * specific horizon direction or the zenith. Each matrix's `forwardWorld` (`-col2`, i.e. `(-R[2],
 * -R[5], -R[8])`) is verified below to equal the direction the name claims, matching exactly how
 * `RotationFrame.forwardWorld` is derived in production.
 *
 * Derivation for an "upright, looking at azimuth `az`" orientation (`az` measured clockwise from
 * North, matching [dev.pointtosky.core.astro.coord.Horizontal]):
 *  - `forward = (sin(az), cos(az), 0)` (horizon-level local-sky vector at that azimuth).
 *  - `col1 (device Y / up) = (0, 0, 1)` (world Up) — the device is held upright.
 *  - `col0 (device X / right) = (forward.y, -forward.x, 0)` — 90 deg clockwise from forward in the
 *    horizontal plane (verified against N-facing: forward=North=(0,1,0) => right=(1,0,0)=East, matching
 *    everyday intuition: facing North, East is to your right).
 *  - `col2 = -forward` (camera looks along device `-Z`).
 *  - Row-major flattening: `row_i = (col0[i], col1[i], col2[i])`.
 */
private val SQRT_HALF = (sqrt(2.0) / 2.0).toFloat()

/** Row-major identity rotation matrix: device axes exactly equal world axes; `forwardWorld` = nadir `(0,0,-1)`. */
internal val IDENTITY_ROTATION_MATRIX = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

/** Upright, facing the north horizon (`az=0`); `forwardWorld` = `(0,1,0)`. */
internal val NORTH_UPRIGHT_ROTATION_MATRIX = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)

/** Upright, facing the south horizon (`az=180`, a 180 deg yaw from [NORTH_UPRIGHT_ROTATION_MATRIX]); `forwardWorld` = `(0,-1,0)`. */
internal val SOUTH_UPRIGHT_ROTATION_MATRIX = floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f)

/** Upright, facing the west horizon (`az=270`, a 90 deg yaw-left from [NORTH_UPRIGHT_ROTATION_MATRIX]); `forwardWorld` = `(-1,0,0)`. */
internal val WEST_UPRIGHT_ROTATION_MATRIX = floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f)

/** Upright, facing the east horizon (`az=90`, a 90 deg yaw-right from [NORTH_UPRIGHT_ROTATION_MATRIX]); `forwardWorld` = `(1,0,0)`. */
internal val EAST_UPRIGHT_ROTATION_MATRIX = floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f)

/** Upright, facing 45 deg east of north (`az=45`); `forwardWorld` = `(sqrt(2)/2, sqrt(2)/2, 0)`. */
internal val NORTHEAST_UPRIGHT_ROTATION_MATRIX =
    floatArrayOf(SQRT_HALF, 0f, -SQRT_HALF, -SQRT_HALF, 0f, -SQRT_HALF, 0f, 1f, 0f)

/** Upright, facing 45 deg west of north (`az=315`); `forwardWorld` = `(-sqrt(2)/2, sqrt(2)/2, 0)`. */
internal val NORTHWEST_UPRIGHT_ROTATION_MATRIX =
    floatArrayOf(SQRT_HALF, 0f, SQRT_HALF, SQRT_HALF, 0f, -SQRT_HALF, 0f, 1f, 0f)

/**
 * Pitched up 90 deg from [NORTH_UPRIGHT_ROTATION_MATRIX] to look at the zenith; `forwardWorld` =
 * `(0,0,1)`. Pitching a further 90 deg down from here returns exactly [IDENTITY_ROTATION_MATRIX]
 * (`forwardWorld` = nadir) — the two matrices are 90 deg pitch apart, not independently chosen.
 */
internal val ZENITH_UPRIGHT_ROTATION_MATRIX = floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, -1f)

/**
 * A star and observing context placing the star exactly on the local meridian (hour angle 0 deg,
 * "upper culmination") or its anti-meridian (hour angle 180 deg, "lower culmination") for the given
 * [latDeg]/[decDeg] — deterministic, closed-form Alt/Az scenarios (standard spherical-astronomy facts,
 * independently hand-verifiable) used to build clean test fixtures without depending on
 * [equatorialToLocalSky]'s general numerical output for arbitrary inputs. See call sites for the exact
 * geometric fact each `(latDeg, decDeg, lowerCulmination)` combination encodes (e.g. `latDeg == decDeg`
 * with `lowerCulmination = true` is the classic "circumpolar-boundary star grazes the north horizon at
 * lower culmination" case).
 *
 * Hour angle `tau = LST - RA`, so `RA = LST - tau`; [lstAt] supplies `LST` for [instant]/[lonDeg].
 */
internal fun meridianTransitStar(
    instant: Instant,
    lonDeg: Double,
    latDeg: Double,
    decDeg: Double,
    lowerCulmination: Boolean = false,
    catalogIndex: Int = 0,
    magnitude: Double? = null,
): Pair<EquatorialStarDirection, StarProjectionContext> {
    val lstDeg = lstAt(instant, lonDeg).lstDeg
    val hourAngleDeg = if (lowerCulmination) 180.0 else 0.0
    val raDeg = wrapDeg0To360(lstDeg - hourAngleDeg)
    val star = EquatorialStarDirection.of(catalogIndex, Math.toRadians(raDeg), Math.toRadians(decDeg), magnitude)
    val context = StarProjectionContext.of(Math.toRadians(latDeg), Math.toRadians(lonDeg), instant.toEpochMilli())
    return star to context
}

/** The legacy-fallback [CameraIntrinsicsResolution], for tests contrasting it with [resolvedIntrinsics]. */
internal fun legacyFallbackIntrinsics(
    imageWidthPx: Int? = null,
    imageHeightPx: Int? = null,
): CameraIntrinsicsResolution.LegacyFallback =
    CameraIntrinsicsResolution.LegacyFallback(
        legacyFallbackCameraIntrinsics(imageWidthPx = imageWidthPx, imageHeightPx = imageHeightPx),
        reason = "test_fixture",
    )

/**
 * A 90 fov/90 fov **physical-sensor-referenced** (`CAMERA_CHARACTERISTICS`) intrinsics value — i.e.
 * [dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference.PhysicalSensor] — used only
 * by tests that specifically exercise the `StarPredictionBatchResult.IntrinsicsMappingUnavailable`
 * path (`PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED`). **Not** a safe default for other tests:
 * `PinholeProjectionModel.forGeometry` throws for this reference, and `projectStars` reports it
 * unavailable rather than projecting. See [analysisBufferIntrinsics] for the analysis-buffer-
 * referenced fixture most tests should use, and [unspecifiedReferenceIntrinsics] for the
 * dimensionless-fallback case.
 */
internal fun resolvedIntrinsics(
    horizontalFovDeg: Double = 90.0,
    verticalFovDeg: Double = 90.0,
    principalPointXPx: Double? = null,
    principalPointYPx: Double? = null,
): CameraIntrinsicsResolution.Resolved =
    CameraIntrinsicsResolution.Resolved(
        CameraIntrinsics(
            horizontalFovDeg = horizontalFovDeg,
            verticalFovDeg = verticalFovDeg,
            focalLengthMm = 4.0,
            sensorWidthMm = 5.0,
            sensorHeightMm = 4.0,
            principalPointXPx = principalPointXPx,
            principalPointYPx = principalPointYPx,
            source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
            reference = CameraIntrinsicsReference.PhysicalSensor,
        ),
    )

/**
 * An **analysis-buffer-referenced** (`LEGACY_FALLBACK`-sourced) intrinsics value with a custom FOV and
 * **explicit, caller-supplied** [CameraIntrinsicsReference.AnalysisBuffer] dimensions
 * ([referenceWidthPx]/[referenceHeightPx]) — unlike the real
 * [dev.pointtosky.core.astro.projection.camera.legacyFallbackCameraIntrinsics], which always
 * hardcodes a 56 deg vertical FOV, this lets tests keep clean hand-computed pixel math
 * ([PinholeProjectionModelTest]'s 90/90 deg worked example) while still being a value `projectStars`/
 * `PinholeProjectionModel.forGeometry` will actually accept.
 *
 * [referenceWidthPx]/[referenceHeightPx] are **required, not defaulted** — deliberately: an earlier
 * revision of this fixture defaulted to a fixed 90/90 deg FOV with no reference dimensions at all
 * (relying solely on `source == LEGACY_FALLBACK`), which let a test build intrinsics "compatible" with
 * *any* buffer size purely by construction — exactly the dimensionless/stale-aspect bug this hardening
 * closes, just relocated into the test fixtures. Callers must say which buffer these dimensions
 * actually describe; [buildTestGeometry] wires its own `bufferWidthPx`/`bufferHeightPx` through by
 * default so ordinary tests get an automatic exact match, and a test that wants to exercise a
 * mismatch must pass different values explicitly (see `*_DIMENSIONS_MISMATCH` tests).
 */
internal fun analysisBufferIntrinsics(
    referenceWidthPx: Int,
    referenceHeightPx: Int,
    horizontalFovDeg: Double = 90.0,
    verticalFovDeg: Double = 90.0,
    principalPointXPx: Double? = null,
    principalPointYPx: Double? = null,
    axisSwapped: Boolean = false,
    negateXInput: Boolean = false,
    negateYInput: Boolean = false,
): CameraIntrinsicsResolution.LegacyFallback =
    CameraIntrinsicsResolution.LegacyFallback(
        CameraIntrinsics(
            horizontalFovDeg = horizontalFovDeg,
            verticalFovDeg = verticalFovDeg,
            focalLengthMm = null,
            sensorWidthMm = null,
            sensorHeightMm = null,
            principalPointXPx = principalPointXPx,
            principalPointYPx = principalPointYPx,
            source = CameraIntrinsicsSource.LEGACY_FALLBACK,
            reference = CameraIntrinsicsReference.AnalysisBuffer(referenceWidthPx, referenceHeightPx),
            axisSwapped = axisSwapped,
            negateXInput = negateXInput,
            negateYInput = negateYInput,
        ),
        reason = "test_fixture",
    )

/**
 * A `LEGACY_FALLBACK`-sourced intrinsics value carrying
 * [dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference.Unspecified] — models a
 * dimensionless legacy fallback (e.g. resolved before the first analyzed frame's real dimensions were
 * known). Used only by tests exercising `StarPredictionBatchResult.IntrinsicsMappingUnavailable`'s
 * `ANALYSIS_BUFFER_REFERENCE_MISSING` path; never a safe default, since there are no reference
 * dimensions to check against anything.
 */
internal fun unspecifiedReferenceIntrinsics(
    horizontalFovDeg: Double = 90.0,
    verticalFovDeg: Double = 90.0,
): CameraIntrinsicsResolution.LegacyFallback =
    CameraIntrinsicsResolution.LegacyFallback(
        CameraIntrinsics(
            horizontalFovDeg = horizontalFovDeg,
            verticalFovDeg = verticalFovDeg,
            focalLengthMm = null,
            sensorWidthMm = null,
            sensorHeightMm = null,
            principalPointXPx = null,
            principalPointYPx = null,
            source = CameraIntrinsicsSource.LEGACY_FALLBACK,
            reference = CameraIntrinsicsReference.Unspecified,
        ),
        reason = "test_fixture",
    )

/** Builds a `Ready` [CameraSessionGeometry] for tests, skipping the CAM-1c/1d/1f plumbing ceremony. */
internal fun buildTestGeometry(
    bufferWidthPx: Int = 1000,
    bufferHeightPx: Int = 500,
    rotationDegrees: Int = 0,
    cropRectLeftPx: Int? = null,
    cropRectTopPx: Int? = null,
    cropRectRightPx: Int? = null,
    cropRectBottomPx: Int? = null,
    viewportWidthPx: Int = 1000,
    viewportHeightPx: Int = 500,
    rotationMatrix: FloatArray = IDENTITY_ROTATION_MATRIX,
    intrinsicsResolution: CameraIntrinsicsResolution =
        analysisBufferIntrinsics(referenceWidthPx = bufferWidthPx, referenceHeightPx = bufferHeightPx),
    frameTimestampNanos: Long = 1_000L,
    rotationTimestampNanos: Long = 1_000L,
): CameraSessionGeometry {
    val frame =
        CameraFrameMetadata(
            timestampNanos = frameTimestampNanos,
            bufferWidthPx = bufferWidthPx,
            bufferHeightPx = bufferHeightPx,
            rotationDegrees = rotationDegrees,
            cropRectLeftPx = cropRectLeftPx,
            cropRectTopPx = cropRectTopPx,
            cropRectRightPx = cropRectRightPx,
            cropRectBottomPx = cropRectBottomPx,
        )
    val rotationSample = TimedRotationSample(timestampNanos = rotationTimestampNanos, rotationMatrix = rotationMatrix)
    val pairingResult =
        pairFrameToNearestRotation(
            frame = frame,
            samples = listOf(rotationSample),
            maxAllowedDeltaNanos = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS,
            clockMismatchThresholdNanos = TimestampSyncConfig.CLOCK_MISMATCH_THRESHOLD_NANOS,
        )
    val result =
        createCameraSessionGeometry(
            frame = frame,
            pairingResult = pairingResult,
            intrinsicsResolution = intrinsicsResolution,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
        )
    return assertIs<CameraSessionGeometryResult.Ready>(result).geometry
}

/** A single [EquatorialStarDirection] with default declination/magnitude, varying only RA. */
internal fun star(
    catalogIndex: Int = 0,
    rightAscensionRad: Double = 0.0,
    declinationRad: Double = 0.0,
    magnitude: Double? = null,
): EquatorialStarDirection = EquatorialStarDirection.of(catalogIndex, rightAscensionRad, declinationRad, magnitude)

/** A neutral [StarProjectionContext] (equator, Greenwich, epoch zero) for tests that don't care about the astronomy. */
internal fun neutralContext(
    latitudeRad: Double = 0.0,
    longitudeRad: Double = 0.0,
    utcEpochMillis: Long = 0L,
): StarProjectionContext = StarProjectionContext.of(latitudeRad, longitudeRad, utcEpochMillis)

/**
 * Mirrors `remapRotationMatrixForDisplay` (`mobile/.../ar/DisplayRemap.kt`) — column-permutes a
 * row-major device→world matrix the same way `SensorManager.remapCoordinateSystem` does for the
 * given display rotation, reimplemented here (not imported: `:core:astro-core` cannot depend on
 * `:mobile`/Android) purely so CAM-2a's **self-consistent basis-composition tests** (see
 * `CameraStarProjectionTest`'s and `PredictedStarClassificationTest`'s coupled-rotation sections) can
 * build one attitude matrix per display rotation from a single base pose, paired via
 * [pairedFrameRotationDegrees] below. This reimplementation is a faithful copy of the *column-
 * permutation formula* `remapForDisplay` uses — it is not a claim that these tests otherwise model or
 * reproduce CameraX's real sensor-orientation/rotation reporting (sensor orientation is not modeled
 * here at all; see [pairedFrameRotationDegrees]'s KDoc). Only columns 0/1 (device X/Y — screen
 * right/up) are permuted; column 2 (device Z — forward/back) is always carried through unchanged,
 * since every display rotation is a rotation about the device's own Z axis.
 *
 * ```text
 * displayRotationDegrees=0:   X' =  X,  Y' =  Y
 * displayRotationDegrees=90:  X' = -Y,  Y' =  X
 * displayRotationDegrees=180: X' = -X,  Y' = -Y
 * displayRotationDegrees=270: X' =  Y,  Y' = -X
 * ```
 */
internal fun remapColumnsForDisplayRotationDegrees(
    matrix: FloatArray,
    displayRotationDegrees: Int,
): FloatArray {
    val out = FloatArray(9)
    for (row in 0 until 3) {
        val o = row * 3
        val x = matrix[o]
        val y = matrix[o + 1]
        val z = matrix[o + 2]
        val (xPrime, yPrime) =
            when (displayRotationDegrees) {
                0 -> x to y
                90 -> -y to x
                180 -> -x to -y
                270 -> y to -x
                else -> error("displayRotationDegrees must be one of 0/90/180/270; was $displayRotationDegrees")
            }
        out[o] = xPrime
        out[o + 1] = yPrime
        out[o + 2] = z
    }
    return out
}

/**
 * The `frame.rotationDegrees` value that keeps this file's **self-consistent basis-composition
 * tests** internally (algebraically) consistent for an attitude matrix built via
 * [remapColumnsForDisplayRotationDegrees] at [displayRotationDegrees] — **not**
 * [displayRotationDegrees] itself. Derived (not guessed) by composing two independent,
 * already-trusted formulas and finding which pairing makes them cancel for the same physical
 * direction:
 *  - [remapColumnsForDisplayRotationDegrees] (mirroring `remapForDisplay`) transforms a fixed world
 *    direction's *device*-frame components by the standard **counter-clockwise** rotation matrix for
 *    angle `+displayRotationDegrees` (verified algebraically from the column-permutation table).
 *  - [DisplayAlignedOpticalToBufferOpticalTransform] — independently derived straight from
 *    `CropScaleTransform.rotateClockwise`'s Jacobian — implements the *inverse* of that same crop's
 *    **clockwise** pixel rotation for a given `rotationDegrees`.
 *
 * A clockwise pixel rotation is a *negative*-signed rotation in the standard (counter-clockwise
 * positive) convention the attitude side uses, so composing the two consistently requires
 * `frame.rotationDegrees = (360 - displayRotationDegrees) % 360`, not `displayRotationDegrees` itself
 * — confirmed both symbolically and by direct numeric substitution (see
 * `CameraStarProjectionTest`'s coupled-rotation section for the worked check).
 *
 * **This is an algebraic pairing only, not a modeled or verified CameraX/device fact.** It happens to
 * mirror a well-known Android convention (rotating a device 90 deg clockwise by hand is reported as
 * `Surface.ROTATION_270`, not `ROTATION_90`), which is a loose corroboration of the sign found here,
 * but that convention is about `Surface`/`Display` rotation reporting in general — it says nothing
 * about `ImageProxy.imageInfo.rotationDegrees` specifically, and CameraX derives that value from the
 * camera sensor's own mounting orientation (`CameraCharacteristics.SENSOR_ORIENTATION`) combined with
 * the target/display rotation, neither of which this pairing (or anything else in this file) models.
 * Do not read this function, or the tests that use it, as establishing "the real production
 * relationship" or "a realistic CameraX pairing" — they establish only that
 * [DisplayAlignedOpticalToBufferOpticalTransform] and `CropScaleTransform`'s forward rotation compose
 * correctly for *some* self-consistent `(attitude, frame.rotationDegrees)` pairing. Whether any real
 * device actually reports *this* pairing for a given physical rotation remains an open,
 * unverified device-alignment risk (unchanged by this PR — see
 * `docs/camera_star_prediction_contract.md` §11).
 */
internal fun pairedFrameRotationDegrees(displayRotationDegrees: Int): Int = (360 - displayRotationDegrees) % 360
