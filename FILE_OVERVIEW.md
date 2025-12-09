# File Overview

## Top-level layout
- `settings.gradle.kts` — declares all Gradle modules (mobile, wear, core libraries, tools, benchmarks).
- `build.gradle.kts`, `gradle/`, `gradle.properties*`, `config/` — shared build configuration and version catalogs.
- Module roots: `mobile/`, `wear/`, `core/`, `tools/`, `app/` (legacy), plus `docs/` and test data under `res/` and `store/`.

## App entry points
- Mobile
  - `mobile/src/main/java/dev/pointtosky/mobile/MainActivity.kt` — Compose host for the phone UI, wires navigation (`MobileDestination`), data-layer bridge, catalog repository, phone location/compass bridges, and routes for AR, search, cards, settings, and debugging screens.
  - `mobile/src/main/java/dev/pointtosky/mobile/PointToSkyMobileApp.kt` — Application class initializing crash logging.
- Wear
  - `wear/src/main/java/dev/pointtosky/wear/MainActivity.kt` — Wear Compose host activity coordinating aim/identify flows and settings on the watch.
  - `wear/src/main/java/dev/pointtosky/wear/tile/tonight/TonightTileService.kt` — TileService entrypoint for the Tonight tile; pairs with `TonightTileRefreshWorker` to trigger updates.
  - `wear/src/main/java/dev/pointtosky/wear/complication/AimStatusDataSourceService.kt` and `wear/src/main/java/dev/pointtosky/wear/complication/TonightTargetDataSourceService.kt` — complication data sources for aim status and tonight targets; use `ComplicationPrefsStore` for settings.

## Core logic
- Astronomy math: `core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/` — vector/angle utilities, coordinate transforms (`EquatorialHorizontalTransform`), sidereal time, aiming geometry, and simple ephemeris helpers.
- Ephemeris over catalogs: `core/astro/src/main/kotlin/dev/pointtosky/core/astro/` — catalog loaders (`PtskCatalogLoader`) and models that combine time/location with catalog data.
- Catalog runtime: `core/catalog/src/main/kotlin/dev/pointtosky/core/catalog/` — star/constellation models, binary adapters, and `CatalogRepository` plus debug view models.
- Location handling: `core/location/src/main/java/dev/pointtosky/core/location/` — fused/remote repositories, orchestrators, and DataStore-backed `LocationPrefs` for persisting location and sharing settings.
- Time utilities: `core/time/src/main/java/dev/pointtosky/core/time/` — `JulianDate`, `ZoneRepo`, and `TimeSource` abstractions used by astro and tests.

## Logging & diagnostics
- `core/logging/src/main/java/dev/pointtosky/core/logging/` — logging pipeline including `CrashLogManager`, log sinks/writers, crash-safe flushing, and uncaught exception handler hooks used by both apps.
- `mobile/src/main/java/dev/pointtosky/mobile/crash/` — phone UI for viewing/sharing crash logs stored by the core logging module.

## Tools
- `tools/ephem-cli/src/main/kotlin/dev/pointtosky/tools/ephemcli/EphemCli.kt` — CLI entry for ephemeris calculations leveraging astro/time helpers.
- `tools/catalog-packer/src/main/kotlin/dev/pointtosky/tools/catalog/PackerMain.kt` — CLI entry coordinating catalog CSV parsers (`csv/`), constellation processing, and binary packing for runtime assets.

## Tests
- Astronomy/ephemeris: `core/astro/src/test/...` — unit/integration tests for angle math, transforms, ephemeris golden samples, and aim pipeline scenarios.
- Catalog: `core/catalog/src/test/...` — tests for binary catalog headers, adapters, constellation boundaries, and tonight target selection scenarios.
- Wear: `wear/src/test/...` — JVM/Robolectric tests for Tonight tile JSON, aim/tonight complications formatting and selection, and aim controller logic.
- Mobile: instrumentation/e2e guidance in `README.md`; unit tests live under `mobile/src/test` and connected tests under `mobile/src/androidTest` when present.
