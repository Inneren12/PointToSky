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
  "schemaVersion": 1,
  "sessionId": "sky_1767225600000",
  "startedAtEpochMillis": 1767225600000,
  "deviceModel": "Google Pixel 9",
  "cameraId": "3",
  "physicalCameraIds": ["2", "3"],
  "bufferWidthPx": 1280, "bufferHeightPx": 720,
  "lumaFormat": "RAW_Y8",
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

## Exposure

Exposure is not optional for this stream. Stars are faint point sources; an auto-exposed frame of the
night sky is an almost-black buffer with a handful of smeared stars, and a vendor "night mode"
additionally stacks and denoises, which destroys exactly the single-frame point spread a detector
needs.

Nothing in this codebase read exposure or requested a manual one before SKY-1 (a grep for
`SENSOR_EXPOSURE_TIME`/`CaptureResult` across the camera code came back empty). Both halves are now
in `SkyCaptureExposure.kt`, via CameraX's `Camera2Interop` (already a dependency):

- `probeSkyManualExposureCapability(cameraInfo)` — reports whether the selected physical camera
  advertises `MANUAL_SENSOR` and `CONTROL_AE_MODE_OFF`, and its exposure/ISO ranges. A camera without
  them **cannot** be driven into a long exposure through the public Camera2 API, and the capture
  screen says so rather than silently recording an auto-exposed session.
- `applySkyCaptureOptions(request, callback)` — sets `CONTROL_AE_MODE_OFF` + `SENSOR_EXPOSURE_TIME` +
  `SENSOR_SENSITIVITY` + `SENSOR_FRAME_DURATION` on the `ImageAnalysis` builder, and installs the
  session capture callback that reads the *actual* per-frame values back.

The requested exposure is never assumed to be the one used: the log records what `CaptureResult`
reported, with `aeMode` alongside it so a reader can confirm AE really was off.

## Capturing a session

CAM diagnostics dialog → **Open sky session capture** (`internalDebug` builds only; the activity is
`android:exported="false"` and reachable only in-app).

Pick a physical camera and analysis resolution, pick an exposure preset, point at the sky, tap
**Record**. The HUD shows analyzed/recorded/dropped frame counts, geometry status, predicted-star
count, whether a `CaptureResult` exposure is arriving, and the session directory path.
