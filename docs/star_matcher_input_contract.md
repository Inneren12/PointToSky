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
matcher built on geometric invariants does need pixel↔ray geometry, so it has to come from the same
place production projection gets it — `CameraSessionGeometry`'s resolved intrinsics.

Resolved in two parts:

- `PinholeProjectionModel.unprojectToCameraRay(point: PixelPoint): BufferOpticalCameraVector` — the one
  canonical inverse of the production forward projection, living beside the forward map it inverts.
- `AnalysisBufferScale`, which stores that model and delegates to it (`cameraRayFor`), plus the derived
  angular quantities. It duplicates no projection math: `forGeometry` delegates to
  `PinholeProjectionModel.forGeometry`, and the angular extents are measured between real unprojected
  rays.

## Where the code lives

| Concern | Module | File |
| --- | --- | --- |
| Canonical pixel→ray inverse | `:core:astro-core` | `…/camera/prediction/PinholeProjectionModel.kt` |
| Catalog port | `:core:astro-core` | `…/projection/camera/match/StarCatalogQuery.kt` |
| Pixel↔ray geometry carrier | `:core:astro-core` | `…/projection/camera/match/AnalysisBufferScale.kt` |
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
- **The magnitude cut is exact `Double` arithmetic** against the magnitude the caller receives:
  `star.magnitude <= magnitudeLimit` holds for every returned star. Storage quantization must not widen
  it. PTSKCAT0 stores centi-magnitudes and rounds a queried limit with `round(limit · 100)`, so its own
  prefix admits a stored `2.00` for a limit of `1.995`; `PtskCat0StarCatalogQuery` therefore uses that
  prefix **only as a candidate-narrowing optimization** and applies the exact comparison afterwards.
  The prefix can never drop a star the exact cut would keep (stored magnitudes are integers in
  centi-magnitudes, and `round(x) >= floor(x)`), except for a limit outside the `Short`
  centi-magnitude range, where the `toInt()` conversion can overflow — those skip the prefix and fall
  through to a full scan plus the exact cut. The on-disk format is unchanged.

Identity is the backing catalog's stable index — for PTSKCAT0, the record index, which is stable for a
given catalog binary and not portable across binaries. Hipparcos numbers, names and colours are
deliberately not carried; a caller that wants them reads them from its own catalog handle, keyed by the
index the port did carry.

## Pixel ↔ ray geometry

### The path a matcher takes

```text
detected pixel (DetectedSource.xPx/yPx)
  -> AnalysisBufferScale.cameraRayFor(...)      // delegates to PinholeProjectionModel.unprojectToCameraRay
  -> unit camera ray (BufferOpticalCameraVector, +x right, +y down, +z forward)
  -> angular invariant (e.g. acos of the dot product of two rays)
```

`cameraRayFor` is the only sanctioned pixel→ray step, and the inverse it delegates to is the only
inversion of the production model anywhere in the codebase. That matters because `project` is not a bare
`f·x + c`: `axisSwapped` decides which normalized component is multiplied by which focal length, and
`negateXInput`/`negateYInput` decide with which sign — flags derived alongside the focal lengths and
principal point by `mapActiveArrayIntrinsicsThroughMatrix`, never independently. A matcher that wrote its
own inverse would be re-deriving that convention from the outside, and would become a second, unversioned
camera-coordinate contract the moment either side changed.

The returned ray is always unit length and always strictly forward-facing (`z > 0`); a point outside the
image is accepted and meaningful, exactly as `project` never clamps its own output. No display or
viewport transform is involved — `CropScaleTransform` is a separate, later stage.

`PinholeProjectionModelUnprojectTest` pins `ray -> project -> unproject` to `1e-12` for a centred and an
off-centre principal point, for `fx != fy`, and for **every** combination of the three orientation flags,
on a fixture asymmetric enough that no flag can hide behind a symmetry — plus explicit assertions that
the fixture *can* tell a flag-aware inverse from a flag-blind one.

### What `AnalysisBufferScale` exposes

- `pinhole` — the production model itself, stored rather than unpacked.
- `cameraRayFor(point)` / `opticalAxisRay`.
- `focalLengthXPx` / `focalLengthYPx`, `principalPointXPx` / `principalPointYPx`,
  `imageWidthPx` / `imageHeightPx`.
- `leftAngularExtentRad` / `rightAngularExtentRad` / `topAngularExtentRad` / `bottomAngularExtentRad`.
- `horizontalFieldOfViewRad` = left + right, `verticalFieldOfViewRad` = top + bottom.
- `enclosingConeRadiusRad` — the axis-centred cone that contains the whole buffer.
- `radiansPerPixelXOnAxis` / `radiansPerPixelYOnAxis` — the exact derivative `dθ/dx = 1/f` **at the
  optical axis**, and nothing more. It falls off as `cos²θ` (about 30 % across a 66° FOV), so it sizes a
  search radius or a match tolerance. **An angle that feeds a geometric invariant comes from
  `cameraRayFor`, never from this.**
- `quality` — `CALIBRATED` or `LEGACY_INTRINSICS_FALLBACK`. The fallback FOV is a hardcoded default, not a
  measurement of the device in the user's hand, so the scale can be wrong by a large unknown factor. The
  flag rides with the numbers rather than being left behind in the geometry bundle.

### The angular extents are per-edge, not `2·atan(W / 2f)`

The closed forms `2·atan(W / (2·fx))` and `2·atan(H / (2·fy))` are exact **only** when the principal
point is the raster centre. `CameraIntrinsics` allows a measured principal point, and `AnalysisBufferScale`
carries whatever the calibrated geometry reports, so the extents are derived from the actual optical-axis
position instead:

```text
left  = angle(opticalAxisRay, cameraRayFor(0, cy))       // = atan(cx / fx) for an axis-aligned model
right = angle(opticalAxisRay, cameraRayFor(W, cy))       // = atan((W - cx) / fx)
```

and analogously for top/bottom with `cy` / `fy`. They are measured between real unprojected rays rather
than from a closed form, so `axisSwapped` and the negation flags are handled once, by the canonical
inverse, instead of needing another special case here. Each extent is signed by which side of the axis
its edge lies on, so the sums remain the true full extent even if the optical axis were to fall outside
the image. For a centred axis the results are identical to the old closed forms, so nothing changes on
the fallback path.

### Sizing a candidate cone

Be explicit about which of the three you want:

| You want | Use |
| --- | --- |
| How far the frame reaches on one side of the axis | the individual `*AngularExtentRad` |
| The full angular width/height of the image | `horizontalFieldOfViewRad` / `verticalFieldOfViewRad` |
| A cone radius for `StarCatalogQuery.nearby` | `enclosingConeRadiusRad` |

A `StarCatalogQuery` cone is specified by a radius about one direction, so `enclosingConeRadiusRad` — the
largest angle from the optical axis to any image **corner** — is the one to query with. Half of
`horizontalFieldOfViewRad` is wrong twice over: it ignores the vertical extent and the corners, and with
an off-centre axis it is not even half the span in the worst direction. `enclosingConeRadiusRad` is
axis-centred and conservative, not minimal: for an off-centre axis the smallest enclosing cone is centred
elsewhere, so this over-covers. Over-covering costs a few extra candidates; under-covering silently drops
stars that are visibly in frame.

Do not assume the optical axis is the raster centre. It is the *default* when nothing was measured, not a
property of the type.

### Unmappable intrinsics

Physical-sensor reference, dimensionless fallback, or an analysis-buffer reference whose recorded
dimensions do not match this frame all **throw** rather than yielding a fabricated scale — the same cases
`projectStars` reports as `IntrinsicsMappingUnavailable`. A fabricated scale is worse than no scale,
because a matcher cannot tell it is wrong.

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
