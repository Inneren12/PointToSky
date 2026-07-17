# Sprint Status

Quick-reference status board for the camera/AR prediction workstream (CAM-*). For full derivations and
risk registers, see `docs/camera_coordinate_calibration_contract.md` and
`docs/camera_star_prediction_contract.md`. This file only tracks what is implemented, tested, and
device-validated — it does not restate the underlying design.

## CAM slice status

**"Tests authored" and "Tests executed" are deliberately separate columns.** A slice whose tests were
written and reviewed by hand but never actually run through Gradle/JUnit is "authored: Yes, executed:
No" — it is never described as simply "tested," which would misstate what was actually verified.

| Slice | What it is | Implemented | Tests authored | Tests executed | Build verified | Device-validated |
|---|---|---|---|---|---|---|
| CAM-1c–1f | CameraX `Preview`+`ImageAnalysis`, frame metadata, timestamp-paired rotation, crop/rotation/display transform, immutable `CameraSessionGeometry`, session-scoped geometry provider | Yes | Yes | Yes (per that PR's own record) | Yes (per that PR's own record) | No |
| CAM-1g | `internalDebug`-only camera-geometry diagnostics overlay; observable geometry result + debug counters | Yes | Yes | Yes (per that PR's own record) | Yes (per that PR's own record) | **No — `CAM-1g BLOCKED ON PHYSICAL DEVICE VALIDATION`** |
| CAM-2a | Pure, Android-free star prediction (`projectStars`): catalog RA/Dec + observer location/time + magnetic declination + `CameraSessionGeometry` → predicted camera/image/display positions, with typed unavailable outcomes | Yes | Yes | Yes (388 tests, `:core:astro-core`, verified via a direct `kotlinc`/JUnit-console invocation — Gradle itself could not run; see `docs/camera_star_prediction_contract.md` §13) | Partial (`:core:astro-core` compilation confirmed this way; `:mobile` not attempted by that PR) | No — not wired into any renderer, no device claim |
| CAM-2b | `internalDebug`-only predicted-star overlay: bounded catalog adapter, pure reducer, diagnostic state, Compose markers/panel/controls consuming `projectStars(...)` for visual diagnosis only, plus an explicit session/diagnostic-fallback intrinsics mode via a total, exact-copy `CameraSessionGeometry.withIntrinsics` substitution | Yes | Yes | **Yes — real Gradle, not a workaround.** `:core:astro-core:test` 388/388, `:mobile:testInternalDebugUnitTest`/`testPublicDebugUnitTest` 271/271 each (incl. `PredictedStarOverlayReducerTest`/`Format`/`CatalogAdapterTest`), `androidTest` compilation for both flavors (after fixing one pre-existing, CAM-2b-unrelated compile bug — see below); connected instrumentation **not run** (no device/emulator in this environment); see below | **Yes for debug** — `compileInternalDebugKotlin`/`compilePublicDebugKotlin`, `lintInternalDebug`/`lintPublicDebug` (0 errors), `assembleInternalDebug`/`assemblePublicDebug` all real-Gradle green. Release: Kotlin/Java compile green for both flavors; APK packaging blocked only by a missing release signing keystore (`storeFile`), not a code defect; see below | **No — `CAM-2b BLOCKED ON PHYSICAL DEVICE VALIDATION`** (build gates now sufficiently verified; no physical device or emulator was available in this environment) |
| CAM-2c | Calibrated Camera2-to-`ImageAnalysis`-buffer pinhole intrinsics over a general (not axis-aligned-only) sensor-to-buffer matrix: full `SensorToBufferMatrix3` + classification, active-array-local active-array intrinsics (`LENS_INTRINSIC_CALIBRATION` when coordinate-space- and skew-tolerance-verified, else focal-length-derived from `SENSOR_INFO_PIXEL_ARRAY_SIZE` pixel pitch — never the active array, which affects only the coordinate domain and centre approximation; see fix §P2 — neither translated by the active array's full-pixel-array placement, see fix round 3 §P1), matrix-composed buffer-space K′ with axis-swap/sign-normalization restricted to the proven `AXIS_ALIGNED_0` class in production (`ORTHOGONAL_90`/`180`/`270` return a typed `RotationOwnershipUnproven` outcome), typed `AnalysisBufferIntrinsicsResolution` (incl. `UnsupportedSensorToBufferTransform`/`RotationOwnershipUnproven`/`MissingPixelArraySize`), coordinator coherent-input gate, `CameraIntrinsics.source=CAMERA_CHARACTERISTICS`+`reference=AnalysisBuffer` accepted by CAM-2a unchanged, debug-only calibration diagnostics panel (incl. active rect, principal-point basis, pixel-array size/delta, focal derivation basis) — **blocked on any logical-multi-camera device, very plausibly including a real Pixel 9's rear camera; see below** | Yes | Yes | **Yes — real Gradle.** `:core:astro-core:test` 468/468, `:mobile:testInternalDebugUnitTest` 412/412, `:mobile:testPublicDebugUnitTest` 370/370 (counts now differ - the CAM diagnostic export tests, including the whole-active-array hypothesis diagnostic, are `internalDebug`-only; see the architecture-fix pass and, most recently, the "CAM-2c hypothesis-diagnostic architecture fix" pass below for the current counts and what each addition covers); see below | **Yes** — `compileInternalDebugKotlin`/`compilePublicDebugKotlin`, `compileInternalDebugAndroidTestKotlin`, `lintInternalDebug`/`lintPublicDebug`, `assembleInternalDebug`/`assemblePublicDebug` all real-Gradle green; see below | **Runtime diagnostics confirmed on a real Pixel 9 (via the previous screenshot-based HUD, and now also via the diagnostic export/freeze workflow) — calibrated mapping still blocked, and whether this device's matrix matches this codebase's one testable source-domain hypothesis is separately unresolved.** `cameraId=0`, `logical=true`, `physicalIds=2,3,4`, `matrixClass=AXIS_ALIGNED_0`, `transformPresent=1115/1115` (original) / `1751/1751` (export-workflow run), `CAM-2c=UnsupportedLogicalMultiCameraMapping`, `publishedReference=PhysicalSensor` — the logical-multi-camera block is exactly the outcome predicted below, now confirmed by two independent real-device observations. **The export-workflow run additionally recorded the real sensor-to-buffer matrix's own 9 values for the first time — the identity matrix** — which the original screenshot-based HUD never captured. That identity matrix does not match the one, named hypothesis this codebase can currently test (that the matrix's source domain is the *complete* active array) — evidence against that hypothesis only, **not** proof the matrix is broken, invalid, or known not to describe the real pipeline (the pinned CameraX version's actual source-domain contract has not been source-traced or device-proven here). This corrects this row's earlier reading of `matrixClass=AXIS_ALIGNED_0`/a high frame count as proof "the sensor-to-buffer transform pipeline itself is working correctly" — see `docs/validation/cam_2c_pixel9_evidence.md`'s §3/§4 for the full finding, the `assessWholeActiveArrayMappingHypothesis` diagnostic this motivated (see the "CAM-2c diagnostics fix — hypothesis-scoped verdict" pass below for why it is named this way, not a general "domain consistency" verdict), and exactly what is/is not confirmed |
| CAM-2c physical-camera provenance experiment | CameraX `1.3.4`→`1.4.2` upgrade (minimum justified — unlocks `CameraSelector.setPhysicalCameraId`/`CameraInfo.getPhysicalCameraInfos()` without an unscoped AGP/compileSdk bump; `1.6.1`/`1.5.x` both require AGP `8.9.1`+, confirmed by a real failed compile attempt), `internalDebug`-only camera-topology recon, `internalDebug`-only explicit physical-camera-binding experiment (standalone `Activity`, fixed-zoom mitigation for the no-per-frame-identity-API gap), a new additive CAM-2c resolution entry point for a verified physical binding — `AnalysisBufferIntrinsicsResolver.kt`'s existing `UnsupportedLogicalMultiCameraMapping` guard is unmodified; a verified physical snapshot simply never triggers it | Yes | Yes | **Yes — real Gradle.** `:core:astro-core:test` 468/468 (unchanged), `:mobile:testInternalDebugUnitTest` 426/426 (+14 new), `:mobile:testPublicDebugUnitTest` 370/370 (unchanged); see below | **Yes** — `compileInternalDebugKotlin`/`compilePublicDebugKotlin`, `compileInternalDebugAndroidTestKotlin`, `lintInternalDebug`/`lintPublicDebug` (0 errors), `assembleInternalDebug`/`assemblePublicDebug` all real-Gradle green; `internalDebug`-only boundary additionally verified via `aapt dump xmltree` and `.dex` string inspection of the actual built APKs; see below | **No — this session ran in a Claude Code remote-execution container with a real (network-provisioned) JDK/Android SDK/Gradle build, a different kind of environment than every prior CAM-2c pass, but still with no physical device or emulator attached.** No live bind was ever attempted; whether a real Pixel 9's `CameraInfo.getPhysicalCameraInfos()` actually reaches `PhysicalCameraBindingResolution.Bound` for this codebase's declared physical IDs (`2`/`3`/`4`) is unconfirmed. See `docs/camera_coordinate_calibration_contract.md` §3.9 and this file's own "CAM-2c physical-camera provenance experiment" section for the full record. |

## CAM-2c (original pass — see "CAM-2c fix" below for corrections)

> **Corrected by the CAM-2c fix pass below:** this section's "replacing CAM-2b's diagnostic-fallback
> substitution as the normal, non-debug session path" claim is only true for a camera that is **not**
> a logical multi-camera; a real Pixel 9's rear camera very plausibly is one, in which case CAM-2c
> still falls back to the CAM-1b/legacy path exactly as before. This section also assumed the
> sensor-to-buffer matrix could only ever be axis-aligned and silently discarded a non-zero
> `LENS_INTRINSIC_CALIBRATION` skew term — both fixed below. Left as-written for historical record;
> do not treat any claim in this section as current without cross-checking the fix section.

- **Scope:** derives real, calibrated pinhole intrinsics (`fx`/`fy`/`cx`/`cy`) over the exact
  `ImageAnalysis` buffer from Camera2 `CameraCharacteristics`, mapped through the real CameraX
  sensor-to-buffer transform — replacing CAM-2b's diagnostic-fallback substitution as the normal,
  non-debug session path. Does not touch astronomy, magnetic declination, raw paired rotation
  ownership, `CropScaleTransform`, or `displayPoint` drawing; does not tune any FOV constant
  empirically. See `docs/camera_coordinate_calibration_contract.md` §3.5 for the full design.
- **Coordinate-space discipline:** `ActiveArrayIntrinsics` (active-array px), `ActiveArraySensorCropRegion`/
  `SensorToBufferTransform` (active-array → buffer px), `AnalysisBufferIntrinsicsValues` (buffer px)
  are distinct types, never interchangeable with `CameraFrameMetadata`'s own buffer-space crop-rect
  fields or `PinholeProjectionModel`'s buffer-space model.
- **`CameraIntrinsics` invariant relaxation:** `source=CAMERA_CHARACTERISTICS` now accepts
  `reference=AnalysisBuffer` in addition to `PhysicalSensor` — `PinholeProjectionModel.forGeometry`/
  `projectStars` were not changed and still gate on `reference` alone, so the `PhysicalSensor`
  rejection is unweakened.
- **`LENS_INTRINSIC_CALIBRATION` coordinate-space verification:** used only when
  `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE` is absent or exactly equals
  `SENSOR_INFO_ACTIVE_ARRAY_SIZE`; otherwise the principal point falls back to the active-array
  geometric centre (`CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT` vs `CALIBRATED`).
- **Logical multi-camera guard:** `UnsupportedLogicalMultiCameraMapping` fires whenever
  `REQUEST_AVAILABLE_CAPABILITIES` includes `LOGICAL_MULTI_CAMERA`, since this camera ID's static
  characteristics cannot be trusted to describe whichever physical sensor actually produced a given
  frame.
- **`ImageProxy.cropRect` provenance:** confirmed (both from CAM-1c's own existing contract and
  `CameraPreview.kt`'s no-`ViewPort` binding) to be buffer-space and always the identity crop here —
  never used as active-array crop metadata. `ImageInfo.getSensorToBufferTransformMatrix()` (stable
  since `camera-core` `1.1.0-beta01`) is the real source instead.
- **No new callback wiring:** the sensor-to-buffer transform rides as a new optional field on
  `CameraFrameMetadata` itself, so `CameraSessionIntrinsicsCoordinator.onFrameMetadata` (already
  receiving the whole frame) needed no new parameter, and neither did `CameraPreview`/`ArScreen`'s
  existing callback wiring.
- **Validation closure pass (this session) — real Gradle.** This environment shipped neither a JDK 17
  toolchain (only JDK 21) nor an Android SDK; both were provisioned locally for this pass
  (`apt-get install openjdk-17-jdk-headless`; an Android SDK via `sdkmanager` against the reachable
  `dl.google.com`, `platform-tools`/`platforms;android-35`/`build-tools;35.0.0`) — a recorded
  environment deviation, not a silent one.
  - *`:core:astro-core:test`*: **PASS, 436/436** (0 failures/errors).
  - *`:mobile:compileInternalDebugKotlin`/`compilePublicDebugKotlin`*: **PASS** (pre-existing warnings
    only: a deprecated `defaultDisplay` API and a missing `@OptIn` marker, neither touched by CAM-2c).
  - *`:mobile:testInternalDebugUnitTest`/`testPublicDebugUnitTest`*: **PASS, 309/309 each** (0
    failures/errors).
  - *`:mobile:compileInternalDebugAndroidTestKotlin`*: **PASS** (no androidTest source needed changes).
  - *`:mobile:lintInternalDebug`/`lintPublicDebug`*: **initially FAILED** on two real, reproducible
    findings, both mine — `LENS_DISTORTION` (`NewApi`, error: requires API 28, this project's minSdk
    is 26) and `REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA` (`InlinedApi`, warning, same API
    floor). Fixed by gating both reads behind `Build.VERSION.SDK_INT >= Build.VERSION_CODES.P`; a
    third self-inflicted `ModifierParameter` warning (`calibrationDiagnostics` placed after
    `modifier` in `CamDiagnosticTopPanels`) was fixed by moving `modifier` back to be the first
    optional parameter. Rerun — **PASS, 0 errors/warnings attributable to CAM-2c** for both flavors.
  - *`:mobile:assembleInternalDebug`/`assemblePublicDebug`*: **PASS**, both debug APKs built.
  - *Connected instrumentation tests*: **not run** — no physical device and no emulator (no
    `/dev/kvm`, no hardware-virtualization CPU flags) are available in this environment; `adb devices
    -l` lists none.
  - *Physical Pixel 9 validation (task §10 — session mode, centre/mid-field/edge dx-dy comparison)*:
    **not executed**, for the same reason.

**Overall CAM-2c status: code/test gates above are real-Gradle green; only the physical-device
checklist (task §10) remains unexecuted, for lack of any device or emulator capability in this
environment — see the individual verdict in the PR description.**

## CAM-2c fix (this sprint)

> **Corrected by the "CAM-2c fix round 2" pass below:** this section's no-double-rotation proof was
> tautological (it never combined the axis-swap mechanism and the rotation transform end-to-end against
> an independently derived expected pixel), its active-array handling silently assumed a zero active-array
> origin, and its `axisSwapped`/`negateXInput`/`negateYInput` invariant checked `reference` alone —
> all fixed below. Left as-written for historical record; do not treat any claim in this section as
> current without cross-checking the round-2 fix section.

- **Scope:** fixes four defects the original CAM-2c pass above left in place — an unverified
  axis-aligned-only assumption about the sensor-to-buffer matrix, a silently-discarded
  `LENS_INTRINSIC_CALIBRATION` skew term, a first-frame-wins coordinator gate that could permanently
  latch onto an unusable transform, and an overstated "normal session path" claim that did not account
  for logical multi-camera devices. Does not implement CAM-3, does not touch astronomy/magnetic
  declination/raw rotation ownership/`CropScaleTransform`/`displayPoint`, and does not tune any FOV
  constant empirically. See `docs/camera_coordinate_calibration_contract.md` §3.5 (rewritten) for the
  full design.
- **`SensorToBufferMatrix3` replaces the axis-aligned-only `SensorToBufferTransform`:** preserves all
  9 reported values; `classifySensorToBufferMatrix` sorts any matrix into `AXIS_ALIGNED_0`/
  `ORTHOGONAL_90`/`ORTHOGONAL_180`/`ORTHOGONAL_270` (supported) or `MIRRORED`/
  `GENERAL_AFFINE_UNSUPPORTED`/`PROJECTIVE_UNSUPPORTED`/`SINGULAR` (typed rejection via
  `AnalysisBufferIntrinsicsResolution.UnsupportedSensorToBufferTransform`) — never a silent `null`.
- **Full K-matrix composition:** `ActiveArrayIntrinsics` gained a `skewPx` term;
  `mapActiveArrayIntrinsicsThroughMatrix` composes the complete active-array `K` with the classified
  sensor-to-buffer matrix via genuine matrix multiplication, deriving buffer-space `fx`/`fy`/`cx`/`cy`
  plus `axisSwapped`/`negateXInput`/`negateYInput` for the rotated/permuted cases — proven (by
  algebraic derivation and by `PinholeProjectionModelTest`'s dedicated test) never to double up with
  the separate, untouched `frame.rotationDegrees`-driven ray rotation, since one is a position-space
  operation resolved once per session and the other is a direction-space operation applied per star.
  `PinholeProjectionModel`/`CameraIntrinsics` both gained matching `axisSwapped`/`negateXInput`/
  `negateYInput` fields, defaulting `false` (100% backward compatible).
- **Skew tolerance policy:** `LENS_INTRINSIC_CALIBRATION`'s skew term is now checked against
  `INTRINSIC_SKEW_TOLERANCE_PX` (0.5px); within tolerance it is used as `CALIBRATED`, otherwise the
  calibrated `K` is rejected entirely (falling back to focal-length-derived, zero-skew intrinsics,
  `CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT`) and
  `CameraCalibrationDiagnostics.skewDiagnosticReason` records `NON_ZERO_INTRINSIC_SKEW` — never a
  silently-dropped skew term.
- **Coordinator coherent-input gate:** `CameraSessionIntrinsicsCoordinator` no longer latches onto the
  first frame's dimensions/transform unconditionally. It now keeps accepting frames (bounded, no
  queue) until one frame's own transform is both non-`null` and classifies as a supported class, using
  that frame's dimensions and transform together; a configurable bound
  (`maxFramesWaitingForUsableTransform`, default 30) falls back to the CAM-1b path if none ever
  arrives. New observable `CameraSessionIntrinsicsCoordinatorState`.
- **Logical multi-camera provenance investigated, guard unchanged:** confirmed this project's pinned
  `androidx.camera:camera-camera2:1.3.4` cannot bind a concrete physical camera
  (`CameraSelector.setPhysicalCameraId`) or read per-frame physical-camera provenance
  (`CameraInfo.getPhysicalCameraInfos()`) — both require `1.4.0-beta01`+. `UnsupportedLogicalMultiCameraMapping`
  still fires unconditionally for any logical multi-camera; `Camera2CharacteristicsSource` gained an
  optional `Context` for a narrow, read-only `CameraManager`-based diagnostic (declared physical camera
  IDs) that never changes this guard's outcome. **This is very plausibly still true of a real Pixel
  9's rear camera** — Pixel phones have used logical multi-camera rear configurations since the Pixel
  4 — so CAM-2c's calibrated path is not a universal replacement for the legacy/CAM-1b path; it is the
  normal path only on non-logical-multi-camera devices, correcting the original pass's unqualified
  "replacing ... as the normal, non-debug session path" claim.
- **Active-array / pre-correction-active-array discipline confirmed exact-rectangle, not
  width/height-only** (already correct in the original pass; added non-zero-origin fixtures to prove
  it): `preCorrectionActiveArrayMatchesActiveArray` compares all four edges.
- **Coordinate-space count corrected:** the original pass's design doc said "five coordinate spaces"
  but listed four (active-array and pre-correction-active-array pixels were bundled into one bullet);
  now split into five explicit bullets.
- **Validation closure pass (this session) — real Gradle**, same provisioned JDK 17 + Android SDK as
  the original pass.
  - *`:core:astro-core:test`*: **PASS, 455/455** (0 failures/errors; up from 436 — new
    `SensorToBufferMatrix3`/classification/composition/axis-swap/no-double-rotation/rotation-pipeline
    tests).
  - *`:mobile:compileInternalDebugKotlin`/`compilePublicDebugKotlin`*: **PASS.**
  - *`:mobile:testInternalDebugUnitTest`/`testPublicDebugUnitTest`*: **PASS, 326/326 each** (0
    failures/errors; up from 309 — new skew-tolerance/unsupported-transform-class/non-zero-origin/
    coherent-input-gate/logical-camera-diagnostics tests).
  - *`:mobile:compileInternalDebugAndroidTestKotlin`*: **PASS.**
  - *`:mobile:lintInternalDebug`/`lintPublicDebug`*: **PASS**, 0 new errors/warnings.
  - *`:mobile:assembleInternalDebug`/`assemblePublicDebug`*: **PASS**, both debug APKs built.
  - *Connected instrumentation tests / physical Pixel 9 validation (task §10)*: **not executed** — no
    physical device or emulator (no `/dev/kvm`, no hardware-virtualization CPU flags, `adb devices -l`
    lists none) is available in this environment. Per the logical-multi-camera paragraph above, a real
    Pixel 9 run is expected to observe `CAM-2c: blocked · logical multi-camera` (not
    `CAM-2b: ready · session · visible: N` / `reference: AnalysisBuffer(640x480)`) on its actual rear
    camera — this cannot be verified without the device.

**Overall CAM-2c fix status: code/test gates above are real-Gradle green; the physical-device
checklist (task §10) remains unexecuted for lack of any device or emulator capability in this
environment, and — independently of that gap — is expected to hit the logical-multi-camera guard on
a real Pixel 9's rear camera rather than reach a calibrated `AnalysisBuffer` result at all. See the
final verdict in the PR description.**

## CAM-2c fix round 2 (this sprint)

> **Corrected by the "CAM-2c fix round 3" pass below:** this section's coordinate-origin fix (the
> `coordinateOriginXPx`/`coordinateOriginYPx` fields and the `activeLeft`/`activeTop` translation) was
> itself wrong — Android's own Camera2 contract expresses `LENS_INTRINSIC_CALIBRATION` and the CameraX
> sensor-to-buffer matrix's own domain as active-array-**local**, not full-pixel-array-relative, so
> adding that translation converted an already-correct local value into a wrong one. The
> rotation-ownership-proof fix and the `CameraIntrinsics` invariant tightening below are unaffected.
> Left as-written for historical record; do not treat any coordinate-origin claim in this section as
> current without cross-checking round 3 below.

- **Scope:** fixes two further defects a review of the CAM-2c fix above identified — active-array
  coordinate-origin handling and a tautological rotation proof — plus tightens one invariant and one
  bounds check the same review flagged. Does not implement CAM-3, does not touch astronomy/magnetic
  declination/`CropScaleTransform`/`displayPoint` ownership, and does not tune any FOV constant
  empirically. See `docs/camera_coordinate_calibration_contract.md` §3.6 for the full design.
- **Active-array coordinate-origin ownership, enforced by a type, not a convention:**
  `ActiveArrayIntrinsics` gained `coordinateOriginXPx`/`coordinateOriginYPx` (default `0.0`, so every
  existing named-argument call site is unaffected); `cxPx`/`cyPx` are now documented as always being in
  sensor matrix space, never rectangle-local. `resolveAnalysisBufferIntrinsics` translates explicitly
  using `activeLeft`/`activeTop` for both the focal-length-derived and calibrated paths before matrix
  composition — previously the origin was read from Camera2 metadata and then silently discarded,
  correct only when the active array happened to start at `(0, 0)`.
- **Crop-region bounds validated against the real rectangle:** `ActiveArraySensorCropRegion` renamed
  `ActiveArrayRect`, now used both for the ground-truth active-array rectangle and the buffer's inferred
  crop region; `resolveAnalysisBufferIntrinsics` validates the inferred crop against the actual reported
  rectangle's four edges, not an assumed `[0, width) x [0, height)` range that silently assumed a
  zero-origin active array.
- **Rotation ownership beyond `AXIS_ALIGNED_0` is now typed-unproven, not composed-and-assumed:** the
  prior fix's no-double-rotation test exercised the `axisSwapped` mechanism and the rotation transform
  independently without ever combining them against an independently hand-derived expected pixel — not
  a real proof. No public CameraX/Camera2 contract states how an `ORTHOGONAL_90`/`180`/`270` matrix
  should combine with `CameraFrameMetadata.rotationDegrees` on a real device, so per the fix task's own
  escape hatch, `resolveAnalysisBufferIntrinsics` now accepts only `AXIS_ALIGNED_0` from the (unchanged,
  still-general) core composition math, returning a new
  `AnalysisBufferIntrinsicsResolution.RotationOwnershipUnproven(transformClass)` for the other three
  classes instead of a `Resolved` value; `CameraSessionIntrinsicsCoordinator.isSupportedTransformClass`
  was narrowed to match. `CalibratedAnalysisBufferProjectionTest` replaces the old mechanism-only test
  with two end-to-end tests chaining the real production functions for an off-axis ray at
  `rotationDegrees` 0 and 90, asserted against pixel values computed entirely by hand from the
  documented per-stage formulas.
- **`axisSwapped`/`negateXInput`/`negateYInput` invariant tightened:** `CameraIntrinsics.init` now
  requires **both** `source == CAMERA_CHARACTERISTICS` **and** `reference is AnalysisBuffer` for a
  non-default flag (previously checked `reference` alone). New constructor-rejection tests cover
  `LEGACY_FALLBACK`, `CAMERA_INTRINSIC_CALIBRATION`, and `CAMERA_CHARACTERISTICS`+`PhysicalSensor`; the
  shared `analysisBufferIntrinsics` test fixture (`LEGACY_FALLBACK`-sourced) had its now-invalid axis-flag
  parameters removed rather than kept unused.
- **Diagnostics:** `CameraCalibrationDiagnostics` gained the active rectangle's four edges, a principal-
  point-basis label, and the principal point before/after origin translation — internal-debug-only, as
  before.
- **Validation closure pass (this session) — real Gradle**, same provisioned JDK 17 + Android SDK as
  the prior passes.
  - *`:core:astro-core:test`*: **PASS, 462/462** (0 failures/errors; up from 455 — new
    `CameraIntrinsics` axis-flag invariant tests and the two hand-derived end-to-end rotation tests).
  - *`:mobile:testInternalDebugUnitTest`/`testPublicDebugUnitTest`*: **PASS, 332/332 each** (0
    failures/errors; up from 326 — new non-zero-origin precise tests, the inverse-crop-recovers-the-
    real-rectangle test, and the `ORTHOGONAL_90`/`180`/`270` → `RotationOwnershipUnproven` tests).
  - *`:mobile:compileInternalDebugAndroidTestKotlin`*: **PASS.**
  - *`:mobile:lintInternalDebug`/`lintPublicDebug`*: **PASS**, 0 errors, 30 warnings each (same
    pre-existing warning count as the prior pass; none attributable to this fix).
  - *`:mobile:assembleInternalDebug`/`assemblePublicDebug`*: **PASS**, both debug APKs built.
  - *Physical Pixel 9 validation (task §7)*: **not executed** — no physical device or emulator is
    available in this environment (same limitation as every prior CAM-2 pass). A real Pixel 9's rear
    camera is still expected to hit `UnsupportedLogicalMultiCameraMapping` before any of this round's
    origin-translation or `AXIS_ALIGNED_0`-only fixes would even be exercised.

**Overall CAM-2c fix round 2 status: code/test gates above are real-Gradle green; physical-device
validation remains unexecuted for lack of any device or emulator capability in this environment, and —
independently of that gap — is expected to hit the logical-multi-camera guard on a real Pixel 9's rear
camera rather than reach a calibrated `AnalysisBuffer` result at all. See the final verdict in the PR
description.**

## CAM-2c fix round 3, §P1 (this sprint)

- **Scope:** a P1 review identified that round 2's own coordinate-origin fix was itself incorrect — it
  treated `LENS_INTRINSIC_CALIBRATION` cx/cy and the CameraX sensor-to-buffer matrix's own domain as
  absolute (full-pixel-array-relative) coordinates and added `SENSOR_INFO_ACTIVE_ARRAY_SIZE.left`/`.top`
  before matrix composition. Android's own Camera2 contract says the opposite: both are
  active-array-**local**. This fix reverts the translation and re-derives the correct model with
  AOSP source evidence. Round 2's rotation-ownership-proof fix, `RotationOwnershipUnproven` outcome,
  logical-multi-camera guard, skew policy, coherent coordinator gate, and `CameraIntrinsics` invariant
  tightening are all unaffected and remain current. See
  `docs/camera_coordinate_calibration_contract.md` §3.7 for the full design and the verbatim AOSP
  quotes this fix is grounded in.
- **The evidence.** Fetched directly from AOSP's `system/media/camera/docs/metadata_definitions.xml`
  (the source that generates the official `CameraCharacteristics` Javadoc) in this session:
  `SENSOR_INFO_ACTIVE_ARRAY_SIZE`'s own details state pixel-coordinate keys "defined relative to the
  active array rectangle ... with `(0, 0)` being the top-left of this rectangle";
  `LENS_INTRINSIC_CALIBRATION`'s own details place its transform in the
  `preCorrectionActiveArraySize` coordinate system, "where `(0,0)` is the top-left of the
  `preCorrectionActiveArraySize` rectangle." Only the rectangles' *own* `left`/`top`/`right`/`bottom`
  (as reported by `CameraCharacteristics`) are full-pixel-array-relative — confirmed by
  `preCorrectionActiveArraySize`'s own worked example, which *subtracts* `activeArray.left`/`.top` to
  convert a full-array coordinate to a local one, never adds it back.
- **The fix.** `ActiveArrayIntrinsics.cxPx`/`cyPx` are active-array-local again;
  `coordinateOriginXPx`/`coordinateOriginYPx` are removed entirely (not merely defaulted).
  `resolveAnalysisBufferIntrinsics` no longer translates either the calibrated or focal-length-derived
  principal point by `activeLeft`/`activeTop`.
- **Two distinct rectangle types prevent the same conflation from recurring:** `ActiveArrayRect` keeps
  its full-pixel-array-relative meaning (ground-truth rect, four-edge match check, diagnostics only); a
  new `ActiveArrayLocalRect` type (returned by the renamed `toActiveArrayLocalRect`, was
  `toActiveArrayRect`) takes over the inferred-crop-region role. Crop-bounds validation now checks
  `[0, activeArrayWidthPx] x [0, activeArrayHeightPx]` — never `ActiveArrayRect`'s full-array-relative
  edges.
- **Diagnostics simplified:** the now-meaningless `principalPointBeforeTranslationXPx`/`YPx` fields
  (no translation step means "before" and "after" are always identical) are removed;
  `principalPointBasis`'s value is renamed `ACTIVE_ARRAY_LOCAL` (was `SENSOR_MATRIX_SPACE`).
  `CameraFrameMetadata`'s stale `ActiveArraySensorCropRegion` KDoc reference (predating even round 2's
  own rename) is fixed.
- **Tests rewritten to match the corrected model:** for the non-zero-origin active array
  `[100,50]-[4132,3074]`, the correct sensor-to-buffer matrix is now **identical** to the zero-origin
  `fullFrameTransform` fixture — a genuine illustration that the origin never affects K composition at
  all. New/rewritten tests assert the local centre resolves to the exact buffer centre (focal-derived
  and calibrated), an off-centre local point maps via the untranslated formula, a regression test
  proves the reverted translation would have produced a measurably different (wrong) result, the
  inverse-crop recovers the local `[0,0]-[4032,3024]` rectangle (not the full-array one), and a new
  test demonstrates the full-array/local coordinate conversion as a documented relationship never fed
  into K.
- **Validation closure pass (this session) — real Gradle**, same provisioned JDK 17 + Android SDK as
  the prior passes.
  - *`:core:astro-core:test`*: **PASS, 465/465** (0 failures/errors; up from 462 — new
    `ActiveArrayLocalRect` tests and the full-array/local conversion test).
  - *`:mobile:testInternalDebugUnitTest`/`testPublicDebugUnitTest`*: **PASS, 333/333 each** (0
    failures/errors; up from 332 — the rewritten non-zero-origin section nets one additional test).
  - *`:mobile:compileInternalDebugAndroidTestKotlin`*: **PASS.**
  - *`:mobile:lintInternalDebug`/`lintPublicDebug`*: **PASS**, 0 errors, 30 warnings each (same
    pre-existing warning count as the prior pass; none attributable to this fix).
  - *`:mobile:assembleInternalDebug`/`assemblePublicDebug`*: **PASS**, both debug APKs built.
  - *Physical Pixel 9 validation*: **not executed** — no physical device or emulator is available in
    this environment (same limitation as every prior CAM-2 pass). The exact pinned
    `androidx.camera:camera-camera2:1.3.4` implementation of `calculateSensorToBufferTransformMatrix`
    could not be located in this sandboxed session either (see §3.7's disclosure); the corrected model
    rests on Camera2's own cross-referenced documentation convention, not a byte-level trace of that
    specific implementation. A real Pixel 9's rear camera is still expected to hit
    `UnsupportedLogicalMultiCameraMapping` before any of this fix would even be exercised.

**Overall CAM-2c fix round 3 status: code/test gates above are real-Gradle green; physical-device
validation remains unexecuted for lack of any device or emulator capability in this environment, and —
independently of that gap — is expected to hit the logical-multi-camera guard on a real Pixel 9's rear
camera rather than reach a calibrated `AnalysisBuffer` result at all. See the final verdict in the PR
description.**

## CAM-2c fix §P2 (this sprint)

- **Scope:** the focal-length-derived fallback (used whenever no usable `LENS_INTRINSIC_CALIBRATION`
  exists) computed pixel focal length as `focalLengthMm / physicalSensorWidthMm * activeArrayWidthPx`
  — mixing `SENSOR_INFO_PHYSICAL_SIZE` (the full pixel array's physical size) with
  `SENSOR_INFO_ACTIVE_ARRAY_SIZE` (which may be smaller, excluding optically black/inactive border
  pixels). This overestimates pixel pitch and underestimates `fx`/`fy` whenever the two arrays differ,
  producing an overly wide computed FOV. The already-resolved non-zero-`LENS_INTRINSIC_CALIBRATION`-skew
  review comment (separate check, separate rejection path, unchanged) is untouched by this fix. See
  `docs/camera_coordinate_calibration_contract.md` §3.8 for the full design.
- **The fix:** `activeArrayIntrinsicsFromFocalLength` now derives `fx`/`fy` from
  `SENSOR_INFO_PIXEL_ARRAY_SIZE` (`fxPixelGrid = focalLengthMm / physicalSensorWidthMm *
  pixelArrayWidthPx`), never `SENSOR_INFO_ACTIVE_ARRAY_SIZE`. `activeArrayWidthPx`/`activeArrayHeightPx`
  keep their separate, existing role — the active-array-local coordinate domain and the default
  principal-point centre approximation — never pixel pitch.
- **Pixel-array metadata:** `CameraCharacteristicsSnapshot` gains `pixelArrayWidthPx`/
  `pixelArrayHeightPx: Int?`, sourced from `CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE` (an
  `android.util.Size`) in `Camera2CharacteristicsSource` — never inferred from
  `activeArrayRight - activeArrayLeft`.
- **A usable calibrated `K` needs no pixel-array size:** the pixel-array-size requirement lives
  exclusively in the focal-derived fallback branch of `resolveAnalysisBufferIntrinsics`. A new typed
  outcome, `AnalysisBufferIntrinsicsResolution.MissingPixelArraySize`, fires only when that fallback is
  actually reached and the pixel array is missing or non-positive — a device with usable
  `LENS_INTRINSIC_CALIBRATION` but no reported pixel-array size still resolves with `quality =
  CALIBRATED`.
- **Diagnostics:** `CameraCalibrationDiagnostics` gains `pixelArrayWidthPx`/`pixelArrayHeightPx: Int?`
  and `focalDerivationBasis: String` (`PIXEL_ARRAY`/`LENS_INTRINSIC_CALIBRATION`), surfaced as new
  `pixel array: W×H`, `pixel-array vs active-array delta: Δw=…, Δh=…`, and `focal derivation basis: …`
  lines — internal-debug-only, as before.
- **Tests:** pixel array equal to the active array is now the default test fixture, so every
  pre-existing focal-derived test already proves the corrected formula matches the old one in that
  case; new tests cover a pixel array larger than the active array (asserting the exact corrected
  `fx`/`fy`, explicitly distinct from the old values, and an end-to-end off-axis
  `PinholeProjectionModel.project` call proving the projected pixel itself reflects the correction);
  missing/invalid pixel-array dimensions (`MissingPixelArraySize`); a usable calibrated `K` resolving
  despite a missing pixel array; and a non-zero active-array origin combined with a larger pixel array,
  confirming the two fixes compose independently.
- **Test-contract correction (same fix):** `CameraCalibrationDiagnosticFormatTest`'s own fixture set
  `activeCxPx`/`activeCyPx` to the full-pixel-array-relative `(2116.0, 1562.0)` while declaring
  `principalPointBasis = ACTIVE_ARRAY_LOCAL` — silently re-encoding the exact `+activeLeft`/`+activeTop`
  regression fix round 3 removed, in a test fixture only (production code was already correct).
  Corrected to the active-array-local `(2016.0, 1512.0)`; the full-array equivalent is now tested only
  as an explicitly separate, labelled conversion the rendered text never contains. Added a direct
  regression assertion (`activeCxPx == activeArrayWidthPx/2`, `!= activeArrayLeftPx +
  activeArrayWidthPx/2`) and strengthened the non-zero-origin-plus-pixel-array resolver test to assert
  `principalPointBasis`/`focalDerivationBasis` explicitly, using the same canonical `4100×3100` pixel
  array as the standalone pixel-array test (previously an unrelated `4200×3200`).
- **Validation closure pass (this session) — real Gradle**, same provisioned JDK 17 + Android SDK as
  the prior passes.
  - *`:core:astro-core:test`*: **PASS, 468/468** (0 failures/errors; up from 465 — four new
    `activeArrayIntrinsicsFromFocalLength` tests).
  - *`:mobile:testInternalDebugUnitTest`/`testPublicDebugUnitTest`*: **PASS, 341/341 each** (0
    failures/errors; up from 333 — six new resolver tests, one new diagnostics-format test, and two
    more from the test-contract correction).
  - *`:mobile:compileInternalDebugAndroidTestKotlin`*: **PASS.**
  - *`:mobile:lintInternalDebug`/`lintPublicDebug`*: **PASS**, 0 errors, 30 warnings each (same
    pre-existing warning count as the prior pass; none attributable to this fix).
  - *`:mobile:assembleInternalDebug`/`assemblePublicDebug`*: **PASS**, both debug APKs built.
  - *Confirmed unchanged:* skew handling (`INTRINSIC_SKEW_TOLERANCE_PX` policy), coordinate-origin
    handling (fix round 3 §P1), rotation ownership (`RotationOwnershipUnproven`), the pinhole
    projection math itself, and `CropScaleTransform`/`displayPoint` ownership — verified by grep and by
    every pre-existing test in those areas continuing to pass unmodified.
  - *Physical Pixel 9 validation*: **not executed** — no physical device or emulator is available in
    this environment (same limitation as every prior CAM-2 pass). A real Pixel 9's rear camera is still
    expected to hit `UnsupportedLogicalMultiCameraMapping` before this fix would even be exercised.

**Overall CAM-2c fix §P2 status: code/test gates above are real-Gradle green; physical-device
validation remains unexecuted for lack of any device or emulator capability in this environment, and —
independently of that gap — is expected to hit the logical-multi-camera guard on a real Pixel 9's rear
camera rather than reach a calibrated `AnalysisBuffer` result at all. See the final verdict in the PR
description.**

- **CAM diagnostic export/freeze workflow + architecture-fix pass (this session) — real Gradle**, same
  provisioned JDK 17 + Android SDK as the prior passes. Replaces the screenshot-driven CAM diagnostics
  HUD with a bounded snapshot/export workflow (`internalDebug`-only), then hardens it: the export
  implementation moved to a genuine `internalDebug`-only Gradle source set (a no-op
  `CamDiagnosticsExportUi` implementation is compiled into `publicDebug`/`internalRelease`/
  `publicRelease` instead - no reflection, no reliance on R8), the snapshot became a deep-copied,
  read-only export DTO tree (mutable `FloatArray`/`Set` inputs are normalized into fresh `List`s at
  capture time, verified by mutation-regression tests; Kotlin's `List` is a read-only interface, not a
  JVM-enforced immutability guarantee, so no field retains a reference to a caller-owned mutable
  collection in the first place), the share `Intent` is now built by a pure,
  independently testable function, the live snapshot's timestamp is throttled (recomputed only on an
  actual input change or dialog-open, never every recomposition, never a timer), and the full-report
  scroll/large-font tests now prove the scrollable container reaches a real end marker and each action
  is individually reachable rather than merely present in the tree. See
  `docs/validation/cam_2c_pixel9_evidence.md` for the full breakdown, including why the original Pixel 9
  evidence and this workflow's own (not yet device-verified) implementation are kept in clearly
  separate sections.
  - *`:core:astro-core:test`*: **PASS, 468/468.**
  - *`:mobile:testInternalDebugUnitTest`*: **PASS, 394/394** (0 failures/errors; includes the new
    snapshot/format/JSON/mutation/variant-presence tests, `internalDebug`-only).
  - *`:mobile:testPublicDebugUnitTest`*: **PASS, 369/369** (0 failures/errors; includes the new
    `publicDebug`-only test proving the real export classes are absent from this variant's classpath).
  - *`:mobile:compileInternalDebugAndroidTestKotlin`*: **PASS.**
  - *`:mobile:lintInternalDebug`/`lintPublicDebug`*: **PASS**, 0 errors, 30 warnings each (same
    pre-existing warning count as prior passes; none attributable to this change).
  - *`:mobile:assembleInternalDebug`/`assemblePublicDebug`*: **PASS**, both debug APKs built.
  - *Confirmed unchanged:* CAM-2a projection math, CAM-2c intrinsics math, logical-multi-camera policy,
    camera binding, renderer, detector, matcher, catalog - this pass only touches the debug-only
    diagnostics export surface and its own source-set boundary.
  - *Physical Pixel 9 run of the new export workflow itself (Freeze/Copy all/Share log/Share JSON)*:
    **not executed** — no physical device or emulator is available in this environment. See
    `docs/validation/cam_2c_pixel9_evidence.md` §3 for exactly what remains to be confirmed there;
    this is distinct from the original, already-confirmed Pixel 9 evidence in that file's §1.

- **Follow-up hardening pass (this session, round 3) — real Gradle**, same provisioned JDK 17 + Android
  SDK. Fixes four defects found in the prior pass: (1) the `internalDebug` instrumented-test source set
  was at the wrong path (`mobile/src/internalDebugAndroidTest` — a directory AGP silently never
  compiled) and is now at AGP's real name, `mobile/src/androidTestInternalDebug` (component prefix
  first); this move surfaced a real, previously-hidden compile error (two missing cross-package test-tag
  imports) in `CamDiagnosticExportUiTest.kt`, now fixed — direct evidence the wrong path had been masking
  a genuine bug. (2) "Copy all"/"Share log"/"Share JSON" now dispatch through an injectable
  `CamDiagnosticActions` interface (`copy(label, text)`/`share(subject, text)`), defaulting to the real
  `AndroidCamDiagnosticActions` (clipboard/chooser) in production; new Compose tests inject a recording
  fake and assert the *exact* label/subject/payload each button sends — including a frozen-snapshot case
  (freeze, advance the live input, then confirm Share log/Share JSON still describe the frozen values,
  not the newer live ones) — closing a gap where a payload-swapping regression could have passed
  unnoticed. (3) KDoc/docs wording that overclaimed the snapshot as "fully immutable" is corrected to
  "deep-copied, read-only export DTO tree", noting explicitly that Kotlin's `List` is a read-only
  interface, not a JVM-enforced immutability guarantee (Option A: wording fix only, no new
  immutable-collections dependency). (4) removed the unused `openState` parameter from the test-only
  `ReticleAndExportUiHost` composable and its call site.
  - *`:core:astro-core:test`*: **PASS, 468/468.**
  - *`:mobile:testInternalDebugUnitTest`*: **PASS, 394/394** (unchanged — this round's new tests are
    instrumented, not JVM unit tests).
  - *`:mobile:testPublicDebugUnitTest`*: **PASS, 369/369** (unchanged).
  - *`:mobile:compileInternalDebugAndroidTestKotlin`*: **PASS** — confirmed via `:mobile:sourceSets`
    that `androidTestInternalDebug` is AGP-registered (Kotlin/Java sources under
    `mobile/src/androidTestInternalDebug/java`), and via the compiled `kotlin-classes` output that both
    `CamDiagnosticActionsTest.class` and `CamDiagnosticExportUiTest.class` (plus the two new payload
    tests' synthetic lambda classes) were actually produced.
  - *`:mobile:lintInternalDebug`/`lintPublicDebug`*: **PASS**, 0 errors, 30 warnings each (identical
    count to the prior pass; same baseline).
  - *`:mobile:assembleInternalDebug`/`assemblePublicDebug`*: **PASS**, both debug APKs built.
  - *Confirmed unchanged:* `git diff --stat` against the prior pass touches only this diagnostics-export
    feature's own files plus two doc-comment path corrections in shared `androidTest`/`main` files - no
    CAM-2a projection math, CAM-2c intrinsics math, logical-multi-camera policy, camera binding,
    renderer, detector, matcher, or catalog code changed.
  - *Physical device/emulator execution*: **not run** — no physical device or emulator is available in
    this environment; this round's proofs are compile/lint/JVM-test/instrumented-test-*compile*
    evidence only, never a claim of connected/device execution.

## CAM-2c diagnostics fix — hypothesis-scoped verdict (this sprint)

*(An earlier revision of this pass shipped as "CAM-2c domain-consistency fix," under names -
`assessSensorToBufferDomainConsistency`, `SensorToBufferDomainConsistency`, `domainConsistency`,
`CONSISTENT`/`MAPPED_BOUNDS_MISMATCH`, and a reserved `DomainConsistencyUnproven` resolver variant - that
overclaimed what the check actually establishes. That revision's prose is not reproduced here. The only
facts from it that remain accurate: a real Pixel 9 `internalDebug` run exported the sensor-to-buffer
matrix's actual 9 values for the first time, and they were the **identity** matrix, alongside a
`4080x3072` active array and a `640x480` `ImageAnalysis` buffer;
`SensorToBufferTransformClass.AXIS_ALIGNED_0` is a structural classification only; that identity matrix
does not match `SourceDomainBasis.ASSUMED_WHOLE_ACTIVE_ARRAY_LOCAL`, the one hypothesis this codebase can
currently test; and the pinned `androidx.camera:camera-camera2:1.3.4` version's real sensor-to-buffer
source domain remains unresolved. See `docs/validation/cam_2c_pixel9_evidence.md` for the full evidence.
This pass below is the current, accurate record.)*

- **Trigger:** a review of the prior "CAM-2c domain-consistency fix" pass above found that its own
  `assessSensorToBufferDomainConsistency` function and `SensorToBufferDomainConsistency`/
  `domainConsistency`/`CONSISTENT`/`MAPPED_BOUNDS_MISMATCH` naming turned one unproven assumption about
  the pinned CameraX version's own source-coordinate-domain contract into a general-sounding semantic
  verdict on the matrix — exactly the kind of overclaim the identity-matrix evidence (§3 of
  `docs/validation/cam_2c_pixel9_evidence.md`) originally motivated fixing. The pinned
  `androidx.camera:camera-camera2:1.3.4` implementation's real source-domain contract for
  `ImageInfo.getSensorToBufferTransformMatrix()` has not been source-traced or device-proven in this
  codebase; a legitimate, correctly-functioning cropped/pre-normalized source domain remains a live,
  unruled-out possibility, so a `MAPPED_BOUNDS_MISMATCH`-style verdict was never entitled to the general
  reading its own name invited.
- **Scope:** diagnostics-only, same as the prior pass — no change to CAM-2a projection math, CAM-2c
  intrinsics composition, logical-multi-camera precedence, camera binding, renderer, detector, matcher,
  or catalog code.
- **Renamed to name the tested hypothesis explicitly, not a general verdict:**
  - `assessSensorToBufferDomainConsistency` → `assessWholeActiveArrayMappingHypothesis`
    (`:core:astro-core` at the time of this pass, file renamed `SensorToBufferDomainConsistency.kt` →
    `WholeActiveArrayMappingHypothesis.kt`; **later moved out of `:core:astro-core` entirely — see the
    "CAM-2c hypothesis-diagnostic architecture fix" pass below**).
  - `SensorToBufferDomainConsistency` (enum) → `WholeActiveArrayHypothesisVerdict`, with values renamed
    `CONSISTENT` → `MATCHES_WHOLE_ACTIVE_ARRAY_HYPOTHESIS`, `SOURCE_DOMAIN_UNAVAILABLE` →
    `SOURCE_METADATA_UNAVAILABLE`, `BUFFER_DOMAIN_UNAVAILABLE` → `BUFFER_METADATA_UNAVAILABLE`,
    `MAPPED_BOUNDS_MISMATCH` → `WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH` (`NON_FINITE_MAPPED_BOUNDS`/
    `UNSUPPORTED_TRANSFORM_CLASS` unchanged). Every value's own KDoc now explicitly disclaims that a
    mismatch proves the matrix itself invalid/unusable/broken.
  - `SensorToBufferDomainConsistencyAssessment` → `WholeActiveArrayMappingAssessment`, gaining a new
    `sourceDomainBasis: SourceDomainBasis` field (one value today,
    `ASSUMED_WHOLE_ACTIVE_ARRAY_LOCAL`) that names, explicitly, which hypothesis the verdict describes —
    a future pass that source-traces or device-proves a different real contract can add further basis
    values without another rename. `mappedSourceBoundsPx` → `mappedAssumedSourceBoundsPx`.
  - `internalDebug` export DTO/JSON: `domainConsistency` → `wholeActiveArrayHypothesisVerdict`,
    `mappedSourceBoundsPx` → `mappedAssumedSourceBoundsPx`, `consistencyReason` → `hypothesisReason`, plus
    a new `sourceDomainBasis` field. Text report lines renamed to match
    (`wholeActiveArrayHypothesisVerdict:`/`mappedAssumedSourceBounds:`/`sourceDomainBasis:`/
    `hypothesisReason:`). `framesWithSupportedTransformClass` (the prior pass's rename) is **kept as-is**
    — that rename was correct and is not part of this correction.
  - `CAM_DIAGNOSTIC_JSON_SCHEMA_VERSION` bumped `2` → `3` (the prior pass's own bump from `1` → `2` for
    `framesWithSupportedTransformClass` stands unchanged).
- **`expectedBufferBoundsPx` contract corrected to match its own KDoc.** The prior pass's KDoc claimed
  this field was `null` only for the buffer-unavailable case, but the implementation actually nulled it
  out for `SOURCE_DOMAIN_UNAVAILABLE`/`UNSUPPORTED_TRANSFORM_CLASS`/`NON_FINITE_MAPPED_BOUNDS` too. Fixed
  by implementation, not by KDoc: `expectedBufferBoundsPx` is now preserved whenever a valid buffer size
  is known, regardless of whether the source-side assessment could complete — `null` only for
  `BUFFER_METADATA_UNAVAILABLE`, where no valid buffer rectangle exists to report at all.
- **Buffer-first precedence fixed.** A follow-up review found that even after the fix above,
  `assessWholeActiveArrayMappingHypothesis` still validated `sourceWidthPx`/`sourceHeightPx` *before*
  `bufferWidthPx`/`bufferHeightPx` — so when **both** were invalid, the function returned
  `SOURCE_METADATA_UNAVAILABLE` with `expectedBufferBoundsPx=null`, directly violating the contract above
  (`null` only for `BUFFER_METADATA_UNAVAILABLE`). Fixed by validating the buffer domain first:
  `BUFFER_METADATA_UNAVAILABLE` now always wins when the buffer is invalid, regardless of the source's own
  validity, so `SOURCE_METADATA_UNAVAILABLE` is only ever reached once a valid `expectedBufferBoundsPx`
  already exists to preserve. Covered by a new test exercising six simultaneous-failure combinations
  (missing+missing, missing+non-positive, non-positive+non-positive, in both parameter orders).
- **`FrameTransformExportSnapshot` KDoc corrected to match actual behavior.** The prior KDoc said the
  hypothesis-verdict field was `null` "when no assessment could be attempted (no transform, or missing
  active-array/buffer dimensions)" — but the implementation always returns a typed
  `SOURCE_METADATA_UNAVAILABLE`/`BUFFER_METADATA_UNAVAILABLE` verdict in the missing-dimensions case, not
  `null`. KDoc corrected: the field is `null` only when no transform is present at all.
- **Removed the reserved `AnalysisBufferIntrinsicsResolution.DomainConsistencyUnproven` sealed variant**
  the prior pass added "for a future gate": no production path ever constructed it, and reserving a typed
  outcome for a source-domain model this codebase has not proven was itself premature — removed along
  with its formatter branches (`CameraSessionIntrinsicsDiagnosticFormat.kt`, `CamDiagnosticSnapshot.kt`,
  `CamDiagnosticReportFormat.kt`) and its dedicated test. A future typed resolver outcome may be added
  once the pinned (or an upgraded) CameraX version's source-domain/crop contract is actually source-traced
  or device-proven.
- **Still diagnostic-only; outcome precedence still unchanged.** `resolveAnalysisBufferIntrinsics` does
  not reject any currently-`Resolved` calibration, does not infer/synthesize a replacement matrix, and
  `UnsupportedLogicalMultiCameraMapping` remains the one externally returned CAM-2c outcome for the real
  Pixel 9 fixture — unchanged by this pass, verified again by the same resolver-level test.
  - *`:core:astro-core:test`*: **PASS, 480/480** (468 baseline + 12 in the renamed
    `WholeActiveArrayMappingHypothesisTest`: identity-matrix-mismatch, mismatch-never-claims-invalid,
    correct-scale-matches, scale-plus-translate exact mapped bounds, non-finite-mapped-bounds with
    buffer-bounds-preserved, missing-source-metadata with buffer-bounds-preserved (4 sub-cases),
    missing-buffer-metadata with no buffer bounds (4 sub-cases), **both-source-and-buffer-invalid always
    returns `BUFFER_METADATA_UNAVAILABLE` with `expectedBufferBoundsPx=null` (6 sub-cases, the
    buffer-first-precedence fix)**, projective-matrix `UNSUPPORTED_TRANSFORM_CLASS` with
    buffer-bounds-preserved, mirrored/singular still forward-mappable, every verdict carries the explicit
    basis, and tolerance validation).
  - *`:mobile:testInternalDebugUnitTest`*: **PASS, 400/400** (unchanged in total from the prior pass: the
    removed `DomainConsistencyUnproven` formatter test lived in the shared `mobile/src/test` set, not
    `testInternalDebug`, so it doesn't subtract here; the renamed/added assertions in
    `CamDiagnosticSnapshotTest`/`CamDiagnosticSnapshotJsonTest`/`CamDiagnosticReportFormatTest` replace
    their prior-pass equivalents one-for-one, plus one new "no misleading labels" JSON test).
  - *`:mobile:testPublicDebugUnitTest`*: **PASS, 370/370** (371 prior − 1: the removed
    `DomainConsistencyUnproven` formatter test lived in the shared `mobile/src/test` set, which both
    flavors run).
  - *`:mobile:compileInternalDebugAndroidTestKotlin`*: **PASS** — compiled only, not run on a connected
    device/emulator.
  - *`:mobile:lintInternalDebug`/`lintPublicDebug`*: **PASS, 0 errors, 30 warnings each** (identical to the
    pre-existing baseline — see `mobile/lint-baseline.xml`; no new lint finding).
  - *`:mobile:assembleInternalDebug`/`assemblePublicDebug`*: **PASS**, both debug APKs built.
  - *Confirmed unchanged:* CAM-2a projection math, CAM-2c intrinsics math's four-class composition
    algebra, the logical-multi-camera policy/precedence, camera binding, renderer, detector, matcher,
    catalog.
  - *Physical device/emulator execution*: **not run** by this pass — no physical device or emulator is
    available in this environment; this is a diagnostics/naming correction with no new device-evidence
    claim beyond what `docs/validation/cam_2c_pixel9_evidence.md` §3 already recorded.

**Overall CAM-2c diagnostics fix (hypothesis-scoped verdict) status: the diagnostic no longer overclaims —
every type, field, text label, and JSON key now names the one, specific, unproven hypothesis it tests
(whole-active-array-local source domain), and a mismatch is documented everywhere as evidence against
that hypothesis only, never as proof the matrix is broken, invalid, or known not to describe the real
pipeline. The reserved, unconstructed `DomainConsistencyUnproven` resolver outcome is removed. The pinned
CameraX version's actual source-coordinate-domain contract remains unresolved — this pass makes that
gap honestly visible, it does not close it — and calibrated mapping is still blocked first by the
unchanged logical-multi-camera guard.**

## CAM-2c hypothesis-diagnostic architecture fix — moved out of `:core:astro-core` (this sprint)

- **Trigger:** a review found `WholeActiveArrayMappingHypothesis.kt` (`assessWholeActiveArrayMappingHypothesis`
  and its `SensorToBufferDomainBounds`/`SourceDomainBasis`/`WholeActiveArrayHypothesisVerdict`/
  `WholeActiveArrayMappingAssessment` types) living in `core/astro-core/src/main` — the shared, public
  production core module's own API surface — despite its **only** non-test caller being the
  `internalDebug`-only CAM diagnostic export. This shipped an unproven diagnostic hypothesis model as
  part of the production core API despite zero production behavior callers.
- **Scope:** diagnostic-only, same as every pass in this workstream — no change to CAM-2a projection
  math, CAM-2c intrinsics composition or resolver, logical-multi-camera precedence, CameraX binding,
  renderer, detector, matcher, catalog, the exported JSON schema version/keys, or the real Pixel 9
  evidence.
- **Moved, not duplicated.** The complete implementation moved from
  `core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/projection/camera/WholeActiveArrayMappingHypothesis.kt`
  to `mobile/src/internalDebug/java/dev/pointtosky/mobile/ar/camera/WholeActiveArrayMappingHypothesis.kt` —
  the exact same, unmodified logic (buffer-first precedence, all six verdicts, the explicit
  `ASSUMED_WHOLE_ACTIVE_ARRAY_LOCAL` basis), now compiled only into the `internalDebug` variant. Every
  type and the function itself are now `internal` (never part of any public API): `SensorToBufferDomainBounds`,
  `SourceDomainBasis`, `WholeActiveArrayHypothesisVerdict`, `WholeActiveArrayMappingAssessment`,
  `DEFAULT_WHOLE_ACTIVE_ARRAY_HYPOTHESIS_TOLERANCE_PX`, `assessWholeActiveArrayMappingHypothesis`. It
  still builds only on the immutable, already-public core `SensorToBufferMatrix3`/
  `classifySensorToBufferMatrix` APIs — no reflection bridge, no duplicated matrix-classification logic.
- **Tests moved alongside it.** `WholeActiveArrayMappingHypothesisTest` (all 12 cases, unchanged) moved
  from `core/astro-core/src/test` to `mobile/src/testInternalDebug` in the same package, importing
  `SensorToBufferMatrix3`/`SensorToBufferTransformClass`/`classifySensorToBufferMatrix` from `:core:astro-core`
  the same way every other `testInternalDebug` file does.
- **Variant-boundary proof extended.** `CamDiagnosticsInternalDebugVariantBoundaryTest`
  (`testInternalDebug`) now also asserts `SensorToBufferDomainBounds`/`SourceDomainBasis`/
  `WholeActiveArrayHypothesisVerdict`/`WholeActiveArrayMappingAssessment`/`WholeActiveArrayMappingHypothesisKt`
  are present on the `internalDebug` classpath; `CamDiagnosticsPublicVariantBoundaryTest` (`testPublicDebug`)
  now also asserts they are genuinely **absent** from `publicDebug`'s compiled classpath — the same
  `Class.forName` compile-time-resolution technique the export UI classes already used, not a new
  mechanism.
- **`docs/validation/cam_2c_pixel9_evidence.md` wording fix.** "despite the transform being present and
  usable on every frame" (§1) — which could be read as a semantic-usability claim — corrected to "despite
  a transform being present and structurally classified as `AXIS_ALIGNED_0` on every frame."
  - *`:core:astro-core:test`*: **PASS, 468/468** — back to this module's original baseline; the 12
    hypothesis tests no longer live here.
  - *`:mobile:testInternalDebugUnitTest`*: **PASS, 412/412** (400 prior + 12: the moved
    `WholeActiveArrayMappingHypothesisTest` cases, now running as `internalDebug` mobile tests).
  - *`:mobile:testPublicDebugUnitTest`*: **PASS, 370/370** (unchanged — the moved tests are
    `internalDebug`-only; `CamDiagnosticsPublicVariantBoundaryTest` gained new assertions, not new test
    methods).
  - *`:mobile:compileInternalDebugAndroidTestKotlin`*: **PASS** — compiled only, not run on a connected
    device/emulator.
  - *`:mobile:lintInternalDebug`/`lintPublicDebug`*: **PASS, 0 errors, 30 warnings each** (identical to
    the pre-existing baseline — see `mobile/lint-baseline.xml`; no new lint finding).
  - *`:mobile:assembleInternalDebug`/`assemblePublicDebug`*: **PASS**, both debug APKs built.
  - *Confirmed unchanged:* CAM-2a projection math, CAM-2c intrinsics composition/resolver, logical-multi-
    camera precedence, CameraX binding, renderer, detector, matcher, catalog, JSON schema version (still
    `3`) and every exported field name/value, real Pixel 9 evidence.
  - *Physical device/emulator execution*: **not run** by this pass — no physical device or emulator is
    available in this environment; this is a source-set/visibility move with no new device-evidence claim.

**Overall CAM-2c hypothesis-diagnostic architecture fix status: the unproven whole-active-array hypothesis
model no longer ships as part of `:core:astro-core`'s public production API — it now lives entirely inside
the `internalDebug`-only diagnostics source set, `internal`-scoped, with its absence from `publicDebug`
proven the same way every other export-only class's absence is proven. Behavior, JSON schema, and every
exported field name/value are unchanged. The pinned CameraX version's real sensor-to-buffer source domain
remains unresolved.**

## CAM-2c physical-camera provenance experiment (this sprint)

> **Corrected by "CAM-2c physical-camera provenance experiment fix" below:** a review found two P1
> correctness blockers (a fabricated `LOGICAL_MULTI_CAMERA` capability value, and calibrated intrinsics
> resolvable from physical-camera identity alone without independently proving the sensor-to-buffer
> transform's own source domain) plus five evidence-integrity defects in this section's own
> implementation. **CameraX physical-binding code is build/test-ready; physical binding is not yet
> device-verified; sensor-to-buffer transform-domain proof is a separate, still-required, currently
> always-failing gate; a fixture-level `Resolved` test result is not evidence a real Pixel 9's actual
> identity matrix is usable; the fixed zoom mitigates one lens-switch trigger, it does not prove
> frame-level physical-camera-identity stability; and no physical camera ID is ever selected by
> default.** Left as-written for historical record; do not treat any claim in this section as current
> without cross-checking the fix section below.

- **Trigger:** every prior CAM-2c pass above confirmed the same real Pixel 9 block twice
  (`docs/validation/cam_2c_pixel9_evidence.md` §1/§3): `UnsupportedLogicalMultiCameraMapping`, because
  this project's pinned `androidx.camera:camera-camera2:1.3.4` cannot bind a concrete physical camera
  or read per-frame physical-camera provenance. This pass is the first to attempt the version bump that
  unlocks those APIs and build the physical-binding path on top of it.
- **Scope:** CameraX dependency upgrade; a new `internalDebug`-only camera-topology recon export; a new
  `internalDebug`-only explicit physical-camera-binding experiment (its own standalone `Activity`, not
  layered onto the live AR screen); a new, additive CAM-2c resolution entry point for a verified
  physical binding. Does **not** touch CAM-2a projection math, magnetic declination handling, the
  device-to-optical transform, the renderer, the detector, the matcher, the star catalog, or
  `AnalysisBufferIntrinsicsResolver.kt`'s existing `UnsupportedLogicalMultiCameraMapping` guard — zero
  lines of that file changed. See `docs/camera_coordinate_calibration_contract.md` §3.9 for the full
  design and the `javap`-inspected API evidence.
- **CameraX upgrade:** `1.3.4` → `1.4.2` (not the current stable `1.6.1` — that requires AGP `8.9.1`+
  and compileSdk `36`, a project-wide change confirmed out of scope by an actual failed compile attempt
  against this project's pinned `AGP 8.7.2`/`compileSdk 35`; `1.4.2` is the latest stable patch of the
  first line exposing `CameraSelector.setPhysicalCameraId`/`CameraInfo.getPhysicalCameraInfos()`, and it
  compiles clean with zero new warnings). Dependencies moved into `gradle/libs.versions.toml`
  (`camerax = "1.4.2"`), replacing four previously hardcoded, uncatalogued version strings.
- **Physical-camera binding:** `CameraPreview.kt` gained two purely additive, default-`null`/no-op
  parameters (`cameraSelectorOverride`, `onExplicitBindFailure`) — no production call site passes a
  non-default value, so the live AR screen's behavior is unchanged. The new
  `PhysicalCameraBindingExperimentActivity` (`internalDebug`-only, registered only in
  `mobile/src/internalDebug/AndroidManifest.xml`) binds `Preview`+`ImageAnalysis` together through one
  explicit `CameraSelector.Builder().setPhysicalCameraId(candidate)`, fixes zoom to `1.0×` immediately
  after bind (task §9's mitigation for the "no per-frame physical-camera-identity API exists at CameraX
  1.4.2" limitation — documented, not solved), and never silently falls back to the logical camera on a
  bind failure.
- **Provenance, typed failures, narrow guard extension:** `PhysicalCameraProvenance`
  (`logicalCameraId`/`physicalCameraId`/`bindingMethod`/`confidence`) and the typed
  `PhysicalCameraBindingResolution` (`Bound`/`PhysicalCameraBindingUnavailable`/
  `PhysicalCameraIdentityUnverified`/`PhysicalCameraCharacteristicsMismatch`) live entirely in
  `internalDebug` — no production/public API surface added. `resolveCam2cForExplicitPhysicalCamera`
  wraps the **unchanged** `resolveAnalysisBufferIntrinsics(snapshot, ...)` with a physical camera's own,
  characteristics-verified snapshot; since that snapshot's own `cameraId` is the physical ID and its own
  `isLogicalMultiCamera` reads `false`, it simply never triggers the existing guard — the guard itself
  needed no modification. The ordinary, no-explicit-binding session path is completely unaffected and
  still hits `UnsupportedLogicalMultiCameraMapping` exactly as before, proven by the pre-existing
  `AnalysisBufferIntrinsicsResolverTest` cases continuing to pass unmodified.
- **Topology recon:** `CameraTopologyBuilder.kt` enumerates every rear Camera2 camera and its declared
  physical candidates via the raw `CameraManager` (never via ID ordering), exported through the existing
  CAM diagnostics dialog's new "Share topology" action.
- **Compile-time `internalDebug`-only boundary — verified two ways, not just by source-set placement.**
  `aapt dump xmltree` on the built `internal-debug` APK shows `PhysicalCameraBindingExperimentActivity`
  present; the identical command on the `public-debug` APK shows it absent. `strings` over the
  `public-debug` APK's `.dex` files finds zero occurrences of any new class/function name introduced by
  this pass.
- **Automated tests (task §11):** `PhysicalCameraProvenanceTest`, `Cam2cPhysicalCameraResolutionTest`,
  `CameraTopologyReportTest` (all new, `mobile/src/testInternalDebug`) — see
  `docs/camera_coordinate_calibration_contract.md` §3.9 for the exact case list. A grep of every new
  production file for the literal strings `"2"`/`"3"`/`"4"` found zero hardcoded physical-camera-ID
  matches.
- **Validation closure pass (this session) — real Gradle, a different kind of environment than every
  prior CAM-2c pass.** This session ran in a Claude Code remote-execution container with real network
  access — a JDK 17 toolchain and a real Android SDK were installed via `apt`/`sdkmanager`, and Gradle
  8.14.3 (pre-installed in the container) ran every gate below as a genuine, non-fabricated build.
  Still: **no physical Android device or emulator is attached to this container**, and none could be
  attached — the same fundamental gap every prior CAM-2c pass recorded, not a smaller version of it.
  - *`:core:astro-core:test`*: **PASS, 468/468** (unchanged — no `:core:astro-core` code touched).
  - *`:mobile:testInternalDebugUnitTest`*: **PASS, 426/426** (412 baseline + 14 new).
  - *`:mobile:testPublicDebugUnitTest`*: **PASS, 370/370** (unchanged — confirms zero leakage into the
    public variant).
  - *`:mobile:compileInternalDebugKotlin`/`compilePublicDebugKotlin`*: **PASS**, zero new warnings.
  - *`:mobile:compileInternalDebugAndroidTestKotlin`*: **PASS.**
  - *`:mobile:lintInternalDebug`/`lintPublicDebug`*: **PASS, 0 errors** (28/26 warnings; two new,
    pre-existing-pattern warnings — `LockedOrientationActivity`/`DiscouragedApi` on the new experiment
    `Activity`'s `android:screenOrientation="portrait"`, mirroring the identical warning `MainActivity`
    itself already carries — not a new class of problem).
  - *`:mobile:assembleInternalDebug`/`assemblePublicDebug`*: **PASS**, both debug APKs built and
    manifest/`.dex`-inspected as described above.
  - *Physical Pixel 9 validation (task §9/§10)*: **not executed** — no physical device or emulator is
    available in this environment. No residual data, no confirmation that a real device's
    `getPhysicalCameraInfos()` actually reaches `PhysicalCameraBindingResolution.Bound`, and no live
    "selected physical camera and evidence supporting that selection" exist from this pass.

**Overall CAM-2c physical-camera provenance experiment status: `CAM-2c PHYSICAL CAMERA PROVENANCE
BLOCKED` — every code/test/lint/assemble gate above is real-Gradle green, the resolution logic and
typed outcomes are proven correct against fixtures, and the compile-time `internalDebug`-only boundary
is verified in the actual packaged APK output — but this remains a code-readiness result, not a device
validation result. No physical Pixel 9 (or any device/emulator) was available in this session to
exercise the actual binding call, confirm `getPhysicalCameraInfos()` behavior, or capture the §10
residual evidence the task requires before any "CAM-2c works" claim would be justified. See the final
verdict in the PR description / final report.**

## CAM-2c physical-camera provenance experiment fix (this sprint)

> **Corrected by "CAM-2c physical-camera provenance experiment — runtime correctness fix" below:** this
> section's launch-path test (`PhysicalCameraBindingExperimentActivityLaunchTest`) only compared a
> reflected class name against a second, independently hand-written string - it never proved the in-app
> action actually launches the registered `Activity`. That pass also fixed a stale-callback defect in
> `CameraPreview`/the experiment's own session state (introduced by this pass's Fix 3/Fix 5 work, not
> caught by this pass's own tests) and a resource-lifecycle gap on explicit zoom/bind failure. See below.

- **Trigger:** a review of the pass above found two P1 correctness blockers and five evidence-integrity
  defects, all confined to the CameraX physical-binding experiment and its `internalDebug` diagnostics -
  see `docs/camera_coordinate_calibration_contract.md` §3.10 for the full design and evidence
  (`javap`-confirmed real capability constant, the new `SensorToBufferDomainProof` gate, both-`CameraInfo`-shape
  handling, etc.). Does not touch CAM-2a projection math, magnetic declination handling, the
  device-to-optical transform, the renderer, the detector, the matcher, the star catalog, or
  `AnalysisBufferIntrinsicsResolver.kt`'s ordinary `UnsupportedLogicalMultiCameraMapping` guard.
- **Fix 1 (P1):** `CameraTopologyBuilder.kt` mapped raw capability `29` to `LOGICAL_MULTI_CAMERA` - the
  real Android constant (confirmed via `javap` against this project's pinned `android-35` `android.jar`)
  is `11`. Fixed; `CameraTopologyBuilderCapabilityLabelTest` proves the real constant maps correctly and
  that `29` no longer does.
- **Fix 2 (P1):** `resolveCam2cForExplicitPhysicalCamera` could previously resolve calibrated
  `AnalysisBuffer` intrinsics from a verified physical-camera binding alone, without independently
  proving the sensor-to-buffer transform's own source domain - meaning a verified binding over the real
  Pixel 9's own recorded identity matrix (`docs/validation/cam_2c_pixel9_evidence.md` §3) could have
  resolved despite that matrix already being known to fail this codebase's one testable hypothesis. Fixed
  with a new `SensorToBufferDomainProof` sealed type (`ProvenActiveArrayLocal`/
  `ProvenPreCorrectionActiveArrayLocal`/`ProvenAnalysisSourceDomain`/`Unresolved`/`HypothesisMismatch`) -
  only `ProvenActiveArrayLocal` unlocks calling `resolveAnalysisBufferIntrinsics`; every real, current
  code path in this codebase can only ever produce `Unresolved`/`HypothesisMismatch`, so
  **`resolveCam2cForExplicitPhysicalCamera` cannot resolve automatically on any device this codebase has
  run on, by design** - preserving the existing whole-active-array hypothesis diagnostic as evidence
  only, never promoting a match to proof.
- **Fix 3:** `resolvePhysicalCameraBindingFromCameraInfo` previously assumed the bound `CameraInfo` is
  always the logical camera's own. A new pure `selectPhysicalCameraInfoSource` (8 new tests) now handles
  both possible CameraX shapes - the bound `CameraInfo` itself already being physical, or the classic
  logical-with-nested-physical-candidates shape - via exact identity comparison, never ID ordering.
  `PhysicalCameraProvenance.bindingSource` records which shape a session matched.
- **Fix 4:** `logicalCameraId` is now `String?`, never fabricated as equal to the physical ID when
  unknown - the experiment report renders `"known(id)"`/`"unavailable"` explicitly.
- **Fix 5:** `CameraControl.setZoomRatio`'s asynchronous future is now actually awaited (via a new shared
  `ListenableFuture.awaitCompletion` suspend adapter) before `onCameraInfo` fires, with a re-checked
  disposal guard at that new suspension point and a typed `EXPLICIT_SELECTOR_ZOOM_FAILED` failure on
  zoom failure - the prior revision fired the future and proceeded as if `1.0×` were already applied.
- **Fix 6:** the experiment report previously printed `CameraIntrinsics.focalLengthMm` (millimetres)
  labelled `"K: fx="`, conflating it with `CameraCalibrationDiagnostics.bufferFxPx` (buffer-space
  pixels). Now prints the real buffer-space `fxPx`/`fyPx`/`cxPx`/`cyPx`, with `focalLengthMm` on its own
  labelled line; `formatCam2cResultLines` was extracted so this is directly unit-tested
  (`PhysicalCameraExperimentReportFormatTest`).
- **Fix 7:** `CameraTopologyReport.kt`'s number formatting used the platform default locale (comma
  decimals under e.g. German); now uses `Locale.ROOT` explicitly, proven by a `Locale.GERMANY`
  regression test.
- **Fix 8:** the experiment `Activity` is `android:exported="false"`, but the prior revision documented
  launching it via `adb shell am start`, which cannot reliably reach a non-exported component. Fixed
  with a real in-app entry point (`CamDiagnosticFullReportDialog`'s new "Open physical-camera
  experiment" action, a same-app `Intent`) - `exported="false"` is unchanged.
- **Fix 9/10:** documentation corrected throughout (this entry, `docs/camera_coordinate_calibration_contract.md`
  §3.10, `docs/validation/cam_2c_pixel9_evidence.md` §6); `CamDiagnosticsInternalDebugVariantBoundaryTest`/
  `CamDiagnosticsPublicVariantBoundaryTest` extended with every new class.
- **Validation closure pass (this session) — real Gradle**, same provisioned JDK 17 + Android SDK +
  Gradle 8.14.3 as the pass this fixes.
  - *`:core:astro-core:test`*: **PASS, 468/468** (unchanged).
  - *`:mobile:testInternalDebugUnitTest`*: **PASS, 451/451** (426 baseline + 25 new).
  - *`:mobile:testPublicDebugUnitTest`*: **PASS, 370/370** (unchanged).
  - *`:mobile:compileInternalDebugKotlin`/`compilePublicDebugKotlin`*: **PASS**, zero new warnings.
  - *`:mobile:compileInternalDebugAndroidTestKotlin`*: **PASS.**
  - *`:mobile:lintInternalDebug`/`lintPublicDebug`*: **PASS, 0 errors, 28/26 warnings** (identical counts
    to the pass this fixes - no new `InlinedApi` finding from the `capabilityLabel` guard rewrite).
  - *`:mobile:assembleInternalDebug`/`assemblePublicDebug`*: **PASS**, both debug APKs built.
  - *Physical Pixel 9 validation*: **still not executed** - no physical device or emulator is available
    in this environment. This fix pass corrects code-level defects found by review; it does not, and
    cannot, newly establish device-level facts.

**Overall CAM-2c physical-camera provenance experiment fix status: every P1 correctness blocker and
evidence-integrity defect a review found is fixed and covered by new tests; every gate above is
real-Gradle green. Final status, unchanged from the pass this fixes and restated explicitly: `CAM-2c
PHYSICAL CAMERA EXPERIMENT CODE READY` / `PHYSICAL BINDING DEVICE VALIDATION PENDING` / `SENSOR-TO-BUFFER
DOMAIN PROOF PENDING` / `CAM-2c CALIBRATED PIXEL 9 RESULT NOT YET ESTABLISHED` - see the final verdict in
the PR description / final report.**

## CAM-2c physical-camera provenance experiment — runtime correctness fix (this sprint)

- **Trigger:** a review of the pass above found a runtime correctness gap confined to
  `CameraPreview`'s callback delivery, the experiment's own session state/lifecycle, and its launch-path
  test coverage - not a device-observed defect (no physical device is attached in this environment; see
  below), a defect found by inspection of the callback/effect/state-transition code itself. Does not
  touch CAM-2a projection math, transform-domain policy, intrinsics math, the renderer, the detector, the
  matcher, the star catalog, or the ordinary logical-camera guard.
- **Fix A (stale callback capture):** `CameraPreview`'s `ImageAnalysis.Analyzer`/bind coroutine are
  installed inside a `DisposableEffect(Unit)` that runs exactly once per composition - a caller passing a
  *new* `onFrameMetadata`/`onCameraInfo`/`onExplicitBindFailure` lambda instance on a later recomposition
  (as the experiment's own session composable does on every state transition) did not, by itself, reach
  the already-installed analyzer, which kept invoking whichever lambda instance existed when the effect
  first ran. Fixed with a new `rememberStableCallback` (`CameraPreview.kt`, production, shared by every
  variant) - backed by `rememberUpdatedState`, `remember`ed once so installing it never itself triggers a
  rebind, but every invocation reads the latest lambda. On the experiment side, the old sealed
  `ExperimentPhase` (whose `Bound` variant bundled binding *and* frame together, and was captured by
  value inside callback closures) was replaced with a flat, pure `ExperimentSessionState` +
  `reduceCameraInfoResolved`/`reduceFrame`/`reduceExplicitBindFailure` reducer trio
  (`mobile/src/internalDebug/.../camera/ExperimentSessionState.kt`) - binding and frame are independent
  fields, either callback order is handled, a later frame never regresses or erases a resolved binding,
  and every reducer is a no-op for a stale/superseded `attemptId`. Covered by `ExperimentSessionStateTest`
  (11 pure JVM tests: order-independence, no-regression, superseded-attemptId no-op, terminal-failure
  no-op, cross-attempt isolation) and a new instrumented Compose test,
  `ExperimentCallbackFreshnessTest` (`androidTestInternalDebug`), that installs a callback while a session
  is still `Binding`, transitions it to `Bound` **without** recreating the simulated analyzer, invokes the
  original callback reference, and proves the report advances from `latestFrame=none yet`/
  `cam2cResult=awaiting frame` to a concrete frame and `DOMAIN_NOT_PROVEN` - the old value is never
  permanently captured. `buildPhysicalCameraExperimentReportText`'s own regression test in
  `PhysicalCameraExperimentReportFormatTest` reproduces the same progression at the pure-state level.
- **Fix B (terminal cleanup on explicit failure):** once `CameraPreview` reports an explicit zoom/bind
  failure, the session previously kept showing an `EXPLICIT_BIND_FAILED` banner while the camera/analyzer
  it had already bound stayed live indefinitely, with no in-app way back to candidate selection. Fixed:
  `PhysicalCameraBindingSession` now stops calling `CameraPreview` entirely once
  `ExperimentSessionState.isTerminallyFailed` is `true` - removing it from composition runs its own
  `DisposableEffect`/`CameraSessionLifecycle` disposal path and actually unbinds the camera; no second,
  ad-hoc unbind call was added, so `CameraSessionLifecycle`'s own exactly-once cleanup guarantee is
  unchanged. New "Retry" and "Back to candidates" actions give the user a way out of the terminal state.
  Every attempt (a fresh candidate pick, or a retry) now draws a new, never-reused `attemptId` from a new
  pure `ExperimentUiModel` (`startAttempt`/`retry`/`backToCandidates`/`updateSession`,
  `mobile/src/internalDebug/.../camera/ExperimentUiModel.kt`), plus `key(attemptId)` at the Compose level
  forcing a full subtree teardown/recreate on every new attempt - so a late callback from a failed or
  superseded attempt can never mutate a retried session. Covered by `ExperimentUiModelTest` (7 pure JVM
  tests, including "a callback from a superseded attemptId never overwrites the retried session") and
  `ExperimentSessionLifecycleUiTest` (`androidTestInternalDebug`, Compose): the terminal-failure banner
  renders and `CameraPreview` is never reached for that state (proven by code-path inspection - the
  `if (!state.isTerminallyFailed)` branch that calls `CameraPreview` is not taken), and Retry/Back actions
  invoke their callbacks exactly once.
- **Fix C (testable launch path):** the prior launch-path test
  (`PhysicalCameraBindingExperimentActivityLaunchTest`) compared a reflected class name against a second,
  independently hand-written string - proving the two strings match, never that the in-app action
  launches the registered `Activity`. Fixed: the `Intent(context, PhysicalCameraBindingExperimentActivity::class.java)`
  construction that was previously inlined in `CamDiagnosticFullReportDialog`'s `onClick` is now the one
  function `buildPhysicalCameraBindingExperimentIntent(context)`; the real button routes through it via a
  new, injectable `onOpenPhysicalCameraExperiment` parameter (defaulting to the real
  `startActivity(buildPhysicalCameraBindingExperimentIntent(context))` call - the same optional-injection
  seam `actions: CamDiagnosticActions?` already uses for Copy/Share). Two new `androidTestInternalDebug`
  tests, run against a real `Context`/`PackageManager`: `ExperimentLaunchIntentTest` asserts the built
  `Intent`'s `component.className` matches the experiment `Activity`'s real class name, that
  `PackageManager.resolveActivity` actually resolves that component in this `internalDebug` build (the
  same lookup a real `startActivity` performs), and that `PackageManager.getActivityInfo` reports
  `exported == false`; `CamDiagnosticPhysicalCameraExperimentLaunchUiTest` asserts a Compose click on
  "Open physical-camera experiment" invokes an injected recording action exactly once. Neither test opens
  the `Activity` itself on a real device - only a real Pixel 9 run (still pending, see below) can claim
  that.
- **Boundary:** `CamDiagnosticsInternalDebugVariantBoundaryTest`/`CamDiagnosticsPublicVariantBoundaryTest`
  extended with every new class (`ExperimentSessionState`, `ExperimentUiModel`, and their Kt-file
  top-level reducer functions); `:mobile:compilePublicDebugKotlin` and `:mobile:testPublicDebugUnitTest`
  both pass with none of this pass's new experiment code reachable from that variant.
- **Validation closure pass (this session) — real Gradle**, same provisioned JDK 17 + Android SDK +
  Gradle 8.14.3 as every pass above.
  - *`:mobile:testInternalDebugUnitTest`*: **PASS, 472/472** (451 baseline + 21 new: 10
    `ExperimentSessionStateTest` + 8 `ExperimentUiModelTest` + 3 new cases added to the existing
    `PhysicalCameraExperimentReportFormatTest`).
  - *`:mobile:testPublicDebugUnitTest`*: **PASS**, unchanged - no publicDebug-visible code touched.
  - *`:mobile:compileInternalDebugKotlin`/`compilePublicDebugKotlin`*: **PASS**, zero new warnings.
  - *`:mobile:compileInternalDebugAndroidTestKotlin`*: **PASS** (3 new instrumented test files:
    `ExperimentCallbackFreshnessTest`, `ExperimentSessionLifecycleUiTest`, `ExperimentLaunchIntentTest`,
    `CamDiagnosticPhysicalCameraExperimentLaunchUiTest`).
  - *Instrumented/connected tests* (`ExperimentCallbackFreshnessTest`, `ExperimentSessionLifecycleUiTest`,
    `ExperimentLaunchIntentTest`, `CamDiagnosticPhysicalCameraExperimentLaunchUiTest`): compiled, **not
    executed** - this environment has no connected device/emulator to run
    `connectedInternalDebugAndroidTest` against. They are code/compilation verified only, exactly as far
    as this pass can honestly claim.
  - *Physical Pixel 9 validation*: **still not executed** - no physical device or emulator is available in
    this environment. This fix pass corrects code-level defects found by review and adds tests; it does
    not, and cannot, newly establish device-level facts.

**Overall CAM-2c physical-camera provenance experiment (runtime correctness fix) status: the stale-callback
defect, the terminal-lifecycle gap, and the launch-path testability gap are all fixed and covered by new
tests - pure JVM tests exercise every reducer/state-transition guarantee directly; new instrumented Compose
tests exercise callback freshness, terminal cleanup, and the real launch `Intent`/`PackageManager`
resolution, though those instrumented tests are themselves only compiled, not executed, in this
device-less environment. Automatic transform-domain proof remains unavailable in this codebase by design
(see the fix above), so the expected current successful experiment outcome, once a physical device is
available, is verified physical binding plus `DOMAIN_NOT_PROVEN` - never `CAM-2c Resolved`. Final status:**

```
CAM-2c PHYSICAL CAMERA EXPERIMENT CODE READY
CALLBACK/LIFECYCLE PATH TESTED
PHYSICAL BINDING DEVICE VALIDATION PENDING
SENSOR-TO-BUFFER DOMAIN PROOF PENDING
CAM-2c CALIBRATED PIXEL 9 RESULT NOT YET ESTABLISHED
```

## CAM-2b (this sprint)

- **Scope:** visualizes CAM-2a's output; never changes production star placement.
  `ArScreen.calculateOverlay()`/`projectionParams(viewport)` unchanged except a byte-for-byte-equivalent
  extraction (`visibilitySelectedStars`) shared with the new overlay.
- **Gate:** reuses `CameraGeometryDiagnosticsGate`/`isDiagnosticsEnabled` verbatim — no second flavor
  check. Enabled only for `internalDebug`.
- **Catalog input:** bounded (200), deterministic (brightest-first), sourced from the phone's own
  visibility-selected `StarRecord` prefix — no second catalog parser, no spatial index.
- **Observer/time/declination:** reuses `ArUiState.Ready.location`/`.instant` and the legacy renderer's
  already-computed `GeomagneticField` declination — no independent `System.currentTimeMillis()`, no
  second `GeomagneticField` instance, no silent `0°` fallback.
- **True-north ownership:** raw, magnetic-north-referenced `CameraSessionGeometry` rotation +
  non-zero `StarProjectionContext.magneticDeclinationRad` — never a matrix pre-corrected via
  `correctedForTrueNorth` in addition to a non-zero context declination (would double-correct).
- **Recomputation:** Compose `remember`, keyed on geometry observation / observer inputs / bounded star
  subset — no polling, no timer, no permanent coroutine, no unbounded queue.
- **Drawing:** only `VISIBLE_IN_VIEWPORT` predictions, anchored exactly at CAM-2a's `displayPoint`, no
  further transform. Fixed cyan diagnostic marker style, drawn on top of the unchanged legacy overlay.
- **Intrinsics mode:** an explicit `PredictedStarOverlayIntrinsicsMode` (`SESSION_INTRINSICS` default,
  `DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK` opt-in) lets CAM-2b draw markers on sessions whose real
  intrinsics are `PhysicalSensor`-referenced (common once Camera2 characteristics resolve), by
  substituting a legacy fixed-FOV, analysis-buffer-referenced intrinsics sized to the exact current
  frame — via `CameraSessionGeometry.withIntrinsics` (`:core:astro-core`), a total (cannot-fail) field-
  level copy of the accepted geometry bundle that changes only `intrinsics` and never re-derives a
  pairing tolerance from the accepted delta, never mutates the original session geometry or provider,
  and is always labeled distinctly ("session" vs. "diagnostic fallback," never "calibrated") in the
  panel.
- **Batched astronomy:** `projectStars` now computes local sidereal time once per batch
  (`PreparedStarProjectionContext`, `:core:astro-core`) instead of once per star, avoiding up to 200
  redundant `lstAt` calls per frame; the public `equatorialToLocalSky(star, context)` API and its output
  are unchanged.
- **Validation closure pass (this session) — real Gradle, not the earlier `kotlinc` workaround.** This
  environment shipped neither an Android SDK nor the project's pinned Gradle 8.11.1 distribution (its
  download redirects to a `github.com` release asset blocked by this environment's egress policy, and its
  pinned JDK-17 toolchain was also missing); both were provisioned locally for this pass — an Android SDK
  (`platform-tools`, `platforms;android-35`, `build-tools;35.0.0`, via `sdkmanager` against the reachable
  `dl.google.com`) and `openjdk-17-jdk-headless` (via `apt`) — and the preinstalled Gradle 8.14.3 binary was
  used in place of the unreachable pinned 8.11.1 (AGP 8.7.2 built and ran cleanly under it for every gate
  below; this substitution is a recorded environment deviation, not a silent one). With those in place,
  every gate below is a real, executed Gradle result:
  - *Main compilation*: `:mobile:compileInternalDebugKotlin` / `compilePublicDebugKotlin` — **PASS**
    (pre-existing warnings only: a deprecated `defaultDisplay` API and a missing `@OptIn` marker, neither
    touched by CAM-2b).
  - *JVM unit tests*: `:core:astro-core:test` — **PASS, 388/388** (0 failures/errors; includes
    `CameraSessionGeometryTest` 34, `PreparedStarProjectionContextTest` 3, `CameraStarProjectionTest` 35,
    each also run individually via `--tests`). `:mobile:testInternalDebugUnitTest` /
    `testPublicDebugUnitTest` — **PASS, 271/271 each** (0 failures/errors; includes
    `PredictedStarOverlayReducerTest` 32, `PredictedStarOverlayFormatTest` 6,
    `PredictedStarCatalogAdapterTest` 10, each also run individually via `--tests`).
  - *androidTest compilation* (`:mobile:compileInternalDebugAndroidTestKotlin` /
    `compilePublicDebugAndroidTestKotlin`): **initially FAILED** on a real, reproducible compile error —
    not in any CAM-2b file (`PredictedStarOverlayUiTest.kt` already compiled cleanly, using exactly the
    imports the task spec calls for: `androidx.compose.ui.test.assert`, `org.junit.Assert.assertEquals`),
    but in the pre-existing, unrelated `PreProdSmokeMobileTest.kt`: an invalid
    `import androidx.compose.ui.test.assertExists` (that symbol is a member function of
    `SemanticsNodeInteraction` in the resolved Compose UI Test 1.7.2, not a top-level import) and a missing
    `import dev.pointtosky.mobile.settings.from` (an extension function on `MobileSettings.Companion`,
    already correctly imported the same way in `MainActivity.kt`/`DlReceiverService.kt`). Fixed by
    deleting the stale import and adding the missing one (2-line diff, no production code touched); rerun
    — **PASS** for both flavors.
  - *Lint*: `:mobile:lintInternalDebug` / `lintPublicDebug` — **PASS, 0 errors** (35 pre-existing warnings,
    148 errors/54 warnings pre-filtered by the existing `lint-baseline.xml`; none attributable to CAM-2b).
  - *Assemble*: `:mobile:assembleInternalDebug` / `assemblePublicDebug` — **PASS**, both debug APKs built.
  - *Release*: `compileInternalReleaseKotlin` / `compilePublicReleaseKotlin` — **PASS**. Full
    `assembleInternalRelease` / `assemblePublicRelease` — **blocked**: `packageInternalRelease` fails with
    `SigningConfig "release" is missing required property "storeFile"` (no release keystore is configured
    in this environment — a credential-provisioning limitation, not a code defect). One run additionally
    hit a non-reproducible crash inside AGP's *bundled* Lint tool
    (`ConcurrentModificationException` in `GradleDetector`/`TomlUtilities` during
    `lintVitalAnalyzeInternalRelease`) that did not recur on retry and does not occur on the debug-variant
    lint gates against the identical `build.gradle.kts` — recorded as an observed upstream tooling flake,
    out of CAM-2b's scope to fix.
  - *Connected instrumentation tests* (`:mobile:connectedInternalDebugAndroidTest`): **not run** — no
    physical device and no emulator (no `/dev/kvm`, no hardware-virtualization CPU flags) are available in
    this environment; `adb devices -l` lists none.
  See `docs/camera_star_prediction_contract.md` §14.10 for the full itemized disclosure and
  `docs/validation/cam_2b_device_validation.md` for the still-unexecuted physical checklist.

**Overall CAM-2b status: `CAM-2b BLOCKED ON PHYSICAL DEVICE VALIDATION`** — every build/compile/unit-test/
lint/debug-assemble gate above is now real-Gradle green (one real, pre-existing bug found and fixed along
the way); only the physical-device/emulator checklist remains unexecuted, for lack of any device or
emulator capability in this environment.
