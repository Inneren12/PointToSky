# Camera Star Prediction Contract (CAM-2a)

**Status:** Pure math slice implemented and unit-tested (398 JVM tests as of the CAM-2b follow-up fix,
§14.9/§14.10 — 388 original + 3 batched-astronomy + 7 `withIntrinsics` exact-preservation — executed via
a direct `kotlinc`/JUnit-console invocation, since Gradle itself cannot run in either that PR's or
CAM-2b's authoring sandbox; see §13/§14.10's disclosures). **Not** wired into any *production* renderer,
not device-validated, not a claim of pixel accuracy. (A separate, `internalDebug`-only diagnostic
*consumer* — CAM-2b, §14 — now visualizes this pipeline's output for physical-device comparison; it does
not change this status line's claims about production rendering or device validation. CAM-2b also made
two small, behavior-preserving/bug-fixing changes to this module's own code — batching the per-star
sidereal-time computation (§14.12) and adding the exact-copy `CameraSessionGeometry.withIntrinsics`
substitution used by the diagnostic-fallback intrinsics mode (§14.11) — both covered by the 398/398
passing result above.)
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
  device-validated or wired into production rendering (see §10 below on CAM-1g's status).

## 2. Input conventions

### 2.1 `EquatorialStarDirection`

(`EquatorialStarDirection.kt`)

```kotlin
@ConsistentCopyVisibility
data class EquatorialStarDirection private constructor(
    val catalogIndex: Int,
    val rightAscensionRad: Double,
    val declinationRad: Double,
    val magnitude: Double? = null,
)
```

- `rightAscensionRad` — radians, increasing eastward (matches
  [`Equatorial.raDeg`](../core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/coord/Coordinates.kt),
  just in radians). Storage is **canonical-only**: the primary constructor is `private`, so both
  direct construction and the compiler-generated `copy()` are confined to this file, and `init`
  re-validates `rightAscensionRad ∈ [0, 2π)` as defense in depth. **`EquatorialStarDirection.of(...)`**
  is the sole public entry point: it wraps RA into `[0, 2π)` via `wrapRadTwoPi` before calling the
  private constructor. This closes a gap an earlier CAM-2a draft had: that draft normalized only in
  `of(...)` while leaving the primary constructor (and therefore `copy()`) able to store a
  noncanonical value directly. Choosing "normalize, don't reject" (rather than validating and
  throwing on out-of-range input) is still the right policy — upstream RA arithmetic (proper motion,
  epoch correction, catalog joins) can easily drift a hair outside `[0, 2π)`, and that is not a caller
  error worth rejecting — but it must be *impossible* to bypass, not just conventionally avoided.
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
@ConsistentCopyVisibility
data class StarProjectionContext private constructor(
    val latitudeRad: Double,
    val longitudeRad: Double,
    val utcEpochMillis: Long,
)
```

- `latitudeRad` — `[-π/2, +π/2]` inclusive, positive north. Always range-checked.
- `longitudeRad` — **east-positive** (matches `GeoPoint.lonDeg` and
  [`lstAt`](../core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/time/SiderealTime.kt)'s
  `longitudeDeg` parameter). Same canonical-storage policy as RA (§2.1): the primary constructor is
  `private` (so `copy()` cannot bypass it either) and `init` re-validates `longitudeRad ∈ [-π, π)`;
  `StarProjectionContext.of(...)` is the sole public entry point, wrapping into `[-π, π)` via
  `wrapRadMinusPiToPi` before calling the private constructor.
- `utcEpochMillis` — an absolute instant (`Instant.ofEpochMilli(utcEpochMillis)`), never compared to
  wall-clock time, never adjusted by a timezone or device locale. No function in this package reads
  `System.currentTimeMillis()`, `ZoneId.systemDefault()`, or any device locale.
- `magneticDeclinationRad` — local magnetic declination in radians, **east-positive**: the angle from
  **true** north to **magnetic** north. See §2.3 below for the full contract; this is a correctness
  fix, not a cosmetic addition — see §4.6.

### 2.3 `magneticDeclinationRad`: an explicit, pure declination input

**This closes a real CAM-2a correctness bug**, not a hardening nicety. `equatorialToLocalSky` (§3)
produces a **true**-north ENU vector — pure astronomy, with no sensor involvement, so it cannot be
affected by magnetic declination. `geometry.pairedRotation.rotationMatrix`, however, is the *raw*
device→world matrix from `SensorManager.getRotationMatrixFromVector`
(`docs/camera_coordinate_calibration_contract.md` §1.3), whose `+Y` axis points at **magnetic** north
— the legacy renderer only ever corrects this via a *separate*, later call to
`RotationFrame.correctedForTrueNorth(declinationDeg)`, applied to build its own `trueNorthFrame` for
pixel math (`ArScreen.kt`'s `calculateOverlay`/`buildLabelPlacements`), never to the matrix fed into
CAM-1d's rotation-sample history that `geometry.pairedRotation` is ultimately built from. Before this
fix, CAM-2a fed a true-north sky vector straight into the raw, magnetic-referenced matrix — silently
rotating every predicted star's azimuth by the local magnetic declination (which can exceed 10°). See
§4.6 for the transform that closes this.

`magneticDeclinationRad`'s sign convention is **not invented for CAM-2a** — it is exactly
`android.hardware.GeomagneticField.getDeclination()`'s convention, which is what the legacy renderer's
`declinationDeg` already is (`ArScreen.kt`'s `GeomagneticField(...).declination` call site) and which
`RotationFrame.correctedForTrueNorth`/`Pointing.kt`'s `Horizontal.toTrueNorth` both already document
and implement: positive means magnetic north lies **east** of true north, and
`trueAzimuth = magneticAzimuth + declination`.

**The default, `0.0`, is an explicit, deliberate "treat magnetic north as true north" mode — not a
claim that the real local declination is known to be zero.** CAM-2a is a pure math slice and must not
call `android.hardware.GeomagneticField` (an Android/hardware dependency `:core:astro-core` cannot
take on) or otherwise compute a real declination itself — see §6 for the caller-contract this implies.
Every existing pure test/caller that does not pass `magneticDeclinationRad` keeps its exact prior
output: `trueEnuToMagneticEnu` at `d = 0.0` is a bit-for-bit identity (`cos(0.0) = 1.0`,
`sin(0.0) = 0.0` exactly in IEEE 754 double), not merely an approximation.

Stored canonically in `[-π, π)` via `wrapRadMinusPiToPi` — the same wraparound policy as
`longitudeRad` — and validated finite, both in `of(...)` and (defense in depth) in `init`.

## 3. Local sky basis

**Convention:** `+x = East`, `+y = North`, `+z = Up` (ENU), right-handed (`East × North = Up`).

This is not a new convention invented for CAM-2a — it is **exactly** the basis the existing AR
projection code already uses and tests:
[`horizontalToVector`](../core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/projection/Projection.kt)
documents the identical cardinal table (`north → (0,1,0)`, `east → (1,0,0)`, `zenith → (0,0,1)`).

**"North" here is astronomical — i.e. TRUE north — always, with no exception.** An earlier revision of
this document additionally claimed `docs/camera_coordinate_calibration_contract.md` §1.3 "documents the
same ENU frame for the production sensor world frame," which was true only for the axis *convention*
(East/North/Up, right-handed) and dangerously easy to misread as "the same frame, full stop": §1.3
itself is explicit that the *production sensor* world frame's `+Y` points at **magnetic** north until
`RotationFrame.correctedForTrueNorth` is applied (§1.3's own "North caveat"; also R3 in that document's
risk register). `LocalSkyDirection` from `equatorialToLocalSky` is always true-north-referenced; the
production `pairedRotation.rotationMatrix` is magnetic-north-referenced until explicitly converted.
§4.6 is the one place these two get reconciled — no other code in this package should ever need its
own declination handling.

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

### 4.5 Display-aligned optical basis is not the native-buffer optical basis: `DisplayAlignedOpticalToBufferOpticalTransform`

**This is the fix for a correctness blocker found in review of the first CAM-2a draft.** §4.1's
`pairedRotation` (and therefore §4.4's `OpticalCameraVector` output) is **display-aligned**: it comes
from `RotationFrame`'s sensor attitude *after* `remapForDisplay` has run
(`docs/camera_coordinate_calibration_contract.md` §1.3/§1.4), so `+x`/`+y` mean *display*-right/-up
for whatever the display's current rotation happens to be — not the sensor's own fixed row/column
axes. §6's `PinholeProjectionModel`, by contrast, projects into the **native, unrotated,
uncropped analysis buffer** (`frame.bufferWidthPx × frame.bufferHeightPx`), and
`CropScaleTransform.imageToDisplay` (§7) then applies its **own** forward rotation by
`frame.rotationDegrees` to reach a display pixel.

The first draft fed `OpticalCameraVector` straight into the pinhole model. For
`rotationDegrees ∈ {0, 180}` this happens to be harmless (the buffer and display axes coincide up to
a sign both stages already agree on), but for `rotationDegrees ∈ {90, 270}` it silently double-applies
rotation: once implicitly, because the display-aligned attitude already has the rotation baked into
its axes, and once explicitly, via `CropScaleTransform`.

`DisplayAlignedOpticalToBufferOpticalTransform` (`DisplayAlignedOpticalToBufferOpticalTransform.kt`)
is the one explicit, pure correction, inserted exactly once between §4.4 and §6:

```text
worldToDeviceVector → DeviceToOpticalCameraTransform → DisplayAlignedOpticalToBufferOpticalTransform → PinholeProjectionModel.project → CropScaleTransform.imageToDisplay
```

It converts a display-aligned `OpticalCameraVector` into a distinct `BufferOpticalCameraVector` type
— a type deliberately different from `OpticalCameraVector` so the two bases can never again be
silently mixed up in this package.

**Derivation (not guessed): the exact inverse of `CropScaleTransform`'s own pixel rotation.**
`CropScaleTransform`'s private `rotateClockwise(x, y, w, h, rotationDegrees)` maps a crop-local buffer
point to its rotated-image-local point:

```text
  0°: xr = x,      yr = y
 90°: xr = h − y,  yr = x
180°: xr = w − x,  yr = h − y
270°: xr = y,      yr = w − x
```

Only the *linear* part (the Jacobian) matters for a direction vector — a ray has no position, so the
`w`/`h` translation terms drop out:

```text
  0°: J = [[ 1, 0], [ 0, 1]]      90°: J = [[ 0,-1], [ 1, 0]]
180°: J = [[-1, 0], [ 0,-1]]     270°: J = [[ 0, 1], [-1, 0]]
```

`displayOptical = J(rotationDegrees) · bufferOptical` is what `imageToDisplay` (buffer→display) does,
so recovering `bufferOptical` from `displayOptical` requires **`J(rotationDegrees)⁻¹`**. Each
`J(k·90°)` is orthogonal, so its inverse is its transpose: `J(90°)⁻¹ = J(270°)`, `J(270°)⁻¹ = J(90°)`
(verified `J(90°)·J(270°) = I`), and `J(0°)`/`J(180°)` are each their own inverse. Substituting gives
the mapping table `DisplayAlignedOpticalToBufferOpticalTransform.apply` implements
(`dx`/`dy` = `displayOptical.x`/`.y`; `z` is never touched — this is a 2D change of basis around the
optical `Z` axis only, and rotating a display's content about its own normal cannot change how far in
front of the lens a direction is):

```text
rotationDegrees   bufferX        bufferY
        0            dx             dy
       90            dy            −dx
      180           −dx            −dy
      270           −dy             dx
```

**Tests** (`DisplayAlignedOpticalToBufferOpticalTransformTest`): the literal table above for all four
rotations, `z` invariance, that composing 90°/270° (either order) returns the original vector, that
0°/180° are each their own inverse, and rejection of any `rotationDegrees` outside `{0, 90, 180,
270}`.

**Coupled attitude/frame-rotation tests** (`CameraStarProjectionTest`'s "coupled rotations" section,
`PredictedStarClassificationTest`'s matching test): a naive per-rotation test that holds one fixed
attitude matrix constant while independently varying `frame.rotationDegrees` cannot catch this bug's
real shape, because a real device changes **both** together (attitude, via `remapForDisplay`, and
`frame.rotationDegrees`) for the same physical pose. These tests instead:

1. Build all four attitude matrices from **one** base pose via the test-only
   `remapColumnsForDisplayRotationDegrees` helper, mirroring `remapForDisplay`'s exact
   column-permutation table.
2. Pair each with the `frame.rotationDegrees` value that keeps the whole chain internally consistent —
   the test-only `pairedFrameRotationDegrees(d) = (360 - d) % 360`, derived by composing
   `remapColumnsForDisplayRotationDegrees` (a standard counter-clockwise rotation) with this
   transform's clockwise-inverse convention and finding the pairing that cancels; this happens to
   match the well-known Android fact that rotating a device 90° clockwise by hand is reported as
   `Surface.ROTATION_270`, which corroborates but does not by itself prove the pairing.
3. Project world directions defined *relative to the device's own current axes* (device-relative
   "screen right"/"screen up") against one **fixed** viewport (a portrait-locked physical display
   whose pixel dimensions do not change shape just because the buffer's rotation differs), and assert:
   the same physical forward direction is the display center for all four rotations; the
   device-relative right direction always increases display X; the device-relative up direction
   always decreases display Y.
4. A dedicated regression test reproduces the **pre-fix bug directly** — feeding a display-aligned ray
   straight into the pinhole model (i.e. skipping this transform entirely, exactly what the first
   draft did) — and confirms that breaks the anchor (the up-direction test's Y-response leaks into an
   X-shift instead). An earlier attempt at this regression test instead tried "apply the *unpaired*
   `rotationDegrees` value consistently to both the direction-correction and `CropScaleTransform`
   stages" — that construction can *never* fail, for any input, because
   `CropScaleTransform.rotateClockwise(·, k)` is by construction the exact inverse of
   `DisplayAlignedOpticalToBufferOpticalTransform.apply(·, k)` for **any** self-consistently-applied
   `k`, not just the physically correct one; only *omitting* the correction (not merely mismatching
   it) reproduces the real bug.

**What these tests do and do not prove:** they are pure-math internal-consistency checks — that this
transform and `CropScaleTransform`'s forward rotation compose correctly for a self-consistent
`(attitude, frame.rotationDegrees)` pairing. They do **not** prove any real device actually reports
that pairing; whether `pairedRotation`'s IMU-derived axes and the raw analysis buffer's pixel grid are
actually co-registered on physical hardware remains open (§11, R12 in the calibration contract's risk
register).

### 4.6 True-north vs magnetic-north world basis: `trueEnuToMagneticEnu`

**This is the fix for a third correctness bug**, distinct from and independent of §4.5's
display/buffer basis fix: `equatorialToLocalSky` (§3) produces a true-north `LocalSkyDirection`, but
`geometry.pairedRotation.rotationMatrix` — the matrix `worldToDeviceVector` (§4.1) transposes — is
**magnetic**-north-referenced (§2.3, §3). Feeding one directly into the other, uncorrected, rotates
every predicted star's azimuth by the local magnetic declination.

`trueEnuToMagneticEnu(direction, magneticDeclinationRad)` (`TrueNorthToMagneticNorthTransform.kt`) is
the one explicit correction, inserted in `CameraStarPredictor.projectOneStar` immediately after
`equatorialToLocalSky` and before `worldToDeviceVector` — mirroring exactly where the legacy renderer's
own `correctedForTrueNorth` sits relative to `transpose(rotationMatrix)` in `calculateOverlay`. It is a
**rotation about the ENU `+Z` (Up) axis only** — altitude/Up is never touched — so no other function in
this package needs its own declination handling.

**Derivation (not guessed): the exact inverse of the production `correctedForTrueNorth`.**
`RotationFrame.correctedForTrueNorth(declinationDeg)` (`mobile/.../ar/RotationFrame.kt`) left-multiplies
the raw device→world matrix `R` by a rotation-about-`+Z` matrix `M = [[c, s, 0], [-s, c, 0], [0, 0,
1]]` (`c = cos(d)`, `s = sin(d)`, `d` = declination in radians): `CM = M · R`, and — independently
confirmed by that function's own doc comment ("maps magnetic azimuth m to true azimuth m +
declinationDeg") and by `ArOverlayScenarioTest`'s
`"correctedForTrueNorth rotates magnetic frame vector to true azimuth"` /
`"declination correction shifts reticle from magnetic to true azimuth"` tests — `CM` is the
device→world matrix for the **true**-north world frame: `v_true = M · v_magnetic` for any world
vector. CAM-2a's `worldToDeviceVector` uses `Rᵀ`, so the required equivalence is

```text
Rᵀ · trueEnuToMagneticEnu(v_true, d)  ≈  CMᵀ · v_true  =  (M · R)ᵀ · v_true  =  Rᵀ · Mᵀ · v_true
```

which holds exactly when `trueEnuToMagneticEnu(v_true, d) = Mᵀ · v_true`. Since `M` is a rotation
matrix, `Mᵀ = M⁻¹ = [[c, -s, 0], [s, c, 0], [0, 0, 1]]` — the **inverse** of the `x' = c·x + s·y, y' =
-s·x + c·y` mapping one might guess directly from `M` (that candidate maps magnetic → true, the wrong
direction for this function; a candidate worth naming explicitly because it is the easy mistake to
make here). The resulting mapping (`(x, y)` = East/North components; `z` — Up — untouched):

```text
x' = cos(d)·x - sin(d)·y
y' = sin(d)·x + cos(d)·y
z' = z
```

**Tests:** `TrueNorthToMagneticNorthTransformTest` (`:core:astro-core`) pins: zero declination is a
bit-for-bit identity for North/East/an arbitrary normalized vector; literal (not function-derived)
expected vectors for `±30°` declination at North and East, chosen so a sign inversion changes the
literal expected answer; unit-length preservation across several directions and declinations; and the
matrix/vector equivalence above, checked against a line-for-line ported reimplementation of
`correctedForTrueNorth`'s own algebra (documented as a port, not a re-derivation), across three
matrices, six directions, and five declinations (`-20°, -5°, 0°, +5°, +20°`), plus an explicit
sign-reversal regression guard. `RotationFrameTrueNorthEquivalenceTest` (`:mobile`) proves the same
equivalence against the **actual production** `correctedForTrueNorth` function directly (not a
reimplementation) — this is the strongest evidence for the fix, since `:core:astro-core` cannot depend
on `:mobile`/Android to call that function itself. `CameraStarProjectionTest`'s `E11` end-to-end test
confirms an off-axis (non-forward-axis) star's predicted display position shifts in the analytically
correct direction when a non-zero declination is supplied, and `E12` reconfirms the zero-declination
default reproduces the pre-fix output exactly, end-to-end through the full `projectStars` pipeline.

**What this fix does not do:** it does not compute a real declination — see §6 for the caller
contract — and it does not touch §4.5's display/buffer basis correction, `frame.rotationDegrees`
handling, or intrinsics provenance (§6.2), all of which remain exactly as before.

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
`frame.rotationDegrees` again here would rotate the point twice. The ray it projects is always a
`BufferOpticalCameraVector` — i.e. it has already gone through §4.5's
`DisplayAlignedOpticalToBufferOpticalTransform` — never the display-aligned `OpticalCameraVector`
directly; the coupled tests described in §4.5 confirm rotation is applied exactly once end-to-end (not
ignored, not doubled, not sign-swapped for the 90°/270° cases).

### 6.2 Deriving focal length in pixels, and the intrinsics-reference gate

**This is the fix for a second correctness blocker found in the same review**, hardened further in a
follow-up review of the fix itself (see the "provenance hardening" note below). `CameraIntrinsics`
never stores a pixel focal length directly — only FOV in degrees (plus optional physical mm focal
length / sensor size, and an optional principal point) — so any source derives pixel focal length via
the same formula:

```text
fx = imageWidthPx  / (2 · tan(horizontalFovDeg / 2))
fy = imageHeightPx / (2 · tan(verticalFovDeg   / 2))
```

The bug: this formula is only valid if `horizontalFovDeg`/`verticalFovDeg` actually describe the field
of view over `imageWidthPx × imageHeightPx` — the **analysis buffer**. Inspecting
`CameraIntrinsicsResolver.kt` (CAM-1b) shows its `CAMERA_CHARACTERISTICS`-sourced (calibrated) FOV is
derived from `SENSOR_INFO_PHYSICAL_SIZE` and focal length alone — there is no
`SCALER_CROP_REGION`/active-array/sensor-orientation reasoning anywhere in this codebase (`grep`
confirms zero hits for any of those across `:mobile`/`:core:astro-core`). That FOV describes the
**physical sensor**, not necessarily the `ImageAnalysis` buffer — CameraX may crop and/or scale the
sensor into that buffer, and nothing here captures the crop/scale metadata that would be needed to
translate one into the other.

**`source` vs `reference`.** `CameraIntrinsics.source` ([CameraIntrinsicsSource]) answers *"where did
these numbers come from"* — it says nothing about which pixel grid the FOV applies to.
`CameraIntrinsics.reference` (`CameraIntrinsicsReference`) is the separate, explicit answer to *that*
question:

```kotlin
sealed interface CameraIntrinsicsReference {
    data class AnalysisBuffer(val widthPx: Int, val heightPx: Int) : CameraIntrinsicsReference
    data object PhysicalSensor : CameraIntrinsicsReference
    data object Unspecified : CameraIntrinsicsReference
}
```

**Provenance hardening (this revision).** An earlier version of this fix derived an
`ANALYSIS_BUFFER`/`PHYSICAL_SENSOR` enum purely from `source` (`LEGACY_FALLBACK` → always
"analysis-buffer-safe"). That was insufficient: `legacyFallbackCameraIntrinsics` can be — and in tests
routinely was — constructed with no image dimensions, with dimensions from a different buffer/session,
or with a different aspect ratio, then reused against an arbitrary `CameraSessionGeometry`. Deriving
"safe" from `source` alone cannot detect any of that; it recreated the exact dimensionless/stale-aspect
fallback bug CAM-1f's own coordinator was built to prevent, just one layer higher, at the CAM-2a
boundary. `CameraIntrinsicsReference.AnalysisBuffer` now **carries its own dimensions** as data, so a
consumer can check them against the buffer it is actually projecting into, not just trust a label:

- **`AnalysisBuffer(widthPx, heightPx)`** — the FOV is measured over an analysis buffer of exactly
  these dimensions. The *only* variant `PinholeProjectionModel.forGeometry`/`projectStars` will ever
  build a pinhole model from, and only when `widthPx`/`heightPx` **exactly** match
  `geometry.frame.bufferWidthPx`/`bufferHeightPx` — matching aspect ratio alone is **not** sufficient
  (a `1000x500` reference must not be silently reused for a `2000x1000` buffer at the same 2:1 shape).
- **`PhysicalSensor`** — the FOV is measured over the physical sensor, with no recorded crop/scale
  mapping to any analysis buffer. Always what `CAMERA_CHARACTERISTICS`/`CAMERA_INTRINSIC_CALIBRATION`
  carry (`CameraIntrinsics.init` enforces this cross-field rule, so `source`/`reference` can never
  claim otherwise for these sources).
- **`Unspecified`** — no analysis-buffer dimensions were available at all, e.g.
  `legacyFallbackCameraIntrinsics()` called with no (or invalid) image dimensions. Distinct from
  `AnalysisBuffer`: there is nothing here to check against anything, so it must never be treated as
  analysis-buffer-compatible. This preserves `legacyFallbackCameraIntrinsics`'s ability to be
  constructed dimensionlessly (for whatever legacy-renderer-adjacent callers might need that outside
  CAM-2a's buffer-projection path) while keeping that uncertainty explicit and machine-checkable
  rather than silently defaulting to a guessed aspect ratio.

`reference` is **not** derived from `source` — it is caller-supplied data. `CameraIntrinsics.init`
only re-validates the one cross-field rule production code must uphold (`CAMERA_CHARACTERISTICS`/
`CAMERA_INTRINSIC_CALIBRATION` ⇒ `PhysicalSensor`; `LEGACY_FALLBACK` ⇒ `AnalysisBuffer` or
`Unspecified`), which rules out the specific mislabeling this hardening exists to prevent without
pretending `reference`'s actual dimensions are somehow implied by `source`.

Two options were considered for closing the original gap (per the task authorizing this fix): deriving
explicit output-buffer intrinsics from active-array + crop + scale metadata ("Option A"), or making the
mismatch an explicit, reported, unavailable result ("Option B"). **Option A is not available**: as
noted above, this codebase captures none of the crop/scale metadata Option A would require, and
fabricating one would mean inventing a calibrated focal length that was never actually measured for
that buffer. **Option B is what this PR implements**, now with exact-dimension checking rather than a
bare reference-space label:

- `PinholeProjectionModel.forGeometry(geometry)` `require`s
  `geometry.intrinsics.intrinsics.reference` to be a `CameraIntrinsicsReference.AnalysisBuffer` whose
  `widthPx`/`heightPx` **exactly** equal `geometry.frame.bufferWidthPx`/`bufferHeightPx`, and throws
  `IllegalArgumentException` otherwise — for a caller that skips the check below, not the
  expected-runtime-outcome path.
- `projectStars` (§8) checks `reference` **first**, before ever constructing a
  `PinholeProjectionModel`, and returns a categorized
  `StarPredictionBatchResult.IntrinsicsMappingUnavailable(reason)` instead of ever fabricating an
  output-buffer FOV or silently substituting/reusing a mismatched one:
  - `PhysicalSensor` → `IntrinsicsMappingUnavailableReason.PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED`.
  - `Unspecified` → `IntrinsicsMappingUnavailableReason.ANALYSIS_BUFFER_REFERENCE_MISSING`.
  - `AnalysisBuffer` with dimensions that do not exactly match the geometry's buffer →
    `IntrinsicsMappingUnavailableReason.ANALYSIS_BUFFER_DIMENSIONS_MISMATCH`.

  A caller that receives `Ready` — never `IntrinsicsMappingUnavailable` — has an explicit guarantee the
  FOV used was actually measured over *this exact* buffer, not merely "some analysis buffer at some
  point."

`PinholeProjectionModel.forGeometry(geometry)` builds `fx`/`fy` from
`geometry.cropScaleTransform.sourceBufferSize` (equal to `frame.bufferWidthPx`/`bufferHeightPx` by
`CameraSessionGeometry`'s own invariant) and `geometry.intrinsics.intrinsics`, once the reference check
above has passed.

**Production assignments:**

- `CameraIntrinsicsResolver.kt`'s `CAMERA_CHARACTERISTICS` branch sets
  `reference = CameraIntrinsicsReference.PhysicalSensor`.
- `legacyFallbackCameraIntrinsics(imageWidthPx, imageHeightPx)` sets
  `reference = CameraIntrinsicsReference.AnalysisBuffer(imageWidthPx, imageHeightPx)` when both are
  known and strictly positive, else `reference = CameraIntrinsicsReference.Unspecified` — mirroring the
  exact same known/unknown split its horizontal-FOV derivation already uses, so the two can never
  silently disagree about whether real dimensions were available.

**Tests:** `CameraIntrinsicsTest` covers `AnalysisBuffer`'s own dimension validation and the
`source`/`reference` cross-field rule. `LegacyFallbackCameraIntrinsicsTest` covers the *real* factory
(`legacyFallbackCameraIntrinsics`, not just a synthetic test constructor): known dimensions produce an
`AnalysisBuffer` with those exact dimensions; unknown/invalid dimensions produce `Unspecified`.
`PinholeProjectionModelTest` covers exact-match `fx`/`fy` derivation (both the custom-FOV test fixture
and the real `.LegacyFallback`), and that `forGeometry` throws for `PhysicalSensor`, `Unspecified`, a
width mismatch, a height mismatch, a same-aspect-ratio-but-different-size mismatch, and a
different-aspect-ratio mismatch. `CameraStarProjectionTest`'s `E8`/`E8b`/`E8c`/`E8d`/`E8e` cases confirm
`projectStars` itself reports the correct categorized `IntrinsicsMappingUnavailableReason` (never a
fabricated or silently-downgraded `Ready` result) for the physical-sensor, dimensionless, mismatched,
and stale-session-reuse cases respectively, and that an exact match still produces `Ready`.
`StarPredictionBatchResultTest` covers `Ready`'s own defensive-copy guarantee (§8).

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
sealed interface StarPredictionBatchResult {
    class Ready private constructor(val projections: List<PredictedStarProjection>) : StarPredictionBatchResult {
        companion object {
            fun of(projections: List<PredictedStarProjection>): Ready = Ready(projections.toList())
        }
    }
    data class IntrinsicsMappingUnavailable(
        val reason: IntrinsicsMappingUnavailableReason,
    ) : StarPredictionBatchResult
}

enum class IntrinsicsMappingUnavailableReason {
    PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED,
    ANALYSIS_BUFFER_REFERENCE_MISSING,
    ANALYSIS_BUFFER_DIMENSIONS_MISMATCH,
}

fun projectStars(
    stars: List<EquatorialStarDirection>,
    context: StarProjectionContext,
    geometry: CameraSessionGeometry,
): StarPredictionBatchResult
```

Pure, deterministic, stateless: no coroutine, no prior-call state, no catalog asset access, input
order preserved within `Ready.projections` (never sorted by magnitude or visibility). The result is
`IntrinsicsMappingUnavailable` (§6.2) whenever `geometry.intrinsics.intrinsics.reference` is not an
exactly-matching `CameraIntrinsicsReference.AnalysisBuffer` — checked once, before any per-star work,
and categorized by `IntrinsicsMappingUnavailableReason` (`PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED`
for `PhysicalSensor`, `ANALYSIS_BUFFER_REFERENCE_MISSING` for `Unspecified`,
`ANALYSIS_BUFFER_DIMENSIONS_MISMATCH` for an `AnalysisBuffer` whose dimensions don't exactly equal
`geometry.frame`'s buffer). Otherwise `PinholeProjectionModel.forGeometry` is computed once per call
(fixed for the whole batch, not once per star) and every star is projected into `Ready`. An empty star
list is `Ready` with an empty `projections` list, not `IntrinsicsMappingUnavailable` — the reference
check runs regardless of input size, but an empty batch is not itself an error.

`Ready`'s primary constructor is `private`; `Ready.of(...)` is the sole public construction path and
stores a **defensive copy** (`List.toList()`) of whatever list it is given, so a caller that later
mutates its own (possibly mutable) source list cannot reach back into an already-returned, documented-
immutable `Ready` result and change it (`StarPredictionBatchResultTest`).

`PredictedStarProjection` carries only: `catalogIndex`, `magnitude`, `classification`,
`cameraDirection` (a plain `CameraDirectionSnapshot`, never null except for `BEHIND_CAMERA`),
`imagePoint`/`displayPoint` (plain `PixelPoint?`). No rotation matrix, no history, no Android type,
no catalog object, no mutable array, no exception text, no device identifier is exposed. Its `init`
enforces there is no publicly-constructible "impossible" combination: `cameraDirection`/`imagePoint`/
`displayPoint` must be all `null` for `BEHIND_CAMERA` and all non-null for every other classification
— this is checked, not just documented, so no direct construction or `copy()` can produce a mismatched
combination (`PredictedStarProjectionTest`). `CameraDirectionSnapshot`'s five scalars are each
validated finite.

`StarPredictionSummary`/`summarizeStarPredictions` (`StarPredictionSummary.kt`) provide a bounded
count-only summary for tests and a future CAM-2b debug overlay — no per-star text, no logging. Every
count is validated non-negative, and the four classification counts' sum is computed in `Long`
specifically so four `Int`-range counts near `Int.MAX_VALUE` cannot silently wrap around into a value
that coincidentally matches `inputCount` (`StarPredictionSummaryTest`).

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
  `projectionParams(viewport)`, `VERTICAL_FOV_DEG = 56.0` are unchanged; no `:mobile` **production**
  code imports this package or calls `projectStars`. (One `:mobile` **test** file,
  `RotationFrameTrueNorthEquivalenceTest`, does import `dev.pointtosky.core.astro.projection.camera.prediction`
  — solely to prove §4.6's equivalence against the real `correctedForTrueNorth`; it is test-only code,
  not a production wiring.)
- **Catalog behavior is unchanged** — PTSKCAT0/PTSKCAT4 assets, `CatalogRepository`, and star-overlay
  selection are untouched; `EquatorialStarDirection` is a standalone input type, not a catalog
  reader.
- **`CameraSessionGeometryProvider` is untouched** — CAM-2a only *consumes* a `CameraSessionGeometry`
  value; it adds no producer, no session ownership, no lifecycle.
- **No Android/hardware dependency was added** — `:core:astro-core` still does not call
  `android.hardware.GeomagneticField` or own any sensor; `magneticDeclinationRad` (§2.3) is a plain
  `Double` the caller supplies.

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
  aware. §6.1's pinhole model projects into the *native, unrotated sensor buffer* grid. §4.5's
  `DisplayAlignedOpticalToBufferOpticalTransform` makes the **basis change** between these two frames
  explicit and internally consistent (closing R12 in the calibration contract's risk register at the
  pure-math level), but it does **not** by itself prove the IMU-derived device axes and the raw
  (pre-`rotationDegrees`) buffer's pixel grid are actually co-registered on a given device — that is
  exactly the kind of alignment `docs/camera_coordinate_calibration_contract.md` §9.9/§9.10 (CAM-1e)
  already flags as unverified without a physical marker/grid test, and remains open. §4.5's
  self-consistent basis-composition tests (`CameraStarProjectionTest`, `PredictedStarClassificationTest`)
  prove only that this transform and `CropScaleTransform`'s forward rotation compose correctly for
  *some* self-consistent `(attitude, frame.rotationDegrees)` pairing built by algebraically composing
  two already-trusted formulas — they do not model, and are not a claim about, the camera sensor's own
  mounting orientation (`CameraCharacteristics.SENSOR_ORIENTATION`) or how CameraX actually derives
  `ImageProxy.imageInfo.rotationDegrees` from it in combination with the target/display rotation.
  Whether any real device reports the specific pairing these tests use remains unverified and is a
  distinct, still-open question from the pure-math correctness these tests do establish.
- **Physical-sensor vs. analysis-buffer intrinsics.** §6.2's `CameraIntrinsicsReference` makes the
  distinction between a physical-sensor-referenced calibrated FOV and an analysis-buffer-referenced one
  explicit and dimension-bearing, and `forGeometry`/`projectStars` refuse to silently mix them or reuse
  a stale/mismatched buffer size (closing R13 in the calibration contract's risk register). It does
  **not** add the crop/scale metadata that would let a physical-sensor intrinsics value ever be
  *safely* mapped onto the analysis buffer — a device with only `CAMERA_CHARACTERISTICS`-sourced
  intrinsics will get `IntrinsicsMappingUnavailable` from this PR onward, not a (possibly wrong)
  projection. Closing that gap for real would require CameraX crop/scale metadata this codebase does
  not currently capture.
- **Camera-to-IMU extrinsic offset**, assumed zero (mirrors the existing CAM-1 assumption,
  `docs/camera_coordinate_calibration_contract.md` §3.3): any physical tilt between the lens axis and
  the IMU is not modeled.
- **Lens distortion**, not modeled (pinhole only, matching CAM-1's own deferred scope).
- **Principal point**, always defaulted to the buffer center in practice, since CAM-1b never
  populates a real one (`LENS_INTRINSIC_CALIBRATION` is deliberately unparsed).
- **Magnetic declination is a caller-supplied number, not a caller-verified one.** §2.3/§4.6 fix the
  *algebra* (feeding a true-north sky vector through a magnetic-referenced sensor matrix without
  correction) — this is the same risk class as `docs/camera_coordinate_calibration_contract.md`'s R3
  ("Magnetic vs true North"), now closed for CAM-2a's own pipeline at the pure-math level. It does
  **not** verify that whatever `magneticDeclinationRad` a caller supplies is the *correct* real-world
  value for that place and time — CAM-2a has no way to check that, by design (§6). A caller that
  passes a stale, wrong, or silently-defaulted-to-zero declination in a production context gets a
  self-consistent but *actually wrong* prediction, with no signal from this package that anything is
  off; only a future mobile integration that supplies (or explicitly reports the absence of) a real
  declination closes this for good, and that integration is unimplemented and physical-device-gated
  like everything else here.

None of these change while CAM-2a stays an unwired pure-math slice; they are recorded here so CAM-2b
(or whoever runs the first physical-device pass) knows exactly what is still unverified.

## 12. Next slice: CAM-2b

CAM-2a produces `PredictedStarProjection` values but nothing consumes them. The natural next step
(**not** part of this PR) is a debug-only prediction overlay in `:mobile`, analogous to CAM-1g's
diagnostic overlay: gated the same way (`internalDebug` only), reading `projectStars(...)` output
purely for display, never feeding `calculateOverlay`/`projectionParams` or any production star
position. That slice would also be the first opportunity to gather a physical-device comparison
between predicted and legacy-rendered positions — still not a matcher, just a visual cross-check.

**Caller contract for `magneticDeclinationRad` (§2.3).** CAM-2a is a pure math slice and must never
compute a real magnetic declination itself — that requires a platform magnetic model
(`android.hardware.GeomagneticField`, driven by latitude, longitude, altitude if available, and UTC
time), an Android/hardware dependency this module must not take on. A future mobile consumer (CAM-2b
or later) is responsible for supplying the real value — the legacy renderer already computes exactly
this (`ArScreen.kt`'s `GeomagneticField(state.location.latDeg, state.location.lonDeg, 0f,
System.currentTimeMillis()).declination`) and should feed the same computation into CAM-2a's
`StarProjectionContext.of(..., magneticDeclinationRad = ...)` rather than deriving a second,
independent value. **Production visual alignment must not silently assume `0°` declination when the
actual value is unknown** — that integration should either supply the real value or expose an explicit
diagnostic/unavailable/uncorrected quality state (mirroring how `CameraIntrinsicsReference`/
`StarPredictionBatchResult.IntrinsicsMappingUnavailable`, §6.2/§8, already refuse to silently fabricate
an intrinsics mapping rather than guess one). Implementing that mobile-side state is out of scope for
this fix; CAM-2a's own default (`0.0`, an explicit "treat magnetic north as true north" mode) exists
only to keep every existing pure test/caller deterministic, never as a production correctness claim.
Do not wire a hidden global declination source (a singleton, a `System` property, a compile-time
constant) into any future integration — declination varies by location and time and must flow through
`StarProjectionContext` like every other observer-dependent input.

## 13. Tests

| File | Section | Covers |
|---|---|---|
| `AngleWrapTest` | — | `wrapRadTwoPi`/`wrapRadMinusPiToPi` wraparound at exact and near-boundary values |
| `EquatorialStarDirectionTest` | F | invariants, canonical-storage-only construction (`of` is the sole public path), many-turn RA, equivalent-canonical equality |
| `StarProjectionContextTest` | F | invariants, canonical-storage-only construction, many-turn longitude, equivalent-canonical equality; §2.3 `magneticDeclinationRad` default/validation/canonical-wrap and independence from the other fields |
| `EquatorialToLocalSkyTest` | A | zenith, meridian transit, east/west hour-angle sign, longitude east-positive, RA wraparound continuity, pole-adjacent declinations, unit-length/finite sweep |
| `CameraStarProjectionTest` | B, E, F | literal-matrix rotation anchors (identity/yaw/pitch/180°, transpose-vs-direct distinguishing); self-consistent basis-composition tests (§4.5: same-forward-is-center, device-relative right/up anchors, pre-fix double-rotation regression guard); 12 end-to-end synthetic cases (`E1`-`E12`, including `E8b`-`E8e` for the intrinsics-reference categories, `E11` for the off-axis magnetic-declination display shift, `E12` for the zero-declination unchanged-output guarantee); defensive invalid-input and no-NaN/Infinity sweep |
| `DisplayAlignedOpticalToBufferOpticalTransformTest` | — | §4.5 literal mapping table for all four rotations, `z` invariance, 90°/270° mutual inverse, 0°/180° self-inverse, invalid-`rotationDegrees` rejection |
| `TrueNorthToMagneticNorthTransformTest` | §4.6 | zero declination is a bit-for-bit identity (North/East/an arbitrary vector); literal `±30°` cardinal expected vectors (North, East); unit-length preservation across several directions/declinations; finite-input/output validation; the `Rᵀ·trueEnuToMagneticEnu(v,d) ≈ correctedForTrueNorth(R,d)ᵀ·v` equivalence against a line-for-line ported reimplementation of the production algebra, across 3 matrices × 6 directions × 5 declinations, plus a sign-reversal regression guard |
| `CameraIntrinsicsTest` | — | FOV/physical-dimension/principal-point invariants; §6.2 `CameraIntrinsicsReference.AnalysisBuffer`'s own positive-dimension validation; `source`/`reference` cross-field consistency (both accepted and rejected combinations) |
| `LegacyFallbackCameraIntrinsicsTest` | — | §6.2 FOV derivation (aspect-derived vs. square-default); the *real* `legacyFallbackCameraIntrinsics` factory's `reference` provenance — known dimensions → `AnalysisBuffer` with those exact dimensions, unknown/invalid → `Unspecified`, distinct dimensions → distinct (never-reused) references |
| `PinholeProjectionModelTest` | C | worked-example arithmetic, edge/just-outside frustum, custom principal point, `forGeometry` exact-match analysis-buffer/legacy-fallback derivation; §6.2 `forGeometry` throws for `PhysicalSensor`, `Unspecified`, a width mismatch, a height mismatch, a same-aspect-ratio-different-size mismatch, and a different-aspect-ratio mismatch |
| `PredictedStarClassificationTest` | D | full crop, non-zero crop origin, outside-crop, horizontal/vertical `FILL_CENTER` exclusion, inclusive edge policy, self-consistent basis-composition classification test for all four rotations |
| `PredictedStarProjectionTest` | — | `BEHIND_CAMERA`/other-classification null-vs-non-null invariant (both directions), partial-combination rejection, scalar validation, `CameraDirectionSnapshot` finiteness |
| `StarPredictionSummaryTest` | — | count-sum invariant, non-negative counts (each field), `Long`-sum overflow safety |
| `StarPredictionBatchResultTest` | §8 | `Ready.of`'s defensive-copy guarantee (mutating the source list afterward, appending to it, and the returned list never being the same instance), input-order preservation, content-equality, empty-list handling |
| `DeviceToOpticalCameraTransformTest` | — | fixed sign-flip, its own inverse |

`:mobile` (not run in this sandbox — see below):

| File | Covers |
|---|---|
| `RotationFrameTrueNorthEquivalenceTest` | §4.6's equivalence proven against the **actual production** `RotationFrame.correctedForTrueNorth`, not a reimplementation — the strongest evidence for this fix, since `:core:astro-core` cannot call `:mobile` code itself; includes the same sign-reversal regression guard and a zero-declination no-op check |

Commands run (see the PR description for full output):

```bash
./gradlew :core:astro-core:test --tests "*EquatorialToLocalSkyTest"
./gradlew :core:astro-core:test --tests "*CameraStarProjectionTest"
./gradlew :core:astro-core:test --tests "*DisplayAlignedOpticalToBufferOpticalTransformTest"
./gradlew :core:astro-core:test --tests "*TrueNorth*Test"
./gradlew :core:astro-core:test --tests "*CameraIntrinsics*Test"
./gradlew :core:astro-core:test --tests "*PinholeProjectionModelTest"
./gradlew :core:astro-core:test --tests "*PredictedStarClassificationTest"
./gradlew :core:astro-core:test --tests "*PredictedStarProjectionTest"
./gradlew :core:astro-core:test --tests "*StarProjectionContextTest"
./gradlew :core:astro-core:test --tests "*StarPredictionSummaryTest"
./gradlew :core:astro-core:test --tests "*StarPredictionBatchResultTest"
./gradlew :core:astro-core:test --tests "*SessionScopedCameraIntrinsicsResolverTest"
./gradlew :core:astro-core:test
./gradlew :mobile:testInternalDebugUnitTest --tests "*CameraSessionIntrinsicsCoordinatorTest"
./gradlew :mobile:testInternalDebugUnitTest --tests "*RotationFrame*Test"
./gradlew :mobile:testInternalDebugUnitTest --tests "*TrueNorth*Test"
./gradlew :mobile:testInternalDebugUnitTest
./gradlew :mobile:testPublicDebugUnitTest
./gradlew :mobile:lintInternalDebug
./gradlew :mobile:assembleInternalDebug
```

**Gradle itself could not run in the authoring sandbox** — no JDK 17 is installed, and both the
Gradle-wrapper distribution download and a foojay-resolver JDK 17 auto-provision attempt were
blocked by the sandbox's egress policy (GitHub release-asset downloads return `403`). All 388 tests
above were verified instead with the Kotlin compiler (`kotlin-compiler-embeddable:2.0.20`, matching
the project's pinned Kotlin version) invoked directly against JDK 21 with `-jvm-target 17`, and run
with the JUnit Platform Console Launcher (`junit-platform-console-standalone:1.10.2`,
`junit-jupiter:5.10.2`, matching the project's pinned JUnit version) — the same compiler, target
bytecode version, and test versions Gradle itself would use, just invoked without Gradle's build
graph. This covers `:core:astro-core:test` only — the `:mobile` Gradle tasks (`testInternalDebugUnitTest`
— which is also where `SessionScopedCameraIntrinsicsResolverTest`/`CameraSessionIntrinsicsCoordinatorTest`/
`RotationFrameTrueNorthEquivalenceTest` actually live, all under `:mobile`, not `:core:astro-core` —
`testPublicDebugUnitTest`, `lintInternalDebug`, `assembleInternalDebug`) require the Android Gradle
Plugin/SDK and were **not** run or approximated in this sandbox (no `android.jar`/CameraX AAR classpath
was assembled either). This PR's other `:mobile` changes are unmodified from before this fix (no
`:mobile` source was touched by the magnetic-declination fix itself, beyond the new
`RotationFrameTrueNorthEquivalenceTest.kt` file) — that new file was reviewed carefully by hand
(package/visibility/API-shape checked against the real `RotationFrame`/`correctedForTrueNorth`
declarations it calls) but its actual compilation is **not** verified, since assembling an
Android SDK/CameraX classpath for a standalone `kotlinc` run was not attempted. This is disclosed, not
hidden: whoever next has a working Gradle/JDK-17/Android-SDK environment should still run the exact
commands above before treating this gate as closed.

---

## 14. CAM-2b: internal-debug predicted-star overlay (separate PR)

CAM-2b is the "next slice" §12 described: an `internalDebug`-only diagnostic *consumer* of this
package's `projectStars(...)` output, wired into `ArScreen` purely for visual diagnosis. It adds no
code to `:core:astro-core` — everything below lives in `:mobile`, package
`dev.pointtosky.mobile.ar.camera.prediction` (plus two small Compose files in `dev.pointtosky.mobile.ar`).

### 14.1 Scope discipline (unchanged from §1/§9)

CAM-2b **visualizes** CAM-2a; it does not extend it. Every "what this is not" bullet in §1 still holds
verbatim: no pixels read, no detector, no matcher, no pose correction, no renderer change to
`ArScreen.calculateOverlay()`/`projectionParams(viewport)`/`VERTICAL_FOV_DEG = 56.0` (still
byte-for-byte unchanged — CAM-2b draws its own, separate, clearly-distinguishable marker layer on top,
never routing CAM-2a output into `calculateOverlay`'s `OverlayData` or any production star position).

### 14.2 Build gate — reused, not reinvented

CAM-2b does **not** introduce a second flavor check. It reuses
`dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticsGate`/`isDiagnosticsEnabled(debug, flavor)`
verbatim — the exact same pure function and object CAM-1g already established
(`docs/camera_coordinate_calibration_contract.md` §11.2). `PredictedStarOverlayReducerTest`'s gate
truth-table test calls `isDiagnosticsEnabled` directly across all four `(debug, flavor)` combinations
and asserts the reducer agrees: `debug+internal → enabled`, every other combination → `Disabled`.

### 14.3 Bounded diagnostic input adapter

`selectPredictedStarDirections(stars: List<StarRecord>, maxCount: Int = 200): List<EquatorialStarDirection>`
(`PredictedStarCatalogAdapter.kt`) is the one place a mobile-side `StarRecord` (the same catalog record
`ArViewModel`/`ArScreen`'s legacy renderer already reads via `PtskCatalogLoader`/`AstroCatalog`) becomes
a CAM-2a `EquatorialStarDirection`:

- **Input is expected to already be the phone's visibility-selected prefix** — `ArScreen.kt`'s
  `visibilitySelectedStars(state)` (extracted, in this PR, from `calculateOverlay`'s previously-inline
  `visibleStars` computation into a shared function both the legacy renderer and CAM-2b now call) —
  never the full ~42k-star catalog read independently. `calculateOverlay`'s own output is byte-for-byte
  unchanged by this extraction (same two filters, same order).
- Sorts that prefix brightest-first (ascending magnitude, stable sort — ties keep catalog order), then
  takes the first `maxCount` — deterministic, no spatial indexing.
- Converts RA/Dec from degrees to radians exactly once, here.
- Preserves a **stable** `catalogIndex` — each star's own `StarId.raw`, not a positional index that
  would shift if the selection changed.
- Passes `magnitude` through when present; carries no name/label.
- Performs no sorting inside CAM-2a itself — `projectStars`'s own input-order-preservation contract
  (§8) is unaffected; the adapter's sort/bound happens entirely before `projectStars` is ever called.

`PREDICTED_STAR_OVERLAY_MAX_INPUT_STARS = 200` sits inside the task's suggested 100–300 range: the
legacy renderer's own (production, non-diagnostic) `StarPointLayer` already caps its Canvas point layer
at `MAX_STAR_POINTS = 1500` without a measured performance issue, so 200 diagnostic markers plus a
status panel is comfortably inside the existing per-frame Canvas budget, while staying small enough
that individual predicted markers stay visually distinguishable from the legacy overlay during a
physical-device side-by-side comparison — the whole point of this slice.

### 14.4 Observer/time/declination — reused, not duplicated

`ArScreen.kt` supplies:

- **Location:** `state.location.latDeg`/`lonDeg` (the same `ArUiState.Ready` field the legacy renderer
  reads), only when `state.locationResolved` is true — otherwise CAM-2b reports
  `ObserverLocationUnavailable` rather than silently projecting from the `(0, 0)` default placeholder
  location.
- **Time:** `state.instant.toEpochMilli()` — the exact `Instant` the sky-render state (`lstDeg`, the
  legacy `calculateOverlay`, etc.) is already built from, sourced from `ArViewModel`'s
  `SystemTimeSource` ticks. CAM-2b never calls `System.currentTimeMillis()` independently.
- **Magnetic declination:** the exact `declinationDeg` local value `ArScreen.kt` already computes once
  per `state.location` change for the legacy renderer's own `correctedForTrueNorth(declinationDeg)` call
  (`android.hardware.GeomagneticField(...).declination`) — read, not recomputed. CAM-2b constructs no
  second `GeomagneticField` instance. If that value were ever non-finite, the reducer reports
  `MagneticDeclinationUnavailable` rather than silently substituting `0.0` — see §2.3's own warning
  against exactly that failure mode.

Degrees are converted to radians exactly once, inside `reducePredictedStarOverlayState`, immediately
before `StarProjectionContext.of(...)` is called.

### 14.5 Raw-rotation / context-declination ownership (closing §12's own caller-contract note)

`reducePredictedStarOverlayState` (`PredictedStarOverlayReducer.kt`) receives a
`CameraSessionGeometryResult` exactly as published by `CameraSessionGeometryProvider` — the same
provider/`observation` `ArScreen` already collects for CAM-1g, reused here rather than re-collected,
re-paired, or interpolated. `geometryResult.geometry.pairedRotation.rotationMatrix` is passed into
`projectStars` **unmodified** — never run through `RotationFrame.correctedForTrueNorth` on the mobile
side — while the declination described in §14.4 is passed as
`StarProjectionContext.magneticDeclinationRad`. This is the single split CAM-2a's own contract requires
(§2.3, §4.6, §12's caller-contract note): raw paired rotation **+** context declination, never a
corrected matrix **+** a non-zero context declination together (which would double-correct true north).
`reducePredictedStarOverlayState`'s KDoc states this explicitly, and
`PredictedStarOverlayReducerTest`'s dedicated test cross-checks the reducer's `Ready` output against an
independently-constructed `projectStars` call over the *same* raw geometry and a
`StarProjectionContext` built with a non-zero declination, proving no extra correction is silently
applied before `projectStars` is called.

### 14.6 Diagnostic state and recomputation policy

`PredictedStarOverlayState` (`PredictedStarOverlayState.kt`) is a small, immutable sealed interface —
`Disabled` / `Waiting(reason)` / `Ready(points, summary, metadata)` / `Unavailable(reason)` — built
exactly once per coherent recomputation by the pure `reducePredictedStarOverlayState`, called from
Compose `remember` (`ArScreen.kt`) keyed on the current geometry observation, `state.locationResolved`/
`state.location`/`state.instant`, `declinationDeg`, and the bounded star subset (itself `remember`ed
separately, keyed only on the loaded catalog and `state.effectiveMagLimit`, so it is *not* rebuilt every
frame just because geometry changed). No polling, no timer, no permanently launched coroutine, no
unbounded queue — the projection itself runs synchronously inside `remember`'s calculation block, which
CAM-2a's own §1 performance profile (a few hundred stars of pure arithmetic, no I/O) supports without
moving off the main thread.

Every non-`Ready` `CameraSessionGeometryResult` maps to a `Waiting(WaitingReason.GeometryNotReady(category))`,
reusing CAM-1g's own `CameraGeometryDiagnosticCategory` mapping (`toDiagnosticCategory()`) rather than a
second, parallel categorization — including `DISPOSED`, so a disposed or replaced geometry provider (a
new camera session's first recompute, before its own first coherent geometry) reports `Waiting`, never
an echo of a previous session's `Ready` points; the reducer is a pure function with no retained state
across calls, so there is no code path that *could* carry a stale bundle forward.
`StarPredictionBatchResult.IntrinsicsMappingUnavailable`'s `IntrinsicsMappingUnavailableReason` is
surfaced as `PredictedStarOverlayState.Unavailable(reason)` directly — reused, not re-wrapped in a
second enum.

### 14.7 Drawing and diagnostic panel

`toOverlayPoints` (`PredictedStarOverlayReducer.kt`) — the pure mapping from a full CAM-2a batch to
drawable points — keeps only `PredictedStarClassification.VISIBLE_IN_VIEWPORT` projections, in their
original batch order, and copies `PredictedStarProjection.displayPoint.x`/`.y` verbatim into
`PredictedStarOverlayPoint.displayX`/`.displayY` — no additional scale, rotation, crop offset, or
rounding; CAM-2a already returns final display coordinates. `PredictedStarOverlayMetadata`'s
`summary`/counts, by contrast, always describe the **full** batch (including `BEHIND_CAMERA`/
`OUTSIDE_IMAGE`/`INSIDE_IMAGE_OUTSIDE_VIEWPORT` counts), never just the drawn subset.

`PredictedStarMarkersCanvas` (`dev.pointtosky.mobile.ar`) draws each point as a hollow circle plus a
small cross, radius bounded to a tight magnitude-dependent range, in a fixed cyan
(`Color(0xFF00E5FF)`) diagnostic color deliberately distinct from the legacy overlay's `BvColor`-toned
filled dots and white reticle/labels — drawn as an additional layer *on top of* the existing
`StarPointLayer`/`ConstellationLayer` output within the same `ArUiState.Ready` composition branch, never
replacing it, so both can be visually compared in the same frame. No star textures, no names, no
constellation art, no touch interaction. `PredictedStarOverlayPanel` renders a compact, bounded,
non-interactive status block (input/visible/behind-camera/outside-image/inside-image-outside-viewport
counts, frame/rotation timestamps, pair delta, `frame.rotationDegrees`, intrinsics source/reference,
declination, and status/reason) — one block, never one line per star, never logged every frame.
`PredictedStarOverlayControls` adds two session-local (non-persisted) toggles, "Show predicted stars"
and "Show CAM-2b panel", defaulting to visible so an `internalDebug` tester sees the overlay
immediately; both are internal-debug-only and never reachable from a public-facing setting.

### 14.8 Scope confirmation

Exactly the same confirmations §9 already makes for CAM-2a still hold, plus: no `:mobile` **production**
code path draws CAM-2a output anywhere except this one `internalDebug`-gated overlay; `ArScreen`'s
`calculateOverlay`/`projectionParams`/legacy star-point/constellation rendering are unmodified (the
only refactor is extracting the pre-existing `visibleStars` filter into `visibilitySelectedStars`, a
byte-for-byte-equivalent, shared, tested computation); no new `CameraSessionGeometryProvider`,
`CameraTimestampSynchronizer`, or intrinsics resolver/coordinator instance was created — CAM-2b reads
the one `geometryProvider.observation` `ArScreen` already collects for CAM-1g; the catalog asset format,
`PtskCatalogLoader`, and `AstroCatalog` are untouched; no spatial index, persistence, analytics, or
network call was added.

### 14.9 Tests

`:core:astro-core` JVM tests (batched-astronomy hardening, §14.12; exact-preservation intrinsics
substitution, §14.11) — **executed** via §14.10's `kotlinc` workaround, 398/398 passing:

| File | Covers |
|---|---|
| `PreparedStarProjectionContextTest` | `prepareStarProjectionContext` matches a manual per-field computation; the public 2-arg `equatorialToLocalSky(star, context)` delegates to the 3-arg prepared overload without changing output; a multi-star batch applies one shared prepared LST value consistently to every star (cross-checked against calling `projectStars` once per star) |
| `CameraSessionGeometryTest` (follow-up additions) | `CameraSessionGeometry.withIntrinsics` (§14.11): changes only `intrinsics` — `frame`, `pairedRotation` values, `frameRotationDeltaNanos`, `cropScaleTransform`, and `viewportSize` are all preserved exactly across a nontrivial 25 ms configured-tolerance/3 ms actual-delta scenario; the original geometry is never mutated; a negative `frameRotationDeltaNanos` survives exactly (never flipped positive by an `abs()` derivation); defensive rotation-matrix ownership — mutating the caller's source array, a value read from the original geometry, or a value read from the replaced geometry never affects either geometry, and the two never share the same array instance |

`:mobile` JVM tests (`src/test/java/dev/pointtosky/mobile/ar/camera/prediction/`) — **executed** via
§14.10's `kotlinc` workaround (JUnit4/`kotlin-test-junit` binding, matching `:mobile`'s real Gradle
configuration), 48/48 passing:

| File | Covers |
|---|---|
| `PredictedStarCatalogAdapterTest` | stable catalog index, exactly-once degree→radian conversion, magnitude pass-through, deterministic bounded/brightest-first selection, empty/zero-bound edge cases |
| `PredictedStarOverlayReducerTest` | gate truth table (§14.2); every non-`Ready` `CameraSessionGeometryResult` → `Waiting`; every `IntrinsicsMappingUnavailableReason` → `Unavailable`; `toOverlayPoints` classification filtering, exact coordinate pass-through, order preservation; full-batch summary counters; raw-rotation/context-declination ownership (§14.5) cross-check; disposal/session-reset statelessness; explicit intrinsics-mode behavior (§14.11) — session mode preserves `PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED`, diagnostic fallback becomes `Ready` with an exact-dimension `AnalysisBuffer` projection reference at several frame sizes, the original session geometry is never mutated, the default mode is `SESSION_INTRINSICS`, and the panel text distinguishes the two modes without ever calling the fallback "calibrated"; (follow-up additions) diagnostic-fallback metadata preserves the session geometry's frame/rotation timestamps, actual pair delta (including a negative delta, exactly), and `frame.rotationDegrees` across a 25 ms configured-tolerance/3 ms actual-delta scenario, and produces byte-identical points/summary/sync-metadata to an equivalent session-mode geometry |
| `PredictedStarOverlayFormatTest` | bounded, deterministic panel text — every required field present, never one line per star; session-mode single intrinsics line vs. diagnostic-fallback dual session/projection lines, never labeled calibrated |

`:mobile` Compose UI tests (`src/androidTest/java/dev/pointtosky/mobile/ar/PredictedStarOverlayUiTest.kt`,
not run in this sandbox — no Android SDK/emulator available, see §14.10): a pure (non-Compose) check that
the `Ready` test fixture carries exactly 3 points (the fact backing the marker-canvas-presence assertion
below — a Compose `Canvas` is one semantics node regardless of how many circles it draws internally, so
marker *count* is never claimed via semantics-node counting); waiting-state panel content with no marker
canvas present; `Unavailable`'s categorized reason text with no marker canvas present; `Ready`'s
single-marker-canvas-node presence and panel counts; and state replacement (`Ready` → `Waiting(DISPOSED)`)
via a small `PredictedStarOverlayTestHost` that conditionally renders the marker canvas exactly as
`ArScreen.kt` does, asserting the canvas node count itself drops to `0` (stale marker-*layer* removal,
not just panel-text replacement) and that the panel no longer contains the prior `Ready` state's counts.
Not pixel-perfect screenshot tests — this repository does not use those.

### 14.10 Validation status

**Superseded — real Gradle now runs.** The paragraphs below (kept for history) described a sandbox where
neither the Android SDK nor a working JDK-17 Gradle toolchain were available, and only a `kotlinc`/
JUnit-console workaround could be exercised. A later validation-closure session provisioned an Android
SDK (`platform-tools`, `platforms;android-35`, `build-tools;35.0.0`) and `openjdk-17-jdk-headless` inside
its own environment, substituted the preinstalled Gradle 8.14.3 for the project's pinned 8.11.1 (whose
download is blocked by that environment's egress policy — the redirect target is a `github.com` release
asset, not `services.gradle.org` itself), and ran the **real** Gradle gates end to end. Every gate is now
either an executed PASS/FAIL or an explicitly recorded NOT RUN — never collapsed into one claim:

- **`:core:astro-core:test`** — **PASS, 388/388**, 0 failures/errors (`CameraSessionGeometryTest` 34,
  `PreparedStarProjectionContextTest` 3, `CameraStarProjectionTest` 35, each individually re-run via
  `--tests` too).
- **`:mobile:compileInternalDebugKotlin` / `compilePublicDebugKotlin`** — **PASS** (two pre-existing
  warnings only: a deprecated `Display` API and a missing coroutines `@OptIn`, neither in CAM-2b code).
- **`:mobile:testInternalDebugUnitTest` / `testPublicDebugUnitTest`** — **PASS, 271/271 each**, 0
  failures/errors (`PredictedStarOverlayReducerTest` 32, `PredictedStarOverlayFormatTest` 6,
  `PredictedStarCatalogAdapterTest` 10, each individually re-run via `--tests` too). `CameraGeometryDiagnosticsGateTest`
  is included and passing, confirming the real `BuildConfig`-backed flavor gate (no stand-in object was
  needed this time — the real AGP-generated `BuildConfig` compiled and ran for both flavors).
- **`:mobile:compileInternalDebugAndroidTestKotlin` / `compilePublicDebugAndroidTestKotlin`** —
  **initially FAILED**, a real and reproducible (identical on both flavors) compile error, but **not** in
  any CAM-2b file: `PredictedStarOverlayUiTest.kt` compiled cleanly on the first attempt, using exactly
  the imports the task calls for (`androidx.compose.ui.test.assert`, `org.junit.Assert.assertEquals`). The
  failure was in the pre-existing, unrelated `PreProdSmokeMobileTest.kt`: (1) `import
  androidx.compose.ui.test.assertExists` does not resolve against the resolved Compose UI Test 1.7.2,
  where `assertExists` is a member of `SemanticsNodeInteraction`, not a top-level package function; (2)
  `MobileSettings.from(context)` (an extension function on `MobileSettings.Companion` declared in
  `MobileSettingsDataStore.kt`) needs its own explicit import — `import
  dev.pointtosky.mobile.settings.from` — the same way `MainActivity.kt`/`DlReceiverService.kt` already do
  it, and this file was simply missing it. Fixed with a 2-line import change (delete one, add the other);
  no CAM-2b file, no production renderer, and no test *behavior* was touched. Rerun: **PASS** for both
  flavors.
- **`:mobile:lintInternalDebug` / `lintPublicDebug`** — **PASS, 0 errors** (35 pre-existing warnings, 148
  errors/54 warnings pre-filtered by the repo's existing `lint-baseline.xml`; none attributable to CAM-2b).
- **`:mobile:assembleInternalDebug` / `assemblePublicDebug`** — **PASS**, both debug APKs built
  (`mobile-internal-debug.apk`, `mobile-public-debug.apk`).
- **`:mobile:assembleInternalRelease` / `assemblePublicRelease`** — **blocked on signing, not code.**
  `compileInternalReleaseKotlin` / `compilePublicReleaseKotlin` — **PASS**. Full assembly fails at
  `packageInternalRelease`: `SigningConfig "release" is missing required property "storeFile"` — no
  release keystore is configured in this environment (`P2S_MOBILE_KEYSTORE_PATH` unset), a
  credential-provisioning gap, not a defect. One attempt separately hit a non-reproducible
  `ConcurrentModificationException` inside AGP's *bundled* Lint tool (`GradleDetector`/`TomlUtilities`,
  during `lintVitalAnalyzeInternalRelease`) that did not recur on retry and does not occur on the
  debug-variant lint gates against the identical `build.gradle.kts` — recorded as an observed upstream
  tooling flake, not a CAM-2b defect and out of scope to patch around.
- **Connected instrumentation tests** (`:mobile:connectedInternalDebugAndroidTest`,
  `PredictedStarOverlayUiTest`): **NOT RUN** — no physical device and no emulator are available in this
  environment (`adb devices -l` lists none; the container has neither `/dev/kvm` nor CPU
  hardware-virtualization flags, so an AVD emulator is not feasible either). The instrumentation source
  compiles (see above) but its four cases (waiting/unavailable/ready/disposed-transition) have not been
  observed executing.

Every file touched by CAM-2b (§14.1–§14.8, §14.11, §14.12) now has real, executed, green Gradle results —
compilation, unit tests, lint, and debug assembly are no longer "corroborating evidence" from a bare
compiler invocation but the actual build graph, including dependency resolution, resource merging, and
manifest merging. The one gate that could not be closed is the physical/emulator-only one: connected
instrumentation tests and the full device checklist in `docs/validation/cam_2b_device_validation.md`.
Final status: **`CAM-2b BLOCKED ON PHYSICAL DEVICE VALIDATION`** — build gates are now sufficiently
verified; only a physical device or emulator run remains.

<details>
<summary>Original (superseded) disclosure, kept for history</summary>

Gradle itself could not run in the original authoring sandbox — no Android SDK was installed, and
`:core:astro-core`'s pinned JDK-17 Gradle toolchain could not be downloaded (egress-blocked), the
identical blocker CAM-2a's own authoring sandbox hit (§13, end). That revision got real, executed (not
fabricated) test results for two of four gates by invoking the Kotlin compiler
(`kotlin-compiler-embeddable:2.0.20`) directly against JDK 21 with `-jvm-target 17`, and running the
compiled tests with the JUnit Platform Console Launcher: `:core:astro-core` main+test — 398/398 passing;
`:mobile`'s CAM-2b prediction-package Android-free subset — 48/48 passing (one disclosed stub
`BuildConfig` object stood in for the AGP-generated one). `:mobile` Compose/Android compilation,
`androidTest` compilation, lint, assemble, and connected instrumentation tests were explicitly **not**
attempted via that workaround. See the real-Gradle results above for the superseding, actually-executed
numbers for every one of those gates except connected instrumentation.

</details>

### 14.11 Explicit intrinsics mode: session vs. diagnostic analysis-buffer fallback

**The problem this closes.** §6.2's `CameraIntrinsicsReference.PhysicalSensor` gate is correct and
unweakened by this section — `projectStars` still refuses to project a physical-sensor-referenced FOV
directly, since no active-array/crop mapping to the `ImageAnalysis` buffer exists in this codebase. But
the production session resolver commonly *does* successfully resolve real `CAMERA_CHARACTERISTICS`
data, which is always `PhysicalSensor`-referenced (`CameraIntrinsics`'s own cross-field rule, §6.2).
Without an alternative, CAM-2b would permanently report
`Unavailable(PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED)` on many real devices and never draw a single
marker — defeating its diagnostic purpose entirely.

**`PredictedStarOverlayIntrinsicsMode`** (`PredictedStarOverlayIntrinsicsFallback.kt`, `:mobile`):

```kotlin
enum class PredictedStarOverlayIntrinsicsMode {
    SESSION_INTRINSICS,
    DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK,
}
```

- **`SESSION_INTRINSICS`** (the default — see `ArScreen.kt`'s `remember { mutableStateOf(...) }`) uses
  `geometryResult`'s geometry exactly as CAM-1f/1g published it; a `PhysicalSensor` session reference
  still reports `Unavailable(PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED)`, unchanged. This is the
  safest, most explicit choice: no substitution happens unless a tester deliberately opts in.
- **`DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK`** builds `legacyFallbackCameraIntrinsics(imageWidthPx =
  geometry.frame.bufferWidthPx, imageHeightPx = geometry.frame.bufferHeightPx)` — sized to the *current*
  analyzed frame's exact dimensions, never a cached or guessed size — and projects with that instead.

**Constructing the derived geometry without mutating anything.** `CameraSessionGeometry` has no public
`copy(...)` (its sole construction path, `CameraSessionGeometry.of`, is `internal` to
`:core:astro-core`), so the substitution is implemented *inside* `:core:astro-core`, beside that
construction path, as a focused extension function:

```kotlin
fun CameraSessionGeometry.withIntrinsics(intrinsics: CameraIntrinsicsResolution): CameraSessionGeometry
```

(`CameraSessionGeometry.kt`). `withIntrinsics` copies the accepted, already-invariant-checked geometry
bundle exactly — `frame`, `pairedRotation`, `frameRotationDeltaNanos`, `cropScaleTransform`, and
`viewportSize` are all passed straight through to `CameraSessionGeometry.of` — and substitutes only
`intrinsics`. It never reconstructs a `FrameRotationPairingResult`, never calls
`pairFrameToNearestRotation`, and never reads, derives, or checks a pairing tolerance at all — there is no
tolerance parameter on its signature to conflate with the accepted delta, so a caller's originally
configured tolerance (which `CameraSessionGeometry` never stores as a field in the first place — see its
own KDoc, "pairing-tolerance conformance is not an invariant of this class") can never be corrupted by
this call. Because `CameraSessionGeometry.init` validates only the cross-field relationships between
`frame`/`cropScaleTransform`/`viewportSize`/`frameRotationDeltaNanos` — never anything about `intrinsics`
— and none of those fields change here, `withIntrinsics` **cannot fail**: it returns
`CameraSessionGeometry` directly, never a `CameraSessionGeometryResult` wrapper, and there is no
`DiagnosticFallbackGeometryUnavailable`-style waiting state for this mode. This never touches
`CameraSessionGeometryProvider`, `SessionScopedCameraIntrinsicsResolver`, the original geometry
observation, or any production intrinsics resolution — only a fresh, independent `CameraSessionGeometry`
is returned, and the original is never mutated.

**Never silently switched, never labeled calibrated.** `reducePredictedStarOverlayState` never chooses a
mode automatically — whichever `intrinsicsMode` the caller supplies is exactly what runs, every time.
`PredictedStarOverlayMetadata` carries `intrinsicsMode` plus **two** distinct intrinsics-description
pairs: `sessionIntrinsicsSource`/`sessionIntrinsicsReference` (always the real session intrinsics) and
`projectionIntrinsicsSource`/`projectionIntrinsicsReference` (whichever intrinsics were actually used to
project — identical to the session pair for `SESSION_INTRINSICS`, the substituted fallback for
`DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK`). The panel (`buildPredictedStarOverlayDiagnosticText`) always
shows `intrinsics mode: session` or `intrinsics mode: diagnostic fallback`, and in fallback mode shows
both lines side by side:

```text
intrinsics mode: diagnostic fallback
session intrinsics: CAMERA_CHARACTERISTICS / PhysicalSensor
projection intrinsics: LEGACY_FALLBACK / AnalysisBuffer(1920x1080)
```

— never "calibrated," never "resolved physical intrinsics." `PredictedStarOverlayControls` exposes the
mode as an explicit two-way `SingleChoiceSegmentedButtonRow` ("Session intrinsics" / "Diagnostic
fallback"), session-local and non-persisted, matching this codebase's existing toggle conventions.

### 14.12 Batched per-frame astronomy (shared local sidereal time)

**The problem this closes.** CAM-2b's bounded catalog subset can carry up to
`PREDICTED_STAR_OVERLAY_MAX_INPUT_STARS = 200` stars, reprojected on every coherent geometry
recomputation (potentially every camera frame). Before this change, `equatorialToLocalSky(star,
context)` computed `Instant.ofEpochMilli(context.utcEpochMillis)` and `lstAt(instant,
longitudeDeg).lstDeg` **independently for every star** — up to 200 redundant, bit-for-bit-identical
local-sidereal-time computations per batch, since sidereal time depends only on the observer's
longitude and the instant, never on any individual star.

**`PreparedStarProjectionContext`** (`PreparedStarProjectionContext.kt`, `:core:astro-core`, `internal`
— a batch-perf implementation detail, not a new public contract):

```kotlin
internal data class PreparedStarProjectionContext(
    val latitudeRad: Double,
    val lstDeg: Double,
    val magneticDeclinationRad: Double,
)

internal fun prepareStarProjectionContext(context: StarProjectionContext): PreparedStarProjectionContext
```

`projectStars` now calls `prepareStarProjectionContext(context)` **once per batch**, before the
`stars.map { ... }` loop, and every star is projected via a new `internal` 3-arg overload,
`equatorialToLocalSky(star, latitudeRad, lstDeg)`, which is identical Meeus/ENU-embedding math to the
existing 2-arg function, just taking an already-computed `lstDeg` instead of a full context. **The
existing public `equatorialToLocalSky(star, context)` API is unchanged and still supported** — it
computes its own `PreparedStarProjectionContext` and delegates to the 3-arg overload, so every existing
caller/test keeps its exact prior behavior and output; only `projectStars`'s own internal batch loop was
changed to share one prepared context instead of rebuilding it per star.

**No astronomical convention changed, no output changed.** This is a pure refactor: the 2-arg function's
observable output is bit-for-bit identical before and after (same `Instant`, same `lstAt` call, same
`raDecToAltAz`/ENU-embedding formulas — only *where* the shared computation happens moved). Every
existing `:core:astro-core` test (`EquatorialToLocalSkyTest`, `CameraStarProjectionTest`, and the rest of
§13's table) continues to describe the same behavior. `PreparedStarProjectionContextTest` (§14.9) adds:
a direct check that `prepareStarProjectionContext` matches a manual per-field computation; that the
public 2-arg function's output equals the 3-arg overload's output for the same effective inputs; and —
the batch-consistency claim itself — that a multi-star `projectStars` batch produces results bit-for-bit
identical to calling `projectStars` once per star (each an independent singleton batch, each
independently computing its own prepared context), proving the same shared LST value is applied
consistently to every star in a real batch, not just the first.

**No global mutable counters were added** to prove invocation counts; the proof above is structural
(one `prepareStarProjectionContext` call site in `projectStars`, visible in the source) plus the
input/output equivalence tests, which is sufficient since `lstAt` is a pure, deterministic function with
no wall-clock or hidden state to drift between repeated calls.

**Cadence cap deliberately not added.** The task authorizing this change permits an optional
latest-only ~10 Hz diagnostic recomputation cap "if profiling or code structure still indicates
main-thread risk" or "actual device testing shows jank." Neither condition has been met — no physical
device is available in this sandbox to profile or observe jank on (§14.10) — so no cadence cap was
added; the shared-LST batching above is judged sufficient on its own, and adding unrequested complexity
without evidence it is needed would violate this task's own scope discipline (§14.1).
