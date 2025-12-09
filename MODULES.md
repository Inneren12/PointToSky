# Modules

## Apps
- `:mobile` — **Android app**
  - Responsibilities: phone UI (Compose) for aim/identify, search, cards, settings/onboarding, AR entrypoint, crash log viewing/sharing.
  - Key dependencies: `:core:catalog`, `:core:astro`, `:core:location`, `:core:time`, `:core:logging`, Wear data-layer bridges from `:core:common`, Compose/Material3, CameraX, Play Services Location.
- `:wear` — **Wear OS app**
  - Responsibilities: watch UI (Compose) for aim/identify and settings, Tonight tile service and refresh worker, complications (aim status/tonight target), data-layer bridge to phone.
  - Key dependencies: `:wear:sensors`, `:core:astro`, `:core:catalog`, `:core:location`, `:core:time`, `:core:logging`, `:core:common`, Wear Compose, Tiles, Complications, WorkManager.
- `:wear:benchmark` — **Benchmark module (self-instrumenting test app)**
  - Responsibilities: orchestrates macrobenchmark cases against the Wear app.
  - Key dependencies: targets `:wear` via `targetProjectPath`, uses AndroidX Benchmark/JUnit.

## Core libraries
- `:core:common` — **Kotlin Multiplatform library**
  - Responsibilities: shared constants, data-layer message envelope/codec, and platform bridges for phone/watch communication.
  - Key dependencies: Kotlin serialization; Android data-layer bridge on `androidMain`.
- `:core:logging` — **Android library**
  - Responsibilities: crash log capture, buffering, and writing; exposes `CrashLogManager` initialization hooks.
  - Key dependencies: kotlin-logging utilities, file I/O.
- `:core:location` — **Android library**
  - Responsibilities: location repositories/orchestrators, DataStore-backed preferences, and remote payload models; provides fused location implementation and stubs for testing.
  - Key dependencies: Play Services Location, DataStore, coroutines/Flow.
- `:core:time` — **Android library**
  - Responsibilities: Julian date math, timezone repository utilities, and time sources for simulations/tests.
  - Key dependencies: Kotlin stdlib; consumed by astro modules.
- `:core:astro-core` — **Android library**
  - Responsibilities: foundational astronomy math (vectors/angles), coordinate transforms, sidereal time, aiming geometry, and ephemeris primitives.
  - Key dependencies: kotlin.math; consumed by `:core:astro` and tools.
- `:core:astro` — **Android library**
  - Responsibilities: ephemeris computations over catalogs, tonight target selection, and catalog loaders bridging runtime repository.
  - Key dependencies: `:core:astro-core`, `:core:time`, `:core:catalog` (tests), Kotlin serialization.
- `:core:catalog` — **Android library**
  - Responsibilities: star/constellation models, binary catalog format adapters, runtime `CatalogRepository`, and debug UI state for catalog inspection.
  - Key dependencies: Kotlin serialization, coroutine flows; referenced by apps and astro tests.

## Wear extras
- `:wear:sensors` — **Android library (Wear)**
  - Responsibilities: sensor fusion for orientation (rotation vector/accel-mag), filters, and orientation repositories for aim UI.
  - Key dependencies: Wear sensor APIs, coroutines.

## Tools/CLI
- `:tools:ephem-cli` — **CLI Kotlin/JVM application**
  - Responsibilities: command-line ephemeris computation using astro math/time helpers.
  - Key dependencies: `:core:time`, `:core:astro-core` primitives.
- `:tools:catalog-packer` — **CLI Kotlin/JVM application**
  - Responsibilities: parse external catalogs (HYG, BSC), process constellation boundaries, and pack binary assets for runtime loading.
  - Key dependencies: Kotlin serialization/CSV parsing, outputs consumed by `:core:catalog` and app assets.
