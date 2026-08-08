# Star matcher input contract (SKY-3, input only)

What a star matcher is given, in what units, in what pixel space, and what it must not assume. **This
document describes the input contract only — no matching algorithm exists yet.** No association, no
geometric invariants, no hashes, no RANSAC, no pose, no plate solve. Those are a later stage.

The contract exists ahead of the matcher because two concrete walls sat between the current code and any
matching algorithm, and both had to be removed before the shape of the input could even be written down.

## The two walls

### Wall A — the module boundary

The matcher belongs in `:core:astro-core`, which is pure JVM (`kotlin("jvm")`, one runtime dependency:
`kotlinx.serialization.json` for the SKY-1 codec). The real catalog reader, `PtskCat0Catalog`, lives in
`:core:catalog`, which is a `com.android.library` — an Android library can never be a dependency of a
pure-JVM module, and `:core:catalog` already depends on `:core:astro-core`, so the arrow points the wrong
way and cannot be reversed.

Resolved with a port: `StarCatalogQuery` is declared in astro-core, and `:core:catalog` supplies the
implementation. `:core:astro-core/build.gradle.kts` is unchanged by this work.

The port returns `EquatorialStarDirection`, not a new `CatalogStar` type. That type already is "one
catalog star's identity, equatorial direction and magnitude", already owns the canonical-RA and range
invariants, and is already what `projectStars` consumes — so a candidate can be projected with no
translation step, and there is only one spelling of the concept to keep correct.

### Wall B — the detector has no scale

By design `StarDetector` does not know the camera intrinsics: a centroid is a fact about pixels. A
matcher built on geometric invariants does need a pixel↔angle scale, so it has to come from the same
place production projection gets it — `CameraSessionGeometry`'s resolved intrinsics.

Resolved with `AnalysisBufferScale`, which stores the production `PinholeProjectionModel` and exposes the
numbers a matcher reads off it. It duplicates no projection math: `AnalysisBufferScale.forGeometry` is a
delegation to `PinholeProjectionModel.forGeometry`.

## Where the code lives

| Concern | Module | File |
| --- | --- | --- |
| Catalog port | `:core:astro-core` | `…/projection/camera/match/StarCatalogQuery.kt` |
| Angular-scale carrier | `:core:astro-core` | `…/projection/camera/match/AnalysisBufferScale.kt` |
| Input DTO | `:core:astro-core` | `…/projection/camera/match/StarMatcherInput.kt` |
| PTSKCAT0 adapter | `:core:catalog` | `…/catalog/binary/PtskCat0StarCatalogQuery.kt` |

## The catalog port

```kotlin
interface StarCatalogQuery {
    fun nearby(
        rightAscensionRad: Double,
        declinationRad: Double,
        radiusRad: Double,
        magnitudeLimit: Double? = null,
    ): List<EquatorialStarDirection>
}
```

Radians throughout, matching astro-core's own convention; the adapter converts to the reader's degrees at
its own boundary. Every implementation validates through the one shared
`requireValidStarCatalogQuery`, so a test fake accepts exactly the queries the device accepts.

Contract points worth stating explicitly:

- A malformed query **throws**. "No stars near here" and "the query was nonsense" are different answers,
  and a matcher that cannot tell them apart concludes the sky is empty.
- `radiusRad == 0.0` returns an empty list.
- `magnitudeLimit` must be finite when present; `null` is the only spelling of "no limit". `±∞` is not a
  harmless synonym: PTSKCAT0 converts a limit with `Math.round(limit * 100.0).toInt()`, and
  `Math.round(+∞).toInt()` is `-1`, so an infinite "no limit" would select **zero** stars.
- Results are deterministic and carry distinct `catalogIndex` values. Nothing beyond determinism is
  promised about the order.

Identity is the backing catalog's stable index — for PTSKCAT0, the record index, which is stable for a
given catalog binary and not portable across binaries. Hipparcos numbers, names and colours are
deliberately not carried; a caller that wants them reads them from its own catalog handle, keyed by the
index the port did carry.

## The angular scale

`AnalysisBufferScale` stores the frame's `PinholeProjectionModel` and exposes:

- `focalLengthXPx` / `focalLengthYPx` — analysis-buffer pixels.
- `principalPointXPx` / `principalPointYPx` — the optical axis; defaults to the buffer's geometric centre
  `(W/2, H/2)`, which is the centre only under the edge-coordinate convention below.
- `imageWidthPx` / `imageHeightPx`.
- `radiansPerPixelXOnAxis` / `radiansPerPixelYOnAxis` — the exact derivative `dθ/dx = 1/f` **at the optical
  axis**. It falls off as `cos²θ` (about 30 % across a 66° FOV), so it sizes a tolerance; it does not
  replace projecting through the model.
- `horizontalFieldOfViewRad` / `verticalFieldOfViewRad`.
- `quality` — `CALIBRATED` or `LEGACY_INTRINSICS_FALLBACK`. The fallback FOV is a hardcoded default, not a
  measurement of the device in the user's hand, so the scale can be wrong by a large unknown factor. The
  flag rides with the numbers rather than being left behind in the geometry bundle.

Unmappable intrinsics (physical-sensor reference, dimensionless fallback, or an analysis-buffer reference
whose recorded dimensions do not match this frame) **throw** rather than yielding a fabricated scale —
the same cases `projectStars` reports as `IntrinsicsMappingUnavailable`. A fabricated scale is worse than
no scale, because a matcher cannot tell it is wrong.

## The input DTO

```kotlin
data class StarMatcherInput(
    val detections: List<DetectedSource>,
    val candidates: List<EquatorialStarDirection>,
    val scale: AnalysisBufferScale,
    val priorProjections: List<PredictedStarProjection> = emptyList(),
)
```

### One pixel space

`detections`' centroids, `priorProjections`' `imagePoint`, and every pixel quantity on `scale` are all
full analysis-buffer pixels in the project's continuous edge-coordinate convention — raster sample
`[x, y]` centred at `(x + 0.5, y + 0.5)`, stated canonically in `PixelGeometry.kt` and
`docs/camera_coordinate_calibration_contract.md` §9.2. This PR consumes that convention and does not
change it. A residual is therefore a plain subtraction:
`detection.xPx - prediction.imagePoint.x`. `StarMatcherInputTest` pins that end to end against a real
`projectStars` output, the same way `PixelConventionBridgeTest` does for the detector, and asserts its own
sensitivity to a half-pixel shift.

### `brightness` is not a magnitude

`DetectedSource.brightness` is flux above the local background summed over the pixels that cleared the
threshold, in raw 8-bit luma units. No zero point, no exposure normalisation, no colour term, no aperture
correction — and the threshold truncates the profile's wings by an amount that depends on how broad the
profile is. **It must never be converted to a magnitude or compared across frames.**

This is measured, not merely asserted: `MatcherInputBrightnessContractTest` renders two sources with
*identical total flux* and profile widths a factor of ~3.7 apart, and they come back with `brightness`
values ~1.43× apart — a 0.38 mag gap between two photometrically identical stars, if anyone converted.

A matcher may use `brightness` as a within-frame rank. The only magnitudes in the input are
`EquatorialStarDirection.magnitude` on `candidates`, which come from the catalog.

### No centroid uncertainty

The detector reports no σ, no FWHM and no covariance, so the input cannot supply one. A matcher that
needs a positional error model derives its own from `pixelCount`, `peakLuma` and `localBackgroundLuma` —
which are carried for exactly that purpose — and states its assumptions where it does so. Absence of an
uncertainty must not be read as "exact".

`saturated` and `nearEdge` are positional caveats, not rejects: a saturated source has a clipped profile
(centroid usable, brightness a lower bound), a near-edge source has a truncated PSF and a centroid biased
inward. The detector keeps and flags them so a matcher can widen a tolerance instead of never learning
they were there.

### Detection identity is list position

There is no stable per-source detection id, and this PR does not add one. `detectStars` returns a list
ordered brightest-first with ties broken by `yPx` then `xPx` — a total order depending only on the pixels
— so the index is already deterministic for identical input, and inserting an id field into
`DetectedSource` would put a value inside that ordering's own data class for the sake of logging.

The identity of a detection is therefore its index in `detections`. Two consequences: the index is only
meaningful together with the frame it came from, and `detections` must be handed to the matcher exactly as
`detectStars` returned it — re-sorting or filtering renumbers every detection. A stable, frame-independent
detection id is **future work**, and belongs with whatever logs matched pairs rather than with the
detector's ordering contract.

### `priorProjections` is a hint, never an answer

`projectStars`' output for `candidates`, when a pointing estimate exists. Legitimate uses: bounding the
search, dropping candidates behind the camera, plotting residuals in a diagnostic.

It must never be the basis of the association itself. `DetectionEvaluation.kt` states the reason and it
holds here: pairing a detection to its nearest prediction and then fitting a pose to that pairing can only
return the pose the predictions came from. The prior carries exactly the pointing error the matcher exists
to correct. Empty is the honest default, and a correct matcher must work with it empty.

Consistency is enforced, not assumed: every `priorProjections` entry must name a star present in
`candidates`, and neither list may repeat a `catalogIndex`.

## What this PR does not touch

SKY-1's wire format and schema, the detector algorithm, the pinned pixel convention (consumed, not
changed), `FrameContent`, and `:core:astro-core`'s build file.
