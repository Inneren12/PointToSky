# CAM-2c: real Pixel 9 evidence, and the CAM diagnostic export/freeze workflow

This file has four distinct sections, kept deliberately separate because they have different
provenance and different confidence levels:

1. **Original Pixel 9 evidence** — the first confirmed, real-device CAM-2c observation. Collected
   *before* the export/freeze workflow existed, through the previous HUD and multiple scrolling
   screenshots. **Its "sensor-to-buffer transform pipeline is working correctly" claim is corrected by
   section 3 below — read that correction before citing this section.**
2. **Diagnostic workflow implemented in response** — what was built afterward, specifically because
   collecting section 1's evidence that way was slow and error-prone.
3. **New workflow device verification** — the export/freeze workflow (section 2) actually run on a
   physical Pixel 9. This is where the **identity-matrix finding** was discovered, and it corrects a
   claim made in section 1.
4. **CAM-2c whole-active-array hypothesis diagnostic** — the diagnostics/validation-terminology/
   documentation response to section 3's finding: a new, separate, explicitly hypothesis-scoped
   diagnostic, renamed diagnostics, and what is and is not gated as a result.

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
  sensor-to-buffer transform, and all 1115 classified as the structurally supported `AXIS_ALIGNED_0`
  class (`CameraSessionIntrinsicsFrameCounters.framesWithUsableTransform` — see this field's own KDoc for
  why its name is kept but no longer described as "usable" — / `.framesAnalyzed`).

  **Correction (see §3 below):** the original write-up of this bullet claimed *"the sensor-to-buffer
  transform pipeline itself is working correctly and consistently on this device."* That claim is **not
  supported** by what `AXIS_ALIGNED_0`/`1115/1115` actually proves — those numbers only establish that a
  transform was *present* and *structurally classifiable* every frame, never that its numbers match any
  particular hypothesis about the matrix's real source domain. §3 recorded the actual matrix on a later
  run of the same device and found it to be the **identity** matrix, which does not match the one
  source-domain hypothesis this codebase can currently test (that the matrix's source is the *complete*
  active array) — this is evidence that hypothesis does not hold, **not** evidence the matrix itself is
  broken, invalid, or known not to describe the real pipeline: the pinned CameraX version's actual
  source-domain contract has not been source-traced or device-proven here, and a legitimate
  cropped/pre-normalized source domain remains a live possibility. Read `transformPresent`/`matrixClass`
  here as transport/structural evidence only, never as semantic-usability evidence.
- `CAM-2c=UnsupportedLogicalMultiCameraMapping` — despite a transform being present and structurally
  classified as `AXIS_ALIGNED_0` on every frame, `resolveAnalysisBufferIntrinsics` still refuses to build
  a calibrated mapping, because
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

## 3. New workflow device verification — a real run, and the identity-matrix finding

The export/freeze workflow described in section 2 **has** now been exercised on a physical Pixel 9
(`internalDebug`), producing a real, `Copy all`-style diagnostic report:

```text
cameraId=0
logicalMultiCamera=true
physicalCameraIds=2,3,4
pixelArray=4080x3072
activeArray=[0,0 — 4080,3072]
ImageAnalysis buffer=640x480
cropRect=[0,0 — 640,480]
rotationDegrees=90
sensorToBuffer matrix=
  [1,0,0,
   0,1,0,
   0,0,1]
transformClass=AXIS_ALIGNED_0
framesWithSupportedTransformClass=1751/1751
CAM-2c result=UnsupportedLogicalMultiCameraMapping
```

Read field by field, against the same fields section 1 already established by hand:

- `cameraId=0`, `logicalMultiCamera=true`, `physicalCameraIds=2,3,4`, `CAM-2c result=UnsupportedLogicalMultiCameraMapping`
  — **exactly** matches section 1's original evidence (same camera identity, same logical-multi-camera
  block, same typed outcome), on a different session (1751 analyzed frames here vs. 1115 in section 1)
  and a different reported buffer/crop (this run's `ImageAnalysis buffer=640x480`/`cropRect=[0,0 —
  640,480]` were not captured at all by section 1's screenshot-driven HUD). The logical-multi-camera
  block remains confirmed and unchanged.
- `pixelArray=4080x3072`, `activeArray=[0,0 — 4080,3072]` — the same round-number active array section 1
  implied but never itself recorded as raw numbers (section 1's HUD did not surface the pixel/active
  array rectangle at all).
- `sensorToBuffer matrix = [1,0,0, 0,1,0, 0,0,1]` — the **identity** matrix. **This is the critical new
  finding.** Section 1 never recorded the matrix's actual 9 values (its HUD showed only the derived
  `matrixClass` label); this run's export/freeze workflow is the first time this codebase has seen the
  real numbers. An identity matrix reports `bufferX = activeX`, `bufferY = activeY` — i.e. **no scaling
  at all** between a `4080x3072` active-array coordinate and a `640x480` buffer coordinate. Under the one
  source-domain hypothesis this codebase can currently test — that the matrix's source is the *complete*
  active array — this matrix does not land on the reported buffer. **This is evidence that hypothesis
  does not hold for this matrix; it is not evidence the matrix itself is broken, invalid, unusable, or
  known not to describe the real pipeline.** The pinned `androidx.camera:camera-camera2:1.3.4`
  implementation's actual source-domain contract for `ImageInfo.getSensorToBufferTransformMatrix()` has
  not been source-traced or device-proven in this codebase; a legitimate, correctly-functioning device
  could plausibly report an identity matrix if its own source domain were already cropped or
  pre-normalized before this matrix is computed. That possibility remains open and unresolved.
- `transformClass=AXIS_ALIGNED_0`, `framesWithSupportedTransformClass=1751/1751` — the identity matrix
  *is* a valid instance of `AXIS_ALIGNED_0` (positive, non-skewed scale — `1.0` counts as a positive
  scale) and is present on every frame. **This is exactly why `AXIS_ALIGNED_0`/a high supported-class
  frame count must never be read as "the mapping pipeline is working correctly"** — both are purely
  *structural* facts (a scale/translate matrix was reported, consistently), and both are fully satisfied
  by a matrix that does not match the one, narrow, whole-active-array hypothesis this codebase can
  currently test for these active-array/buffer dimensions. This is the exact gap the CAM-2c diagnostics
  fix (§4 below) exists to make visible — without claiming to have closed it.

**What is, and is not, confirmed by this run:**

- **Confirmed:** the export/freeze workflow opens on a real device and its captured/copied report
  reproduces the same camera-identity/logical-multi-camera fields section 1 established by hand, plus new
  fields (raw matrix values, `ImageAnalysis` buffer, crop rect, rotation) section 1 never captured at all.
- **Pending, not separately exercised in this evidence:** Share log, Share JSON, and Resume live are not
  confirmed by the report above (which is consistent with a `Freeze` + `Copy all`/equivalent text capture,
  not necessarily all seven of section 2's own checklist items) — do not read this section as closing
  every item of that checklist; only the items the captured report above actually demonstrates are
  claimed here.
- **Not established by this run:** what this matrix's real source domain actually is, on the pinned
  CameraX version. `matrixClass=AXIS_ALIGNED_0` and `1751/1751` prove the transform is present and
  structurally classifiable, never that its numbers match any particular source-domain hypothesis — see
  §4's `wholeActiveArrayHypothesisVerdict` diagnostic for the one, narrow hypothesis this codebase can
  currently test, and the real verdict it computes for this exact fixture
  (`WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH`, not `MATCHES_WHOLE_ACTIVE_ARRAY_HYPOTHESIS`) — a mismatch
  that is evidence only against *that* hypothesis, never proof the matrix itself is broken, invalid, or
  known not to describe the real pipeline.

## 4. CAM-2c diagnostics fix — an explicit, named hypothesis test, not a general verdict

An earlier revision of this fix introduced a function (`assessSensorToBufferDomainConsistency`, a
`SensorToBufferDomainConsistency` verdict including a `CONSISTENT`/`MAPPED_BOUNDS_MISMATCH` pair) that
described its result in general terms — "domain consistency," "consistent," "mismatch" — as if it were a
proven, general verdict on whether the matrix itself is semantically usable. **That framing was itself a
defect, corrected in this revision.** The public CameraX/Camera2 contract for
`ImageInfo.getSensorToBufferTransformMatrix()`'s own source domain has not been source-traced or
device-proven in this codebase at the pinned `androidx.camera:camera-camera2:1.3.4` version — it is not
established whether the matrix always maps the *complete* active array, or some already-cropped/
pre-normalized sub-region of it. A general "consistency" verdict overstated what a whole-active-array-only
check can actually prove, and risked exactly the kind of overclaim this whole fix exists to correct.

This codebase now computes and surfaces one, explicitly-named hypothesis test instead:

- **`dev.pointtosky.mobile.ar.camera.assessWholeActiveArrayMappingHypothesis`**
  (`mobile/src/internalDebug`, pure JVM, `internal`-scoped) — tests exactly one, named hypothesis
  (`SourceDomainBasis.ASSUMED_WHOLE_ACTIVE_ARRAY_LOCAL`: that the matrix's source domain is the *complete*
  active array): forward-maps that assumed domain through the matrix and compares the result against the
  reported analysis-buffer domain, within an explicit, bounded tolerance
  (`DEFAULT_WHOLE_ACTIVE_ARRAY_HYPOTHESIS_TOLERANCE_PX`, half a pixel). Returns a typed
  `WholeActiveArrayHypothesisVerdict`: `MATCHES_WHOLE_ACTIVE_ARRAY_HYPOTHESIS`,
  `SOURCE_METADATA_UNAVAILABLE`, `BUFFER_METADATA_UNAVAILABLE`, `WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH`,
  `NON_FINITE_MAPPED_BOUNDS`, or `UNSUPPORTED_TRANSFORM_CLASS` — never a bare boolean, never inferred from
  `transformClass` alone, and every field/KDoc explicit that this tests one hypothesis, not the matrix's
  general validity. For this exact fixture (identity matrix, `4080x3072` source, `640x480` buffer) it
  returns `WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH`, not `MATCHES_WHOLE_ACTIVE_ARRAY_HYPOTHESIS` — see that
  function's own KDoc, "Scope and provenance," for why a mismatch here is evidence only against *this*
  hypothesis: an ordinary, legitimate active-array crop would produce the identical verdict, since this
  codebase has no proof of what a cropped relationship should look like either.
- **Diagnostics renamed, not just re-labelled.** `CameraSessionIntrinsicsFrameCounters.framesWithUsableTransform`
  (the runtime field, kept as-is to limit this fix's scope) is now described everywhere user-facing as
  counting a **structurally supported transform class**, never a "usable" one; the `internalDebug`
  export DTO's own field was renamed to `framesWithSupportedTransformClass` (JSON schema version bumped
  1 → 2), and every text/JSON export line that previously said "usable"/"usableAxisAligned0" now says
  "supported transform class"/"supportedClassAxisAligned0".
- **New, separate, hypothesis-specific fields**, alongside (never replacing) `transformClass`:
  `sourceDomainBasis` (which hypothesis was tested — always `ASSUMED_WHOLE_ACTIVE_ARRAY_LOCAL` today),
  `wholeActiveArrayHypothesisVerdict`, `mappedAssumedSourceBounds`, `expectedBufferBounds`,
  `hypothesisReason` — in both the plain-text report (`FRAME TRANSFORM` section) and the JSON export
  (`cam2c.frameTransform`, JSON schema version bumped 2 → 3). The earlier revision's general
  `domainConsistency`/`mappedSourceBoundsPx`/`consistencyReason` field names, and the `CONSISTENT`/
  `MAPPED_BOUNDS_MISMATCH` verdict names, no longer appear anywhere in exported text or JSON.
- **No reserved resolver outcome for this hypothesis test.** The earlier revision reserved a
  `AnalysisBufferIntrinsicsResolution.DomainConsistencyUnproven` sealed variant, constructed by no
  production path, "for a future gate." That variant has been **removed**: reserving a typed outcome for
  an unproven source-domain model invites exactly the kind of overclaim this fix corrects, and no
  production code ever constructed it. A future typed resolver outcome should be added only once the
  pinned (or an upgraded) CameraX version's source-domain/crop contract is actually source-traced or
  device-proven — not before.
- **Diagnostic-only; outcome precedence unchanged.** `resolveAnalysisBufferIntrinsics` does not reject any
  currently-`Resolved` calibration because of this hypothesis check, and does not infer or synthesize a
  replacement matrix. For the real Pixel 9 logical-multi-camera path specifically,
  `UnsupportedLogicalMultiCameraMapping` remains the one externally returned CAM-2c outcome, unchanged;
  the hypothesis verdict is diagnostic evidence alongside it, never a replacement for it.
- **Calibrated mapping remains blocked first by physical-camera provenance** (the logical-multi-camera
  guard, §1/§3), exactly as before. The identity-matrix observation, and the fact that it does not match
  the one hypothesis this codebase can currently test, remain an open investigation — not a proven
  defect — that a future pass source-tracing or device-proving the real CameraX source-domain contract
  should resolve before CAM-2d (or any pass that lifts the logical-multi-camera block) publishes
  calibrated `AnalysisBuffer` intrinsics on the strength of an untested assumption.
- **Buffer-first result precedence.** `assessWholeActiveArrayMappingHypothesis` validates the buffer
  domain before the source domain, so `BUFFER_METADATA_UNAVAILABLE` always wins when both are invalid —
  `SOURCE_METADATA_UNAVAILABLE` is only ever returned once a valid `expectedBufferBoundsPx` already
  exists to preserve, matching `WholeActiveArrayMappingAssessment.expectedBufferBoundsPx`'s own documented
  contract (`null` only for `BUFFER_METADATA_UNAVAILABLE`).
- **Moved out of `:core:astro-core`.** This function and its types (`SensorToBufferDomainBounds`,
  `SourceDomainBasis`, `WholeActiveArrayHypothesisVerdict`, `WholeActiveArrayMappingAssessment`) originally
  shipped inside `:core:astro-core`'s own public production API, despite having zero non-test callers
  outside the `internalDebug`-only CAM diagnostic export. Moved, unmodified, into `mobile/src/internalDebug`
  and marked `internal`, so an unproven diagnostic hypothesis no longer ships as part of the shared core
  module's public surface. `CamDiagnosticsInternalDebugVariantBoundaryTest`/
  `CamDiagnosticsPublicVariantBoundaryTest` now prove its presence in `internalDebug` and absence from
  `publicDebug` the same way every other export-only class's boundary is proven.

See `docs/SPRINT_STATUS.md`'s CAM-2c row for the exact Gradle commands and test counts this fix's own
pass ran.

## Final verdict

Scoped to what this file actually establishes:

- Section 1 (original evidence): confirmed, real-device, camera-identity/logical-multi-camera fields
  unchanged by anything since; its "the transform pipeline is working correctly" phrasing is corrected —
  see the correction note inline in that section and §3's identity-matrix finding.
- Section 2 (the export/freeze workflow's own implementation): build/lint/unit-test verified; device
  execution now confirmed for the Freeze/report-capture path specifically (§3) — Share log, Share JSON,
  and Resume live remain unconfirmed unless separately exercised.
- Section 3 (device verification of the workflow itself): the report-capture path confirmed on a real
  Pixel 9; produced the identity-matrix finding that motivated §4. The identity matrix does not match the
  whole-active-array-local hypothesis this codebase can currently test — this is **not** established to
  mean the matrix is semantically impossible, defective, or known not to describe the real pipeline; the
  pinned CameraX version's actual source-domain contract remains unresolved.
- Section 4 (the diagnostics fix): build/lint/unit-test verified (see `docs/SPRINT_STATUS.md`);
  `assessWholeActiveArrayMappingHypothesis` can now *detect and report*, honestly and narrowly scoped,
  whether one specific hypothesis holds — it does not, and cannot yet, establish the matrix's real source
  domain, and calibrated mapping is still blocked first by the unchanged logical-multi-camera guard.

See the accompanying review response for the overall `CAM DIAGNOSTIC EXPORT PASS` /
`CAM DIAGNOSTIC EXPORT NEEDS FIX` verdict, which also accounts for the architecture/immutability/test
fixes made alongside this documentation correction.

## 5. CAM-2c physical-camera provenance experiment — code-only pass, no new device evidence

**Read this section before assuming the CAM-2c block above is lifted.** Everything in §1-§4 above is
**real, confirmed Pixel 9 device evidence**, gathered by hand on physical hardware before this pass
began (the task that requested this pass supplied it as "current confirmed Pixel 9 evidence" — the
exact `cameraId=0`/`physicalIds=2,3,4`/`sensorToBuffer=identity`/`CAM-2c=UnsupportedLogicalMultiCameraMapping`
readings match §1/§3 above exactly). This section describes a **separate, code-only** pass that
upgraded CameraX and built a physical-camera-binding mechanism on top of it — see
`docs/camera_coordinate_calibration_contract.md` §3.9 and `docs/SPRINT_STATUS.md`'s "CAM-2c
physical-camera provenance experiment" section for the full design and validation record. **This pass
did not run on a Pixel 9 or any other physical device.** It ran in a Claude Code remote-execution
container with real network access (used to install a JDK 17 toolchain, a real Android SDK, and run a
genuine, non-fabricated Gradle build) — a different *kind* of sandboxed environment than every prior
CAM-2c pass (which had no network access at all for provisioning), but still a cloud container with
**no physical Android device or emulator attached**.

**What this pass established (real, Gradle-verified, this session):**
- CameraX `1.3.4` → `1.4.2` compiles clean under this project's existing `AGP 8.7.2`/`compileSdk 35`
  with zero behavioral regressions (`:core:astro-core:test` 468/468 and
  `:mobile:test{Internal,Public}DebugUnitTest` both unchanged from the pre-upgrade baseline).
- `CameraSelector.Builder.setPhysicalCameraId(String)`, `CameraInfo.getPhysicalCameraInfos(): Set<CameraInfo>`,
  and `Camera2CameraInfo.from(CameraInfo)`'s dual constructor (logical vs. physical impl) are all
  confirmed present in the resolved `camera-core`/`camera-camera2` `1.4.2` API jars via direct `javap -p`
  inspection of the compiled classes — not merely read from (occasionally truncated/unreliable) web
  documentation.
- A new, additive, `internalDebug`-only resolution path (`resolveCam2cForExplicitPhysicalCamera`)
  reaches a calibrated `Resolved` `AnalysisBufferIntrinsicsResolution` against **fixture data** —
  a `CameraCharacteristicsSnapshot` shaped like a physical Pixel 9 sub-camera (`cameraId="2"`,
  `isLogicalMultiCamera=false`) resolves exactly like an ordinary single-sensor device would, proven by
  14 new unit tests (`PhysicalCameraProvenanceTest`/`Cam2cPhysicalCameraResolutionTest`/
  `CameraTopologyReportTest`).
- `AnalysisBufferIntrinsicsResolver.kt`'s `UnsupportedLogicalMultiCameraMapping` guard (the exact one
  §1/§3 above observed firing) is **unmodified** — zero lines changed. The new path works *around* it
  by construction (feeding a physical camera's own, non-logical snapshot), not by weakening it.

**What this pass did *not* establish — the honest gap:**
- Whether a real Pixel 9's `CameraInfo.getPhysicalCameraInfos()` actually returns a non-empty `Set`
  containing a `CameraInfo` resolvable to physical ID `2`, `3`, or `4` when bound via
  `CameraSelector.Builder().setPhysicalCameraId("2")` (or `"3"`/`"4"`) — this is a real-device behavior
  this session could not observe. The `1.4.2` API surface guarantees the *methods exist*; it does not
  guarantee this exact device's HAL populates them the way this codebase's `resolvePhysicalCameraBindingFromCameraInfo`
  expects.
- Whether such a bind, if it succeeds, actually constrains the physical sensor that produces subsequent
  `ImageAnalysis` frames (as opposed to merely accepting the selector but still routing through the
  logical camera's own fusion pipeline) — no per-frame physical-camera-identity signal exists in this
  CameraX version to verify this even in principle (`docs/camera_coordinate_calibration_contract.md`
  §3.9's §9 subsection).
- Any center/edge/corner residual data (task §10) — this requires a live camera pointed at real or
  simulated stars, which no cloud container can do.
- Whether the sensor-to-buffer transform's real source domain (§3/§4 above's still-open question) looks
  any different for a physical-camera-bound session than the logical-camera-bound identity matrix this
  file's §3 already recorded — untested, since no physical-camera-bound frame was ever captured.

**Read the two kinds of evidence in this file as distinct classes, not a single narrative:** §1-§4 are
device observations; §5 (this section) is code that is *ready to be run* on a device and has never been
run on one. Do not cite this section as evidence the Pixel 9 block is lifted — it is evidence the
mechanism that *could* lift it, pending real-device confirmation, now exists and compiles/tests green.

## 6. Fix pass on §5's own mechanism — two P1 correctness blockers, still no new device evidence

A review of §5's mechanism found two P1 correctness blockers and five evidence-integrity defects, fixed
in a follow-up pass (`docs/camera_coordinate_calibration_contract.md` §3.10,
`docs/SPRINT_STATUS.md`'s "CAM-2c physical-camera provenance experiment fix" entry). Read this section
before treating anything in §5 as more settled than it was — the fixes make the *mechanism* more
correct; they do not, and cannot, add real Pixel 9 evidence.

**The two P1 blockers, in plain terms:**

1. `CameraTopologyBuilder`'s capability-code mapping used a fabricated value (`29`) instead of the real
   `CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA` constant (`11`, confirmed
   via `javap` against this project's own pinned Android SDK jar). Any topology report this mechanism
   produced before the fix could have silently mislabeled — or failed to label — a real device's
   logical-multi-camera capability.
2. `resolveCam2cForExplicitPhysicalCamera` could resolve calibrated `AnalysisBuffer` intrinsics from a
   verified physical-camera binding **alone**, without independently proving the sensor-to-buffer
   transform's own source domain. Concretely: had this mechanism been run against the real Pixel 9
   identity matrix this file's §3 recorded, a verified physical binding could have resolved a
   calibrated `K` despite that exact matrix already being known, from §3's own finding, not to match the
   one hypothesis this codebase can currently test. This is now closed by a `SensorToBufferDomainProof`
   gate — physical-sensor identity and transform-domain proof are independent, both-required conditions;
   see the calibration contract doc for the full type.

**Explicit statements this section exists to make unambiguous (matching the fix task's own requirement
list):**

- The CameraX physical-binding *code* is build/test-ready — every gate a real Gradle run in this
  environment can check is green.
- Physical binding is **not yet device-verified**. It never was, in §5 or here.
- Sensor-to-buffer transform-domain proof is a **separate, independently required** gate, now made
  explicit in code rather than implicit and unchecked.
- A fixture reaching `Cam2cPhysicalCameraResolution.Resolved` in a unit test (constructed with an
  explicitly-supplied `SensorToBufferDomainProof.ProvenActiveArrayLocal`, which no real code path in
  this codebase can produce automatically) is **not** evidence that the real Pixel 9 identity matrix
  this file's §3 recorded is usable. On every device this codebase can currently run on, the automatic
  domain-proof computation can only ever produce `Unresolved` or `HypothesisMismatch` — never `Proven*` —
  so `resolveCam2cForExplicitPhysicalCamera` cannot resolve automatically today, by design.
- The fixed `1.0×` zoom mitigation (§5, unchanged by this fix pass except that it is now actually
  *awaited* before provenance is established — see the calibration contract doc's Fix 5) removes one
  lens-switch trigger this codebase can control. It does not prove, and was never claimed by this fix
  pass to prove, frame-level physical-camera-identity stability against every possible OEM behavior.
- No physical camera ID is selected by default anywhere in this codebase. The experiment screen requires
  an explicit user tap on a candidate enumerated from real `CameraManager` topology.

**Still true, unchanged by this fix pass:** everything §5 already said about what has *not* been
established — real-device `getPhysicalCameraInfos()` behavior, whether a bind actually constrains frame
production, center/edge/corner residuals, and the sensor-to-buffer transform's real source domain — all
remain exactly as unestablished as §5 recorded. This fix pass improves the correctness of code that has
still never touched a physical camera.

## 7. Runtime correctness fix — stale callbacks, terminal lifecycle, launch-path testability, still no
new device evidence

A further review, confined to `CameraPreview`'s callback delivery, the experiment's own session
state/lifecycle, and its launch-path test coverage, found a runtime correctness gap: a stale-callback
defect where a long-lived `CameraX` analyzer could keep invoking a lambda instance captured at an earlier
composition rather than the current one; a resource-lifecycle gap where an explicit zoom/bind failure
left a bound camera/analyzer live indefinitely with no in-app way back to candidate selection; and a
launch-path test that proved only a reflected class name matched a hand-written string, never that the
in-app action launches anything. All three are fixed
(`docs/camera_coordinate_calibration_contract.md` §3.11, `docs/SPRINT_STATUS.md`'s "CAM-2c
physical-camera provenance experiment — runtime correctness fix" entry). As with §6, read this section
before treating anything above as more settled than it was: these fixes make the *mechanism* more
correct and more thoroughly tested; they do not, and cannot, add real Pixel 9 evidence — no physical
device or emulator has been attached in any pass, including this one.

**Explicit statements this section exists to make unambiguous (matching this fix task's own requirement
list):**

- The prior P1 capability/domain defects (§6) remain closed; nothing in this pass reopens or revisits
  them, and CAM-2a projection math, transform-domain policy, intrinsics math, the renderer, the detector,
  the matcher, the catalog, and the ordinary logical-camera guard are all untouched by this pass.
- Callback freshness (the stale-callback fix) and the terminal experiment lifecycle (removing
  `CameraPreview` from composition on explicit failure, with Retry/Back actions and per-attempt
  isolation) are now covered by new tests — pure JVM tests for every reducer/state-transition guarantee
  (`ExperimentSessionStateTest`, `ExperimentUiModelTest`), and new instrumented Compose tests for the
  Compose-level behavior itself (`ExperimentCallbackFreshnessTest`, `ExperimentSessionLifecycleUiTest`).
- Build, compile, unit-test, and instrumentation-*compilation* gates are each real, and each is a
  **distinct, lesser** claim than a physical-device run. In particular: "the instrumented tests compile
  against real Android/Compose-test APIs" is not the same claim as "the instrumented tests pass on a
  device" — this pass achieves only the former; no connected device/emulator exists in this environment
  to run `connectedInternalDebugAndroidTest` and obtain the latter.
- The in-app launch route (`buildPhysicalCameraBindingExperimentIntent` +
  `CamDiagnosticFullReportDialog`'s "Open physical-camera experiment" button) is verified only to the
  exact level actually tested: the built `Intent` targets the real, registered, `exported="false"`
  `Activity` and `PackageManager` resolves it in this `internalDebug` build (`ExperimentLaunchIntentTest`,
  instrumented, compiled but not run on a device), and a Compose click on the button invokes the launch
  action (`CamDiagnosticPhysicalCameraExperimentLaunchUiTest`, same caveat). Neither test claims, and this
  document does not claim, that the `Activity` was ever actually opened on a real Pixel 9 — that requires
  a physical device run that has not occurred.
- Physical binding remains **device-validation pending**, exactly as in §5 and §6. This pass fixes code
  defects and adds tests; it establishes no new device-level fact.
- Automatic sensor-to-buffer transform-domain proof remains unavailable in this codebase by design (§6's
  Fix 2, unchanged by this pass): every real, current code path can only ever produce `Unresolved` or
  `HypothesisMismatch`, never a `Proven*` variant. The expected current successful experiment outcome,
  once a physical device is available, is therefore **verified physical binding plus
  `DOMAIN_NOT_PROVEN`** — never `CAM-2c Resolved`.

**Final status, this pass:**

```
CAM-2c PHYSICAL CAMERA EXPERIMENT CODE READY
CALLBACK/LIFECYCLE PATH TESTED
PHYSICAL BINDING DEVICE VALIDATION PENDING
SENSOR-TO-BUFFER DOMAIN PROOF PENDING
CAM-2c CALIBRATED PIXEL 9 RESULT NOT YET ESTABLISHED
```
