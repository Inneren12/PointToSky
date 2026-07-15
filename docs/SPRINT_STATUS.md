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
| CAM-2c | Calibrated Camera2-to-`ImageAnalysis`-buffer pinhole intrinsics: active-array intrinsics (`LENS_INTRINSIC_CALIBRATION` when coordinate-space-verified, else focal-length-derived), mapped through the real `ImageInfo.getSensorToBufferTransformMatrix()` (not `ImageProxy.cropRect`, proven buffer-space/always-identity here), typed `AnalysisBufferIntrinsicsResolution`, `CameraIntrinsics.source=CAMERA_CHARACTERISTICS`+`reference=AnalysisBuffer` now accepted by CAM-2a unchanged, `CameraIntrinsicsQuality`, debug-only calibration diagnostics panel | Yes | Yes | **Yes — real Gradle.** `:core:astro-core:test` 436/436 (incl. new `ActiveArrayIntrinsicsTest`/`AnalysisBufferIntrinsicsMappingTest`/`CalibratedAnalysisBufferProjectionTest`), `:mobile:testInternalDebugUnitTest`/`testPublicDebugUnitTest` 309/309 each (incl. new `AnalysisBufferIntrinsicsResolverTest`/`CameraFrameMetadataSourceTest` matrix-extraction cases/`CameraCalibrationDiagnosticFormatTest`); see below | **Yes** — `compileInternalDebugKotlin`/`compilePublicDebugKotlin`, `lintInternalDebug`/`lintPublicDebug` (0 new errors/warnings after fixing two genuine `NewApi`/`InlinedApi` findings on `LENS_DISTORTION`/`REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA`, both API-28-gated), `assembleInternalDebug`/`assemblePublicDebug` all real-Gradle green; see below | **No — physical-device validation (task §10) could not be attempted: no physical device or emulator is available in this environment** |

## CAM-2c (this sprint)

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
