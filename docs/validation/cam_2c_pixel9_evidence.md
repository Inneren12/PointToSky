# CAM-2c: real Pixel 9 evidence, and the CAM diagnostic export/freeze workflow

> **Epistemic update (dual-basis slice).** Sections 1–7 below predate
> `docs/recon/cam_2c_sensor_to_buffer_domain_recon.md`. Where they say the pinned CameraX version's
> sensor-to-buffer source-domain contract "has not been source-traced", read that as historical: the
> recon **has** since traced CameraX 1.4.2 (declared domain = the CameraX-opened camera's
> `SENSOR_INFO_ACTIVE_ARRAY_SIZE` rect → full unrotated buffer, inverse `ScaleToFit.CENTER`
> construction) and resolved §3's identity-matrix finding (CameraX 1.3.4 never set the matrix
> without a ViewPort — the default identity leaked through; the "pre-normalized source domain"
> speculation is retired). What remains unproven is narrower and named precisely: physical/logical
> **basis compatibility** under `setPhysicalCameraId`, and **frame-content correspondence**. See §8 for
> the matrix-construction-only dual-basis evidence, and §11 for the frame-content-correspondence
> measurement machinery (code-only; not yet run on a device) — §8's own dual-basis match, even a
> perfect one, remains matrix-construction evidence only, never frame-content proof, until §11's
> experiment is actually exercised on a real Pixel 9.

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

## 7. Runtime correctness fix — stale callbacks, terminal lifecycle, terminal-UI reachability, launch-path
testability, still no new device evidence

A further review, confined to `CameraPreview`'s callback delivery, the experiment's own session
state/lifecycle, and its launch-path test coverage, found a runtime correctness gap: a stale-callback
defect where a long-lived `CameraX` analyzer could keep invoking a lambda instance captured at an earlier
composition rather than the current one; a resource-lifecycle gap where an explicit zoom/bind failure
left a bound camera/analyzer live indefinitely with no in-app way back to candidate selection; and a
launch-path test that proved only a reflected class name matched a hand-written string, never that the
in-app action launches anything. A follow-up review then found a **terminal-UI reachability defect** in
the fix for the second gap: Retry/Back were rendered underneath a fullscreen scrollable report layer in a
plain `Box`, which visually covered them and intercepted every pointer event, making them physically
unreachable despite existing in the semantics tree. All four are fixed
(`docs/camera_coordinate_calibration_contract.md` §3.11, `docs/SPRINT_STATUS.md`'s "CAM-2c
physical-camera provenance experiment — runtime correctness fix" entry). As with §6, read this section
before treating anything above as more settled than it was: these fixes make the *mechanism* more
correct and more thoroughly covered by tests — the pure reducer/state-machine layer is executed and
passing, the instrumented Compose/`PackageManager` layer is compiled but not executed — and they do not,
and cannot, add real Pixel 9 evidence — no physical device or emulator has been attached in any pass,
including this one.

**Explicit statements this section exists to make unambiguous (matching this fix task's own requirement
list):**

- The prior P1 capability/domain defects (§6) remain closed; nothing in this pass reopens or revisits
  them, and CAM-2a projection math, transform-domain policy, intrinsics math, the renderer, the detector,
  the matcher, the catalog, and the ordinary logical-camera guard are all untouched by this pass.
- Callback freshness (the stale-callback fix), the terminal experiment lifecycle (removing
  `CameraPreview` from composition on explicit failure, with Retry/Back actions and per-attempt
  isolation), and the terminal-UI reachability fix (Retry/Back are no longer covered by the report
  overlay) are covered by new/updated tests: pure JVM tests for every reducer/state-transition guarantee
  (`ExperimentSessionStateTest`, `ExperimentUiModelTest`) — **these have actually executed and passed** —
  and instrumented Compose tests for the Compose-level behavior itself
  (`ExperimentCallbackFreshnessTest`, `ExperimentSessionLifecycleUiTest`) — **these compile against real
  Android/Compose-test APIs but have not been executed.**
- Pure reducer/state-machine guarantees were **executed and passed** — real `Test` results from
  `:mobile:testInternalDebugUnitTest`, no device required. Instrumented Compose/`PackageManager` tests
  were **compiled only** — `:mobile:compileInternalDebugAndroidTestKotlin` passing proves they type-check
  against real Android runtime APIs, never that they ran or passed. These are different, and this
  document does not conflate them: "compiles" is not "tested," and neither is the same claim as "passes
  on a device."
- Terminal cleanup (removing `CameraPreview` from composition once `ExperimentSessionState.isTerminallyFailed`)
  is **structurally implemented**, provable by code-path inspection (the terminal branch early-returns
  before ever reaching the `CameraPreview` call) — but the full live transition (bind → explicit zoom/bind
  failure → actual `CameraSessionLifecycle` unbind) has **not been executed on Android hardware or an
  emulator**. No device run has observed the camera actually being released.
- The in-app launch route (`buildPhysicalCameraBindingExperimentIntent` +
  `CamDiagnosticFullReportDialog`'s "Open physical-camera experiment" button) is verified only to the
  exact level actually tested: `ExperimentLaunchIntentTest` and
  `CamDiagnosticPhysicalCameraExperimentLaunchUiTest` are written, and compile, to assert the built
  `Intent` targets the real, registered, `exported="false"` `Activity`, that `PackageManager` resolves it
  in this `internalDebug` build, and that a click invokes the launch action — but **neither test has been
  device-executed, so none of those assertions have actually passed yet.** Neither test claims, and this
  document does not claim, that the `Activity` was ever actually opened on a real Pixel 9, or that the
  `PackageManager` resolution assertion has passed — both require a physical device or emulator run that
  has not occurred.
- Physical binding remains **device-validation pending**, exactly as in §5 and §6. This pass fixes code
  defects, adds tests, and fixes a UI reachability bug; it establishes no new device-level fact.
- Automatic sensor-to-buffer transform-domain proof remains unavailable in this codebase by design (§6's
  Fix 2, unchanged by this pass): every real, current code path can only ever produce `Unresolved` or
  `HypothesisMismatch`, never a `Proven*` variant. The expected current successful experiment outcome,
  once a physical device is available, is therefore **verified physical binding plus
  `DOMAIN_NOT_PROVEN`** — never `CAM-2c Resolved`.

**Final status, this pass:**

```
CAM-2c PHYSICAL CAMERA EXPERIMENT CODE READY
CALLBACK/LIFECYCLE LOGIC UNIT-TESTED
INSTRUMENTED CALLBACK/LIFECYCLE TESTS COMPILE
INSTRUMENTED EXECUTION PENDING
PHYSICAL BINDING DEVICE VALIDATION PENDING
SENSOR-TO-BUFFER DOMAIN PROOF PENDING
CAM-2c CALIBRATED PIXEL 9 RESULT NOT YET ESTABLISHED
```

## 8. Dual-basis diagnostic slice — evidence-capture machinery, and the exact device runs it enables

**Code-only pass; no new device evidence.** Building on the recon (see the epistemic-update note at
the top of this file), this slice added the `internalDebug`-only dual-basis diagnostic:
per-frame assessment of the observed matrix under **both** the opened logical camera's and the
selected physical camera's active-array bases (each fully labelled — camera ID, role, coordinate
space, full native rect, metadata source), the `WholeActiveArrayGeometryClass` classifier (the real
Pixel 9 1.4.2 matrix classifies `UNIFORM_SCALE_CENTER_CROP` in unit fixtures; the historical 1.3.4
identity classifies `UNEXPLAINED`), the pure CameraX 1.4.2 implementation-model prediction with
per-coefficient/mapped-point residuals, opened-logical-camera snapshot capture from the same bound
`CameraInfo` (typed `Unavailable` when unidentifiable), per-attempt matrix-stability counters,
near-4:3/16:9 resolution selection from device-declared sizes (each switch a fresh generation), and
deterministic text/JSON export at full available precision (widened float32 documented as such).
See `docs/SPRINT_STATUS.md`'s "CAM-2c dual-basis diagnostic slice" and
`docs/camera_coordinate_calibration_contract.md` §3.12 for the full inventory and validation record.

**The device workflow this enables (not yet run — no device has been attached in any pass):**

For each physical ID `2`, `3`, `4`:

- **A. near-4:3 session** — select the candidate, select 640×480 (or the actual supported
  equivalent), wait ≥ 100 valid frames, Freeze, Copy report + Share JSON.
- **B. 16:9 session** — Back to candidates (fresh generation), same candidate, select the supported
  16:9 size (e.g. 1280×720), wait ≥ 100 valid frames, Freeze, export. The 1.4.2 model predicts the
  crop magnitude flips from ≈ 0.94 px/side (4:3) to ≈ 121.88 px/side (16:9, vertical) for a
  4080×3072 basis — a sharp, falsifiable discriminator.
- **C. restart** — fully leave and reopen the experiment; repeat one session for lifecycle
  reproducibility.

Each export answers: did binding reach `Bound`; which `CameraInfo` shape; which logical camera
remained open; matrix stability across frames; does the matrix match the logical-basis model, the
physical-basis model, both (numerically indistinguishable — never "proven equal"), or neither; does
the aspect switch flip the crop geometry as predicted; is fixed zoom actually reported ≈ 1.0×; and
that CAM-2c remains `DomainNotProven`.

**Expected current outcome after a successful physical bind:**

```
PHYSICAL CAMERA BINDING VERIFIED
TRANSFORM GEOMETRY CLASSIFIED
LOGICAL/PHYSICAL BASIS COMPARISON EXPORTED
CAM-2c DOMAIN NOT PROVEN
```

A dual-basis model match — even a perfect one under both bases — is matrix-construction evidence
only. It is **not** frame-content proof, it constructs no `SensorToBufferDomainProof.Proven*`
variant, and calibrated Pixel 9 `AnalysisBuffer` intrinsics remain blocked.

**Fix pass (read before interpreting any §8 export):** a review of this slice fixed four defects
before any device run occurred, so no captured evidence predates them
(`docs/camera_coordinate_calibration_contract.md` §3.13, experiment JSON schema now `2`):

- A model match now requires the observed matrix to be inside the model's axis-aligned affine
  structural scope — a projective/sheared matrix whose top two rows resemble the prediction reports
  `modelComparison=COMPARISON_UNSUPPORTED_STRUCTURE`, never a match; `maxMappedPointResidualPx` is
  Euclidean and absent for unsupported structures.
- The requested aspect family (`requestedAnalysisResolutionFamily`) is explicit in every export and
  independent of exact `WxH` equality — a near-16:9 size such as `848x480` is bound and reported as
  `NEAR_16_9`.
- `comparisonVerdict` distinguishes `MATCHES_BOTH_EQUAL_RECTS_NUMERICALLY_INDISTINGUISHABLE` (equal
  candidate rects) from `MATCHES_BOTH_DIFFERING_RECTS_WITHIN_TOLERANCE` (ambiguous dual match);
  `basesNumericallyIndistinguishable` is pure rect identity.
- `matrixStability` reports `bitwiseMatrixChanges` (exact float32-value inequality) separately from
  `mappedDisplacementChangesBeyondTolerance` (mapped-pixel displacement over the exported reference
  rectangle vs the exported `MATRIX_STABILITY_MAPPED_DISPLACEMENT_TOLERANCE_PX`); device reports are
  self-describing about their thresholds.

**Architecture-leak fix pass (`docs/camera_coordinate_calibration_contract.md` §3.14, experiment
JSON schema now `3`):** terminology-only — `matrixStability.bitwiseMatrixChanges` /
`bitwiseChangeCriterion` are now `exactValueMatrixChanges` / `exactValueChangeCriterion` in every
export (the comparison itself, its meaning, and every threshold/value are unchanged; the old name
overstated the precision — it was always `Double` structural equality, never a raw-bits
(`Double.toRawBits()`) comparison). A report or captured JSON showing the older field names predates
schema `3`.

## 9. Mobile usability fix — action controls fixed, report body scrollable (UI-only, no new device evidence)

**Code-only pass; no new device evidence.** A review of §8's own device workflow found the live
overlay's Freeze/Resume, Copy report, and Share JSON controls rendered inside the *same*
`verticalScroll`ed `Column` as the report text they sit above (`PhysicalCameraBindingSession`'s live,
non-terminal branch). On a real device, §8's report — the requested/actual resolution, matrix
stability counters, opened-logical-camera snapshot, and the full dual-basis matrix assessment — is
long enough that this pushed the action controls below the viewport, and the overlay drew flush under
the status bar (this codebase now targets `compileSdk`/`targetSdk 35`, which enforces edge-to-edge by
default). Both defects made §8's own device workflow ("Freeze, Copy report + Share JSON" for each of
three physical IDs, in two resolution bands) difficult or impossible to actually carry out on a phone.

**What changed (`PhysicalCameraBindingExperimentScreen.kt` only):**

- **Stable two-region layout.** The live overlay is now a fixed action header — a compact
  `physicalId=…/attemptId=…/status=…/frames=…` summary of whichever state is displayed, then
  Freeze/Resume, Copy report, Share JSON (`physical_camera_experiment_action_header`) — above a
  separately-scrollable report body (`physical_camera_experiment_report_scroll`), never one shared
  scroll container. The header is never itself scrollable and never resizes or is displaced by report
  length.
- **System insets.** The overlay applies `WindowInsets.safeDrawing` via `windowInsetsPadding`, so the
  header clears the status bar and the bottom of the report body clears gesture/navigation insets.
- **Long text behavior.** The report keeps its monospace formatting and wraps within the scrollable
  body's width (never horizontally clipped); the report text is now wrapped in a `SelectionContainer`
  (selectable, since that was trivial to add alongside the layout fix).
- **Freeze/export semantics are unchanged.** Freeze still pins `displayedState`; Copy report and Share
  JSON still always read `displayedState`; `frozenState` is still `remember`ed keyed to
  `attemptId`, so a new attempt/generation still cannot inherit a frozen snapshot. Every byte
  `buildPhysicalCameraExperimentReportText`/`buildPhysicalCameraExperimentJson` produce, the JSON
  schema, `ExperimentSessionState`, the dual-basis evidence math, matrix stability, and
  `CameraPreview`'s own binding behavior are all untouched — this is a layout-only fix, verified by the
  fact that `PhysicalCameraExperimentExportTest`/`PhysicalCameraExperimentReportFormatTest` (pure JVM,
  `mobile/src/testInternalDebug`) needed no changes.
- **Extracted `PhysicalCameraExperimentLiveOverlay`.** The header+report layout was pulled out of
  `PhysicalCameraBindingSession` into its own composable specifically so it can be exercised by Compose
  UI tests without also binding a real `CameraPreview` (this container has no camera hardware — same
  constraint as every prior pass in this file).
- **New Compose UI tests** (`PhysicalCameraExperimentLiveOverlayUiTest`,
  `mobile/src/androidTestInternalDebug`): Freeze is displayed on session start; Copy report and Share
  JSON are displayed simultaneously; with a synthetic long report/session state on a deliberately small
  viewport, all three action controls remain visible in the semantics tree with **no scrolling
  performed** to reach them (the exact defect this fix corrects); the report scroll container carries a
  real scroll action; tapping Freeze changes the label to "Resume live" and back; Copy report still
  writes the frozen state's exact report text to the real Android clipboard after the live state
  advances further; a new `attemptId` resets frozen state back to "Freeze".

**Validation this pass actually ran (real Gradle, this session, mirroring §5/§7's own honesty
convention):**

```
./gradlew :mobile:testInternalDebugUnitTest              — PASSED (unit tests unaffected/unchanged)
./gradlew :mobile:compileInternalDebugAndroidTestKotlin   — PASSED (new/updated Compose tests compile)
./gradlew :mobile:lintInternalDebug                       — PASSED (0 errors)
./gradlew :mobile:assembleInternalDebug                   — PASSED
```

**Not run in this pass, same as every prior CAM-2c pass in this file:** the new
`PhysicalCameraExperimentLiveOverlayUiTest` Compose UI tests were compiled, never executed — no
physical device or emulator was attached in this session. This pass adds and fixes no device-level
fact; it does not claim the layout has been seen working on a real Pixel 9, only that it compiles,
type-checks against real Compose UI test APIs, and leaves the underlying export/freeze/binding
mechanism §1-§8 already established completely unchanged.

**Final status, this pass (corrected by §10 below — the wording that used to be here, "CAM-2c PHYSICAL
EXPERIMENT UI USABLE" / "ACTIONS FIXED ON SCREEN" / "DEVICE EVIDENCE COLLECTION MAY CONTINUE", stated
runtime UI facts this pass's own "Not run" paragraph immediately above already said were never
observed — an internal contradiction §10 fixes; read §10's final status block instead of this one):**

```
CAM-2c PHYSICAL EXPERIMENT UI CODE READY
FIXED-HEADER / SCROLL-BODY LAYOUT IMPLEMENTED
INSTRUMENTED UI TEST EXECUTION PENDING
PIXEL 9 UI VALIDATION PENDING
EXPORT CONTENT UNCHANGED
DEVICE EVIDENCE NOT YET COLLECTED
```

## 10. Compact binding status honesty fix, and correcting §9's own overclaimed final status

**Code-only pass; no new device evidence.** A review of §9's own compact action-header summary
(`buildPhysicalCameraExperimentCompactSummaryText`) found two defects, one a correctness bug and one a
documentation-honesty bug in §9 itself:

1. **Compact status collapsed every non-`Bound` binding outcome into `"BOUND"`.** The prior derivation
   was `status = if (bindingResolution == null) "BINDING" else "BOUND"`. `PhysicalCameraBindingResolution`
   is a four-variant sealed interface — `Bound`, `PhysicalCameraBindingUnavailable`,
   `PhysicalCameraIdentityUnverified`, `PhysicalCameraCharacteristicsMismatch` — and only `Bound` means
   physical-camera identity was actually verified. The three non-`Bound` variants are each a *non-null*,
   *unsuccessful* outcome the old `!= null` check silently reported as `"BOUND"`, even though
   `resolveCam2cForExplicitPhysicalCamera` can only ever reach `Cam2cPhysicalCameraResolution.BindingFailure`
   from any of them (`binding !is Bound` is its first, unconditional check). A device operator glancing
   at the fixed header during §8's own workflow could have read "BOUND" and believed physical-camera
   identity was proven when it was not.
2. **§9's "Final status" block overclaimed runtime facts.** `CAM-2c PHYSICAL EXPERIMENT UI USABLE` /
   `ACTIONS FIXED ON SCREEN` / `DEVICE EVIDENCE COLLECTION MAY CONTINUE` read as observed, working-UI
   claims — but the same section's own "Not run in this pass" paragraph, one paragraph above, already
   said the instrumented Compose tests were compiled, never executed, and no device or emulator was
   attached. That final-status block contradicted the honesty the rest of §9 was careful about; it is
   corrected in place above (pointing here) rather than left standing.

**What changed (scope: `PhysicalCameraExperimentLiveOverlay`,
`buildPhysicalCameraExperimentCompactSummaryText`, and the new `PhysicalExperimentCompactStatus` typed
helper — same file, `PhysicalCameraBindingExperimentScreen.kt`):**

- **`PhysicalExperimentCompactStatus`** (new, `internal enum class`): `BINDING`, `BOUND_VERIFIED`,
  `BINDING_UNAVAILABLE`, `IDENTITY_UNVERIFIED`, `CHARACTERISTICS_MISMATCH`, `EXPLICIT_BIND_FAILED`. Every
  `PhysicalCameraBindingResolution` variant maps to its own distinct case via an exhaustive `when`
  (`physicalExperimentCompactStatus`) — `BOUND_VERIFIED` is emitted only for
  `PhysicalCameraBindingResolution.Bound`, never inferred from "non-null."
  `ExperimentSessionState.explicitBindFailureReason`, when present, wins over every binding-resolution
  case — the classifier is a pure function of `ExperimentSessionState` and is correct for *any* state
  handed to it, never dependent on `PhysicalCameraBindingSession`'s own terminal-branch early return to
  keep it honest.
- **`buildPhysicalCameraExperimentCompactSummaryText`** now renders `status.name` from
  `physicalExperimentCompactStatus`, still one line, still carrying `physicalId`/`attemptId`/`frames`
  unchanged.
- **Nothing else changed.** The two-region layout (fixed header / weighted scroll body), the
  `WindowInsets.safeDrawing` inset padding, `frozenState` keyed by `attemptId`, and Copy report/Share
  JSON both reading the same `displayedState` are all exactly as §9 left them — this pass touched no
  layout code, only the status classification.
- **New pure JVM tests** (`PhysicalCameraExperimentCompactStatusTest`, `mobile/src/testInternalDebug`):
  no binding resolution → `BINDING`; `Bound` → `BOUND_VERIFIED`; each of
  `PhysicalCameraBindingUnavailable`/`PhysicalCameraIdentityUnverified`/`PhysicalCameraCharacteristicsMismatch`
  → its own distinct status, never `BOUND_VERIFIED`; `explicitBindFailureReason` winning over a `Bound`
  resolution and over a non-`Bound` resolution; `physicalId`/`attemptId`/`frames` staying present
  regardless of status. The existing Compose assertion (`PhysicalCameraExperimentLiveOverlayUiTest`) was
  updated from `status=BOUND` to `status=BOUND_VERIFIED`.

**Established by code inspection / compilation, this pass:**

- The compact-status classifier is exhaustive over `PhysicalCameraBindingResolution` (a `when` with no
  `else` branch — the Kotlin compiler itself enforces exhaustiveness, so a future fifth variant fails to
  compile here until handled).
- The fixed-header / weighted-scroll-body layout from §9 is unchanged in structure; `WindowInsets.safeDrawing`
  is still wired the same way.
- `buildPhysicalCameraExperimentReportText`/`buildPhysicalCameraExperimentJson` and the JSON schema are
  byte-for-byte unchanged — the new status classifier is called from nowhere near either export function.
- `PhysicalCameraExperimentCompactStatusTest` (pure JVM) passed under real Gradle execution this
  session — this **is** genuine execution evidence, but only for the pure classification logic, not for
  any Compose/Android runtime behavior.
- `PhysicalCameraExperimentLiveOverlayUiTest` (Compose/instrumented) compiles against real Android/Compose
  test APIs under real Gradle execution this session.

**Still pending, unchanged by this pass:**

- Execution of the `PhysicalCameraExperimentLiveOverlayUiTest` Compose instrumented test suite — no
  emulator or physical device was attached in this session, so these tests have never actually run.
- Any emulator or physical-device observation of the fixed-header/scroll-body layout, the status text,
  or `WindowInsets.safeDrawing`'s real on-screen effect.
- Pixel 9 UI validation specifically — §8's own device workflow (Freeze/Copy/Share per physical ID, per
  resolution band) remains not run on real hardware, exactly as §1-§9 already recorded.
- Everything §1-§9 already listed as pending (physical-camera-binding device verification,
  sensor-to-buffer domain proof, center/edge/corner residuals) remains exactly as pending as before —
  this pass touches none of it.

**Final status, this pass (supersedes §9's own final-status block):**

```
CAM-2c PHYSICAL EXPERIMENT UI CODE READY
COMPACT BINDING STATUS HONEST
ACTION HEADER / REPORT LAYOUT COMPILES
INSTRUMENTED UI TEST EXECUTION PENDING
PIXEL 9 UI VALIDATION PENDING
EXPORT CONTENT UNCHANGED
```

## 11. CAM-2c frame-content correspondence experiment — a new, separate diagnostic; still no device evidence

> **Corrected by §12 below, before any device evidence was collected.** A review found this section's
> own experiment had a circularity defect: pose was fit under `PHYSICAL_ACTIVE_ARRAY_MODEL_PATH` and
> then that same hypothesis was scored against its own fitted pose — biasing any "path better" verdict
> toward the hypothesis the pose was anchored to, regardless of which hypothesis is actually correct.
> §12 removes every unconditional path-winner verdict, makes the pose-anchoring explicit and
> inescapable in every export, and fixes several other correctness gaps found in the same review
> (rotation-semantics honesty, "calibrated" language, orientation-ambiguous dot-grid correspondence,
> frozen evidence metadata, and a complete JSON schema). **Device evidence collection was blocked on
> this fix and never happened under this section's code.**

**Code-only pass; no new device evidence.** Everything in §1-§10 above is matrix-construction evidence
only — §8 says this explicitly: *"A dual-basis model match — even a perfect one under both bases — is
matrix-construction evidence only. It is **not** frame-content proof."* This section adds the
diagnostic §8 itself asked for: an `internalDebug`-only experiment that measures whether **actual
detected image points**, not just the matrix's own construction, correspond to projections under the
competing coordinate-basis hypotheses. **Read the honesty statement below before citing this section —
it establishes measurement machinery, not a measurement.**

### 11.1 Why a new target/detector was required

A repo-wide search before this pass began found **no ChArUco, AprilTag, ArUco, or checkerboard
detection code anywhere in this codebase, no OpenCV dependency, no JNI/native module, and no
PnP/pose-estimation code of any kind** — CAM-2a's own pipeline (`docs/camera_star_prediction_contract.md`)
forward-projects known-direction *catalog stars*, never fiducial markers, and solves no pose from
observed correspondences. Adding real ChArUco or AprilTag decoding would require a brand-new native
computer-vision dependency, out of scope for one internal-debug diagnostic. This pass instead adds one
new, minimal, first-of-its-kind **planar dot-grid target** (`FrameContentTarget.kt`, default 4x5 dots,
25mm spacing) and a connected-component/weighted-centroid detector
(`FrameContentCornerDetector.kt`) operating directly on the `ImageProxy` luma plane — never through
`PreviewView`/display coordinates. **This is explicitly not ChArUco or AprilTag**: there is no
per-point ID encoding, and row/column correspondence is assigned by sorting into rows only when the
detected blob count exactly equals the target's full point count — an explicit, honest scope
limitation (assumes the whole grid is visible and not rotated far from upright), not a hidden one.

### 11.2 Why a new pose solver was required, and its documented scope

The same search found no PnP/`solvePnP`/reprojection code anywhere. `FrameContentPoseMath.kt` adds one
new, minimal **planar-homography pose solver** (Hartley-normalized DLT, `K^-1 H` decomposition,
Gram-Schmidt orthogonalization, Rodrigues conversion) — a first-cut, non-iteratively-refined estimate,
not a general Levenberg-Marquardt PnP solve. Every exported pose report carries
`POSE_ESTIMATION_MODEL=PHYSICAL_CAMERA_CALIBRATED_DOMAIN` and
`POSE_REUSED_UNCHANGED_ACROSS_ALL_MAPPING_HYPOTHESES=true` explicitly: pose is solved **once**, using
detected `ImageAnalysis`-buffer points directly against the `PHYSICAL_ACTIVE_ARRAY_MODEL_PATH`
hypothesis's own resolved buffer-space camera matrix (chosen specifically so the pose solve and the
observed points live in the same coordinate domain by construction), then reused **completely
unchanged** when computing every competing hypothesis's own residuals — never independently refit per
hypothesis, which task §4 identifies as a defect that would hide mapping errors behind each
hypothesis's own optimized residual.

### 11.3 The three competing hypotheses, and why the third reports NOT_IMPLEMENTED

`FrameContentMappingHypothesis.kt` computes, for the same frozen object points and the same frozen
pose:

- **`LOGICAL_CAMERAX_MATRIX_PATH`** — the physical camera's own active-array-local pinhole projection,
  mapped through the **observed** real CameraX `sensorToBufferTransformMatrix` directly, with no basis
  correction — testing whether that direct application, despite the logical and physical active arrays
  differing in size (§3's own `4080x3072` vs `4032x3024` finding), still lands correctly on real
  detected content.
- **`PHYSICAL_ACTIVE_ARRAY_MODEL_PATH`** — the same physical-basis projection, mapped through a
  **freshly built** CameraX-1.4.2-style matrix (`predictCameraX142SensorToBufferMatrix`) constructed
  from the physical camera's own active array and the real buffer dimensions — never the observed
  matrix.
- **`RECONCILED_PHYSICAL_TO_LOGICAL_PATH`** — always reports `NOT_IMPLEMENTED`. No mathematically
  explicit physical-active-array-to-logical-active-array coordinate transform is source-traced or
  device-proven anywhere in this codebase; task §3 explicitly requires reporting `NOT_IMPLEMENTED`
  rather than fabricating one, and this pass does exactly that.

**Correctness note from this pass's own review.** An earlier revision of hypothesis A/B routed through
this codebase's existing, gated `resolveAnalysisBufferIntrinsics` resolver — the exact function
§4-§10's own diagnostics reuse for matrix-construction evidence. That resolver's own crop-bounds
safety check (its step 7: the matrix-implied source region must lie within the active array it is
being validated against) would have silently made `LOGICAL_CAMERAX_MATRIX_PATH` `Unavailable` on
*every* real device where the logical and physical active arrays differ in size — precisely the Pixel 9
physical-camera-3 case this experiment exists to measure, defeating its own purpose. This pass composes
the buffer-space camera matrix via the same underlying pure algebra
(`activeArrayIntrinsicsFromFocalLength`, `mapActiveArrayIntrinsicsThroughMatrix`) but does **not** gate
on crop-region containment: a domain mismatch is exactly the evidence this experiment reports, never a
reason to hide the computation. Neither hypothesis path calls `resolveAnalysisBufferIntrinsics`,
`resolveCam2cForExplicitPhysicalCamera`, or constructs any `SensorToBufferDomainProof` value — verified
both by code review and by a dedicated pure-JVM regression test file
(`FrameContentMappingHypothesisTest.kt`, `FrameContentCorrespondenceSnapshotTest.kt`) that never
imports `SensorToBufferDomainProof` at all.

### 11.4 Residuals, region bucketing, and the conservative verdict

`FrameContentResidual.kt` computes, per detected point per hypothesis: observed/predicted buffer pixel
coordinates, `dx`/`dy`, Euclidean residual, residual normalized by the buffer diagonal, and a
CENTER/EDGE/CORNER region bucket (`FrameContentPointRegion.kt`, default 20%-of-axis edge band —
exported and justified, never a hidden constant). Points behind the camera, outside the physical active
array's own domain, outside the buffer bounds, or with a non-finite (invalid homogeneous-division)
projection are never silently discarded — each gets a typed
`FrameContentPointRejectionReason` and is counted, never merged into the accepted-point RMS/median/p95/
max statistics. `FrameContentVerdict.kt` computes one of seven evidence-only verdicts
(`INSUFFICIENT_POINTS`/`POSE_FIT_INVALID`/`LOGICAL_PATH_BETTER`/`PHYSICAL_PATH_BETTER`/
`RECONCILED_PATH_BETTER`/`PATHS_NUMERICALLY_INDISTINGUISHABLE`/`MIXED_OR_INCONCLUSIVE`), requiring at
least 6 accepted points spanning at least 2 distinct regions before any conclusive verdict, and
requiring the RMS margin between the best and second-best hypothesis to exceed the larger of an
exported absolute-pixel margin (`1.0px`) and an exported, explicitly-labelled-as-unmeasured detection
-noise placeholder (`0.75px`) before calling one hypothesis meaningfully better than another. **This
verdict is never converted into a `SensorToBufferDomainProof`, never publishes `AnalysisBuffer`
intrinsics, and never unblocks CAM-2c calibrated projection** — every export (`FrameContentCorrespondenceExport.kt`)
states this explicitly, in both the plain-text report and the JSON (`sensorToBufferDomainProofConstructed:
false`, `analysisBufferIntrinsicsPublished: false`).

### 11.5 The frozen snapshot, session state machine, and device workflow

`FrameContentCorrespondenceSnapshot.kt` freezes one immutable snapshot per analyzed frame/generation —
attempt/generation identifiers, requested/selected physical camera ID, verified physical-camera
provenance, opened-logical and selected-physical characteristics, requested/actual analysis resolution,
buffer dimensions, `cropRect`, `rotationDegrees`, the real CameraX matrix, zoom target/observed,
physical-camera calibrated K source and native basis, captured (never applied) distortion coefficients,
detected object/image points, and the one frozen pose — never mixing values across frames or
generations, mirroring `CamDiagnosticSnapshot`'s own deep-copy-at-capture convention.
`FrameContentCorrespondenceSessionState.kt`/`FrameContentCorrespondenceUiModel.kt` mirror
`ExperimentSessionState`/`ExperimentUiModel`'s own generation-scoped no-op-unless-current-attempt
pattern exactly: every reducer is a no-op for a stale `attemptId` or a terminally failed session, and a
new attempt (`startAttempt`) always constructs a brand-new session, never mutating the previous one's
snapshot. The device workflow (`FrameContentCorrespondenceScreen.kt`) is optimized for Pixel 9
validation per task §7: physical camera 3 listed first among candidates, fixed 640x480/1280x720
resolution buttons, Freeze/Resume, Copy report, Share JSON, a CENTER/TOP_LEFT/TOP_RIGHT/BOTTOM_LEFT/
BOTTOM_RIGHT target-placement label, a freeform-millimetre distance field, and a live detected-point
count plus per-hypothesis RMS in the action header. Launched in-app only, from a new "Open
frame-content experiment" action alongside the existing physical-camera experiment's own button in
`CamDiagnosticFullReportDialog`, registered `android:exported="false"` in
`mobile/src/internalDebug/AndroidManifest.xml` — the same non-exported, in-app-launch-only convention
every prior experiment in this file uses.

### 11.6 What is honestly established by this pass, and what is not

**Established, this pass (real Gradle execution, this session):**

```
./gradlew :mobile:testInternalDebugUnitTest              — PASSED (588 tests, 0 failures — 44 new
                                                             frame-content tests, all passing)
./gradlew :mobile:compileInternalDebugAndroidTestKotlin   — PASSED
./gradlew :mobile:lintInternalDebug                       — PASSED (0 errors, 30 pre-existing warnings
                                                             unrelated to this pass)
./gradlew :mobile:assembleInternalDebug                   — PASSED
./gradlew :mobile:testPublicDebugUnitTest                 — PASSED (publicDebug/production untouched)
```

The 44 new pure-JVM tests cover: identical predicted/observed points producing zero residual; a known
translation error reported exactly; a known scale error whose residual provably grows toward the
buffer edges; center/edge/corner bucketing at both default and custom thresholds; RMS/median/p95/max
against hand-computed values; rejected invalid-homogeneous-division points excluded from statistics but
counted by reason; two hypotheses with equal RMS reported `PATHS_NUMERICALLY_INDISTINGUISHABLE`; the
synthetic Pixel-9-physical-camera-3 case (`4080x3072` logical vs. `4032x3024` physical active array) in
which `LOGICAL_CAMERAX_MATRIX_PATH` and `PHYSICAL_ACTIVE_ARRAY_MODEL_PATH` both resolve but disagree
by a measurable, non-trivial buffer-pixel amount; the `RECONCILED_PHYSICAL_TO_LOGICAL_PATH` always
reporting `NOT_IMPLEMENTED`; one frozen pose reproducibly reused (bit-for-bit reprojection match)
across every hypothesis's own residual computation; freeze/generation atomicity and late-callback
rejection in the session state machine; and a new attempt cleanly clearing the previous attempt's
snapshot.

**Not established by this pass — the honest gap, matching every prior entry in this file's own
convention:**

- **No physical device or emulator has been attached in this pass, or any pass in this file.** Every
  claim above is build/lint/unit-test verified in a cloud container; none of it is a real Pixel 9
  observation.
- **The detector's real-world accuracy on an actual printed dot-grid target, under real lighting, at a
  real angle, is completely unverified.** The pure-JVM tests exercise the residual/hypothesis/verdict
  *math* against synthetic, hand-constructed or self-consistently-generated points — none of them feed
  the detector a real camera frame. `FrameContentCornerDetector.kt`'s own algorithm has not been proven
  against any real image.
- **The planar-homography pose solver's real-world accuracy is unverified** beyond the noiseless,
  self-consistent synthetic round trip `FrameContentCorrespondenceSnapshotTest.kt` exercises. It is a
  first-cut, non-iteratively-refined estimate by design (§11.2) — real detector noise, a real target
  not perfectly planar/positioned, and real lens distortion (never applied — see below) will all degrade
  it in ways this pass cannot measure without a device.
- **Distortion is captured, never applied.** `FrameContentDistortionCapture` records Android's raw
  `LENS_DISTORTION` coefficients as evidence only; this codebase has no verified interpretation of
  their semantics (the division-model contract per API 29+ is not source-traced or device-proven here),
  so every projection in this experiment is distortion-free pinhole math. A real device's residuals
  will include whatever real lens distortion this omission cannot correct for.
- **The conservative-verdict thresholds (`1.0px` absolute margin, `0.75px` detection-noise placeholder)
  are not measured values from any real session** — no device has run this experiment yet to measure
  real detection/pose noise. They are conservative, documented placeholders, exported alongside every
  verdict so a reader can judge them, never presented as calibrated.
- Whether a real Pixel 9 physical-camera-3 session even produces a detectable target at all — camera
  focus, target size/distance, lighting, and the fixed-grid correspondence assumption (§11.1) are all
  untested against real hardware.

**Final status, this pass:**

```
CAM-2c FRAME-CONTENT EXPERIMENT CODE READY
PHYSICAL CAMERA 3 IS PRIMARY DISCRIMINATING CASE
ONE FROZEN POSE REUSED ACROSS HYPOTHESES
PER-POINT BUFFER RESIDUAL EXPORT READY
INSTRUMENTED/DEVICE EXECUTION PENDING
SENSOR-TO-BUFFER DOMAIN PROOF NOT CONSTRUCTED
CALIBRATED ANALYSISBUFFER PUBLICATION STILL BLOCKED
```

## 12. CAM-2c frame-content correspondence experiment fix — epistemic correctness (this sprint, before any device evidence)

> **Corrected/extended by §13 below**, before any Pixel 9 device evidence was collected under this
> section's code: Freeze did not actually pin a complete, immutable evidence snapshot (a placement/
> distance edit after Freeze could still desynchronize the displayed selection from what Copy/Share
> exported), and the orientation marker's own measurements were discarded instead of exported (so an
> accepted detection's orientation decision could not be audited). Everything below remains otherwise
> accurate.

**Code-only pass; still no device evidence — device evidence collection remains explicitly deferred
until this fix landed.** A review of §11's experiment, conducted before any Pixel 9 run was attempted,
found a circularity defect serious enough to invalidate any path-winner verdict the experiment could
have produced, plus several smaller honesty gaps. This section fixes all of them. **Scope: internalDebug
only** — the same `FrameContent*.kt` files §11 added, plus their tests and this documentation. CAM-2a
production projection, `publicDebug`/release, the existing physical-camera-binding experiment,
`AnalysisBuffer` intrinsics publication, and every `SensorToBufferDomainProof` variant are all untouched
(same structural proof as §11: no file this pass touches imports `SensorToBufferDomainProof`).

### 12.1 The circularity defect, and how it is fixed

§11.2 solved pose once, against `PHYSICAL_ACTIVE_ARRAY_MODEL_PATH`'s own resolved buffer-space camera
matrix, then reused that pose "unchanged" across every hypothesis's residual computation. That reuse is
real and still true — but §11 then let `FrameContentVerdict.kt` report `PHYSICAL_PATH_BETTER` or
`LOGICAL_PATH_BETTER` from the resulting residuals. That is circular: a pose fit *under* one hypothesis
will, by construction, tend to produce a lower residual for that same hypothesis, independent of which
hypothesis actually describes the real coordinate mapping. A "physical path wins" verdict from that setup
is not evidence the physical path is correct — it is largely evidence the pose was anchored to it.

**Fix:**

- `FrameContentPoseMath.kt` renames the pose model constant to
  `CHARACTERISTICS_DERIVED_APPROXIMATE_PHYSICAL_PINHOLE_DOMAIN` (removing the old, overclaiming
  `_CALIBRATED_` naming — see §12.4) and exports two new, load-bearing constants on every pose solution:
  `POSE_REFERENCE_HYPOTHESIS = PHYSICAL_ACTIVE_ARRAY_MODEL_PATH` (which hypothesis the pose was actually
  anchored to, explicit rather than implied) and `CROSS_HYPOTHESIS_RESIDUAL_INTERPRETATION =
  CONDITIONAL_ON_PHYSICAL_ANCHORED_POSE` (a machine-readable flag that every residual comparison across
  hypotheses is conditional on that anchoring, never an independent measurement).
- `FrameContentVerdict.kt`'s enum no longer has `LOGICAL_PATH_BETTER`, `PHYSICAL_PATH_BETTER`, or
  `RECONCILED_PATH_BETTER` — those three overclaiming names cannot be emitted by any code path (locked by
  a regression test, §12.6). The replacement set is `INSUFFICIENT_POINTS`, `POSE_FIT_INVALID`,
  `CONDITIONAL_PATHS_NUMERICALLY_INDISTINGUISHABLE`, `CONDITIONAL_RESIDUALS_DIFFER`, and
  `MIXED_OR_INCONCLUSIVE`. The lowest-residual hypothesis is still computed and exported
  (`lowerResidualHypothesisId`) — ranking under the stated conditions remains useful evidence — but the
  verdict never asserts that hypothesis is the *correct* one. Every verdict export carries
  `residualInterpretation` and `independentPoseReferenceAvailable` (currently always `false`) alongside
  it, so the conditional nature cannot be silently dropped downstream.
- **What a stronger, non-circular experiment would require (documented, not implemented, in
  `FRAME_CONTENT_STRONGER_EXPERIMENT_REQUIRED`):** either (a) a pose or reference position sourced
  independently of every mapping hypothesis under test — e.g. a fixed, pre-surveyed rig position, an
  external tracking system, or a target whose pose is known by physical placement rather than fit from
  the same image points being scored — or (b) a multi-view/fixed-rig calibration that solves pose and
  hypothesis parameters jointly across many frames with cross-validation, not independently refitting
  each hypothesis to the same single-frame data (which merely relocates the circularity rather than
  removing it).
- A dedicated regression test in `FrameContentVerdictTest.kt` constructs a physical-anchored fixture with
  a large, clean residual gap and asserts the result can never resemble a "PHYSICAL_PATH_BETTER"
  proof-like verdict: no enum value ending in `_PATH_BETTER` exists at all, the verdict is
  `CONDITIONAL_RESIDUALS_DIFFER`, `lowerResidualHypothesisId` is reported but `residualInterpretation`
  and the reason text both state the conditional nature explicitly, and
  `independentPoseReferenceAvailable` is `false`.

### 12.2 Rotation-semantics correctness fix

§11's exported `rotationFoldedIn = true` for both implemented hypotheses was wrong — neither hypothesis's
math ever reads or applies `rotationDegrees` anywhere, and Pixel 9 evidence (§3/§8) shows
`rotationDegrees = 90` while the real observed matrix class is `AXIS_ALIGNED_0` (no rotation folded in).
`FrameContentMappingHypothesis.kt` now exports `rotationFoldedIn = false` for both `LOGICAL_CAMERAX_
MATRIX_PATH` and `PHYSICAL_ACTIVE_ARRAY_MODEL_PATH` (matching what the implementation has always actually
done — this is a documentation-honesty fix, not a math change), keeps `cropRectFoldedIn = false`
unchanged, and adds an explicit, shared `bufferRotationContract` string
(`FRAME_CONTENT_UNROTATED_BUFFER_CONTRACT`) describing the unrotated-`ImageProxy`-buffer contract this
experiment operates under. `whyOutputIsBufferCoordinates` no longer references
`resolveAnalysisBufferIntrinsics`, since neither implemented hypothesis calls it (§11.3's correctness
note already established this; the exported text simply said the wrong thing). Both facts are locked by
a new regression test in `FrameContentMappingHypothesisTest.kt`.

### 12.3 Frozen placement/distance evidence metadata

§11's `targetPlacementLabel`/distance field existed in the UI but was never part of the frozen snapshot,
so Copy/Share could silently export a stale or mismatched label relative to what was on-screen at capture
time. `FrameContentCorrespondenceSnapshot.kt` now carries `targetPlacementLabel` and `distanceLabelMm` as
frozen snapshot fields, threaded through from session state at build time. **Documented, chosen
edit-after-freeze behavior:** editing placement or distance after a snapshot already exists immediately
patches that exact snapshot's metadata in place, at the same generation and attemptId, leaving every
other field (frame, detection, pose, hypotheses, residuals, verdict) untouched — never a second,
re-derived snapshot, and never a live-UI value that could disagree with what Copy/Share actually exports.
A new attempt resets both fields to their documented defaults (`CENTER`, `null`). All three behaviors are
covered by new tests in `FrameContentCorrespondenceSessionStateTest.kt`.

### 12.4 Complete JSON evidence schema

§11's JSON export omitted several fields present in the text report and vice versa. `FrameContentCorrespondenceExport.kt`'s
JSON schema version is bumped to `2` (documented changelog in-file) and now exports, exhaustively: full
session/provenance identifiers, both characteristics snapshots, crop rect, requested resolution family,
placement/distance metadata, the renamed approximate-pinhole-K evidence block, detection tolerances, every
object point, every detected point, the pose (including `referenceHypothesis` and
`crossHypothesisResidualInterpretation`), every hypothesis's full documentation/intrinsics/diagnostics and
*every* residual point (not a summary only), and the verdict's full field set including
`lowerResidualHypothesisId`, `residualInterpretation`, `independentPoseReferenceAvailable`, and
`strongerExperimentRequired`. New tests in `FrameContentCorrespondenceSnapshotTest.kt` parse the JSON via
`kotlinx.serialization.json.Json.parseToJsonElement` and assert on the parsed structure — never substring
matching — so a future field rename or removal fails a test instead of silently degrading the evidence
artifact.

### 12.5 "Calibrated" language removed

§11 called the characteristics-derived K "calibrated" in places, which overclaims: it is focal-length- and
sensor-geometry-derived from `CameraCharacteristics`, not a measured lens calibration
(`usedLensIntrinsicCalibration = false`, always). Every reference is renamed to "characteristics-derived
approximate physical pinhole K" / `CHARACTERISTICS_DERIVED_APPROXIMATE_PHYSICAL_PINHOLE_DOMAIN`, and the
exported `FrameContentApproximatePinholeKEvidence` block now states `quality =
APPROXIMATE_PRINCIPAL_POINT`, `usedLensIntrinsicCalibration = false`, and an explicit principal-point
assumption string, alongside the focal-derivation inputs (focal length, sensor physical size, pixel/active
array sizes) it was derived from. Locked by a regression test asserting the old "calibrated" wording is
absent from every hypothesis's documentation.

### 12.6 Hardened dot-grid correspondence identity

§11.1 already documented its own scope limitation honestly ("assumes the whole grid is visible and not
rotated far from upright") but did not actually detect or reject the 180°-rotation ambiguity a plain
symmetric dot grid has no way to resolve from image content alone: a grid observed upside-down is
indistinguishable, blob-for-blob, from the same grid right-side-up. This pass implements the preferred
fix (an asymmetric orientation feature) rather than merely flagging the ambiguity as permanently unusable:
`FrameContentTargetSpec` gains a `markerAreaScaleFactor` (default 2.5x), and the target now includes one
distinctly larger marker dot. `FrameContentCornerDetector.kt` requires exactly `pointCount + 1` candidate
blobs, identifies the single marker candidate by area ratio, resolves grid orientation from the marker's
position relative to the four grid corners with an explicit confidence-ratio gate, and validates row
separation, monotonic ordering, and spacing consistency before accepting a detection. Any case that does
not cleanly resolve — wrong blob count, no single unambiguous marker, overlapping rows, inconsistent
spacing — returns a new, explicit `FrameContentDetectionResult.OrientationAmbiguous` outcome (Option B,
layered under Option A) rather than ever guessing. Detection is never accepted from blob count alone.
Partial-grid support remains explicitly out of scope, as it was in §11.

### 12.7 Honest refinement-status naming

§11's `CornerRefinementStatus.SUBPIXEL_REFINED` implied a real subpixel refinement stage that did not
exist — the value was actually derived from blob area alone. Renamed to `WEIGHTED_CENTROID_SUBPIXEL_
ESTIMATE` / `COARSE_CENTROID`, describing what the code actually computes (a pixel-weighted centroid, which
is a legitimate subpixel *estimate*, just not an iteratively refined one) rather than implying a
refinement pass this experiment does not run.

### 12.8 Camera/UI robustness

`FrameContentCorrespondenceScreen.kt`'s five placement controls are wrapped in a horizontally scrollable
row so they cannot overflow a portrait Pixel 9 layout. Copy report / Share JSON buttons are now `enabled
= false` (not a silent no-op) when no snapshot exists yet. The pose reference hypothesis and the
CONDITIONAL nature of the verdict are shown in a fixed, prominent header banner, alongside the verdict
itself, so a device operator cannot miss the conditional framing while reading results live.
Generation-scoped callback/cleanup behavior from §11 is unchanged.

### 12.9 Validation (real Gradle 8.14.3/JDK 17, same sandbox provisioning as every prior CAM-2c pass)

```
./gradlew :mobile:testInternalDebugUnitTest              — PASSED (596/596, 0 failures)
./gradlew :mobile:compileInternalDebugAndroidTestKotlin   — PASSED
./gradlew :mobile:lintInternalDebug                       — PASSED (0 errors)
./gradlew :mobile:assembleInternalDebug                   — PASSED
./gradlew :mobile:testPublicDebugUnitTest                 — PASSED (371/371, publicDebug/production
                                                             untouched)
```

No physical device or emulator was attached in this pass. **Instrumented (`androidTest`) sources compiled
successfully but were not executed** — no emulator/device is available in this sandbox. Everything §11.6
already listed as "not established" (detector real-world accuracy, pose-solver real-world accuracy,
distortion never applied, real-device detectability) remains equally unestablished here; this pass adds
epistemic correctness to the measurement machinery, not a device measurement.

**Final status, this pass:**

```
CAM-2c FRAME-CONTENT MEASUREMENT CODE READY
SINGLE-FRAME RESIDUALS EXPLICITLY CONDITIONAL ON PHYSICAL-ANCHORED POSE
NO INDEPENDENT PATH-WINNER VERDICT
UNROTATED IMAGEPROXY BUFFER CONTRACT EXPLICIT
PLACEMENT / DISTANCE FROZEN AND EXPORTED
COMPLETE JSON EVIDENCE SCHEMA READY
INSTRUMENTED/DEVICE EXECUTION PENDING
SENSOR-TO-BUFFER DOMAIN PROOF NOT CONSTRUCTED
CALIBRATED ANALYSISBUFFER PUBLICATION STILL BLOCKED
```

## 13. CAM-2c frame-content correspondence experiment fix round 2 — Freeze correctness and orientation auditability (this sprint, still before any device evidence)

**Code-only pass; still no device evidence.** A review of §12's fixed experiment, conducted before any
Pixel 9 run, found two further P1 issues: neither invalidates §12's own epistemic-correctness fix (the
conditional-verdict vocabulary, rotation semantics, and "never calibrated" language all remain unchanged),
but both were serious enough that device evidence collection stayed blocked until they were fixed.
**Scope: `internalDebug` only** — the same `FrameContent*.kt` file family, one new file
(`FrameContentTargetSvg.kt`), their tests, and this documentation. CAM-2a production projection,
`publicDebug`/release, the existing physical-camera-binding experiment, `AnalysisBuffer` intrinsics
publication, and every `SensorToBufferDomainProof` variant remain untouched.

### 13.1 Freeze did not pin a complete, immutable evidence snapshot

`FrameContentExperimentLiveOverlay` (`FrameContentCorrespondenceScreen.kt`) stored an entire frozen
`FrameContentExperimentSessionState` locally in a `frozenState` variable, and derived
`displayedState = frozenState ?: state`. That looks correct in isolation, but §12.3's own chosen
placement/distance behavior patches the **live** session's `latestSnapshot` in place on every edit —
so after Freeze, a stale live callback (or simply the operator continuing to tap a placement button,
which §12 never disabled) could advance the live session's snapshot while the frozen copy's own nested
snapshot silently diverged from what a device operator believed was displayed. The failure mode: Copy
report/Share JSON could export a placement/distance value that disagreed with the frozen label rendered
in the UI, or vice versa — precisely the "never silently show one label while exporting another" failure
§12.3 itself set out to prevent, reintroduced by an incomplete Freeze.

**Fix.** `FrameContentExperimentLiveOverlay` now stores only
`frozenSnapshot: FrameContentCorrespondenceSnapshot?` — a single, genuinely immutable value, never a
whole session state that could contain further-mutable-in-spirit nested fields. Every read in the overlay
(header, report body, Copy, Share) derives from one value: `displayedSnapshot = frozenSnapshot ?:
state.latestSnapshot`. Specifically:

- **Freeze** pins `frozenSnapshot` to the exact `FrameContentCorrespondenceSnapshot` instance live at
  that moment, including its own `targetPlacementLabel`/`distanceLabelMm` — not a reference to anything
  that can change afterward.
- **While frozen**, the five placement buttons and the distance text field are `enabled = false` —
  editing them could never be reflected in what's displayed anyway, so the UI no longer implies it would
  be. The displayed placement/distance value while frozen is read from `frozenSnapshot` itself, never
  from the live session's own (possibly since-changed) fields.
- **Copy report / Share JSON** always read `displayedSnapshot` — the frozen snapshot while frozen, the
  live snapshot otherwise. There is no code path that can display one selection while exporting another.
- **Resume** (`frozenSnapshot = null`) immediately shows whatever the live session's own snapshot is
  now — never a stale intermediate value.
- **The header** now states three facts explicitly, so a frozen generation is never implied to be the
  current live one: `liveFramesObserved` (the live session's own frame count, which keeps advancing while
  frozen), `displayedGeneration` (the generation of whichever snapshot is actually shown), and
  `liveness=LIVE|FROZEN`.

New Compose UI tests (`FrameContentExperimentLiveOverlayUiTest.kt`, `internalDebug` `androidTest`)
exercise the full contract: a snapshot A exists; Freeze; placement/distance controls are disabled; a live
callback publishes a second, different snapshot B while frozen; the displayed report/live-summary/Copy
output all still describe snapshot A, never B; Resume switches the display to the latest live snapshot;
and editing placement while live is reflected consistently in the report. A fifth test confirms Freeze
itself is disabled (not a silent no-op) when there is no snapshot yet to freeze.

### 13.2 The orientation marker's own measurements were discarded, not exported

The orientation marker is load-bearing (§11.1's own "correspondence-identity fix" KDoc, §12's regression
test for the 180-degree case) — it is the sole basis for deciding which raster corner is the target's true
`R0C0`, and therefore for every detected point's semantic `FrameContentPointId`. But
`FrameContentDetectionResult.Detected` retained only the final, remapped `points` list — the raw
marker-centroid position, its measured area, the corner-distance comparison, and the row/spacing
statistics that justified accepting the grid in the first place were computed inside
`detectFrameContentTargetCorners` and then thrown away. A reviewer auditing an accepted detection had no
way to check *why* it was accepted beyond re-deriving approximate values from the already-remapped
points — which is not the same evidence, and is exactly the kind of downstream recomputation task §2
explicitly rules out ("do not recompute these later from the remapped points").

**Fix.** Two new frozen, immutable evidence types, populated by `detectFrameContentTargetCorners` itself
at the exact point each value is computed — never recomputed afterward:

- **`FrameContentOrientationEvidence`**: the marker blob's own centroid and pixel area, the candidate
  set's median grid-dot area, the actual `observedMarkerAreaRatio` this frame achieved (marker area ÷
  median grid-dot area), the resolved origin corner, the nearest/second-nearest raster-corner distances
  to the marker, and the actual `observedCornerConfidenceRatio` this frame achieved.
- **`FrameContentGridGeometryEvidence`**: the minimum adjacent-row separation, the min/max/median
  within-row gap, the min/max/median between-row gap, the median gap used for the consistency check, and
  the configured spacing-consistency limits it was validated against.

Both are carried on `FrameContentDetectionResult.Detected` (nullable — `null` for any non-accepted
outcome), threaded unchanged through `FrameContentCorrespondenceSnapshot`, and exported in both the text
report (new `ORIENTATION EVIDENCE`/`GRID GEOMETRY EVIDENCE` sections) and the JSON (schema bumped to `3`;
new `orientationEvidence`/`gridGeometryEvidence` objects, parsed structurally in
`FrameContentCorrespondenceSnapshotTest.kt`, never substring-matched).

**The report now explicitly distinguishes three ratios that must never be conflated** — a direct fix for
the failure mode task §2 called out ("do not imply that a 2.5x design marker was observed merely because
the detector accepted a value above the 1.5x threshold"):

1. `FrameContentTargetSpec.markerAreaScaleFactor` (renamed `designMarkerAreaScaleFactor` in the JSON's
   `target` object) — the physical target's *printed design* ratio, `2.5x` by default.
2. `FrameContentDetectionTolerances.markerAreaRatioThreshold` — the detector's own *acceptance*
   threshold, `1.5x` by default; this is the only ratio a detection is actually gated on.
3. `FrameContentOrientationEvidence.observedMarkerAreaRatio` — the *actual measured* ratio for this
   specific frame, which may legitimately differ from both of the above (perspective, lighting, and
   detection noise all affect it).

New tests in `FrameContentCornerDetectorTest.kt` render a marker at a deliberately different ratio
(`1.8x`) than the target spec's own design ratio (`2.5x`) and confirm `observedMarkerAreaRatio` tracks the
actual rendered value, never the spec's — proving these are genuinely independent, separately computed
fields rather than one echoing the other.

### 13.3 Printable target geometry (task §3's "make the physical target reproducible")

`FrameContentTargetSpec` gained explicit printed-target geometry: `regularDotDiameterMm` (a real printed
diameter, default `8.0mm`), `markerDiameterMm` (a computed property — `regularDotDiameterMm *
sqrt(markerAreaScaleFactor)`, never an independently settable field, so the printed marker and the
detector's own area-ratio math can never silently drift apart), and `markerOffsetXMm`/`markerOffsetYMm`
placing the marker's centre relative to `R0C0`. `FrameContentTargetSpec.init` now validates the marker
offset is strictly nearer `R0C0` than every other grid corner — a misconfigured spec fails fast at
construction, rather than silently describing a physically ambiguous printed target (locked by two new
`FrameContentTargetTest` regression tests). A new `frameContentTargetPrintableBounds` function computes
the smallest rectangle containing every printed regular dot and the marker at their real radii — the
"total printable target bounds" task §3 asked for, exported in both the text report and the JSON's
`target.printableBounds` object.

**A new deterministic SVG generator, `buildFrameContentTargetSvg`** (`FrameContentTargetSvg.kt`, Option A
from task §3, preferred over a checked-in static asset) renders this exact geometry — every regular dot
at its real printed diameter, the marker at its own derived diameter, at true millimetre scale (`width`/
`height` in `mm`, so a compliant SVG viewer/printer reproduces the physical target exactly). Wired to a
new "Share target SVG" button on the device workflow's candidate-picker screen (`FrameContentCorrespondenceScreen.kt`,
before an attempt even starts) via the same `shareCamDiagnosticText` mechanism every other export in this
file family uses. Every device report already exports the full target spec used (§12.4's JSON schema
work); this generator is the corresponding physical artifact, so the printed target behind any future
device run is always reproducible from code, never an ad hoc hand-drawn substitute.

> **Superseded by §14 below:** `shareCamDiagnosticText` always builds `type = "text/plain"`/`EXTRA_TEXT`
> — the "Share target SVG" button described in this paragraph never actually created or attached any
> file, contradicting this same section's own "printing this exact file"/reproducibility framing. §14.1
> replaces the button's wiring with a dedicated `image/svg+xml` file share. `buildFrameContentTargetSvg`
> itself, and everything this paragraph says about its geometry, is unaffected.

### 13.4 Validation (real Gradle 8.14.3/JDK 17, same sandbox provisioning as every prior CAM-2c pass)

```
./gradlew :mobile:testInternalDebugUnitTest              — PASSED (609/609, 0 failures — 13 new tests:
                                                             FrameContentTargetTest (7),
                                                             FrameContentCornerDetectorTest (6))
./gradlew :mobile:compileInternalDebugAndroidTestKotlin   — PASSED (new
                                                             FrameContentExperimentLiveOverlayUiTest.kt,
                                                             5 tests, compiled)
./gradlew :mobile:lintInternalDebug                       — PASSED (0 errors)
./gradlew :mobile:assembleInternalDebug                   — PASSED
./gradlew :mobile:testPublicDebugUnitTest                 — PASSED (371/371, publicDebug/production
                                                             untouched)
```

`FrameContentCornerDetectorTest` is the first test file in this experiment to actually render and detect
a target, rather than only exercising the residual/hypothesis/verdict math against hand-constructed
`DetectedTargetPoint` fixtures: it synthesizes a `LumaBuffer` from flat-luma filled circles at exact,
computable pixel positions derived from the target's own millimetre geometry (the same convention
`buildFrameContentTargetSvg` uses), and proves the detector recovers the correct semantic point IDs — not
merely the correct point *count* — for both an unrotated render and a 180-degree-rotated render of the
identical physical target, and that an ambiguous marker size or an ambiguous corner-confidence render
correctly falls back to `OrientationAmbiguous`, never a guess. This is still a synthetic render, not a
real photograph: the detector's accuracy against actual camera noise, lighting, lens distortion, and
imperfect target placement remains exactly as unestablished as every prior pass in this file already
recorded. No physical device or emulator was attached in this pass; the new
`FrameContentExperimentLiveOverlayUiTest` compiled successfully but was not executed.

**Final status, this pass:**

```
CAM-2c FRAME-CONTENT MEASUREMENT CODE READY
SINGLE-FRAME RESIDUALS CONDITIONAL ON PHYSICAL-ANCHORED POSE
FREEZE PINS COMPLETE EVIDENCE SNAPSHOT
ORIENTATION DECISION FULLY AUDITABLE
PRINTABLE TARGET GEOMETRY REPRODUCIBLE
NO INDEPENDENT PATH-WINNER VERDICT
INSTRUMENTED/DEVICE EXECUTION PENDING
SENSOR-TO-BUFFER DOMAIN PROOF NOT CONSTRUCTED
CALIBRATED ANALYSISBUFFER PUBLICATION STILL BLOCKED
```

**Superseded by §14 below** on the two points noted inline in §13.3 above (SVG text-only sharing, marker/
dot overlap not validated) — every other line of this status block still stands unchanged.

## 14. Printable-target workflow blockers, round 3: real SVG file sharing + printed-circle overlap rejection

**Trigger:** two further P1 issues found in §13's own output, before any Pixel 9 device evidence was
collected under its code.

### 14.1 "Share target SVG" never shared a real file

`CandidatePicker` (§13.3) called `shareCamDiagnosticText(context, "CAM-2c frame-content target SVG",
buildFrameContentTargetSvg(DEFAULT_FRAME_CONTENT_TARGET_SPEC))`. `shareCamDiagnosticText`
(`CamDiagnosticActions.kt`) unconditionally constructs `ACTION_SEND` with `type = "text/plain"` and
`EXTRA_TEXT` — it has no file-writing or `FileProvider` code path at all. The result: no `.svg` file was
ever created or attached; a receiving app was offered a plain-text blob it could paste somewhere, never
something it could open as an SVG, preview as an image, or print at true millimetre scale — directly
contradicting the UI's own "Share target SVG" label and §13.3's "printing this exact file"/"print-ready
SVG" framing.

**Fix — a dedicated `image/svg+xml` file-sharing path, `FrameContentTargetSvgSharing.kt`
(`internalDebug`-only):**

- `writeFrameContentTargetSvgToCache(context, spec)` writes `buildFrameContentTargetSvg(spec)`'s *exact*
  UTF-8 bytes (`.toByteArray(Charsets.UTF_8)`, explicit charset, never platform-default) to
  `<cacheDir>/cam2c_target/cam_2c_frame_content_target.svg` — a stable, descriptive filename; every call
  overwrites the same file with the same deterministic bytes for the same `spec`, never a randomized or
  timestamped name.
- `buildFrameContentTargetSvgShareIntent(context, spec): Intent` — pure `Intent` construction (mirrors
  `buildCamDiagnosticShareIntent`'s own testability rationale: never launches anything itself, never
  wraps a chooser). Writing the cache file is unavoidable here (there is no `Uri` without the underlying
  file existing), but nothing beyond that file write is a side effect the caller cannot predict. Builds an
  explicit `ACTION_SEND` with:
  - `type = "image/svg+xml"`
  - `Intent.EXTRA_STREAM` = a `FileProvider`-issued content `Uri` for the written file — never a bare
    `file://` `Uri`, which throws `FileUriExposedException` on API 24+ and which no other app's process
    could read from directly regardless
  - `Intent.FLAG_GRANT_READ_URI_PERMISSION`
  - a `ClipData` (`ClipData.newUri`) wrapping the *same* `Uri` — some receiving apps only honour the
    temporary read grant via `ClipData`, not `EXTRA_STREAM` alone
- `shareFrameContentTargetSvg(context, spec)` wraps `buildFrameContentTargetSvgShareIntent`'s result in
  `Intent.createChooser` and calls `context.startActivity` — the one place in this file that actually
  launches anything, matching `shareCamDiagnosticText`'s own chooser-at-the-boundary convention (including
  the same `FLAG_ACTIVITY_NEW_TASK`-for-non-`Activity`-context defensive check).

**FileProvider:** the existing `${applicationId}.logs` provider (`mobile/src/main/AndroidManifest.xml`,
`filepaths_logs.xml`) is scoped only to a `files-path name="crash_logs" path="crash/"` — it has **no**
`cache-path` entry at all, so it cannot serve a cache-backed file; reusing it was not an option. A new,
narrowly-scoped provider was added to `mobile/src/internalDebug/AndroidManifest.xml` instead:
`android:authorities="${applicationId}.cam2c.target"`, `android:exported="false"`,
`android:grantUriPermissions="true"`, pointing at a new `res/xml/filepaths_cam2c_target.xml` whose only
entry is `<cache-path name="cam2c_target" path="cam2c_target/" />` — scoped to just the one subdirectory
the SVG file lives in, never the whole cache directory. This provider is declared only in the
`internalDebug` manifest source set, so it is never merged into `publicDebug`/`internalRelease`/
`publicRelease` (same convention as both existing `internalDebug`-only `<activity>` entries in that file).
No external-storage permission is requested anywhere in this fix — the file never leaves the app's own
cache directory except through the `FileProvider`'s temporary read grant.

A real, reproducible manifest-merge defect surfaced while wiring this up, not a code-review nit: AGP's
manifest merger keys `<provider>` elements by `android:name` alone (never by `android:authorities`), so a
second bare `<provider android:name="androidx.core.content.FileProvider" ...>` entry collided with the
existing `.logs` provider (`Attribute provider#androidx.core.content.FileProvider@authorities ... is also
present at ...`), even though every other attribute differs. Fixed with the standard workaround for
declaring more than one `FileProvider` in the same app: a new, behavior-free
`FrameContentTargetSvgFileProvider : FileProvider()` subclass, so the manifest's `android:name` differs
between the two `<provider>` entries. `android:authorities`/`grantUriPermissions`/`exported` are otherwise
unchanged from the description above.

`CandidatePicker`'s "Share target SVG" button now calls `shareFrameContentTargetSvg(context,
DEFAULT_FRAME_CONTENT_TARGET_SPEC)` directly; the label is unchanged because it is now factually accurate
— tapping it does share a real `.svg` file.

**New tests, `FrameContentTargetSvgSharingTest.kt` (`androidTestInternalDebug`)** — real `Uri`/
`FileProvider`/`PackageManager` behavior requires a real Android runtime (this project has no Robolectric,
matching every other `androidTest`-only file in this codebase), so every one of these lives in
`androidTest`, mirroring `CamDiagnosticActionsTest`'s own precedent:

- the generated file exists in cache after `buildFrameContentTargetSvgShareIntent`;
- the filename is exactly `cam_2c_frame_content_target.svg` (ends in `.svg`);
- the written bytes are exactly `buildFrameContentTargetSvg(spec).toByteArray(Charsets.UTF_8)`
  (`contentEquals`, byte-for-byte);
- the built `Intent`'s `action` is `ACTION_SEND`;
- its `type` is `"image/svg+xml"`;
- `EXTRA_STREAM` is a `content://` `Uri` whose authority is `${applicationId}.cam2c.target`;
- `FLAG_GRANT_READ_URI_PERMISSION` is set on the built `Intent`'s flags;
- the attached `ClipData` carries exactly one item, and that item's `Uri` equals `EXTRA_STREAM`'s;
- `PackageManager.resolveContentProvider` resolves the authority, and `ContentResolver.openInputStream`
  on the `Uri` returns the exact same bytes as the file on disk — the provider is genuinely readable, not
  merely declared;
- the resolved `ProviderInfo.exported` is `false` and `.grantUriPermissions` is `true`;
- `PackageManager.getPackageInfo(..., GET_PERMISSIONS).requestedPermissions` contains neither
  `WRITE_EXTERNAL_STORAGE` nor `READ_EXTERNAL_STORAGE`.

These compiled (`:mobile:compileInternalDebugAndroidTestKotlin`) in this pass but were **not executed** —
no emulator/device is attached in this sandbox (same limitation recorded for every `androidTest` file in
every prior CAM-2c pass). This document does not claim instrumented execution, a real chooser having been
opened, or a real SVG file having been opened or printed by an external application — none of those
actions occurred in this environment.

### 14.2 Overlapping target circles were never rejected

`FrameContentTargetSpec.init` (§13.3 and earlier) validated only that the marker centre is non-zero and
strictly closer to `R0C0` than to every other grid corner. It never checked that the *printed marker
circle* is spatially separate from the *printed `R0C0` dot circle* — a marker centre can be strictly
nearer `R0C0` than every other corner (satisfying every check that existed) while still sitting close
enough to `R0C0` that the two printed circles overlap. `detectFrameContentTargetCorners` requires exactly
`pointCount + 1` separate connected components; an overlapping marker/dot merges into one blob during
flood fill, silently changing the total blob count and making the resulting printed target intrinsically
undetectable — a configuration the old constructor happily accepted as valid.

**Fix — a physical non-overlap invariant, based on the real printed radii, added to
`FrameContentTargetSpec.init`:**

- for every regular object point: `centerDistanceMm > markerRadiusMm + regularDotRadiusMm +
  minimumBlobClearanceMm` (computed from the same row/col formula `frameContentTargetObjectPoints` uses,
  inlined in `init` rather than calling that function with a not-yet-fully-constructed `this`);
- regular dots clear each other by the same margin: `dotSpacingMm > regularDotDiameterMm +
  minimumBlobClearanceMm` (the closest any two regular dots ever get is one full `dotSpacingMm` apart,
  along a row or column, so this single inequality covers every dot-to-dot pair).

`minimumBlobClearanceMm` is a new field on `FrameContentTargetSpec`, defaulting to a new named constant
`FRAME_CONTENT_MINIMUM_BLOB_CLEARANCE_MM = 3.0` (mm) — a real, positive, documented margin, never merely
`>=` the sum of the two radii (printed ink spread, motion blur, and detector noise can all turn two
idealized-tangent circles into one merged blob in a real photograph). Both new `require`s use a strict
`>`, matching every other geometric invariant already in this class (never `>=`): a clearance exactly
equal to `minimumBlobClearanceMm` is rejected, only a clearance strictly greater than it is accepted.

The default spec was re-verified against this invariant: the marker-to-`R0C0` distance (`≈21.21mm`) clears
the required threshold (`markerRadiusMm≈6.32 + regularDotRadiusMm=4.0 + minimumBlobClearanceMm=3.0 ≈
13.32mm`) by a wide margin, and `dotSpacingMm=25.0` clears `regularDotDiameterMm=8.0 +
minimumBlobClearanceMm=3.0 = 11.0` comfortably — the default target needed no geometry changes.

`minimumBlobClearanceMm` is exported in both the text report (`TARGET` section's `printableGeometry`
line) and the JSON (`target.minimumBlobClearanceMm`; schema bumped `3` → `4`, §12.4/§13.2's schema-history
convention continued) — a physical target reproduced from a report can never silently drift into a
physically-overlapping, undetectable configuration. `buildFrameContentTargetSvg`'s own output is
unaffected by this fix beyond an added `minimumBlobClearanceMm=` field in its metadata comment — its
circle geometry already came directly from the (now more strictly validated) `spec`, so "SVG output
reflects the validated geometry exactly" required no rendering changes.

**New tests, `FrameContentTargetTest.kt` (`testInternalDebug`, plain JVM):**

- the default target passes every physical separation invariant (marker-to-every-dot and dot-to-dot
  alike);
- a marker centre different from `(0, 0)` (so not the pre-existing "coincides with R0C0" case) but still
  overlapping `R0C0`'s printed circle is rejected;
- a marker exactly tangent to `R0C0`'s printed circle (zero clearance) is rejected;
- a marker clearance just below `minimumBlobClearanceMm` is rejected;
- a marker clearance exactly at the `minimumBlobClearanceMm` boundary is rejected, and a clearance
  strictly above it is accepted — documenting the strict-`>` rule explicitly, in one test;
- a `regularDotDiameterMm` too large for `dotSpacingMm` is rejected (marker offset pushed far out along
  the same diagonal so only the dot-to-dot invariant, not the marker-to-dot one, is exercised);
- a custom, non-default valid `FrameContentTargetSpec` still passes construction, renders via a local
  synthetic-render helper (the same flat-luma filled-circle convention `FrameContentCornerDetectorTest`
  uses), and is correctly detected by `detectFrameContentTargetCorners` — proving the new invariant does
  not silently over-constrain valid custom targets;
- every circle pair parsed out of `buildFrameContentTargetSvg(DEFAULT_FRAME_CONTENT_TARGET_SPEC)`'s own
  `<circle cx cy r>` elements is separated (`centreDistance - r1 - r2`) by at least the spec's own exported
  `minimumBlobClearanceMm` — checked against the rendered SVG text itself, not just the underlying spec
  math.

`FrameContentCorrespondenceSnapshotTest.kt`'s existing JSON-schema structural test gained one assertion:
`target.minimumBlobClearanceMm` in the exported JSON equals `DEFAULT_FRAME_CONTENT_TARGET_SPEC.
minimumBlobClearanceMm`.

### 14.3 Scope

`internalDebug`-only: `FrameContentTarget.kt`, `FrameContentTargetSvg.kt`,
`FrameContentCorrespondenceExport.kt`, `FrameContentCorrespondenceScreen.kt`, one new file
`FrameContentTargetSvgSharing.kt`, one new manifest `<provider>` entry, one new
`res/xml/filepaths_cam2c_target.xml`, plus their tests (`FrameContentTargetTest.kt`,
`FrameContentCorrespondenceSnapshotTest.kt`, new `FrameContentTargetSvgSharingTest.kt`) and this
documentation. CAM-2a production projection, `publicDebug`/release, the existing
physical-camera-binding experiment, `AnalysisBuffer` intrinsics publication, and every
`SensorToBufferDomainProof` variant remain untouched — verified by scope: no file outside the list above
was modified, and the new `<provider>` is declared only in `mobile/src/internalDebug/AndroidManifest.xml`.

### 14.4 Validation (real Gradle 8.14.3/JDK 17)

This sandbox started with neither an Android SDK nor a JDK 17 toolchain provisioned (a fresh environment,
not a continuation of any prior pass's sandbox) — both were provisioned locally for this pass, matching
every prior CAM-2c pass's own recorded deviation: an Android SDK (`platform-tools`,
`platforms;android-35`, `build-tools;35.0.0`, via `sdkmanager` against the reachable `dl.google.com`
through this environment's egress proxy) and `openjdk-17-jdk-headless` (via `apt`); the pinned Gradle
8.11.1 distribution (`gradle/wrapper/gradle-wrapper.properties`) was again unreachable, so the
preinstalled Gradle 8.14.3 binary was used in its place, as before. With those in place, every gate below
is a real, executed Gradle result:

```
./gradlew :mobile:testInternalDebugUnitTest              — PASSED (617/617, 0 failures/errors — 15
                                                             tests in FrameContentTargetTest (8 new: the
                                                             physical non-overlap invariant suite), 1 new
                                                             assertion in
                                                             FrameContentCorrespondenceSnapshotTest)
./gradlew :mobile:compileInternalDebugAndroidTestKotlin   — PASSED (new
                                                             FrameContentTargetSvgSharingTest.kt, 10
                                                             tests, compiled)
./gradlew :mobile:lintInternalDebug                       — PASSED (0 errors, 30 warnings — 147
                                                             errors/23 warnings pre-filtered by the
                                                             existing lint-baseline.xml; none
                                                             attributable to this pass)
./gradlew :mobile:assembleInternalDebug                   — PASSED
./gradlew :mobile:testPublicDebugUnitTest                 — PASSED (371/371, publicDebug/production
                                                             untouched — proves the new internalDebug-only
                                                             FileProvider/manifest changes are not merged
                                                             into the public flavor)
```

No physical device or emulator was attached in this pass (same unresolved limitation every prior CAM-2c
pass has recorded): `FrameContentTargetSvgSharingTest` compiled but was not executed; nothing in this
section claims instrumented execution, successful external SVG opening/printing, or Pixel 9 device
validation.

**Final status, this pass:**

```
CAM-2c FRAME-CONTENT MEASUREMENT CODE READY
FREEZE PINS COMPLETE EVIDENCE SNAPSHOT
ORIENTATION DECISION FULLY AUDITABLE
PRINTABLE TARGET GEOMETRY REPRODUCIBLE
TARGET CIRCLES PHYSICALLY SEPARATED BY VALIDATED CLEARANCE
TARGET SVG SHARED AS A REAL image/svg+xml FILE
SINGLE-FRAME RESIDUALS CONDITIONAL ON PHYSICAL-ANCHORED POSE
NO INDEPENDENT PATH-WINNER VERDICT
INSTRUMENTED/DEVICE EXECUTION PENDING
SENSOR-TO-BUFFER DOMAIN PROOF NOT CONSTRUCTED
CALIBRATED ANALYSISBUFFER PUBLICATION STILL BLOCKED
```
