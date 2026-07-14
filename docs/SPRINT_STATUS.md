# Sprint Status

Quick-reference status board for the camera/AR prediction workstream (CAM-*). For full derivations and
risk registers, see `docs/camera_coordinate_calibration_contract.md` and
`docs/camera_star_prediction_contract.md`. This file only tracks what is implemented, tested, and
device-validated — it does not restate the underlying design.

## CAM slice status

| Slice | What it is | Implemented | JVM-tested | Device-validated |
|---|---|---|---|---|
| CAM-1c–1f | CameraX `Preview`+`ImageAnalysis`, frame metadata, timestamp-paired rotation, crop/rotation/display transform, immutable `CameraSessionGeometry`, session-scoped geometry provider | Yes | Yes | No |
| CAM-1g | `internalDebug`-only camera-geometry diagnostics overlay; observable geometry result + debug counters | Yes | Yes | **No — `CAM-1g BLOCKED ON PHYSICAL DEVICE VALIDATION`** |
| CAM-2a | Pure, Android-free star prediction (`projectStars`): catalog RA/Dec + observer location/time + magnetic declination + `CameraSessionGeometry` → predicted camera/image/display positions, with typed unavailable outcomes | Yes | Yes (388 JVM tests, `:core:astro-core`) | No — not wired into any renderer, no device claim |
| CAM-2b | `internalDebug`-only predicted-star overlay: bounded catalog adapter, pure reducer, diagnostic state, Compose markers/panel/controls consuming `projectStars(...)` for visual diagnosis only | Yes | Yes (JVM unit tests only — not executed in the authoring sandbox, no Android SDK; see below) | **No — `CAM-2b BLOCKED ON PHYSICAL DEVICE VALIDATION`** |

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
- **Validation:** Gradle could not run any `:mobile` task in the authoring sandbox (no Android SDK, no
  network path to provision one). JVM unit tests were written and reviewed by hand against the real
  production signatures they call, but not executed. See
  `docs/camera_star_prediction_contract.md` §14.10 for the full disclosure and
  `docs/validation/cam_2b_device_validation.md` for the unexecuted physical checklist.

**Overall CAM-2b status: `CAM-2b BLOCKED ON PHYSICAL DEVICE VALIDATION`** (and, separately, on someone
with a working Android SDK/Gradle environment actually running the JVM/lint/assemble commands listed in
`docs/camera_star_prediction_contract.md` §14.10).
