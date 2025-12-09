# PointToSky – Project Overview

## Purpose & scope
- PointToSky is an Android + Wear OS astronomy companion that lets users aim their phone or watch at the sky, identify objects, and see contextual information.
- Mobile features include AR aiming, search, cards for Tonight targets, crash log sharing, and onboarding/settings flows backed by shared data-layer messaging with Wear. Wear features include tiles, complications, and on-device identify/aim views.
- Supported platforms: Android phones (`:mobile`) and Wear OS watches (`:wear`).

## Core concepts
- Orientation → sky coordinates → catalog lookup → UI rendering. Device sensors and location feed the astro pipeline that converts device pose into equatorial/horizontal coordinates and matches visible targets.
- Star/planet catalog: binary-packed catalogs with stars, constellations, and tonight targets consumed by runtime repositories.
- Ephemeris & coordinate systems: astronomical math (sidereal time, transforms) to project objects for the current time/location; used by aim/identify and tonight recommendations.

## Tech stack
- Kotlin across all modules, Gradle with Kotlin DSL; multiplatform `:core:common` for shared data-layer messaging.
- UI: Jetpack Compose on mobile, Wear Compose for watch UI, Tiles, and complications; CameraX-backed AR entrypoint in `:mobile`.
- Platform services: Google Play Services location, Wear data layer bridges, DataStore/Preferences for settings, WorkManager for refresh tasks, CameraX for AR, and sensor fusion in `:wear:sensors`.
- Concurrency/testing: Kotlin coroutines/Flow; unit tests via JUnit 5/JUnit 4 interop and Robolectric where needed.

## Modules at a glance
- Apps: `:mobile` (phone experience with AR, search, cards, settings) and `:wear` (watch experience with aim/identify screens, Tonight tile, complications, settings).
- Core libraries: `:core:astro-core` (astronomy math and transforms), `:core:astro` (ephemeris and catalog-backed calculations), `:core:catalog` (catalog formats and runtime repository), `:core:location` (location orchestration and preferences), `:core:time` (Julian dates, timezone helpers), `:core:logging` (crash log pipeline), `:core:common` (shared constants and data-layer messages).
- Wear extras: `:wear:sensors` (orientation fusion for watch), `:wear:benchmark` (baseline benchmark app shell).
- Tools: `:tools:ephem-cli` (CLI to compute ephemerides) and `:tools:catalog-packer` (CSV/HYG/BSC parsers to build binary catalogs).

## Build & run basics
- Assemble phone app: `./gradlew :mobile:assembleDebug`.
- Assemble watch app: `./gradlew :wear:assembleInternalDebug` (or other flavors defined in the module).
- If builds fail, check module-specific Gradle files for dependency constraints and lint baselines; tiles/complications depend on Wear OS SDK versions declared in `settings.gradle.kts` and module build scripts.

## Where to go next
- See `MODULES.md` for per-module responsibilities and dependencies.
- See `FILE_OVERVIEW.md` for pointers to key entrypoints and implementation files.
- Additional docs (e.g., `CONTENT_GUIDE.md`, `stage.md`) will be added later; consult repository docs for updates.
