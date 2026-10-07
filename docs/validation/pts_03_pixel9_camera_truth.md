# PTS-03 — Pixel 9 camera truth: identity, projection domain, distortion state, extrinsics

> **Status: PTS-03 instrumentation complete · Pixel 9 evidence pending · PTS-03 NOT YET COMPLETE.**
>
> No Pixel 9 was attached to the environment that produced this revision. Every A/B/C/D result below is
> **UNRESOLVED (device evidence pending)**. Nothing in this file is a device conclusion. Fill the result
> tables only from exported JSON files, never from screenshots or memory.

Master recon: `docs/pointtosky/MASTER_POINTTOSKY_RECON_2026-10-06.md` §10, §11, §14.1, §21, §23, §27 G-03,
§28.1–28.2, §29 PTS-03, §30, §31. Earlier device evidence: `docs/validation/cam_2c_pixel9_evidence.md`
(CameraX **1.3.4**; its 640×480 analysis buffer is **not** CameraX 1.4.2 evidence).

The four groups are independent. None proves another:

| Group | Question | A known value here does **not** prove |
|---|---|---|
| A — physical-frame identity | which physical camera produced each analysis frame | B, C, D |
| B — projection-domain compatibility | which sensor basis the delivered `sensorToBufferTransformMatrix` maps from, and whether that physical camera's arrays/intrinsics compose with it | A, C, D |
| C — distortion state / final pixel domain | requested vs effective `DISTORTION_CORRECTION_MODE`, the metadata basis it implies, and whether lens distortion **measurably remains** in the analysis YUV | A, B, D |
| D — camera↔device extrinsics | `R_Cphys_from_S(id)` from `LENS_POSE_*` and its provenance | A, B, C |

Status vocabulary used in every table: **PROVEN** (independent evidence satisfies the proof contract),
**OBSERVED** (recorded on device; not a proof), **UNRESOLVED** (not established — including "a hypothesis
was not falsified"), **MISMATCH** (evidence contradicts the hypothesis), **UNAVAILABLE** (the platform/device
does not report it). "Not yet failed" is never PROVEN.

---

## Environment

| Item | Value |
|---|---|
| Device model | _pending (from `environment.deviceModel`)_ |
| Android build / API | _pending (`environment.buildFingerprint`, `environment.sdkInt`)_ |
| App commit | _pending (`git rev-parse HEAD` of the installed build; record by hand)_ |
| App version | _pending (`environment.appVersionName` / `appVersionCode`)_ |
| CameraX | 1.4.2 (declared; `environment.cameraXVersion`, pinned to `gradle/libs.versions.toml` by `Pts03CameraTruthExportTest`) |
| Logical camera ID | _pending (`session.logicalCameraId`)_ |
| Physical camera IDs | _pending (`staticCharacteristics.declaredPhysicalCameraIds` — discovered, never hard-coded)_ |
| Session IDs | _pending (`sessionId`, format `pts03-<startEpochMillis>-a<attemptId>-<logical|physN>`)_ |

## A — Physical identity

Each joined frame carries one immutable snapshot of its `TotalCaptureResult`, with two parts kept apart:
- **`logicalTopLevelResult`** — the top-level (logical) result. Its `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`
  names the physical sensor backing the logical camera's non-physical-specific streams.
- **`physicalResultsByCameraId`** — every per-physical-camera result (`getPhysicalCameraTotalResults()` on
  API 31+, `getPhysicalCameraResults()` on API 28–30), sorted by ID, each with its **own**
  `SENSOR_TIMESTAMP`. A physical entry is used as frame truth only when that timestamp equals the frame's
  exactly; otherwise it is recorded as `TIMESTAMP_MISMATCH` / `TIMESTAMP_MISSING` (no nearest matching).

Two session classes, never merged, with different identity semantics (per-frame `attribution` in the JSON):
- **A1 `LOGICAL_UNPINNED`** — logical rear camera bound as production binds it (`DEFAULT_BACK_CAMERA`, no
  `setPhysicalCameraId`, no zoom call). Producing camera = the top-level active ID when reported, else
  unknown; the top-level result is the stream's dynamic metadata. **Only here** is natural switching inferred.
- **A2 `EXPLICIT_PHYSICAL`** — `setPhysicalCameraId(id)` on the selector and, from export schema 2, on the
  Preview and ImageAnalysis `Camera2Interop` too (see *A2 physical-ID propagation follow-up* below), so the
  producing camera is the requested ID **by configuration**. The top-level active ID describes the logical
  camera's backing sensor, not this output: it is recorded as a **diagnostic only** and never confirms or
  contradicts the pin. Dynamic metadata for the output comes only from `physicalResultsByCameraId[id]` with a
  matching timestamp; when absent or mismatched it is **UNRESOLVED** and the logical values are not substituted.

Transitions are counted only between two **reported** top-level active IDs; a frame without the key is a gap
(`3 → (missing) → 3` = no transition; `3 → (missing) → 4` = one transition `3 → 4`, stamped on the first
frame reporting `4`). Missing-key frames are still counted (`framesWithNullActivePhysicalId`).

| Question | Status | Evidence field |
|---|---|---|
| Is `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` reported? | UNRESOLVED (pending) | `groupA_identity.logicalTopLevel.activePhysicalIdAvailabilityCounts` |
| Which physical camera produces A1 analysis frames (normal light)? | UNRESOLVED (pending) | A1: `groupA_identity.logicalTopLevel.framesByLightingAndLogicalActivePhysicalId.NORMAL_INDOOR` |
| Does it switch in low light? When? | UNRESOLVED (pending) | A1: `groupA_identity.logicalTopLevel.transitions[]` (timestamp, from, to, lighting) |
| Does it switch at night? | night-sky device evidence pending | A1: `framesByLightingAndLogicalActivePhysicalId.NIGHT_SKY` |
| Do focal length / intrinsics / crop / zoom change with the producing camera? | UNRESOLVED (pending) | `groupA_identity.producingCamera.{focalLengthsMm,intrinsics,cropRegions,zoomRatios,activePhysicalSensorCropRegions}ByProducingPhysicalId` |
| A2: requested physical output ID | UNRESOLVED (pending) | `groupA_identity.explicitPhysicalOutput.requestedPhysicalOutputId` |
| A2: physical result present for it? timestamp matched? | UNRESOLVED (pending) | `explicitPhysicalOutput.physicalResultStatus` (`PHYSICAL_RESULT_PRESENT` / `_PARTIALLY_PRESENT` / `_NOT_REPORTED` / `_TIMESTAMP_MISMATCH`), `physicalResultStatusCounts`; per frame `attribution.physicalResultTimestampMatched` |
| A2: top-level logical active ID | diagnostic only | `groupA_identity.logicalTopLevel.framesByLogicalActivePhysicalId` (role `DIAGNOSTIC_ONLY`) |
| AF / focus behaviour per producing camera | UNRESOLVED (pending) | `producingCamera.afStateCountsByProducingPhysicalId`; per frame `controlAfMode`, `controlAfState`, `lensFocusDistanceDiopters`, `lensState` |

### A2 physical-ID propagation follow-up (Pixel 9 re-test pending)

**Trigger.** Pixel 9 (`tokay`, Android 17 / API 37, CameraX 1.4.2), A2 bound with the selector pin only
(export schema 1): physical 2 → 746/746 matched, top-level active ID 2, `physicalResultsByCameraId = {}`;
physical 3 → 389/389 matched, top-level active ID **2** on every frame, top-level focal length 6.9 mm,
`physicalResultsByCameraId = {}`, and a 1280×720 `sensorToBufferTransformMatrix` with
`sx = sy = 0.3137255` (= 1280 / 4080, the logical active-array width, not camera 3's 4032).

**Bind configuration.**

| | Selector | Preview interop | ImageAnalysis interop |
|---|---|---|---|
| Schema 1 (#245), A2 `X` | `X` | — | — |
| Schema 2 (this follow-up), A2 `X` | `X` | `X` | `X` |
| A1 (both schemas) | — | — | — |

Recorded in the export as `physicalBindingRequest` (`provenance = REQUESTED_BIND_CONFIGURATION`, never a
proven producer). The selector pin is kept deliberately; the redundancy is a diagnostic control.

**What CameraX 1.4.2 does with each request (read from its sources, not observed on the device).**
`ProcessCameraProvider` copies the selector's physical ID onto every bound `UseCase`. `Preview` passes it to
its `SessionConfig.OutputConfig`; `ImageAnalysis.createPipeline` passes `null`, so with the selector alone
the analysis `OutputConfig` carries no physical ID. `Camera2Interop.Extender.setPhysicalCameraId` is a
different option (`camera2.cameraCaptureSession.physicalCameraId`); `CaptureSession` applies it to **every**
stream's `OutputConfiguration.setPhysicalCameraId`, in preference to the per-`OutputConfig` ID. The distortion
request is a separate `Camera2Interop` capture-request option, so the two do not overwrite each other. This
makes the interop request a real control for the analysis stream. It does not predict what the Pixel 9 HAL
reports: that is what the re-test decides.

**Device-result discriminator** (`groupA_identity.explicitPhysicalOutput.physicalResultObservation`, counts in
`physicalResultObservationCounts`, per frame `attribution.physicalResultObservation`):
`PHYSICAL_RESULT_PRESENT` · `PHYSICAL_RESULT_NOT_REPORTED_TOP_LEVEL_MATCHES_REQUESTED` ·
`PHYSICAL_RESULT_NOT_REPORTED_TOP_LEVEL_DIFFERS_FROM_REQUESTED` · `PHYSICAL_RESULT_NOT_REPORTED_TOP_LEVEL_NOT_REPORTED` ·
`PHYSICAL_RESULT_TIMESTAMP_MISMATCH` · `PHYSICAL_RESULT_TIMESTAMP_MISSING` (session level also `MIXED`,
`NO_MATCHED_FRAMES`, `NOT_APPLICABLE_LOGICAL_SESSION`). Only `PRESENT` resolves physical dynamic metadata; the
top-level ID qualifies a missing result as a diagnostic and never becomes the producer. The schema-1 phys3
shape (requested 3, top-level 2, no nested result) classifies as `..._DIFFERS_FROM_REQUESTED`: unresolved
dynamic metadata, not proof that pixels came from camera 2.

**Re-test (operator).** Install this revision as internalDebug; candidate **Physical camera 3**, lighting
`NORMAL_INDOOR`, distortion `DEVICE_DEFAULT`, resolution 1280×720; run ~20–30 s; **Finalize & save PTS-03 JSON**.
Decisive fields: `physicalBindingRequest`, `session.requestedPhysicalCameraId`, per-frame
`captureResult.physicalResultsByCameraId`, `explicitPhysicalOutput.physicalResultStatus` /
`physicalResultObservation`, `logicalTopLevel.framesByLogicalActivePhysicalId`, top-level `lensFocalLengthMm`,
`streamConfiguration.analysisResolutionInfo`, `sensorToBufferTransformMatrix`.

**Decision.**
- *Outcome A* — `physicalResultsByCameraId["3"]` appears: the per-use-case request changed the observable
  Camera2 result path; continue A2/B/C with this configuration.
- *Outcome B* — `physicalResultsByCameraId` stays empty while all three `physicalBindingRequest` IDs are `3`:
  record "Pixel 9 / Android 17 / CameraX 1.4.2: nested physical result metadata not exposed in this
  experiment path even with selector + explicit Preview/ImageAnalysis physical-ID requests", stop metadata
  experiments, and continue PTS-03 on configuration provenance, static physical characteristics,
  frame-content correspondence, and conservatively unresolved dynamic physical metadata.

| Re-test | Status |
|---|---|
| Physical 3, schema 2 | **pending device execution** |

## B — Projection domain (per physical ID)

`SensorToBufferDomainProof` is **unchanged**. PTS-03 builds only the existing evidence-only proof
(`evidenceOnlySensorToBufferDomainProof`), which can return `Unresolved` or `HypothesisMismatch`, never a
`Proven*` variant; `resolveCam2cForExplicitPhysicalCamera` therefore still returns `DomainNotProven`
(pinned by `Pts03ProjectionDomainEvidenceTest`). A matching whole-array hypothesis or a small frame-content
residual is recorded as **UNRESOLVED**.

| Physical ID | Logical whole-array hypothesis | Physical whole-array hypothesis | Physical pre-correction hypothesis | Frame-content verdict | Outcome |
|---|---|---|---|---|---|
| _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | UNRESOLVED (pending) |

Attribution (never pixels from camera X with metadata from camera Y): static arrays are taken for the
attributed **producing** camera (`projectionDomain.producingPhysicalCameraId`; A2 = requested ID, A1 =
reported active ID, unknown → UNRESOLVED). The top-level active ID appears only as
`projectionDomain.logicalActivePhysicalCameraIdDiagnostic`.

Fields: `groupB_and_C_targetEvidence[].{attribution,projectionDomain}.*`; per frame
`sensorToBufferTransformMatrix`, buffer size, crop, rotation; static logical and physical arrays in
`staticCharacteristics`.

## C — Distortion state / pixel domain (per physical ID and mode)

Requested and effective modes are separate fields. The **metadata basis** follows the effective mode
(`OFF` → pre-correction active array; `FAST`/`HIGH_QUALITY` → active array). Whether the **pixels** are
corrected is measured from printed-target residuals (`d_radial = a·ρ + b·ρ³`, cubic term = distortion
signature; `measurePts03ResidualDistortion`), never inferred from the mode. `LENS_DISTORTION` is never
applied. Only modes the logical camera advertises are offered.

The effective mode of an A2 stream is read from the physical result for the requested ID; without a
timestamp-matched entry it is `UNRESOLVED_PHYSICAL_RESULT_UNAVAILABLE` (metadata basis UNKNOWN) — the logical
mode is never substituted. Each evidence capture's `lighting` is the label frozen with its own frame
(`lightingSource = FRAME_AT_CAPTURE`), never the label selected later.

| Physical ID | Requested | Effective | Metadata basis | Active vs pre-correction | Residual distortion | Guidance | Final pixel domain |
|---|---|---|---|---|---|---|---|
| _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | UNRESOLVED (pending) | UNRESOLVED | UNRESOLVED |

`LENS_INTRINSIC_CALIBRATION` source basis: pre-correction active array (Camera2 contract), recorded as
`intrinsicCalibrationSourceBasis`. `finalPixelDomainEstablished` can only be true when the matrix domain is
PROVEN **and** the residual is measured either way for a single effective mode — not reachable from PTS-03
code alone, by design.

## D — Extrinsics (per physical ID)

`LENS_POSE_ROTATION` is parsed as `[x, y, z, w]`, direction `S → Cphys` (`parseCamera2LensPoseRotation`,
regression-tested against an independent Rodrigues matrix; a `[w, x, y, z]` misreading and a direction swap
both fail). No independent device-frame reference exists in PTS-03 (a printed-target pose is relative to the
target and cannot reveal `S`), so a valid pose with a useful reference stays
`PLATFORM_POSE_PRESENT_BUT_UNVERIFIED`. PointToSky's prediction continues to use `NOMINAL_FALLBACK`.
`LENS_POSE_TRANSLATION` is recorded, not used.

| Physical ID | Rotation `[x,y,z,w]` | Reference | Camera +Z in `S` | ∠ to nominal rear axis `S −Z` | Platform pose status |
|---|---|---|---|---|---|
| _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | UNRESOLVED (pending) |

## Stream configuration (CameraX 1.4.2, actual)

| Session | Requested analysis | `ImageAnalysis.getResolutionInfo()` | Delivered `ImageProxy` (w×h, crop, rotation, matrix) | `Preview.getResolutionInfo()` |
|---|---|---|---|---|
| A1, CameraX default (production-like) | none | _pending_ | _pending_ | _pending (UNKNOWN if CameraX returns none)_ |
| A2 per ID | 640×480 / 1280×720 / default | _pending_ | _pending_ | _pending_ |

Preview size is taken only from CameraX's `ResolutionInfo`, never from the `PreviewView` size.

## Timestamp join quality

Exact `CaptureResult.SENSOR_TIMESTAMP == ImageProxy.imageInfo.timestamp` (`SkyExposureJoin`, unchanged rule).

| Session | analysisFrameCount | captureResultCount | matchedCount | matchedFraction | timeouts (frame/result) | evictions (frame/result) | duplicates (frame/result) | unkeyed |
|---|---|---|---|---|---|---|---|---|
| _pending_ | | | | | | | | |

Unmatched `CaptureResult`s are expected under `STRATEGY_KEEP_ONLY_LATEST` (frames CameraX dropped before
analysis); `matchedFraction` is therefore defined over **analysis frames**.

Only a **finalized** export (`session.finalized = true`) carries final statistics: **Finalize & save**
stops the join, drains it into `framesPendingAtStopCount` / `captureResultsPendingAtStopCount`, freezes the
statistics, then writes the file. Finalizing again changes nothing (no double counting); offers arriving
afterwards are only counted in `offersIgnoredAfterFinalizeCount`.

## Raw evidence files

Authoritative: **Finalize & save PTS-03 JSON** → `/sdcard/Android/data/dev.pointtosky.mobile.int/files/pts03_sessions/<sessionId>.json`
(`adb pull`). **Save non-final snapshot** writes `<sessionId>-nonfinal-<millis>.json`, which is never
authoritative. Large raw files stay off-repo; commit only compact summaries and list session IDs here.

| Session ID | Class | Physical ID | Lighting | Mode requested | File (off-repo / committed summary) |
|---|---|---|---|---|---|
| _pending_ | | | | | |

## Remaining unknowns

All of A, B, C, D, the 1.4.2 stream configuration and the join quality are pending device execution.
Beyond that, even after a full run: B cannot reach PROVEN without an independent proof source (e.g. a
source-trace plus a multi-view/fixed-rig calibration or star solutions with independent pose); D cannot
reach `CALIBRATED_PLATFORM_POSE` without an independent `S`-frame reference; the residual-distortion floor
(1.0 px) and significance (3σ) are placeholders until Pixel 9 detector noise is measured; the per-frame
pose fit can absorb part of real distortion, so `NOT_DETECTED_AT_THRESHOLD` is OBSERVED, not PROVEN.

## Decision boundary for PTS-09

PTS-03 does not choose a strategy and does not change production. After device evidence exists:
- **Strategy A** (truthful per-frame physical geometry) can be evaluated further only if A shows a reported,
  joinable active ID **and** B and C are established for every ID that occurs.
- **Strategy B** (explicit physical binding) still needs B and C per bound ID; identity alone is insufficient.
- **Strategy C** (approximate logical intrinsics + scale-tolerant solve) remains the fallback.

---

## Operator runbook (Pixel 9)

Prerequisites: Pixel 9 with USB debugging; this branch built as **internalDebug**; the printed target
(`Share target SVG` on the experiment's first screen; print at 100 % scale, measure the dot spacing).

1. Build and install: `./gradlew :mobile:installInternalDebug`. Record `git rev-parse HEAD`.
2. Open the app's AR camera screen → **CAM diagnostics** → **Open diagnostics** → **Open frame-content
   experiment**. Grant camera permission.
3. On the candidate screen choose the **PTS-03 lighting label** and **requested distortion mode** (only
   advertised modes are shown; `DEVICE_DEFAULT` sets nothing). These are fixed per attempt; the lighting
   label can also be changed mid-session.

**A1 — logical, normal light**
4. Lighting `NORMAL_INDOOR`, mode `DEVICE_DEFAULT`, candidate **Logical rear camera (no pin) — PTS-03 A1**,
   resolution **CameraX default (no selector, as production)**.
5. Hold on a lit scene ≥ 60 s. Watch the PTS-03 summary lines (join fraction, active IDs). **Finalize & save PTS-03 JSON**.

**A1 — logical, low light (same session class)**
6. Start a new A1 attempt (Back → same choices) with lighting `NORMAL_INDOOR`; after ~20 s switch the label
   to `LOW_LIGHT_INDOOR` and dim/cover the room light (a dark room is sufficient); hold ≥ 60 s; restore light
   and switch back. **Finalize & save PTS-03 JSON**. Transitions are recorded with timestamp and label.
7. Repeat 4–6 with resolution 640×480 and 1280×720 if time permits (stream-configuration evidence).

**A2/B/C — every declared physical child, every advertised mode**
8. For each `Physical camera <id>` button (IDs come from the device): for each advertised mode
   (`DEVICE_DEFAULT`, then `OFF`, `FAST`, `HIGH_QUALITY` as offered), start an attempt at 640×480.
9. Place the target so the grid is detected (`detectedPoints` > 0, verdict shown). For each placement
   label `CENTER`, `TOP_LEFT`, `TOP_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_RIGHT`: frame the target near that
   image region (edges/corners as close as detection allows), select the label, **Freeze**, then
   **Add PTS-03 evidence**, then **Resume live**. Aim for ≥ 2 captures per placement.
10. **Finalize & save PTS-03 JSON** before leaving each attempt. Optionally **Share JSON** (frame-content, schema 5)
    for one representative frozen frame per attempt.

**Night (optional for PTS-03)**
11. Outdoors at night: A1 with lighting `NIGHT_SKY`, ≥ 2 min, **Finalize & save PTS-03 JSON**. If not possible, leave
    "night-sky device evidence pending".

**Collect**
12. `adb pull /sdcard/Android/data/dev.pointtosky.mobile.int/files/pts03_sessions/ ./pts03_sessions/`
    (internal flavor application ID `dev.pointtosky.mobile.int`; confirm with `adb shell pm list packages | grep pointtosky`).
13. Fill the tables above from the JSON (field paths given per table). Commit only compact summaries.

---

## Recorded fields

**Per CaptureResult (one immutable `SkyCaptureResultSnapshot`, exact-joined to one `ImageProxy`):**
`SENSOR_TIMESTAMP`, `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` (API 29+, availability recorded),
`DISTORTION_CORRECTION_MODE` (API 28+, raw + name + effective + metadata basis), `LENS_FOCAL_LENGTH`,
`LENS_INTRINSIC_CALIBRATION`, `LENS_FOCUS_DISTANCE`, `LENS_STATE`, `CONTROL_AF_MODE`, `CONTROL_AF_STATE`,
`SCALER_CROP_REGION`, `CONTROL_ZOOM_RATIO` (API 30+), `SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY`,
`SENSOR_FRAME_DURATION`, `CONTROL_AE_MODE`, `CONTROL_AWB_MODE`, `LENS_OPTICAL_STABILIZATION_MODE`,
`CONTROL_VIDEO_STABILIZATION_MODE`, `HOT_PIXEL_MODE`, `NOISE_REDUCTION_MODE`, `EDGE_MODE`,
`LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION` (API 35+, optional diagnostic, recorded independently
of `SCALER_CROP_REGION` / `CONTROL_ZOOM_RATIO`; nothing depends on it). `null` = not reported; unknown enum
values kept as `UNKNOWN_<raw>`. The same fields are recorded for the top-level result
(`logicalTopLevelResult`) and for every physical result entry (`physicalResultsByCameraId[id]`, with its own
timestamp).

**Per frame (`ImageProxy`):** buffer width/height, crop rect, `rotationDegrees`, `sensorToBufferTransformMatrix[9]`.

**Static, logical + every declared child:** camera ID, role, logical flag, physical IDs,
`LENS_INFO_AVAILABLE_FOCAL_LENGTHS`, `SENSOR_INFO_PHYSICAL_SIZE`, `SENSOR_INFO_PIXEL_ARRAY_SIZE`,
`SENSOR_INFO_ACTIVE_ARRAY_SIZE`, `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`, `LENS_INTRINSIC_CALIBRATION`,
`LENS_DISTORTION`, `DISTORTION_CORRECTION_AVAILABLE_MODES`, `LENS_POSE_ROTATION`, `LENS_POSE_TRANSLATION`,
`LENS_POSE_REFERENCE`, `SENSOR_ORIENTATION`, `LENS_FACING`, `REQUEST_AVAILABLE_CAPABILITIES`,
`INFO_SUPPORTED_HARDWARE_LEVEL`, `SENSOR_INFO_TIMESTAMP_SOURCE`.

## Local validation (this revision, base `main @ dfffb23bef7022a61f6cfe8bbaf61da3ddbacf74`)

Tested PR head: `979f7053d5cfb06d9f2be3c355b88d8ed87944c1` (all code and tests of this revision; the commit that
follows it changes only this document). Run locally with a JDK 17 toolchain and Android SDK 35. In this sandbox,
Maven Central answered HTTP 429, so a local-only Gradle init script outside the repository resolved it via
Google's public Maven Central mirror; no build file was changed.

| Command | Result |
|---|---|
| `./gradlew :core:astro-core:test --rerun-tasks` | PASS — 681 tests, 0 failures, 0 errors |
| `./gradlew :mobile:testInternalDebugUnitTest --rerun-tasks` | PASS — 860 tests, 0 failures, 0 errors (83 classes) |
| `./gradlew :mobile:testInternalDebugUnitTest --tests '*Pts03PhysicalBindingRequestTest*' --rerun-tasks` | PASS — 13 tests, 0 failures |
| `./gradlew :mobile:testPublicDebugUnitTest --rerun-tasks` | PASS — 371 tests, 0 failures, 0 errors |
| `./gradlew :mobile:compileInternalDebugKotlin` | PASS |
| `./gradlew :mobile:assembleInternalDebug` | PASS |
| `./gradlew :mobile:compileInternalDebugAndroidTestKotlin` | PASS — instrumented tests compiled, **not executed** on a device |
| `./gradlew :mobile:lintInternalDebug` | PASS |

Focused PTS-03 classes, 101 tests across 10 classes, all passing in the run above: `Pts03CaptureResultTruthTest` (9),
`SkyExposureJoinPts03Test` (9), `Pts03LensPoseTest` (12), `Pts03DistortionStateTest` (9),
`Pts03ProjectionDomainEvidenceTest` (5), `Pts03StaticCharacteristicsTest` (5), `Pts03CameraTruthSessionTest` (21),
`Pts03CameraTruthExportTest` (8), `Pts03PhysicalResultTest` (10), `Pts03PhysicalBindingRequestTest` (13).

GitHub Actions on this PR: `Lint` PASS; `smoke` and `Build catalog artifacts` fail in
`android-actions/setup-android` (`sdkmanager`: "Failed to find package 'tools'") before any project build step,
as on base `main @ dfffb23`. That is the known PTS-02 SDK-setup infrastructure failure and is not addressed here.
