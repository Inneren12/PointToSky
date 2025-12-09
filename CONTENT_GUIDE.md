# Content Guide – PointToSky

## 1. Philosophy & goals
- Astronomical correctness first: trust core ephemeris/catalog math over UI shortcuts.
- Responsive on mobile and Wear: prefer streaming updates over blocking calls; avoid ANRs.
- Battery- and storage-friendly: reuse sensors, limit catalog I/O, keep logging lightweight in release.
- Testable and reproducible: isolate Android glue; prefer deterministic time and location inputs in tests.
- Readable, traceable code: small, composable functions with explicit data flow and logging breadcrumbs.

## 2. Architecture
- **App shells (mobile, wear)** own platform glue (Activities, services, tiles/complications) and Compose UI. ViewModels expose immutable state flows for screens and tiles.
- **Core modules** carry domain logic:
  - `core/astro-core` + `core/astro` for transformations (RA/Dec ↔ Alt/Az), ephemeris, and math helpers.
  - `core/catalog` for catalog ingestion and queries (`CatalogRepository`, star lookups).
  - `core/location` for device/manual location orchestration and preferences.
  - `core/time` for tick/time abstractions.
  - `core/logging` for structured logging (`LogBus`).
  - `core/common` for shared types/utilities.
- **Tools** (under `tools/`) are offline helpers (e.g., generating constellation shapes/binaries) and should not leak into runtime code.
- **Typical flow**: device/manual location + orientation + time tick → astro transforms → catalog/star selection → ViewModel state → Compose UI/tiles/complications.
- **Patterns**: MVVM-ish ViewModels with `StateFlow`, repository interfaces, and composable UIs. Prefer dependency injection via constructors/factories; keep Android framework access at the edges.

## 3. Coroutines & Flow
- Launch long-lived jobs in `viewModelScope` (UI) or injected scopes in services/workers; prefer structured concurrency over ad-hoc jobs.
- Use `Dispatchers.IO` for I/O- and CPU-heavy work (catalog loading, ephemeris computation, location providers). Keep main-thread collectors light.
- **Do NOT** call `runBlocking` on UI/main/binder threads; use suspending APIs and flows instead.
- Expose immutable `Flow`/`StateFlow` from repositories and ViewModels; keep mutable state private.
- Collect in Compose with lifecycle-aware helpers (e.g., `collectAsStateWithLifecycle`) to avoid leaks and stale observers.

## 4. UI / Compose
- Prefer stateless composables with explicit state passed in; keep state in ViewModels or higher-level screen owners.
- Use `remember`/`derivedStateOf` for derived values to minimize recomposition; avoid heavy work inside composable bodies.
- Separate screen state models (`*UiState`/`StateFlow`) from rendering functions; keep side effects in `LaunchedEffect` tied to keys.
- Mobile screens live under `mobile/...`; Wear screens/tiles/complications live under `wear/...` with platform-specific entry points.
- Wear specifics: tiles/complications favor lightweight layouts and precomputed data; prefer background workers for refresh where needed.

## 5. Logging & diagnostics
- Use the project logger (`LogBus` from `core:logging`) for events and diagnostics. Avoid direct `android.util.Log` except for temporary debugging.
- Prefer structured payloads: `LogBus.event(tag, mapOf("key" to value))` or domain-specific tags to keep logs searchable.
- Keep frequently-emitted logs lightweight (short messages, small payloads). Reserve verbose traces for debug builds.
- Crash/diagnostic surfaces (mobile & wear) already expose log export; ensure new logging integrates with existing writers instead of ad-hoc files.

## 6. Testing
- Focus tests on: astro math/ephemeris, catalog loading/querying, ViewModel state transitions, Wear tiles/complications data, and data-layer bridges.
- Tooling: JUnit + Kotlin test assertions, Truth where available, and `kotlinx-coroutines-test` for deterministic virtual time. Robolectric is used where Android components are required.
- Prefer fakes over real Android services (location/orientation/time). Drive flows with `MutableStateFlow`/`MutableSharedFlow`; advance virtual time instead of sleeping.
- Keep tests deterministic: inject fixed `Instant`/time sources and stable catalog fixtures; avoid network/system clock dependencies.

## 7. Adding new features
- **New domain logic**: add to the relevant `core/*` module (astro math in `astro/astro-core`, catalog ingest/query in `catalog`, time/location utilities in `time`/`location`).
- **New mobile feature**: place ViewModels and UI under `mobile/src/main/java/dev/pointtosky/mobile/<feature>`; wire navigation/DI in the app shell.
- **New Wear tile/complication/screen**: add under `wear/src/main/java/dev/pointtosky/wear/...` and keep background refresh/data sources lightweight.
- Update docs when roles change: `MODULES.md`/`FILE_OVERVIEW.md` for structure shifts, and `stage.md` when priorities or milestones move.
