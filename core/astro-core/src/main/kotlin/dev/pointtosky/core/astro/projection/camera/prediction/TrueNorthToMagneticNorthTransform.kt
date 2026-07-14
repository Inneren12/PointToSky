package dev.pointtosky.core.astro.projection.camera.prediction

import kotlin.math.cos
import kotlin.math.sin

/**
 * Converts a **true**-north [LocalSkyDirection] (as [equatorialToLocalSky] always produces) into the
 * **magnetic**-north ENU basis that
 * [dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry.pairedRotation]'s
 * `rotationMatrix` is actually expressed in, *before* that matrix's transpose is applied by
 * [worldToDeviceVector].
 *
 * ## Why this exists (the bug this closes)
 * [equatorialToLocalSky] computes true-north ENU directly from RA/Dec/LST — it has no sensor
 * involvement and so is unaffected by magnetic declination. The production rotation sample paired
 * into a `CameraSessionGeometry`, however, comes from
 * `SensorManager.getRotationMatrixFromVector` (`mobile/.../ar/RotationFrame.kt`) *before* the existing
 * `RotationFrame.correctedForTrueNorth(declinationDeg)` correction is ever applied to it — that
 * correction is only applied downstream, separately, to build the legacy renderer's own
 * `trueNorthFrame` (see `ArScreen.kt`'s `calculateOverlay`/`buildLabelPlacements`). So
 * `geometry.pairedRotation.rotationMatrix`'s `+Y` points at **magnetic**, not true, north. Feeding a
 * true-north `LocalSkyDirection` straight into `worldToDeviceVector` with that raw matrix silently
 * rotates every predicted star in azimuth by the local magnetic declination (which can exceed 10°) —
 * exactly the risk `docs/camera_coordinate_calibration_contract.md`'s existing risk register already
 * flags for the legacy renderer (R3, "Magnetic vs true North"), just not yet closed for CAM-2a. This
 * transform is the one explicit correction, applied once, in world-basis space, immediately before
 * [worldToDeviceVector] — mirroring where `correctedForTrueNorth` sits in the legacy pipeline.
 *
 * ## Derivation (not guessed): the exact inverse of `RotationFrame.correctedForTrueNorth`
 * `correctedForTrueNorth(declinationDeg)` (`mobile/.../ar/RotationFrame.kt`) left-multiplies the
 * device→world rotation matrix `R` (row-major, `R[i·3+j]`) by a rotation about world `+Z`:
 * ```text
 * cm[0] = c·r[0] + s·r[3]   cm[1] = c·r[1] + s·r[4]   cm[2] = c·r[2] + s·r[5]
 * cm[3] = -s·r[0] + c·r[3]  cm[4] = -s·r[1] + c·r[4]  cm[5] = -s·r[2] + c·r[5]
 * cm[6..8] = r[6..8]                                   (c = cos(d), s = sin(d), d = declinationRad)
 * ```
 * which is exactly `CM = M · R` in standard matrix notation, for `M = [[c, s, 0], [-s, c, 0], [0, 0,
 * 1]]`. Since `R` is device→world, `CM = correctedForTrueNorth(R, d)`'s matrix is also device→world,
 * now for the **true**-north world frame — i.e. `v_true = M · v_magnetic` for any world vector `v`
 * (confirmed independently by `RotationFrame.kt`'s own doc comment: "maps magnetic azimuth m to true
 * azimuth m + declinationDeg", and pinned by `ArOverlayScenarioTest`'s
 * `"correctedForTrueNorth rotates magnetic frame vector to true azimuth"` /
 * `"declination correction shifts reticle from magnetic to true azimuth"` tests).
 *
 * CAM-2a's `worldToDeviceVector` uses `Rᵀ` (world→device), so the required equivalence is:
 * ```text
 * Rᵀ · trueEnuToMagneticEnu(v_true, d)  ≈  CMᵀ · v_true  =  (M · R)ᵀ · v_true  =  Rᵀ · Mᵀ · v_true
 * ```
 * which holds (for any invertible `Rᵀ`) exactly when `trueEnuToMagneticEnu(v_true, d) = Mᵀ · v_true`.
 * `M` is a rotation matrix, so `Mᵀ = M⁻¹ = [[c, -s, 0], [s, c, 0], [0, 0, 1]]` — the **inverse** of the
 * `x' = c·x + s·y, y' = -s·x + c·y` candidate one might guess from `M` directly (deliberately not used
 * here; that candidate maps magnetic → true, the wrong direction for this function).
 *
 * ## Mapping (`(x, y)` = [direction]'s East/North components; `z` — Up — is never touched, since this
 * is a rotation about the ENU `+Z` axis only, matching where `correctedForTrueNorth` itself rotates)
 * ```text
 * x' = cos(d)·x - sin(d)·y
 * y' = sin(d)·x + cos(d)·y
 * z' = z
 * ```
 * Pinned by literal-value tests in `TrueNorthToMagneticNorthTransformTest`, including a direct
 * matrix/vector equivalence check against `correctedForTrueNorth`'s own algebra reproduced verbatim,
 * and (in `:mobile`) `RotationFrameTrueNorthEquivalenceTest`, which calls the actual production
 * `correctedForTrueNorth` function directly.
 *
 * @param direction a **true**-north [LocalSkyDirection], e.g. from [equatorialToLocalSky].
 * @param magneticDeclinationRad local magnetic declination in radians, east-positive (see
 *   [StarProjectionContext.magneticDeclinationRad]). `0.0` is an exact no-op (`cos(0)=1`, `sin(0)=0`,
 *   so `direction` is returned bit-for-bit unchanged) — the explicit "uncorrected" mode.
 * @return the equivalent direction expressed in the **magnetic**-north ENU basis
 *   `geometry.pairedRotation.rotationMatrix` actually uses.
 */
fun trueEnuToMagneticEnu(
    direction: LocalSkyDirection,
    magneticDeclinationRad: Double,
): LocalSkyDirection {
    require(magneticDeclinationRad.isFinite()) {
        "magneticDeclinationRad must be finite; was $magneticDeclinationRad"
    }
    val c = cos(magneticDeclinationRad)
    val s = sin(magneticDeclinationRad)
    return LocalSkyDirection(
        x = c * direction.x - s * direction.y,
        y = s * direction.x + c * direction.y,
        z = direction.z,
    )
}
