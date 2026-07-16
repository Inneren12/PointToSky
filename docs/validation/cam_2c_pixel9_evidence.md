# CAM-2c: real Pixel 9 evidence, and the CAM diagnostic export/freeze workflow

This file records the first **confirmed, real-device** CAM-2c evidence gathered on a physical Pixel 9 —
superseding every earlier "expected to hit `UnsupportedLogicalMultiCameraMapping`" prediction in
`docs/SPRINT_STATUS.md` and `docs/camera_coordinate_calibration_contract.md` with an actual observation.
It also documents the diagnostic export/freeze workflow this evidence was collected through, and why it
replaced the previous scrolling-screenshot process.

## The exact observed result

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

## What this confirms

- **CAM-2c's runtime diagnostics passed.** The coordinator, the coherent-input gate, the per-frame
  transform classification, the typed `AnalysisBufferIntrinsicsResolution` outcome, the camera
  characteristics read (camera ID, logical flag, physical IDs), and the CAM-1b fallback publication all
  behaved exactly as designed and documented, on a real device, across a real 1115-frame session. Nothing
  here is a bug in the runtime integration — every prior CAM-2c fix round's own reasoning is now backed
  by an actual observation, not just unit-test fixtures and "expected to..." language.
- **Calibrated mapping remains blocked on physical-camera provenance**, not on anything this fix round
  addresses. The block is exactly the one every prior CAM-2c entry in `docs/SPRINT_STATUS.md` predicted:
  a real Pixel 9 rear camera is a logical multi-camera, and this codebase's pinned CameraX version has no
  way to bind or attribute a concrete physical sensor. Lifting this block requires either bumping
  `androidx.camera:camera-camera2` past `1.4.0-beta01` (Option A/B from the CAM-2c fix's own KDoc) or an
  equivalent read of per-frame physical-camera identity — out of this fix's scope.
- **The export workflow exists to collect future CAM-2d evidence without screenshot stitching.** Reaching
  the single-page result block above previously required manually reading and transcribing several
  scrolled, multi-block `Text` overlays across multiple screenshots — error-prone and slow, and the
  reason this evidence-collection fix (`CamDiagnosticSnapshot`/`buildCamDiagnosticReportText`/
  `buildCamDiagnosticJson`, see below) exists at all. Any future CAM-2d work (e.g. verifying a CameraX
  bump actually resolves physical-camera provenance) should use "Freeze snapshot" → "Copy all"/"Share
  log"/"Share JSON" from the full diagnostics dialog to capture its own before/after evidence in one
  step, not a new round of scrolling screenshots.

## The diagnostic export/freeze workflow (internalDebug only)

Replaces the previous "expand the HUD, scroll, screenshot, repeat" workflow for the CAM diagnostics HUD
(`dev.pointtosky.mobile.ar.CamDiagnosticTopPanels`, gated by `CameraGeometryDiagnosticsGate.isEnabled` —
`internalDebug` builds only, never public or release):

- **`CamDiagnosticSnapshot`** (`dev.pointtosky.mobile.ar.camera.CamDiagnosticSnapshot`) — a bounded,
  fully immutable value snapshot of one CAM diagnostics moment: CAM-2b status/reason, CAM-2c coordinator
  state and typed attempt, published intrinsics (source/reference/quality), camera ID/logical
  flag/physical IDs, pixel/active/pre-correction arrays, physical sensor size, focal lengths, all 9
  sensor-to-buffer matrix values and their class, frame counters, coordinator frames waited, buffer
  size/crop rect/rotation/viewport, the resolved buffer `K` when available, and CAM-1g's own geometry
  state/session counters. Never a live provider/coordinator reference, never star catalog contents, never
  image pixels.
- **Compact HUD summary** — the expanded HUD now shows only the highest-value root-cause summary (camera,
  physical IDs, matrix class/health, published reference, and — like the block above — a short reason
  phrase when blocked), not the previous giant per-domain text blocks. "Open diagnostics" opens the full
  surface.
- **Full diagnostics dialog** (`dev.pointtosky.mobile.ar.CamDiagnosticFullReportDialog`) — a full-screen,
  scrollable dialog with every action pinned above the scrollable report body:
  - **Freeze snapshot / Resume live** — freezes the displayed report to one captured moment (camera and
    projection keep running unaffected underneath); repeated recompositions never replace a frozen
    snapshot; Resume immediately reflects every update since freezing.
  - **Copy all** — copies the complete, deterministic plain-text report
    (`buildCamDiagnosticReportText`) to the real Android `ClipboardManager` — never a Compose
    semantics-tree scrape.
  - **Share log** — `ACTION_SEND`, `type = text/plain`, the same deterministic report text. No
    external-storage permission required.
  - **Share JSON** — the same `ACTION_SEND` mechanism, sharing the versioned JSON export
    (`buildCamDiagnosticJson`, `schemaVersion = 1`) instead of plain text.
- **JSON export** (`dev.pointtosky.mobile.ar.camera.buildCamDiagnosticJson`) — deterministic field names,
  explicit `null`s (never silently omitted fields), all 9 matrix values preserved as JSON numbers (never
  a formatted string), no localized number strings (kotlinx.serialization's own number formatting is
  locale-independent), no screenshots or image data.

## Validation

Pure JVM formatting/JSON/snapshot tests: `dev.pointtosky.mobile.ar.camera.CamDiagnosticSnapshotTest`,
`CamDiagnosticReportFormatTest`, `CamDiagnosticSnapshotJsonTest` (`:mobile:testInternalDebugUnitTest`).
Compose/instrumentation tests: `dev.pointtosky.mobile.ar.CamDiagnosticFullReportDialogTest` and the
updated `CamDiagnosticHudLayoutTest` (`:mobile:compileInternalDebugAndroidTestKotlin`; connected
instrumentation itself was not run in the authoring environment — no physical device or emulator
available there, same limitation as every prior CAM-2 device-validation pass; see
`docs/validation/cam_2b_device_validation.md` for the same disclosure pattern). See
`docs/SPRINT_STATUS.md`'s CAM-2c row for the exact Gradle commands and results this pass actually ran.

## Final verdict

**CAM DIAGNOSTIC EXPORT PASS** — the export/freeze workflow itself (snapshot, text/JSON formatting,
Freeze/Resume, Copy/Share, compact HUD redesign) is implemented, unit-tested, and compiles/lints/builds
cleanly. It is not a claim that CAM-2c's calibrated mapping now works on a Pixel 9 — it still does not,
for the physical-camera-provenance reason documented above.
