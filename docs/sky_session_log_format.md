# Sky session log (SKY-1)

The sky-track capture format: what a night-sky shoot writes to disk, and how to read it back
offline. This is the data contract a future star detector is developed against.

It is a **separate stream** from both the CAM-2c `FrameContent` dot-grid track and the app-wide
`dev.pointtosky.core.logging` event log. The serialization *style* is shared with the latter
(hand-built `JsonObject`s, one object per line); the streams are not.

## Where the code lives

| Concern | Module | File |
| --- | --- | --- |
| Record model | `:core:astro-core` | `…/projection/camera/skylog/SkySessionLog.kt` |
| JSONL encode | `:core:astro-core` | `…/skylog/SkySessionLogCodec.kt` |
| JSONL decode | `:core:astro-core` | `…/skylog/SkySessionLogDecode.kt` |
| Exposure join | `:mobile` (`internalDebug`) | `…/ar/camera/SkyExposureJoin.kt` |
| Bind generations | `:mobile` (`internalDebug`) | `…/ar/camera/SkyCaptureGeneration.kt` |
| Per-bind session state | `:mobile` (`internalDebug`) | `…/ar/camera/SkySessionCaptureSession.kt` |
| Offline replay | `:core:astro-core` | `…/skylog/SkySessionLogReplay.kt` |
| Disk writer | `:mobile` (`internalDebug`) | `…/ar/camera/SkySessionLogWriter.kt` |
| Record assembly | `:mobile` (`internalDebug`) | `…/ar/camera/SkySessionRecordBuilder.kt` |
| Exposure read / manual exposure | `:mobile` (`internalDebug`) | `…/ar/camera/SkyCaptureExposure.kt` |
| Capture screen | `:mobile` (`internalDebug`) | `…/ar/camera/SkySessionCaptureScreen.kt` |

## On-device layout

```text
<getExternalFilesDir()>/sky_sessions/sky_<epochMillis>/
  session.jsonl            header line, then one line per analyzed frame
  frames/frame_000000.y    packed 8-bit luma
  frames/frame_000001.y
  …
```

Pull the whole directory (`adb pull`) — a frame line references its pixels by a path **relative to
the session directory**, so the directory is self-contained and relocatable.

## Pixel format: raw `Y8`, not PNG

`ImageProxy.planes[0]` is already an 8-bit intensity plane, which is exactly what a star detector
reads. Writing it verbatim is lossless, needs no encoder, and costs nothing at capture time. Chroma
is not read at all: no star detector uses it, and it would cost ~3x the bytes per frame in a mode
where storage is the practical limit on session length.

Reading one frame offline:

```python
import numpy as np, json
line  = json.loads(open("session.jsonl").readlines()[1])
luma  = line["luma"]
data  = np.fromfile(luma["path"], dtype=np.uint8)
image = data.reshape(luma["heightPx"], luma["rowStridePx"])[:, :luma["widthPx"]]
```

`rowStridePx` may exceed `widthPx` (row padding) — always slice, never assume they are equal.

## Line 1: the session header

```jsonc
{
  "kind": "session",
  "sessionId": "sky_1767225600000",
  "startedAtEpochMillis": 1767225600000,
  "deviceModel": "Google Pixel 9",
  "cameraId": "3",
  "physicalCameraIds": ["2", "3"],
  "bufferWidthPx": 1280, "bufferHeightPx": 720,
  "lumaFormat": "RAW_Y8",
  "schemaVersion": 1,                       // required; an unsupported value is never parsed
  "maxPairDeltaNanos": 25000000,            // the pairing tolerance this session actually used
  "clockMismatchThresholdNanos": 5000000000,
  "clockAlignment": { "frameClock": "CAMERA_SENSOR_NANOS", "poseClock": "SENSOR_EVENT_NANOS",
                      "poseToFrameOffsetNanos": 0 },
  "intrinsics": { "horizontalFovDeg": …, "verticalFovDeg": …, "source": "CAMERA_CHARACTERISTICS",
                  "referenceKind": "ANALYSIS_BUFFER", "referenceWidthPx": 1280, "referenceHeightPx": 720,
                  "axisSwapped": false, "negateXInput": false, "negateYInput": false,
                  "pinhole": { "fxPx": …, "fyPx": …, "cxPx": …, "cyPx": … } },
  "calibration": { "activeArrayWidthPx": …, "bufferFxPx": …, … }   // optional
}
```

`maxPairDeltaNanos`/`clockMismatchThresholdNanos` are recorded so replay reproduces the device's own
accept/reject decisions rather than library defaults.

### `schemaVersion` is required, and never defaulted

Only the versions in `SUPPORTED_SKY_SESSION_LOG_SCHEMA_VERSIONS` are read. A header whose version is
missing, non-integer, zero, negative, or simply newer than this build knows becomes
`SkySessionLogLine.UnsupportedSchema` — it is *not* coerced to the current version. A future build may
have reinterpreted a field this one still recognises by name, and "parse it anyway and hope" is
exactly what a version number exists to prevent.

Frames appearing **before** an accepted header land in `SkySessionLogDocument.orphanFrames`, never in
`records`, and replay never touches them: they were captured under intrinsics and tolerances this
document has no record of.

**Lens distortion is absent, deliberately.** Camera2's `LENS_DISTORTION` /
`LENS_RADIAL_DISTORTION` characteristics are not read anywhere in this codebase, so there is no
distortion model to record. An always-`null` field would only imply one exists and was empty. Adding
a distortion read is additive here, with a `schemaVersion` bump.

## Lines 2..n: one frame each

```jsonc
{
  "kind": "frame",
  "seq": 0,
  "capturedAtEpochMillis": 1767225600123,
  "frame": { "timestampNanos": …, "bufferWidthPx": 1280, "bufferHeightPx": 720,
             "rotationDegrees": 90, "cropRect": {…}, "sensorToBufferTransform": [9 doubles] },
  "viewportWidthPx": 1080, "viewportHeightPx": 2400,
  "luma": { "path": "frames/frame_000000.y", "format": "RAW_Y8",
            "widthPx": 1280, "heightPx": 720, "rowStridePx": 1280, "byteLength": 921600 },
  "pose": { "timestampNanos": …, "frameToPoseDeltaNanos": …,
            "rotationMatrix": [9 doubles], "quaternion": { "x": …, "y": …, "z": …, "w": … } },
  "observer": { "latitudeDeg": …, "longitudeDeg": …, "utcEpochMillis": …,
                "horizontalAccuracyM": …, "magneticDeclinationDeg": … },
  "exposure": { "exposureTimeNanos": 500000000, "sensitivityIso": 1600,
                "frameDurationNanos": …, "aeMode": "OFF", "awbMode": "AUTO",
                "sensorTimestampNanos": … },
  "predictedStars": [ { "id": 32349, "mag": -1.46, "raRad": …, "decRad": …,
                        "classification": "VISIBLE_IN_VIEWPORT",
                        "xPx": …, "yPx": …, "displayXPx": …, "displayYPx": … } ]
}
```

Absent optional fields are simply omitted — readers must treat "missing" and `null` identically.

### Field notes that matter

- **`pose.rotationMatrix` is authoritative; `pose.quaternion` is derived on write and ignored on
  parse.** The matrix is the display-remapped, magnetic-north-referenced device→world matrix
  `RotationFrame.kt` publishes — the one the projection math actually consumes. It is *not* the raw
  `TYPE_ROTATION_VECTOR` quaternion. The same applies to `intrinsics.pinhole`: derived on write from
  the FOV, ignored on parse. Storing a derived value as parseable state would let a hand-edited log
  carry a quaternion that disagrees with its own matrix, with no way to say which one to believe.
- **`predictedStars[].raRad`/`decRad` are the projector's input**, carried alongside its output on
  purpose. Without them the log records an answer with no question, and replay could not re-run the
  projection without also shipping the star catalog.
- **`xPx`/`yPx` are full analysis-buffer pixels** — the same space `frames/*.y` stores, so a
  detection found in the luma file is directly comparable. `displayXPx`/`displayYPx` are the
  viewport-space equivalents. All four are absent for a star behind the camera.
- **`exposure.sensorTimestampNanos` equals `frame.timestampNanos`** for the same frame. It is what
  makes an exposure sample provably the one belonging to these pixels rather than merely the most
  recent one seen — `CaptureResult` and `ImageProxy` arrive on different threads with no guaranteed
  ordering.
- **`observer.magneticDeclinationDeg` may be absent, and absent never means zero.** Replay skips
  such a frame with `MAGNETIC_DECLINATION_UNAVAILABLE` rather than projecting an uncorrected result
  that looks corrected.

## Clocks

`clockAlignment` records which clock each timestamp is on, and the measured offset between them:

- offset present → applied, even when the two clock names agree;
- offset absent and both clocks equal and known → the offset is `0` because the timestamps are on the
  same clock, not because zero was assumed;
- offset absent and clocks differ (or either is `UNKNOWN`) → **no alignment is possible**; replay
  skips the frame with `POSE_CLOCK_UNALIGNED` rather than comparing incomparable numbers.

The on-device capture writes `poseToFrameOffsetNanos: 0` explicitly, because this app already pairs
`SensorEvent.timestamp` directly against `ImageProxy.imageInfo.timestamp` (CAM-1d's
`pairFrameToNearestRotation`, which has a `ClockMismatchSuspected` outcome for the devices where that
assumption fails).

## Damage tolerance

Parsing never throws. A truncated final line — a capture ended by the battery, a crash, or the
operator walking away — costs one frame, not the session, and is reported as
`SkySessionLogLine.Unreadable`. A blank line mid-stream is also reported rather than skipped: a gap
is real evidence about the write that produced it.

## Replaying a log offline

```kotlin
val document = parseSkySessionLog(File(dir, "session.jsonl").readText())
val report = replaySkySessionLog(document) ?: error("no readable header")

report.readyFrames.forEach { frame ->
    frame.projections   // freshly computed, in recorded-star order
    frame.residuals     // recomputed vs recorded, in analysis-buffer pixels
}
report.skippedFrames    // each with a categorized SkyFrameReplaySkipReason
```

Replay rebuilds the exact inputs the device had and re-runs the *same* `createCameraSessionGeometry`
and `projectStars`, at the session's own recorded tolerances. It reimplements no math and substitutes
no defaults, so a frame the device rejected replays as rejected for the same reason. The recorded
pixel coordinates are only ever the expected value to diff against, never an input — that is what
makes a replay a check rather than an echo.

## Exposure: a gate, not a setting

Exposure is not optional for this stream. Stars are faint point sources; an auto-exposed frame of the
night sky is an almost-black buffer with a handful of smeared stars, and a vendor "night mode"
additionally stacks and denoises, which destroys exactly the single-frame point spread a detector
needs.

Nothing in this codebase read exposure or requested a manual one before SKY-1 (a grep for
`SENSOR_EXPOSURE_TIME`/`CaptureResult` across the camera code came back empty). What exists now is not
just a request — setting `CONTROL_AE_MODE_OFF` is something devices clamp, ignore, or apply a few
frames late. Three separate checks turn the request into a fact, and **a frame that fails any of them
is not written to the log**.

### 1. Two-phase bind

The first bind of a camera carries **no** exposure request at all. Nothing is yet known about the
device's ranges, and a `SENSOR_EXPOSURE_TIME` outside them makes the HAL discard the whole manual
request and silently auto-expose. So:

1. bind plain → `probeSkyManualExposureCapability(cameraInfo)` reads `MANUAL_SENSOR`,
   `CONTROL_AE_MODE_OFF`, `SENSOR_INFO_EXPOSURE_TIME_RANGE`, `SENSOR_INFO_SENSITIVITY_RANGE` and
   `SENSOR_INFO_MAX_FRAME_DURATION`;
2. `SkyManualExposureCapability.resolve(request)` clamps the request into those ranges and works out a
   legal `SENSOR_FRAME_DURATION` — `frameDuration == exposureTime` is **not** assumed legal: the
   exposure is capped at the max frame duration, and a device whose constraints cannot be satisfied
   returns a typed `Unresolvable` reason instead of an illegal request;
3. rebind with the resolved values.

### 2. The recording gate

`evaluateSkyRecordingGate(...)` decides whether Record may be tapped at all. It blocks, with the
reason shown in the HUD, when:

| Reason | Meaning |
| --- | --- |
| `AUTO_EXPOSURE_NOT_ALLOWED` | No manual exposure requested. This is a manual-exposure dataset. |
| `CAMERA_CAPABILITY_UNKNOWN` | No camera bound yet, so nothing is known about it. |
| `MANUAL_SENSOR_CAPABILITY_ABSENT` | The camera does not advertise `MANUAL_SENSOR`. |
| `CONTROL_AE_MODE_OFF_UNAVAILABLE` | The camera cannot turn auto-exposure off. |
| `CAMERA2_INFO_UNAVAILABLE` | `Camera2CameraInfo` could not be read. |
| `EXPOSURE_RANGE_UNSATISFIABLE` / `SENSITIVITY_RANGE_UNSATISFIABLE` / `FRAME_DURATION_UNSATISFIABLE` | The request cannot be clamped into the device's ranges. |
| `EXPOSURE_NOT_APPLIED_YET` | The resolved exposure is not the one the current bind carries — phase one, or a rebind still pending. |
| `INTRINSICS_NOT_RESOLVED` | No frame has been analyzed yet, so the session has no intrinsics for its header. |

### 3. Per-frame validation

Every frame is joined to the `CaptureResult` that produced it (below), and the recorder refuses to
count it unless that result reports **all** of: an `exposureTimeNanos`, a `sensitivityIso`, a
`sensorTimestampNanos` exactly equal to the frame timestamp, and `aeMode == "OFF"`. Otherwise the
frame is dropped with a typed `SkyRecordOutcome` (`EXPOSURE_TIME_MISSING`,
`EXPOSURE_SENSITIVITY_MISSING`, `EXPOSURE_TIMESTAMP_MISMATCH`, `EXPOSURE_AE_NOT_OFF`) and counted in
the HUD's dropped total.

So `exposure` is never absent from a recorded frame line, and its `aeMode` is always `"OFF"`. A log
that says otherwise did not come from this writer.

**Known gap, stated rather than guessed at:** Camera2 also imposes a *minimum* frame duration that
depends on the configured output size (`StreamConfigurationMap.getOutputMinFrameDuration`). The
capability type carries a `minFrameDurationNanos` field and honours it when supplied, but the probe
does not currently read it — that needs the exact chosen `ImageAnalysis` surface, which CameraX
resolves only after the bind. On a device where that minimum exceeds the requested exposure, the frame
duration is raised by the HAL rather than by us.

## Joining pixels to their exposure

`ImageProxy` arrives on the analysis executor and `CaptureResult` on the camera callback thread, in
either order — and at a 2 s exposure the gap is large. `SkyExposureJoin` holds **both** sides and
completes a pair whichever arrives second, keyed on exact `SENSOR_TIMESTAMP` equality. A one-shot
"look up the exposure when the image arrives" would silently record every late result as
`exposure: null`, which for this dataset is the worst possible failure: the log looks complete and is
not.

The join is bounded on both sides — by capacity (oldest evicted first) and by age measured against the
sensor clock itself — so pending luma planes cannot accumulate. Everything released without a pair is
reported with a typed reason (`FRAME_TIMED_OUT`, `FRAME_EVICTED`, `FRAME_DUPLICATE_TIMESTAMP`,
`EXPOSURE_TIMED_OUT`, `EXPOSURE_EVICTED`, `EXPOSURE_DUPLICATE_TIMESTAMP`, `EXPOSURE_UNKEYED`,
`PENDING_AT_STOP`) and counted in the HUD, never dropped silently. Duplicate timestamps are first-wins
on both sides. Nothing is ever matched approximately.

## Session isolation

Every bind gets a monotonically increasing **epoch**, and the whole per-bind apparatus — intrinsics
resolver, pairing synchronizer, geometry provider, bound camera id — is rebuilt for it and the
previous one disposed. That matters because `SessionScopedCameraIntrinsicsResolver.resolveOnce` caches
its first answer for the life of the instance: reused across a rebind, the second bind's frames would
be projected through the first bind's intrinsics, and a header could pair the new camera id with the
old camera's calibration. Callbacks carry their epoch; anything older is counted as stale and
discarded. Changing the physical camera, the analysis resolution, or the exposure is a rebind, and a
rebind stops any recording in progress.

A session directory is **created**, never adopted: `Files.createDirectory` for the directory and
`CREATE_NEW` for `session.jsonl` and every `frame_NNNNNN.y`. An existing directory, log file, or frame
file is a typed failure, not something to append to or overwrite. Start-after-stop always makes a new
directory — a recorder is terminal once stopped, and its lock covers every sink call including
`close`, so a frame already committing finishes atomically and one starting after Stop writes nothing.

## Capturing a session

CAM diagnostics dialog → **Open sky session capture** (`internalDebug` builds only; the activity is
`android:exported="false"` and reachable only in-app).

Pick a physical camera and analysis resolution, pick an exposure preset, point at the sky, tap
**Record**. Record stays disabled until the gate above allows it, and the HUD names the blocking
reason. It also shows analyzed/recorded/dropped/stale frame counts, join-drop counts with the last
reason, geometry status, predicted-star count, the requested vs applied exposure, and the session
directory path.
