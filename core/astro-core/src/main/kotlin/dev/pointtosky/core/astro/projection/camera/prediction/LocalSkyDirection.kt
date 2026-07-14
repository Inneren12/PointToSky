package dev.pointtosky.core.astro.projection.camera.prediction

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.transform.raDecToAltAz
import dev.pointtosky.core.astro.units.radToDeg
import kotlin.math.cos
import kotlin.math.sin

/**
 * Unit-length local-sky direction in the project's one canonical ENU tangent-plane basis:
 * `+x = East`, `+y = North`, `+z = Up`. This is **exactly** the convention already documented and
 * tested for [dev.pointtosky.core.astro.projection.horizontalToVector] (cardinal check: north
 * horizon → `(0,1,0)`, east horizon → `(1,0,0)`, zenith → `(0,0,1)`) and for the production sensor
 * world frame (`docs/camera_coordinate_calibration_contract.md` §1.3: "the world frame produced by
 * `SensorManager.getRotationMatrixFromVector` after `remapForDisplay`... ENU: `+X = East, +Y = North,
 * +Z = Up`"). CAM-2a reuses this basis rather than inventing a second one specifically so a future
 * caller cannot silently mix the two.
 *
 * Right-handed: East × North = Up.
 *
 * [equatorialToLocalSky] computes this from Double-precision spherical trigonometry directly (not by
 * calling the `FloatArray`-based `horizontalToVector`) so that hand-computed pinhole test expectations
 * downstream are not polluted by `Float` round-off; `LocalSkyDirectionCardinalAgreementTest`-style
 * checks pin that the two independently-implemented formulas agree to within `Float` precision, so
 * this is a precision choice, not a second, divergent convention.
 *
 * Components are validated finite on construction; callers that build this from
 * [equatorialToLocalSky] additionally get unit length within floating-point tolerance (`sin`/`cos` of
 * real angles), which is documented, not re-enforced by a runtime norm check here.
 */
data class LocalSkyDirection(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) {
            "LocalSkyDirection components must be finite; was ($x, $y, $z)"
        }
    }
}

/**
 * Converts one star's equatorial direction into a local-sky ENU unit vector for the observer/time in
 * [context].
 *
 * Reuses the project's existing, tested astronomy transforms rather than re-deriving sidereal time or
 * equatorial↔horizontal spherical trigonometry:
 *  1. [dev.pointtosky.core.astro.time.lstAt] — local sidereal time from
 *     [StarProjectionContext.utcEpochMillis] (as a UTC `Instant`) and
 *     [StarProjectionContext.longitudeRad] (converted to east-positive degrees, matching `lstAt`'s own
 *     convention).
 *  2. [raDecToAltAz] — the canonical Meeus equatorial→horizontal relations, with
 *     **`applyRefraction = false`**. CAM-2a is predict-only geometry, not an observed-appearance
 *     model: refraction would bias altitude by a caller-invisible, non-invertible amount and is out
 *     of scope (`docs/camera_coordinate_calibration_contract.md` §2 Step A already recommends
 *     refraction off "for geometric matching").
 *  3. The ENU embedding documented on [LocalSkyDirection] (`x = cosAlt·sinAz`, `y = cosAlt·cosAz`,
 *     `z = sinAlt`), computed directly in `Double`.
 *
 * A single-star convenience: computes its own [PreparedStarProjectionContext] via
 * [prepareStarProjectionContext] and delegates to the 3-arg [equatorialToLocalSky] overload below. A
 * batch caller projecting many stars for the same [context] (`projectStars`) calls
 * [prepareStarProjectionContext] **once** instead — see that function's KDoc for why re-deriving
 * sidereal time per star is redundant, not merely wasteful for its own sake.
 */
fun equatorialToLocalSky(
    star: EquatorialStarDirection,
    context: StarProjectionContext,
): LocalSkyDirection {
    val prepared = prepareStarProjectionContext(context)
    return equatorialToLocalSky(star, latitudeRad = prepared.latitudeRad, lstDeg = prepared.lstDeg)
}

/**
 * The batch-efficient core of [equatorialToLocalSky]: identical Meeus/ENU-embedding math, but taking an
 * already-computed [lstDeg] (see [PreparedStarProjectionContext.lstDeg]/[prepareStarProjectionContext])
 * instead of a full [StarProjectionContext] — so a caller projecting many stars for one observer/time
 * (`projectStars`) computes sidereal time exactly once per batch, not once per star. `internal`: not a
 * new public contract, purely `projectStars`'s own batch-perf implementation detail.
 */
internal fun equatorialToLocalSky(
    star: EquatorialStarDirection,
    latitudeRad: Double,
    lstDeg: Double,
): LocalSkyDirection {
    val horizontal =
        raDecToAltAz(
            eq =
                Equatorial(
                    raDeg = radToDeg(star.rightAscensionRad),
                    decDeg = radToDeg(star.declinationRad),
                ),
            lstDeg = lstDeg,
            latDeg = radToDeg(latitudeRad),
            applyRefraction = false,
        )
    return localSkyDirectionFromHorizontal(
        azimuthRad = Math.toRadians(horizontal.azDeg),
        altitudeRad = Math.toRadians(horizontal.altDeg),
    )
}

/**
 * The ENU embedding documented on [LocalSkyDirection]: `x = cosAlt·sinAz`, `y = cosAlt·cosAz`, `z =
 * sinAlt`, where [azimuthRad] is a compass angle (clockwise from north, matching
 * [dev.pointtosky.core.astro.coord.Horizontal]'s convention — **not** a math-positive
 * counter-clockwise angle) and [altitudeRad] is elevation above the horizon. Exposed standalone (not
 * just inline inside [equatorialToLocalSky]) so the cardinal directions can be pinned directly against
 * this formula without routing through sidereal time or equatorial coordinates.
 *
 * Cardinal check: north horizon (`az=0, alt=0`) → `(0,1,0)`; east horizon (`az=π/2, alt=0`) →
 * `(1,0,0)`; south horizon (`az=π, alt=0`) → `(0,-1,0)`; west horizon (`az=3π/2, alt=0`) →
 * `(-1,0,0)`; zenith (`alt=π/2`) → `(0,0,1)` regardless of azimuth.
 */
fun localSkyDirectionFromHorizontal(
    azimuthRad: Double,
    altitudeRad: Double,
): LocalSkyDirection {
    val cosAlt = cos(altitudeRad)
    return LocalSkyDirection(
        x = cosAlt * sin(azimuthRad),
        y = cosAlt * cos(azimuthRad),
        z = sin(altitudeRad),
    )
}
