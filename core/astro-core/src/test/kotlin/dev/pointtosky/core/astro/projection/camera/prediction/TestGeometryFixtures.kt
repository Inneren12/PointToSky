package dev.pointtosky.core.astro.projection.camera.prediction

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
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
    val star = EquatorialStarDirection(catalogIndex, Math.toRadians(raDeg), Math.toRadians(decDeg), magnitude)
    val context = StarProjectionContext(Math.toRadians(latDeg), Math.toRadians(lonDeg), instant.toEpochMilli())
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
 * A 90 fov/90 fov "resolved" (calibrated) intrinsics value, used by tests that need a real (not
 * legacy-fallback) [CameraIntrinsicsResolution].
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
        ),
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
    intrinsicsResolution: CameraIntrinsicsResolution = resolvedIntrinsics(),
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
): EquatorialStarDirection = EquatorialStarDirection(catalogIndex, rightAscensionRad, declinationRad, magnitude)

/** A neutral [StarProjectionContext] (equator, Greenwich, epoch zero) for tests that don't care about the astronomy. */
internal fun neutralContext(
    latitudeRad: Double = 0.0,
    longitudeRad: Double = 0.0,
    utcEpochMillis: Long = 0L,
): StarProjectionContext = StarProjectionContext(latitudeRad, longitudeRad, utcEpochMillis)
