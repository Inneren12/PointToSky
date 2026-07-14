# Camera Star Prediction Contract (CAM-2a)

**Status:** Pure math slice implemented and unit-tested. **Not** wired into any renderer, not
device-validated, not a claim of pixel accuracy.
**Scope:** The predict-only geometry pipeline that turns a catalog star's RA/Dec, an observer
location/time, and a [`CameraSessionGeometry`](../core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/projection/camera/CameraSessionGeometry.kt)
bundle into a predicted position in camera/image/display space, plus a bounded visibility
classification.
**Module:** `:core:astro-core`, package
`dev.pointtosky.core.astro.projection.camera.prediction` — Android-free, JVM-testable, no new
module dependency.
**Cross-link:** This is a focused companion to
[`docs/camera_coordinate_calibration_contract.md`](camera_coordinate_calibration_contract.md) §12,
which the main contract keeps short and points here for the full derivation. Read that section
first for how CAM-2a fits into the overall CAM-0/1/2 sequence.

---

## 1. What this is, and is not

CAM-2a answers one question: *if a star's true RA/Dec is correct and the device attitude/intrinsics
are correct, where would it appear?* It is **predict-only**:

- **No image pixels are read.** Nothing in this package imports `ImageProxy`, touches a `Y/U/V`
  plane, or reads a pixel row/stride.
- **No observed points exist.** There is no detector, no blob/centroid extraction, no bright-point
  list — only predicted positions computed from catalog RA/Dec.
- **No matching or pose correction.** There is no correspondence step, no residual/attitude
  correction, no calibration solve. A future CAM-2b/CAM-3 slice would need that; this PR does not
  add it.
- **No renderer change.** `ArScreen.calculateOverlay()`, `projectionParams(viewport)`, and
  `VERTICAL_FOV_DEG = 56.0` are byte-for-byte unchanged. Nothing in `:mobile` calls into this
  package. This is deliberate: the task authorizing CAM-2a explicitly allows it to be implemented
  as an "pure isolated math slice" precisely *because* it must not be represented as
  device-validated or wired into production rendering (see §8 below on CAM-1g's status).

## 2. Input conventions

### 2.1 `EquatorialStarDirection`

(`EquatorialStarDirection.kt`)

```kotlin
data class EquatorialStarDirection(
    val catalogIndex: Int,
    val rightAscensionRad: Double,
    val declinationRad: Double,
    val magnitude: Double? = null,
)
```

- `rightAscensionRad` — radians, increasing eastward (matches
  [`Equatorial.raDeg`](../core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/coord/Coordinates.kt),
  just in radians). The primary constructor only requires it to be **finite** — it does not enforce
  the canonical `[0, 2π)` range, so a directly-constructed value may legitimately sit outside it.
  **`EquatorialStarDirection.of(...)`** is the canonical factory: it wraps RA into `[0, 2π)` via
  `wrapRadTwoPi` before constructing. This is the "normalize in the factory" policy the CAM-2a task
  called for (as opposed to "require canonical input and reject otherwise") — chosen because
  upstream RA arithmetic (proper motion, epoch correction, catalog joins) can easily drift a hair
  outside `[0, 2π)`, and that is not a caller error worth rejecting.
- `declinationRad` — radians, `[-π/2, +π/2]` inclusive, positive toward the north celestial pole.
  Always range-checked (no wraparound concept applies to declination).
- `magnitude` — apparent visual magnitude, smaller = brighter, `null` when unknown. **Never** used
  by the projector to sort, filter, or otherwise change which stars are projected or in what order.
- No catalog name, string, texture, or renderer state is carried — only `catalogIndex`, so a caller
  can map a result back to its own catalog entry.

Wraparound is tested (`EquatorialStarDirectionTest`, `AngleWrapTest`, and
`EquatorialToLocalSkyTest`'s RA-seam continuity tests).

### 2.2 `StarProjectionContext`

(`StarProjectionContext.kt`)

```kotlin
data class StarProjectionContext(
    val latitudeRad: Double,
    val longitudeRad: Double,
    val utcEpochMillis: Long,
)
```

- `latitudeRad` — `[-π/2, +π/2]` inclusive, positive north. Always range-checked.
- `longitudeRad` — **east-positive** (matches `GeoPoint.lonDeg` and
  [`lstAt`](../core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/time/SiderealTime.kt)'s
  `longitudeDeg` parameter). Same normalize-in-factory policy as RA:
  `StarProjectionContext.of(...)` wraps into the canonical `[-π, π)` via `wrapRadMinusPiToPi`; the
  primary constructor only requires it finite.
- `utcEpochMillis` — an absolute instant (`Instant.ofEpochMilli(utcEpochMillis)`), never compared to
  wall-clock time, never adjusted by a timezone or device locale. No function in this package reads
  `System.currentTimeMillis()`, `ZoneId.systemDefault()`, or any device locale.

## 3. Local sky basis

**Convention:** `+x = East`, `+y = North`, `+z = Up` (ENU), right-handed (`East × North = Up`).

This is not a new convention invented for CAM-2a — it is **exactly** the basis the existing AR
projection code already uses and tests:
[`horizontalToVector`](../core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/projection/Projection.kt)
documents the identical cardinal table (`north → (0,1,0)`, `east → (1,0,0)`, `zenith → (0,0,1)`),
and `docs/camera_coordinate_calibration_contract.md` §1.3 documents the same ENU frame for the
production sensor world frame. CAM-2a's `LocalSkyDirection` (`LocalSkyDirection.kt`) reuses this
basis exactly rather than inventing a second one, specifically so a future caller cannot silently
mix the two.

`equatorialToLocalSky(star, context)` computes this by:

1. **Local sidereal time** via
   [`lstAt(instant, longitudeDeg)`](../core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/time/SiderealTime.kt)
   — reused verbatim, not re-derived.
2. **Equatorial → horizontal** via
   [`raDecToAltAz(eq, lstDeg, latDeg, applyRefraction = false)`](../core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/transform/EquatorialHorizontalTransform.kt)
   — the canonical Meeus relations, reused verbatim. **Refraction is explicitly off**: CAM-2a is
   predict-only geometry, not an observed-appearance model, and
   `docs/camera_coordinate_calibration_contract.md` §2 Step A already recommends refraction off "for
   geometric matching."
3. **The ENU embedding** (`localSkyDirectionFromHorizontal`, exposed standalone so cardinal
   directions can be pinned directly): `x = cosAlt·sinAz`, `y = cosAlt·cosAz`, `z = sinAlt`, computed
   directly in `Double` — not by calling the existing `FloatArray`-based `horizontalToVector`, so
   that the hand-computed pinhole test expectations downstream are not polluted by `Float`
   round-off. This is a precision choice, not a second, divergent convention; nothing here changes
   the formula itself.

No sidereal-time or equatorial-to-horizontal formula is duplicated — only the small ENU-embedding
arithmetic (three lines) is written fresh, in `Double`, mirroring the existing `Float` formula
exactly.

Azimuth is a **compass** angle (clockwise from North), matching `Horizontal.azDeg` — never fed to a
math-positive (counter-clockwise) routine.

## 4. Camera / device / optical basis, and the rotation direction

### 4.1 Does `pairedRotation` map device→world or world→device?

**Traced from the production sensor pipeline, not guessed from names:**

- `RotationFrame.forwardWorld` (`mobile/.../ar/RotationFrame.kt`) is computed as `(-R[2], -R[5],
  -R[8])` — the negated **third column** of `R` — and is documented as "the world direction the
  back camera points at." Multiplying row-major `R` by the device unit vector `(0, 0, -1)` (the
  device `-Z` axis the back camera looks along) picks out exactly that negated third column:
  `R · (0,0,-1) = (-R[2], -R[5], -R[8])`. So **`R` maps device-frame vectors to world-frame
  vectors**: `v_world = R · v_device`.
- `ArScreen.calculateOverlay` independently derives `worldToDevice = transpose(rotationMatrix)` from
  this exact same `R` for its own (legacy fixed-FOV) projection — confirming the transpose, not `R`
  itself, is the world→device direction.

Since `R` is a rotation matrix (orthonormal), its transpose is its inverse. Prediction needs the
world→device direction, so it uses:

```text
v_device = Rᵀ · v_world
```

implemented in `worldToDeviceVector` (`RotationMath.kt`) as a direct transpose-multiply computed in
`Double` (converting each `Float` matrix element once), **not** by calling
`dev.pointtosky.core.astro.projection.transpose`/`multiply`, which operate on `FloatArray`/`Float` —
this keeps literal test matrices (identity, exact 90°/180° rotations) round-tripping with zero
additional floating-point error, which the hand-computed pinhole tests depend on.

This is pinned by `CameraStarProjectionTest`'s Section B: literal matrices for identity, 90° yaw
left/right, 90° pitch up/down, 180°, plus an explicit test that applying `R` directly (instead of its
transpose) gives a materially different, wrong device vector.

### 4.2 Device (sensor/attitude) frame

`+x = display right`, `+y = display up`, `+z = out of the display toward the user`; **the back
camera looks along `-Z`.** This matches
[`projectDeviceVector`](../core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/projection/Projection.kt)'s
own already-tested, already-documented convention — CAM-2a does not invent a new device-axis
convention, it reuses the existing one.

### 4.3 Optical pinhole camera frame

`+x = image right`, `+y = image down`, `+z = forward` (into the scene, away from the lens — positive
for anything in front of the camera).

`+y = down` (not `up`) is a deliberate choice: `PixelPoint`/`CropScaleTransform` both document image
axes as `+x` right, `+y` down. Matching that in the optical frame means `PinholeProjectionModel`
needs **no extra Y-flip** in its pinhole formula.

### 4.4 `DeviceToOpticalCameraTransform`

The one fixed, explicit sign-flip (`DeviceToOpticalCameraTransform.kt`) — so no sign flip is
scattered across projection code:

```text
opticalX = deviceX
opticalY = -deviceY
opticalZ = -deviceZ
```

This is not a fresh guess: it exactly reproduces the sign pattern already implied by the existing,
tested `projectDeviceVector` (`ndcX = deviceX / (-deviceZ)`; positive `deviceY` maps to a *smaller*
screen `Y`, per `ProjectionTest."positive device Y projects upward in NDC but to a lower screen
Y"`). Substituting `opticalZ = -deviceZ` and `opticalY = -deviceY` reproduces both signs exactly once
`PinholeProjectionModel` uses a **positive** focal length, so `u = fx·normalizedX + cx` / `v =
fy·normalizedY + cy` land in the same sign convention the legacy renderer already uses — independent
confirmation that this is the physically-correct basis change, not an arbitrary pick.

## 5. Camera-ray result and forward gate

`CameraDirectionProjection` (`CameraDirectionProjection.kt`):

```kotlin
sealed interface CameraDirectionProjection {
    data class InFront(
        val cameraX: Double, val cameraY: Double, val cameraZ: Double,
        val normalizedX: Double, val normalizedY: Double,
    ) : CameraDirectionProjection
    data object BehindCamera : CameraDirectionProjection
}
```

`normalizedX = cameraX / cameraZ`, `normalizedY = cameraY / cameraZ`, only when `cameraZ >
FORWARD_EPSILON` (`1e-9`) — anything at or behind the image plane is `BehindCamera`, never divided.
A non-finite `normalizedX`/`normalizedY` (defense in depth; should not occur given finite, validated
inputs) also folds into `BehindCamera` rather than ever being returned as a normalized coordinate.

## 6. Pinhole projection model

`PinholeProjectionModel` (`PinholeProjectionModel.kt`):

```kotlin
data class PinholeProjectionModel(
    val focalLengthXPx: Double, val focalLengthYPx: Double,
    val principalPointXPx: Double, val principalPointYPx: Double,
    val imageWidthPx: Double, val imageHeightPx: Double,
)
```

`project(normalizedX, normalizedY) = PixelPoint(fx·normalizedX + cx, fy·normalizedY + cy)`. Never
clamped to the image bounds.

### 6.1 Coordinate space

All of `imageWidthPx`/`imageHeightPx`/`principalPointXPx`/`principalPointYPx` (and therefore every
`PixelPoint` this model returns) are in the **full analyzed-buffer** space —
`frame.bufferWidthPx × frame.bufferHeightPx`, *unrotated* and *uncropped*. This is exactly
`CropScaleTransform.imageToDisplay`'s documented input ("buffer space"), and exactly what
`CameraFrameMetadata` itself documents (buffer dimensions "are not swapped to account for
`rotationDegrees`, and are not assumed to equal any display/viewport size").

This is CAM-2a's chosen **Model A**: project directly into unrotated source-buffer coordinates, then
let `CropScaleTransform.imageToDisplay`'s existing rotate→scale→offset pipeline carry the point the
rest of the way to a display pixel. The pinhole model applies **no rotation of its own** — applying
`frame.rotationDegrees` again here would rotate the point twice. `PredictedStarClassificationTest`'s
all-four-rotations test confirms rotation is applied exactly once (not ignored, not doubled).

### 6.2 Deriving focal length in pixels

`CameraIntrinsics` never stores a pixel focal length directly — only FOV in degrees (plus optional
physical mm focal length / sensor size, and an optional principal point). So both
`CameraIntrinsicsResolution.Resolved` (real per-device FOV) and `.LegacyFallback` (the hardcoded 56°
vertical / aspect-derived horizontal default) go through the **same** derivation:

```text
fx = imageWidthPx  / (2 · tan(horizontalFovDeg / 2))
fy = imageHeightPx / (2 · tan(verticalFovDeg   / 2))
```

`PinholeProjectionModel.forGeometry(geometry)` builds this from
`geometry.cropScaleTransform.sourceBufferSize` (equal to `frame.bufferWidthPx`/`bufferHeightPx` by
`CameraSessionGeometry`'s own invariant) and `geometry.intrinsics.intrinsics`.

### 6.3 Principal-point policy

If `CameraIntrinsics.principalPointXPx`/`principalPointYPx` is present, it is used directly (already
in buffer-pixel coordinates, per `CameraIntrinsics`'s own contract). If absent — which is **always**
true as of CAM-1b (`docs/camera_coordinate_calibration_contract.md` §3.4: "Principal point: always
`null` in CAM-1b") — it defaults to the buffer's geometric center (`imageWidthPx/2`,
`imageHeightPx/2`).

### 6.4 Worked example (hand-computed, pinned in `PinholeProjectionModelTest`)

```text
image = 1000x500, horizontal FOV = vertical FOV = 90 deg, principal point = center
fx = 1000 / (2 * tan(45 deg)) = 1000 / (2 * 1) = 500
fy =  500 / (2 * tan(45 deg)) =  500 / (2 * 1) = 250
normalizedX = 0.5, normalizedY = 0.0
u = 500 * 0.5 + 500 = 750
v = 250 * 0.0 + 250 = 250
```

## 7. Crop / image / viewport classification

`projectStars` (`CameraStarPredictor.kt`), after computing `imagePoint` for an in-front direction:

1. `insideCrop = geometry.cropScaleTransform.sourceCrop.contains(imagePoint, CropScaleTransform.DEFAULT_VISIBILITY_TOLERANCE_PX)`
2. `displayPoint = geometry.cropScaleTransform.imageToDisplay(imagePoint)` (always computed, even if
   not visible — geometry is never withheld because a point turned out not to be visible)
3. `visibleInViewport = geometry.cropScaleTransform.isImagePointVisible(imagePoint)`

**Why a non-zero tolerance on the crop check, not the `PixelRect.contains` default of `0.0`:** an
earlier revision used the bare `0.0` default and failed its own classification tests — an on-axis
star's `fx`-derived focal length is not bit-exact `500.0` (it is `500.00000000000006`, since it comes
from `1000.0 / (2.0 * tan(π/4))`), so multiplying by an exact `normalizedX = -1.0` and adding a
principal point of exactly `500.0` produced `-5.68e-14` instead of `0.0` — a point that is
geometrically exactly on the crop's left edge, misclassified `OUTSIDE_IMAGE` purely by floating-point
rounding direction. `CropScaleTransform.isImagePointVisible` already solves this with its own
`DEFAULT_VISIBILITY_TOLERANCE_PX = 1e-6` default specifically to "absorb sub-pixel float noise... 
without materially widening the visible region"; `projectStars` now uses that same constant for its
own crop-containment check, for the same reason and by the same margin.

Classification (`PredictedStarClassification`):

```text
BEHIND_CAMERA                    - CameraDirectionProjection was BehindCamera
OUTSIDE_IMAGE                    - in front, but outside sourceCrop
INSIDE_IMAGE_OUTSIDE_VIEWPORT    - inside sourceCrop, but removed by FILL_CENTER's center crop
VISIBLE_IN_VIEWPORT              - inside sourceCrop and inside the visible viewport
```

These four states are kept distinct rather than collapsed into a boolean, because a point can be in
front of the camera but outside the analyzed crop, or inside the crop but removed by `FILL_CENTER`'s
center crop before it ever reaches the viewport — exactly the distinction
`docs/camera_coordinate_calibration_contract.md` §9 (CAM-1e) exists to make possible.

Boundaries are **inclusive** on both the crop and viewport checks (matching
`CropScaleTransform`/`PixelRect`'s own documented convention) — pinned in
`PredictedStarClassificationTest`'s exact-edge tests.

## 8. Batch API and result shape

```kotlin
fun projectStars(
    stars: List<EquatorialStarDirection>,
    context: StarProjectionContext,
    geometry: CameraSessionGeometry,
): List<PredictedStarProjection>
```

Pure, deterministic, stateless: no coroutine, no prior-call state, no catalog asset access, input
order preserved (never sorted by magnitude or visibility). `PinholeProjectionModel.forGeometry` is
computed once per call (fixed for the whole batch), not once per star.

`PredictedStarProjection` carries only: `catalogIndex`, `magnitude`, `classification`,
`cameraDirection` (a plain `CameraDirectionSnapshot`, never null except for `BEHIND_CAMERA`),
`imagePoint`/`displayPoint` (plain `PixelPoint?`). No rotation matrix, no history, no Android type,
no catalog object, no mutable array, no exception text, no device identifier is exposed.

`StarPredictionSummary`/`summarizeStarPredictions` (`StarPredictionSummary.kt`) provide a bounded
count-only summary for tests and a future CAM-2b debug overlay — no per-star text, no logging.

## 9. Scope confirmation

Confirmed by code review and the test suite below:

- **No pixels are read** — no `ImageProxy`, `planes`, `image`, or pixel row/stride reference anywhere
  in this package.
- **No detector or matcher exists** — no correspondence step, no bright-point list, no CV library
  import.
- **No pose correction exists** — no attitude/residual correction, no calibration solve.
- **No interpolation exists** — no SLERP, no rotation blending; `pairedRotation` is used exactly as
  supplied by `CameraSessionGeometry`.
- **The renderer remains on the legacy 56° FOV** — `ArScreen.calculateOverlay()`,
  `projectionParams(viewport)`, `VERTICAL_FOV_DEG = 56.0` are unchanged; nothing in `:mobile` imports
  this package.
- **Catalog behavior is unchanged** — PTSKCAT0/PTSKCAT4 assets, `CatalogRepository`, and star-overlay
  selection are untouched; `EquatorialStarDirection` is a standalone input type, not a catalog
  reader.
- **`CameraSessionGeometryProvider` is untouched** — CAM-2a only *consumes* a `CameraSessionGeometry`
  value; it adds no producer, no session ownership, no lifecycle.

## 10. CAM-1g device-gate status (unchanged by this PR)

`docs/camera_coordinate_calibration_contract.md` §11.9 recorded **`CAM-1g BLOCKED ON PHYSICAL DEVICE
VALIDATION`** — that status is **unchanged**: no physical-device test has been run as part of CAM-2a.
The task authorizing this PR explicitly permits CAM-2a to proceed as a "pure isolated math slice"
*despite* that outstanding gate, on the condition that it is not wired into production rendering and
not represented as device-validated — which this PR honors (§1, §9 above). This PR makes **no** claim
about on-device pixel accuracy of the predicted positions; it only claims the geometry is internally
consistent and matches its own documented, hand-verified conventions.

## 11. Known unresolved device-alignment risks

These are risks a physical-device pass (not yet run) would need to check, not problems this PR
claims to have solved:

- **Buffer-axis vs. device-axis co-registration.** §4.2's device frame comes from
  `SensorManager.getRotationMatrixFromVector` after `remapForDisplay` — i.e. it is *display-rotation*
  aware. §6.1's pinhole model projects into the *native, unrotated sensor buffer* grid. Whether the
  IMU-derived device axes and the raw (pre-`rotationDegrees`) buffer's pixel grid are actually
  co-registered on a given device is exactly the kind of alignment `docs/camera_coordinate_calibration_contract.md`
  §9.9/§9.10 (CAM-1e) already flags as unverified without a physical marker/grid test.
- **Camera-to-IMU extrinsic offset**, assumed zero (mirrors the existing CAM-1 assumption,
  `docs/camera_coordinate_calibration_contract.md` §3.3): any physical tilt between the lens axis and
  the IMU is not modeled.
- **Lens distortion**, not modeled (pinhole only, matching CAM-1's own deferred scope).
- **Principal point**, always defaulted to the buffer center in practice, since CAM-1b never
  populates a real one (`LENS_INTRINSIC_CALIBRATION` is deliberately unparsed).

None of these change while CAM-2a stays an unwired pure-math slice; they are recorded here so CAM-2b
(or whoever runs the first physical-device pass) knows exactly what is still unverified.

## 12. Next slice: CAM-2b

CAM-2a produces `PredictedStarProjection` values but nothing consumes them. The natural next step
(**not** part of this PR) is a debug-only prediction overlay in `:mobile`, analogous to CAM-1g's
diagnostic overlay: gated the same way (`internalDebug` only), reading `projectStars(...)` output
purely for display, never feeding `calculateOverlay`/`projectionParams` or any production star
position. That slice would also be the first opportunity to gather a physical-device comparison
between predicted and legacy-rendered positions — still not a matcher, just a visual cross-check.

## 13. Tests

| File | Section | Covers |
|---|---|---|
| `AngleWrapTest` | — | `wrapRadTwoPi`/`wrapRadMinusPiToPi` wraparound at exact and near-boundary values |
| `EquatorialStarDirectionTest` | F | invariants, RA wraparound policy (constructor vs. `of`) |
| `StarProjectionContextTest` | F | invariants, longitude wraparound policy (constructor vs. `of`) |
| `EquatorialToLocalSkyTest` | A | zenith, meridian transit, east/west hour-angle sign, longitude east-positive, RA wraparound continuity, pole-adjacent declinations, unit-length/finite sweep |
| `CameraStarProjectionTest` | B, E, F | literal-matrix rotation anchors (identity/yaw/pitch/180°, transpose-vs-direct distinguishing), 10 end-to-end synthetic cases, defensive invalid-input and no-NaN/Infinity sweep |
| `PinholeProjectionModelTest` | C | worked-example arithmetic, edge/just-outside frustum, custom principal point, `forGeometry` buffer-space/calibrated/fallback derivation |
| `PredictedStarClassificationTest` | D | full crop, non-zero crop origin, outside-crop, horizontal/vertical `FILL_CENTER` exclusion, inclusive edge policy, all four rotations |
| `StarPredictionSummaryTest` | — | count invariant |
| `DeviceToOpticalCameraTransformTest` | — | fixed sign-flip, its own inverse |

Commands run (see the PR description for full output):

```bash
./gradlew :core:astro-core:test --tests "*EquatorialToLocalSkyTest"
./gradlew :core:astro-core:test --tests "*CameraStarProjectionTest"
./gradlew :core:astro-core:test --tests "*PinholeProjectionModelTest"
./gradlew :core:astro-core:test --tests "*PredictedStarClassificationTest"
./gradlew :core:astro-core:test
```

**Gradle itself could not run in the authoring sandbox** — no JDK 17 is installed, and both the
Gradle-wrapper distribution download and a foojay-resolver JDK 17 auto-provision attempt were
blocked by the sandbox's egress policy (GitHub release-asset downloads return `403`). All 300 tests
above were verified instead with the Kotlin compiler (`kotlin-compiler-embeddable:2.0.20`, matching
the project's pinned Kotlin version) invoked directly against JDK 21 with `-jvm-target 17`, and run
with the JUnit Platform Console Launcher (`junit-platform-console-standalone:1.10.2`,
`junit-jupiter:5.10.2`, matching the project's pinned JUnit version) — the same compiler, target
bytecode version, and test versions Gradle itself would use, just invoked without Gradle's build
graph. This is disclosed, not hidden: whoever next has a working Gradle/JDK-17 environment should
still run the exact commands above before treating this gate as closed.
