# CAM-2c: real Pixel 9 evidence, and the CAM diagnostic export/freeze workflow

This file has three distinct sections, kept deliberately separate because they have different
provenance and different confidence levels:

1. **Original Pixel 9 evidence** — the first confirmed, real-device CAM-2c observation. Collected
   *before* the export/freeze workflow existed, through the previous HUD and multiple scrolling
   screenshots.
2. **Diagnostic workflow implemented in response** — what was built afterward, specifically because
   collecting section 1's evidence that way was slow and error-prone.
3. **New workflow device verification** — the status of actually running the new workflow on a
   physical device. As of this writing, **not yet executed** — see that section for exactly what is
   and is not claimed.

Do not read section 2 as having produced section 1's evidence. It did not. The workflow was built
*after* section 1 was already observed, so that future evidence-gathering (CAM-2d or otherwise) would
not need another round of screenshot stitching.

## 1. Original Pixel 9 evidence (screenshot-derived)

Collected via the previous, now-superseded diagnostics HUD: expand the HUD, scroll through several
`Text` overlays, and manually read/transcribe the values across multiple screenshots. Superseding every
earlier "expected to hit `UnsupportedLogicalMultiCameraMapping`" prediction in `docs/SPRINT_STATUS.md`
and `docs/camera_coordinate_calibration_contract.md` with an actual observation.

```text
cameraId=0
logical=true
physicalIds=2,3,4
matrixClass=AXIS_ALIGNED_0
transformPresent=1115/1115
CAM-2c=UnsupportedLogicalMultiCameraMapping
publishedReference=PhysicalSensor
```

Read literally, field by field:

- `cameraId=0` — the bound Camera2 camera ID for this session's rear camera is `"0"`.
- `logical=true` — that camera ID's `REQUEST_AVAILABLE_CAPABILITIES` includes
  `REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA`; its static `CameraCharacteristics` are not
  guaranteed to describe whichever physical sensor actually produced a given analyzed frame (see
  `AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping`'s own KDoc).
- `physicalIds=2,3,4` — the declared physical camera IDs behind logical camera `0`, read via the
  read-only `CameraManager.getCameraCharacteristics(cameraId).physicalCameraIds` diagnostic bypass
  (CAM-2c fix §5, "Option C") — never used to bind or select a physical camera.
- `matrixClass=AXIS_ALIGNED_0` — every observed frame's `ImageInfo.getSensorToBufferTransformMatrix()`
  classified as `SensorToBufferTransformClass.AXIS_ALIGNED_0` (pure scale + translate, both scales
  positive) — the one class this codebase's calibrated mapping is willing to compose.
- `transformPresent=1115/1115` — of 1115 analyzed frames in this session, all 1115 carried a non-null
  sensor-to-buffer transform, and all 1115 classified as the usable `AXIS_ALIGNED_0` class
  (`CameraSessionIntrinsicsFrameCounters.framesWithUsableTransform` / `.framesAnalyzed`). The
  sensor-to-buffer transform pipeline itself is working correctly and consistently on this device — this
  is not the failure.
- `CAM-2c=UnsupportedLogicalMultiCameraMapping` — despite the transform being present and usable on
  every frame, `resolveAnalysisBufferIntrinsics` still refuses to build a calibrated mapping, because
  `logical=true` (see `AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping`'s KDoc):
  this codebase's pinned `androidx.camera:camera-camera2:1.3.4` cannot bind a concrete physical camera
  (`CameraSelector.setPhysicalCameraId`) or read per-frame physical-camera provenance
  (`CameraInfo.getPhysicalCameraInfos()`) — both require `1.4.0-beta01`+.
- `publishedReference=PhysicalSensor` — CAM-1b's unchanged fallback path published a
  `CameraIntrinsicsReference.PhysicalSensor`-referenced `CAMERA_CHARACTERISTICS` value instead, exactly
  as designed for a non-`Resolved` CAM-2c attempt (`resolveCameraIntrinsicsPreferringCalibration`). CAM-2a
  still correctly rejects a `PhysicalSensor` reference for projection (unchanged), which is why the
  downstream CAM-2b symptom is `PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED`.

**What this confirms:**

- **CAM-2c's runtime diagnostics passed.** The coordinator, the coherent-input gate, the per-frame
  transform classification, the typed `AnalysisBufferIntrinsicsResolution` outcome, the camera
  characteristics read (camera ID, logical flag, physical IDs), and the CAM-1b fallback publication all
  behaved exactly as designed and documented, on a real device, across a real 1115-frame session. Nothing
  here is a bug in the runtime integration — every prior CAM-2c fix round's own reasoning is now backed
  by an actual observation, not just unit-test fixtures and "expected to..." language.
- **Calibrated mapping remains blocked on physical-camera provenance**, not on anything any later fix
  round addresses. The block is exactly the one every prior CAM-2c entry in `docs/SPRINT_STATUS.md`
  predicted: a real Pixel 9 rear camera is a logical multi-camera, and this codebase's pinned CameraX
  version has no way to bind or attribute a concrete physical sensor. Lifting this block requires either
  bumping `androidx.camera:camera-camera2` past `1.4.0-beta01` (Option A/B from the CAM-2c fix's own
  KDoc) or an equivalent read of per-frame physical-camera identity — out of scope here. **This is not
  CAM-2d and CAM-2d is not started by this file.**

## 2. Diagnostic workflow implemented in response (internalDebug only)

Built *after* section 1's evidence was already in hand, specifically because reaching it required
manually reading and transcribing several scrolled, multi-block `Text` overlays across multiple
screenshots. The goal is that any future evidence-gathering session (CAM-2d or otherwise) can use
"Freeze snapshot" → "Copy all"/"Share log"/"Share JSON" instead of repeating that process — not a claim
that this workflow produced section 1's evidence.

Replaces the previous "expand the HUD, scroll, screenshot, repeat" workflow for the CAM diagnostics HUD
(`dev.pointtosky.mobile.ar.CamDiagnosticTopPanels`, gated at runtime by
`CameraGeometryDiagnosticsGate.isEnabled`, and — since the architecture fix below — compiled only into
the `internalDebug` variant in the first place, not merely hidden behind a runtime check):

- **Compile-time `internalDebug`-only boundary.** `mobile/src/main` defines only a variant-safe seam -
  `CamDiagnosticsExportUi` (an interface) and `CamDiagnosticsExportInput` (a plain value) in
  `dev.pointtosky.mobile.ar.camera.CamDiagnosticsExportUi.kt`. The real implementation
  (`CamDiagnosticsExportUiProvider`, the snapshot capture, the text/JSON formatters, the clipboard/share
  actions, the full-report dialog) lives under `mobile/src/internalDebug/...` and is compiled only into
  the `internalDebug` variant; `mobile/src/release/...` and `mobile/src/publicDebug/...` each provide a
  no-op `CamDiagnosticsExportUiProvider` that renders nothing and references none of that code. No
  reflection, no runtime class lookup, no reliance on R8/minification (`publicDebug` is never minified in
  this project) — a plain Kotlin/AGP source-set resolution. See `docs/SPRINT_STATUS.md` and this
  workstream's own `CamDiagnosticsExportUi.kt` KDoc for the full source-set layout, and
  `CamDiagnosticsPublicVariantBoundaryTest`/`CamDiagnosticsInternalDebugVariantBoundaryTest`
  (`mobile/src/testPublicDebug`/`mobile/src/testInternalDebug`) for the classpath-presence proof.
- **`CamDiagnosticSnapshot`** (`dev.pointtosky.mobile.ar.camera.CamDiagnosticSnapshot`, `internalDebug`
  only) — a bounded, **deep-copied, read-only export DTO tree** of one CAM diagnostics moment: `cam2b`
  (status/reason scalars only — never the overlay `points`/`summary` payload), `cam2c` (coordinator
  state, typed attempt, published intrinsics, camera metadata, frame transform - all 9 sensor-to-buffer
  matrix values as a plain `List<Double>`, resolved buffer `K` when available), `geometry` (CAM-1g's
  buffer/crop/rotation/viewport/session counters), and `calibration`. Every `Set`/`FloatArray` from the
  runtime types it is captured from is normalized into a freshly built, sorted `List` at capture time
  (`source?.toList()?.sorted()`, `source?.map(Float::toDouble)`) - mutating the original array/set
  afterward cannot change an already-captured snapshot (see `CamDiagnosticSnapshotTest`'s
  mutation-regression tests). Kotlin's `List` is a read-only *interface*, not a JVM-enforced
  immutability guarantee, so this document deliberately avoids claiming no caller can ever mutate a
  backing collection through an unsafe cast; what is guaranteed is that no field retains a reference to
  a caller-owned mutable collection in the first place. Never a live provider/coordinator reference,
  never star catalog contents, never image pixels.
- **Compact HUD summary** — the expanded HUD now shows only the highest-value root-cause summary (camera,
  physical IDs, matrix class/health, published reference, and a short reason phrase when blocked), not
  the previous giant per-domain text blocks. "Open diagnostics" opens the full surface.
- **Full diagnostics dialog** (`dev.pointtosky.mobile.ar.CamDiagnosticFullReportDialog`, `internalDebug`
  only) — a full-screen, scrollable dialog with every action pinned above the scrollable report body,
  which itself ends in an explicit "END OF CAM DIAGNOSTICS" marker node so a scroll-to-end test has a
  real target to reach (not just the giant report `Text` existing somewhere in the tree):
  - **Freeze snapshot / Resume live** — freezes the displayed report to one captured moment (camera and
    projection keep running unaffected underneath); repeated recompositions never replace a frozen
    snapshot; Resume immediately reflects the current live value. The live snapshot's own timestamp is
    only re-stamped when the diagnostic input actually changes value or the dialog (re)opens - never on
    every arbitrary recomposition, and never via a polling timer.
  - **Copy all** — copies the complete, deterministic plain-text report
    (`buildCamDiagnosticReportText`) to the real Android `ClipboardManager` — never a Compose
    semantics-tree scrape.
  - **Share log** / **Share JSON** — `ACTION_SEND`, `type = text/plain`, built via the pure
    `buildCamDiagnosticShareIntent(subject, text)` (testable without launching a real chooser). No
    external-storage permission required.
- **JSON export** (`dev.pointtosky.mobile.ar.camera.buildCamDiagnosticJson`) — deterministic field names,
  explicit `null`s (never silently omitted fields), all 9 matrix values preserved as JSON numbers (never
  a formatted string), no localized number strings (kotlinx.serialization's own number formatting is
  locale-independent), no screenshots or image data.

### Validation of the workflow itself (build/test gates, not a device run)

Pure JVM formatting/JSON/snapshot/mutation tests: `dev.pointtosky.mobile.ar.camera.CamDiagnosticSnapshotTest`,
`CamDiagnosticReportFormatTest`, `CamDiagnosticSnapshotJsonTest` (`mobile/src/testInternalDebug`, run via
`:mobile:testInternalDebugUnitTest`). Variant classpath-presence/absence:
`CamDiagnosticsInternalDebugVariantBoundaryTest` / `CamDiagnosticsPublicVariantBoundaryTest`
(`:mobile:testInternalDebugUnitTest` / `:mobile:testPublicDebugUnitTest`). Compose/instrumentation tests
(compiled, not run on-device in this authoring environment - see section 3):
`dev.pointtosky.mobile.ar.CamDiagnosticExportUiTest`, `CamDiagnosticActionsTest`
(`mobile/src/androidTestInternalDebug`) and the shared, variant-independent `CamDiagnosticHudLayoutTest`
(`mobile/src/androidTest`). See `docs/SPRINT_STATUS.md`'s CAM-2c row for the exact Gradle commands and
results this pass actually ran.

## 3. New workflow device verification — NOT YET RUN

The export/freeze workflow described in section 2 has **not** been exercised on a physical device as of
this writing. No physical device or emulator was available in the authoring environment (same
limitation as every prior CAM-2 device-validation pass — see `docs/validation/cam_2b_device_validation.md`
and `docs/validation/cam_1g_device_validation.md` for the same disclosure pattern).

**Do not treat this section as passed.** It will be filled in only after someone actually:

1. Opens full diagnostics on a real Pixel 9.
2. Presses Freeze.
3. Presses Copy all and pastes the clipboard contents somewhere to confirm they match what the screen
   shows.
4. Presses Share log and confirms a real share sheet appears with the correct text.
5. Presses Share JSON and confirms a real share sheet appears with valid, schema-versioned JSON.
6. Confirms the copied/shared report contains the same fields section 1 already established by hand
   (`cameraId=0`, `logical=true`, `physicalIds=2,3,4`, `matrixClass=AXIS_ALIGNED_0`, the frame-transform
   counters, `UnsupportedLogicalMultiCameraMapping`, `publishedReference=PhysicalSensor`).
7. Presses Resume live and confirms the counters continue updating afterward.

This is a distinct, separate confirmation from section 1 — section 1 already proves the *values* are
correct (by hand, via screenshots); this section would prove the *workflow* built in section 2
reproduces the same values correctly, end to end, on a real device. Until run, treat the workflow as
build/test-verified only, not device-verified.

## Final verdict

Scoped to what this file actually establishes:

- Section 1 (original evidence): confirmed, real-device, unchanged by anything since.
- Section 2 (the export/freeze workflow's own implementation): build/lint/unit-test verified; not
  device-verified.
- Section 3 (device verification of the workflow itself): not yet run.

See the accompanying review response for the overall `CAM DIAGNOSTIC EXPORT PASS` /
`CAM DIAGNOSTIC EXPORT NEEDS FIX` verdict, which also accounts for the architecture/immutability/test
fixes made alongside this documentation correction.
