# CAM-2c recon: `getSensorToBufferTransformMatrix()` source domain — CameraX 1.4.2 source trace + Pixel 9 matrix analysis

**Recon only.** This document changes no production behavior. It does not construct any
`SensorToBufferDomainProof.Proven*` variant, does not modify `AnalysisBufferIntrinsicsResolver`,
does not weaken `UnsupportedLogicalMultiCameraMapping`, and does not publish calibrated
`AnalysisBuffer` intrinsics. See §8 for the explicit list of claims that remain prohibited.

---

## 1. Verdict

```
CAMERAX TRANSFORM CONTRACT EXPLAINED;
PHYSICAL/LOGICAL BASIS COMPATIBILITY AND FRAME-CONTENT CORRESPONDENCE UNPROVEN
```

- The observed Pixel 9 matrix is **bit-for-bit identical** (float32-exact, see §4) to what CameraX
  1.4.2's `CameraUseCaseAdapter.calculateSensorToBufferTransformMatrix(activeArrayRect = [0,0,4080,3072],
  useCaseSize = 640×480)` computes. The "uniform scale + symmetric ~0.941 px vertical center crop"
  hypothesis is confirmed as the *intended construction*, not merely a plausible fit: the matrix is
  the float32 inverse of `Matrix.setRectToRect(bufferRect, activeArrayRect, ScaleToFit.CENTER)`.
- **CameraX's declared source domain is established** — this is the verdict's first clause, and it
  is a documented-contract fact, not an open question: public `ImageInfo` Javadoc (§2.7) declares
  the transform's coordinate domain as the `SENSOR_INFO_ACTIVE_ARRAY_SIZE` rect of the
  **CameraX-opened camera**, and the 1.4.2 implementation (§2.1–§2.2) confirms it. For this
  Pixel 9's logical bind, the implementation basis is **logical camera 0's active array**
  (`[0,0 — 4080,3072]`, zero offsets — coinciding exactly with active-array-local coordinates).
- **What remains unproven** — the verdict's second clause. These are device/HAL evidence gaps, not
  ambiguity about the documented transform coordinate domain:
  1. **Frame-content correspondence.** CameraX computes this matrix **blindly** from static
     metadata (active array rect + stream resolution). It never measures what the HAL actually put
     in the buffer. The matrix encodes CameraX's *assumption* that the buffer contains the
     full-FOV, aspect-fill, centered crop of the active array. No frame content has ever been
     checked against that assumption on this device (no star/landmark residual data exists) —
     whether observed content actually follows the declared mapping is unmeasured.
  2. **Physical/logical basis compatibility.** On a **logical multi-camera**, `getSensorRect()`
     returns the *logical* camera's active array (§2.3). Whether per-frame content from physical
     sensors 2/3/4 is actually represented in the logical camera's active-array basis — and stays
     so across lens switches — is a HAL behavior this codebase has never observed. Under the
     planned physical-binding experiment the matrix will (predictably, §2.3/§7) still be computed
     from the **logical** camera's active array, while `resolveAnalysisBufferIntrinsics` would be
     fed the **physical** camera's snapshot — whether physical intrinsics can be reconciled with
     the logical basis is unresolved and must be settled before any implementation.
  3. Digital zoom (`SCALER_CROP_REGION` / `CONTROL_ZOOM_RATIO`) is **not** incorporated in the matrix
     (§2.6) — at any zoom ≠ 1.0× the matrix does not describe the frame content at all. The
     experiment's fixed 1.0× zoom mitigates but does not prove this away.

Per this codebase's own standard (`SensorToBufferDomainProof` requires positive, device-level proof),
`ProvenActiveArrayLocal` must **not** be constructed on the strength of this recon.

---

## 2. Exact CameraX 1.4.2 source trace

### Artifacts inspected

| Artifact | Source | SHA-256 |
|---|---|---|
| `androidx.camera:camera-core:1.4.2` sources jar | `https://dl.google.com/android/maven2/androidx/camera/camera-core/1.4.2/camera-core-1.4.2-sources.jar` | `0dc32d6beaea2f385b4a7226b536f46f588701d49844cc31df5c201e9166f73c` |
| `androidx.camera:camera-camera2:1.4.2` sources jar | `https://dl.google.com/android/maven2/androidx/camera/camera-camera2/1.4.2/camera-camera2-1.4.2-sources.jar` | `03d02e14d05b5c17560b0bb6159d47e8c924f4c9e44cecd66dd2263dc15bb239` |
| `androidx.camera:camera-core:1.3.4` sources jar (historical check, §2.8) | same host, `1.3.4` path | `7cb72295002efd193ca11b6ecc0d13cdd6c3af8a289797f671bbc64fa21f9170` |

These are the official Google Maven source jars for the exact versions this project resolves
(`gradle/libs.versions.toml:31` pins `camerax = "1.4.2"`). Line numbers below are from these jars.

### 2.1 Construction — `CameraUseCaseAdapter` (camera-core)

`androidx/camera/core/internal/CameraUseCaseAdapter.java`:

- `updateUseCases(...)` → line 425: `updateViewPortAndSensorToBufferMatrix(primaryStreamSpecMap, cameraUseCases)`
  — runs on every use-case attach/update (bind), for **every** use case.
- `updateViewPortAndSensorToBufferMatrix(...)` (lines 909–951). The ViewPort crop-rect block is
  conditional on `mViewPort != null`, but the matrix block is **unconditional** (lines 941–949):

  ```java
  // Regardless of having ViewPort, SensorToBufferTransformMatrix must be set correctly
  // in order for get the correct meteringPoint coordinates.
  for (UseCase useCase : useCases) {
      useCase.setSensorToBufferTransformMatrix(
              calculateSensorToBufferTransformMatrix(
                      mCameraInternal.getCameraControlInternal().getSensorRect(),
                      Preconditions.checkNotNull(
                              suggestedStreamSpecMap.get(useCase)).getResolution()));
  }
  ```

- `calculateSensorToBufferTransformMatrix(Rect fullSensorRect, Size useCaseSize)` (lines 953–968):

  ```java
  RectF fullSensorRectF = new RectF(fullSensorRect);
  Matrix sensorToUseCaseTransformation = new Matrix();
  RectF srcRect = new RectF(0, 0, useCaseSize.getWidth(), useCaseSize.getHeight());
  sensorToUseCaseTransformation.setRectToRect(srcRect, fullSensorRectF, Matrix.ScaleToFit.CENTER);
  sensorToUseCaseTransformation.invert(sensorToUseCaseTransformation);
  return sensorToUseCaseTransformation;
  ```

  `Matrix.ScaleToFit.CENTER` (Android platform contract) computes one **uniform** scale
  `min(dst.w/src.w, dst.h/src.h)` that fits `src` entirely inside `dst`, centered. So the forward
  map is *buffer rect → largest centered same-aspect sub-rect of the sensor rect* (a letterbox fit
  of the buffer into the sensor rect); the returned matrix is its **inverse**: *sensor rect →
  buffer, uniform scale, with the sensor rect's aspect excess overflowing the buffer bounds
  symmetrically* — i.e. an aspect-fill / center-crop mapping. `android.graphics.Matrix` stores
  **float32** entries; the exported doubles are widened floats (§4 confirms bit-exactly).

### 2.2 What "sensor" means — `getSensorRect()` (camera-camera2)

`androidx/camera/camera2/internal/Camera2CameraControlImpl.java`, lines 610–620:

```java
public Rect getSensorRect() {
    Rect sensorRect =
            mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
    if ("robolectric".equals(Build.FINGERPRINT) && sensorRect == null) {
        return new Rect(0, 0, 4000, 3000);
    }
    return Preconditions.checkNotNull(sensorRect);
}
```

"Sensor" = the **`SENSOR_INFO_ACTIVE_ARRAY_SIZE` rect of the camera CameraX opened** (the
characteristics of `mCameraInternal`). It is **not** the pixel array, not the pre-correction active
array, not `SCALER_CROP_REGION`, and not any use-case input rect.

**Active-array left/top offsets:** `SENSOR_INFO_ACTIVE_ARRAY_SIZE` is a `Rect` positioned relative
to the full pixel array and may have non-zero `left`/`top`. `setRectToRect` targets
`new RectF(fullSensorRect)` *including* those offsets, and the inversion therefore *removes* them:
the matrix's input coordinates are the rect's own native (pixel-array-relative) coordinates, and a
point at `(left, top)` maps to the buffer-side fit origin. Strictly, the source basis is
"the active-array rect in its native position", which **equals active-array-local coordinates only
when `left == top == 0`** — true for this Pixel 9 (`activeArray=[0,0 — 4080,3072]`), so the
distinction is moot for this device but must be re-checked per device (capture the raw `Rect`, §7).

### 2.3 Whose active array on a logical multi-camera / under physical binding

`mCameraCharacteristics` in `Camera2CameraControlImpl` belongs to the camera that was opened — for
this Pixel 9, **logical camera `"0"`**. `CameraSelector.Builder.setPhysicalCameraId(String)`
(camera-core `CameraSelector.java`, lines 304–337) does **not** open the physical camera: its own
Javadoc states the physical ID is applied per-stream via
`OutputConfiguration.setPhysicalCameraId(String)`; the plumbing is
`SessionConfig.OutputConfig.getPhysicalCameraId()` (camera-core `impl/SessionConfig.java:118`) →
`CaptureSession.java` lines 343/444/1007 (camera-camera2), which sets it on the
`OutputConfigurationCompat`. The logical camera stays open, so:

> **Prediction (falsifiable, §7):** in the physical-binding experiment (`setPhysicalCameraId("2")`
> etc.), `getSensorRect()` still returns the **logical** camera 0 active array `4080×3072`, and the
> delivered matrix for a 640×480 buffer is **numerically identical** to the logical-bind matrix
> observed here — even though the frames come from physical sensor 2/3/4. If a device run shows a
> different matrix, this trace's conclusion about the basis is wrong and must be revisited.

This is the single most consequential recon finding for the future implementation: the matrix's
source basis is tied to the **bound (logical) camera's** active array, while
`resolveCam2cForExplicitPhysicalCamera` feeds `resolveAnalysisBufferIntrinsics` the **physical**
camera's snapshot. Unless the physical sensor's active array happens to equal the logical one, the
two live in different coordinate bases.

### 2.4 Propagation into the `ImageProxy` the analyzer receives

- `UseCase.setSensorToBufferTransformMatrix(Matrix)` (camera-core `UseCase.java:1026`) stores a
  defensive copy in `mSensorToBufferTransformMatrix` (field at line 147, default `new Matrix()` =
  **identity**).
- `ImageAnalysis.setSensorToBufferTransformMatrix` (`ImageAnalysis.java:596–598`) forwards it to
  `ImageAnalysisAbstractAnalyzer.setSensorToBufferTransformMatrix` (`ImageAnalysisAbstractAnalyzer.java:343–347`),
  which keeps `mOriginalSensorToBufferTransformMatrix` and a working copy
  `mUpdatedSensorToBufferTransformMatrix`.
- Per frame, `analyzeImage(ImageProxy)` (`ImageAnalysisAbstractAnalyzer.java`, lines ~178–295):
  - `outputImageDirty = mOutputImageRotationEnabled && currentBufferRotationDegrees != mPrevBufferRotationDegrees`
    (line 186). With `setOutputImageRotationEnabled` **not** enabled — this project's exact
    configuration, `mobile/src/main/java/dev/pointtosky/mobile/ar/CameraPreview.kt:209–211` builds
    `ImageAnalysis` with only a backpressure strategy — `outputImageDirty` is always false and
    `recalculateTransformMatrixAndCropRect(...)` (lines 444–460, which would concat an additional
    rotation matrix) is **never** invoked. The delivered matrix is the bind-time matrix, unchanged.
  - The analyzer's callback receives `SettableImageProxy(outputImageProxy, imageInfo)` where
    `imageInfo = ImmutableImageInfo.create(tagBundle, timestamp, mOutputImageRotationEnabled ? 0 : mRelativeRotation, transformMatrix)`
    (lines ~272–279). `ImmutableImageInfo` (an `@AutoValue`, `ImmutableImageInfo.java:59`) is the
    `ImageInfo` implementation whose `getSensorToBufferTransformMatrix()` this project reads.

### 2.5 Rotation handling

`rotationDegrees=90` is carried as **separate metadata** (`mRelativeRotation`, from the use case's
target rotation vs. sensor orientation). It is **not** folded into the matrix: the matrix's
destination is the **unrotated** buffer as delivered (640×480 landscape). The `ImageInfo` Javadoc
(§2.7) states this explicitly for the `setOutputImageRotationEnabled(false)` default. Consequence:
composing buffer coordinates onward to display requires applying `rotationDegrees` separately —
exactly as this codebase already treats it.

### 2.6 Crop rect and zoom handling

- `ImageProxy.getCropRect()` is buffer-local and orthogonal to the matrix. `SettableImageProxy.setCropRect`
  is called only when the ViewPort-derived crop is non-empty (`ImageAnalysisAbstractAnalyzer.java:280–283`);
  with no ViewPort (this project), the observed `cropRect=[0,0 — 640,480]` is simply the full buffer.
  The matrix's destination is the **full raw buffer rect** `(0,0,width,height)` from the stream
  spec — never a cropRect-local domain.
- `SCALER_CROP_REGION` / zoom ratio is **not incorporated**: the matrix is computed once per
  `updateUseCases` from the *full* active array; nothing recomputes it on zoom changes (the only
  call site is line 425). At any zoom ≠ 1.0×, the matrix therefore does not describe frame content.

### 2.7 The public API contract — `ImageInfo` (camera-core)

`androidx/camera/core/ImageInfo.java`, lines ~72–118: `getSensorToBufferTransformMatrix()` Javadoc:

> "The returned matrix is a mapping from sensor coordinates to buffer coordinates, which is, from
> the value of `CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE` to
> `(0, 0, image.getWidth, image.getHeight)`."

So the **domain endpoints** (active array → full buffer) are publicly documented, i.e. more than an
implementation accident — but the Javadoc does not specify fit semantics (uniform scale?
center-crop? offset handling?), does not mention zoom, and CameraX ships no CTS-style test this
project has verified. The *specifics* rest on the implementation trace above.

### 2.8 Why the earlier Pixel 9 run (CameraX 1.3.4) reported the identity matrix — resolved

`docs/validation/cam_2c_pixel9_evidence.md` §3 recorded an **identity** matrix on the same device
under CameraX **1.3.4**. The 1.3.4 source explains it completely: in
`camera-core-1.3.4` `CameraUseCaseAdapter.updateViewPort(...)` (lines 764–800),
`setSensorToBufferTransformMatrix` was called **only inside the `if (mViewPort != null)` block**.
This project binds no `ViewPort`, so on 1.3.4 the matrix was **never set** and
`UseCase.mSensorToBufferTransformMatrix`'s default `new Matrix()` (identity) leaked through to
`ImageInfo`. The 1.4.2 rework (the "Regardless of having ViewPort…" block, §2.1) sets it
unconditionally. The identity matrix was therefore **not** a HAL quirk and **not** evidence of a
pre-normalized source domain — it was an un-set default in the older CameraX. This retroactively
resolves the §3 open question and removes the "legitimate cropped/pre-normalized source domain"
speculation for the 1.3.4 identity observation.

---

## 3. Evidence classification

| Claim | Class |
|---|---|
| Matrix maps `SENSOR_INFO_ACTIVE_ARRAY_SIZE` → full buffer rect (domain endpoints) | API_GUARANTEED (public Javadoc, §2.7) — but under-specified; details below are not |
| Fit semantics: inverse of `setRectToRect(buffer, activeArrayRect, ScaleToFit.CENTER)` — uniform scale, symmetric center-crop overflow | IMPLEMENTATION_OBSERVED (1.4.2 source, §2.1) |
| Active-array left/top offsets are removed by the matrix (source = rect in native position) | IMPLEMENTATION_OBSERVED (§2.2); untested on any device with non-zero offsets |
| `getSensorRect()` is the **bound (logical) camera's** active array, incl. under `setPhysicalCameraId` | IMPLEMENTATION_OBSERVED (§2.2/§2.3); the physical-binding half is an untested prediction |
| `SCALER_CROP_REGION`/zoom not incorporated; matrix static per bind | IMPLEMENTATION_OBSERVED (§2.6) |
| Target rotation not incorporated (with output rotation disabled) | API_GUARANTEED (Javadoc, §2.7) + IMPLEMENTATION_OBSERVED (§2.4/§2.5) |
| Observed Pixel 9 matrix values equal the 1.4.2 construction float32 bit-for-bit | DEVICE_OBSERVED (the matrix) + INFERENCE (bit-match to simulated construction, §4) |
| 1.3.4 identity matrix = un-set default (no ViewPort) | IMPLEMENTATION_OBSERVED (1.3.4 source, §2.8) explaining a DEVICE_OBSERVED value |
| Buffer content physically is the centered aspect-fill crop of the active array | **NOT ESTABLISHED** — CameraX assumes it; no device measurement exists (this is the residual gap) |
| Frames from physical sensors 2/3/4 are presented in logical-camera active-array coordinates | **NOT ESTABLISHED** — HAL behavior, never observed |

---

## 4. Mathematical analysis of the observed Pixel 9 matrix

Observed (CameraX 1.4.2, logical camera 0, active array 4080×3072, buffer 640×480, rotation 90°):

```
[ 0.1568627506494522   0.0                  ~0.0        ]
[ 0.0                  0.1568627506494522   ~-0.9411765 ]
[ 0.0                  0.0                  1.0         ]
```

All quantities below computed with double precision from the reported values, with float32
(`android.graphics.Matrix` storage) round-trips where noted.

- **scaleX = scaleY = 0.1568627506494522.** This is *exactly* `double(float32(640/4080))`:
  `640/4080 = 0.1568627450980392…`, whose nearest float32 widened back to double is
  `0.1568627506494522` — a bit-for-bit match. Scale isotropy residual: `m00 − m11 = 0.0` exactly.
- **Shear/perspective:** `m01 = m10 = 0`, bottom row `0,0,1` — pure axis-aligned scale+translate
  (`AXIS_ALIGNED_0` under `classifySensorToBufferMatrix`).
- **translateY:** the construction predicts `−6/6.375 = −0.9411764705882353`; as float32 widened to
  double: `−0.9411764740943909`, which prints as `−0.9411765` at the export's 7-digit rounding —
  consistent with the reported value. (`6.375 = 4080/640` is the fit scale; `6 = (3072 − 480·6.375)/2`
  is the letterbox margin.) **translateX:** predicted exactly `0.0` (or `−0.0` after inversion),
  consistent with "approximately 0.0".
- **Mapped source corners** of `[0,0 — 4080,3072]` (using `m12 = float32(−6/6.375)`):
  `(0,0)→(0, −0.94117647)`, `(4080,0)→(640.0000226, −0.94117647)`,
  `(0,3072)→(0, 480.9411935)`, `(4080,3072)→(640.0000226, 480.9411935)`.
- **Mapped bounds:** `[0, −0.9411765 — 640.0000226, 480.9411935]` — reproducing the diagnostic's
  reported `[0.0,−0.9411765 — 640.00002,480.94119]`.
- **Mapped source center** `(2040,1536) → (320.0000113, 240.0000085)` vs. buffer center `(320,240)`:
  center-alignment residual `(+1.1e−05, +8.5e−06)` px — pure float32 noise.
- **Overflow:** left `0.0`, right `+2.26e−05` (float32 noise); top `−0.94117647`,
  bottom `+0.94119352`. Vertical symmetry residual `|top-overflow + bottom-overflow| = 1.7e−05` px —
  the crop is symmetric to float32 precision.
- **Expected scaled dimensions:** `4080·s = 640.0000226` (width fits exactly);
  `3072·s = 481.8823529…` ⇒ expected center-crop `(481.882 − 480)/2 = 0.9411765` px/side —
  matching the observed translate and overflow exactly.
- **Aspect ratios:** active array `4080/3072 = 1.328125` vs. buffer `640/480 = 1.3̅3` — the ~0.4%
  aspect mismatch is the entire source of the 0.941 px overflow.

**Consistency verdict per candidate model:**

| Model | Fit? |
|---|---|
| Exact bounds fit (anisotropic `640/4080`, `480/3072`, zero translate) | **No** — would need `sy = 0.15625 ≠ 0.15686275` |
| Uniform scale + **centered** crop (inverse `ScaleToFit.CENTER`) | **Yes — float32 bit-exact** (simulated construction reproduces `m00` and `m12` to the last bit) |
| Uniform scale + asymmetric crop | No — symmetry residual is 1.7e−05 px |
| Letterbox/padding (fit array *inside* buffer) | No — would need `s = min(640/4080, 480/3072) = 0.15625` and positive y-translate |
| Non-uniform scale / shear / perspective / unexplained affine | No — isotropy exact, shear exactly 0 |

Float32 storage noise (≤ 2.3e−05 px across the full width) is three orders of magnitude below the
0.941 px geometric crop — the two effects are cleanly separable and must not share one tolerance.

**Is the center-crop interpretation supported?** Yes — and it is now more than an interpretation:
it is the traced construction (§2.1). **Is the active-array-local source domain established?** As
CameraX's *declared coordinate domain*, yes: public Javadoc (§2.7) plus the traced construction fix
it to the CameraX-opened (logical) camera's active-array rect, which for this device's zero-offset
rect is exactly active-array-local. What remains unproven is device/HAL-level, not contractual:
whether physical-stream frame content is actually represented in that logical basis, whether
physical intrinsics can be reconciled with it, and whether observed content follows the declared
mapping (§1, items 1–3) — CameraX asserts, never verifies, and no device measurement of content
correspondence exists. Hence the verdict's second clause — and none of this justifies constructing
any `SensorToBufferDomainProof.Proven*` variant.

---

## 5. Review of the current diagnostic (`assessWholeActiveArrayMappingHypothesis`)

File: `mobile/src/internalDebug/java/dev/pointtosky/mobile/ar/camera/WholeActiveArrayMappingHypothesis.kt`.

**Finding: the binary verdict misclassifies the traced, intended CameraX geometry as a mismatch.**
The function demands that the whole active array map **exactly onto** `[0,0 — bufW,bufH]` within
0.5 px on all four edges (`DEFAULT_WHOLE_ACTIVE_ARRAY_HYPOTHESIS_TOLERANCE_PX = 0.5`). That
expectation is satisfiable only when the source and buffer aspect ratios match (or under a
non-uniform, aspect-distorting scale — which CameraX never produces). For any aspect-mismatched
pair, CameraX's own construction (§2.1) *guarantees* symmetric overflow on one axis — here
0.941 px > 0.5 px, hence `WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH` for a matrix that is *exactly*
CameraX's intended whole-active-array mapping. The verdict conflates "does not map bounds-to-bounds"
with "is not sourced from the whole active array". (Its KDoc's honesty scoping — mismatch ≠ broken —
remains correct and prevented any wrong downstream action; the *classification* is what's too
coarse. Two accuracy nits for a later PR: the KDoc still cites pinned CameraX `1.3.4` while
`libs.versions.toml` pins `1.4.2`, and the §2.8 finding resolves its "pre-normalized source domain"
speculation for the old identity observation.)

**Recommended (not implemented) evidence classification.** Following the codebase's style (typed
enum + assessment data class, hypothesis-scoped naming, explicit `INSUFFICIENT`/degenerate arms), a
future PR should compute a *geometry class* of the mapped-bounds relationship instead of a binary
match, e.g. `WholeActiveArrayGeometryClass`:

- `EXACT_BOUNDS_FIT` — all four edges within float-noise tolerance (aspect ratios match).
- `UNIFORM_SCALE_CENTER_CROP` — isotropic scale; one axis fits exactly; other axis overflows
  symmetrically (the traced CameraX 1.4.2 shape; the Pixel 9 case).
- `UNIFORM_SCALE_ASYMMETRIC_CROP` — isotropic scale, overflow present but not symmetric.
- `UNIFORM_SCALE_LETTERBOX` — isotropic scale, mapped bounds strictly *inside* buffer on one axis.
- `NON_UNIFORM_SCALE` — anisotropic but axis-aligned.
- `SHEAR_OR_PERSPECTIVE` — off-diagonal / projective terms (delegating to
  `classifySensorToBufferMatrix` for the structural part).
- `DEGENERATE` — singular/collapsed mapped bounds.
- `INSUFFICIENT_INPUT` — missing/non-positive dimensions (preserving buffer-first precedence).
- `UNEXPLAINED` — finite, none of the above within tolerances.

Two separated tolerances are required: a float-noise tolerance (order 1e−3 px, covering float32
storage error at these magnitudes) for "fits exactly"/symmetry residuals, and a geometric tolerance
for classifying crop/letterbox magnitudes. **Scope guard:** the classification must stay
hypothesis-scoped diagnostic evidence (a new field alongside the existing verdict in the
snapshot/JSON export), must not feed `SensorToBufferDomainProof`, and
`evidenceOnlySensorToBufferDomainProof` must continue to never emit `Proven*` —
`UNIFORM_SCALE_CENTER_CROP` is *consistent with* the traced construction, not proof of content
correspondence.

---

## 6. Recommended implementation scope for a later PR (proposal only — nothing applied)

1. **Diagnostic classification** (internalDebug only): add the geometry classifier of §5 as a pure
   function + enum next to `assessWholeActiveArrayMappingHypothesis`, surfaced as new fields in
   `CamDiagnosticSnapshot`/JSON (schema version bump), leaving the existing verdict fields intact.
   Unit tests: the exact Pixel 9 1.4.2 fixture (must classify `UNIFORM_SCALE_CENTER_CROP`), the
   1.3.4 identity fixture (must classify `UNEXPLAINED` or `UNIFORM_SCALE_LETTERBOX`-negative —
   i.e. anything but a crop/fit class), exact-fit, asymmetric, letterbox, anisotropic, degenerate,
   and float-noise-boundary cases.
2. **Docs corrections**: update `WholeActiveArrayMappingHypothesis.kt`/`SensorToBufferDomainProof.kt`
   KDoc's stale `1.3.4` references to `1.4.2`; record §2.8's resolution of the identity-matrix
   question in `docs/validation/cam_2c_pixel9_evidence.md`.
3. **Dual-basis capture** for the physical experiment (internalDebug): capture *both* the logical
   camera's and the bound physical camera's active/pre-correction/pixel arrays per session, so the
   §2.3 prediction is testable from one export.
4. **Only after §7's device evidence**: decide whether a `SensorToBufferDomainProof.Proven*`
   variant can be constructed, and against *which* basis (logical-active-array-local is the traced
   candidate — note `ProvenActiveArrayLocal` today implicitly means the *physical* snapshot's
   active array inside `resolveCam2cForExplicitPhysicalCamera`, which §2.3 shows is the wrong basis
   whenever logical ≠ physical active array; reconciling that is a design decision for that PR, out
   of scope here).

Explicitly out of scope for any next PR: changing CAM-2a math, `AnalysisBufferIntrinsicsResolver`,
the logical-multi-camera guard, renderer behavior, tolerances on the existing verdict, or any
pixel-center offset "fixes".

## 7. Remaining physical-device validation steps (exact data to capture)

For **each** of physical IDs `"2"`, `"3"`, `"4"` (experiment screen, fixed 1.0× zoom), one export per
session containing:

1. Requested physical ID; actual bound `CameraInfo`'s Camera2 ID; `PhysicalCameraBindingSource`
   (`BOUND_CAMERA_INFO_IS_PHYSICAL` vs `MATCHED_DECLARED_PHYSICAL_CAMERA_INFO`); full
   `PhysicalCameraBindingResolution` / `PhysicalCameraProvenance` (method + confidence).
2. Physical characteristics snapshot: `SENSOR_INFO_ACTIVE_ARRAY_SIZE` (full `Rect`, incl. left/top),
   `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`, `SENSOR_INFO_PIXEL_ARRAY_SIZE` — **and the same
   three rects for logical camera 0** (needed to discriminate the §2.3 basis prediction).
3. ImageAnalysis buffer width/height; `ImageProxy.cropRect`; `rotationDegrees`; all nine matrix
   values **at full double precision** (the current 7-digit rounding cost bit-level checks here —
   export `Double.toString` of the widened floats).
4. Derived: transform classification; mapped whole-source bounds + center under **both** candidate
   bases (logical active array; physical active array); per-edge overflow; symmetry, isotropy, and
   center residuals; predicted-vs-observed scale/translate for the §2.1 construction under each basis.
5. Stability: the nine values repeated across ≥ 100 frames (any variation frame-to-frame?); repeat
   after full lifecycle restart (unbind/rebind and process restart); repeat across at least two
   supported ImageAnalysis resolutions with different aspect ratios (e.g. 640×480 and a 16:9 size —
   the §2.1 construction predicts *vertical* (top/bottom) overflow for a 16:9 buffer from this
   4080×3072 array too, but at a sharply different magnitude, a strong discriminator: for 1280×720
   the buffer width fits exactly and it predicts `s = 1/3.1875 ≈ 0.313725`, `ty ≈ −121.88` —
   symmetric vertical center-crop overflow of ≈121.88 px per side, vs. 0.941 px at 640×480).
6. Zoom sanity check (diagnostic only): with zoom held at 1.0× confirm `CONTROL_ZOOM_RATIO`/crop
   region actually reported ≈ 1.0×/full; do **not** vary zoom in the evidence sessions.

Explicitly: fixed 1.0× zoom does **not** prove frame-level physical identity, and none of the above
proves that buffer *content* matches the mapping — content correspondence (star/landmark residuals)
remains a separate, later validation gate.

## 8. Prohibited claims (unchanged by this recon)

This document does **not** establish, and no one may cite it as establishing:

1. `SensorToBufferDomainProof.ProvenActiveArrayLocal` (or any `Proven*` variant) for any session.
2. That calibrated `AnalysisBuffer` intrinsics may be published on any current device path.
3. That the logical-multi-camera guard may be weakened or bypassed.
4. That frame **content** matches the matrix's mapping (unmeasured).
5. That physical binding constrains which sensor produces frames, or that fixed zoom proves
   frame-level physical identity.
6. That the §2.3 logical-basis prediction holds on-device (it is a prediction until captured).
7. That a `UNIFORM_SCALE_CENTER_CROP` classification (once implemented) is proof of source domain.

---

## Validation run for this recon

Docs-only change — no production or test code was modified, so no Gradle build/test gates apply and
none were run (per task instruction not to manufacture broad build claims). What was actually run:

- `curl` of the three official Google Maven source jars (SHA-256s in §2) + `unzip`/`grep`/`sed`
  inspection of the exact files/lines cited.
- A Python double/float32 analysis script (scratchpad, `matrix_analysis.py`) producing every number
  in §4; key outputs: `m00 == double(float32(640/4080))` → `True`; simulated
  `setRectToRect(CENTER)+invert` in float32 → scale `0.1568627506494522`, ty `−0.9411764740943909`,
  both equal to the observed/derived device values bit-for-bit.
