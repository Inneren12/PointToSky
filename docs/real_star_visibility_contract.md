# Real-Star Visibility Service Contract (VF-1)

This document describes the contract of `RealStarVisibilityService`
(`:core:catalog`) — the manual, sky-quality-driven pipeline that turns a
Bortle class, SQM reading, or direct limiting magnitude into a visible-star
selection over the PTSKCAT0 real star catalog. It exists so a future
renderer/camera-matcher integration (VF-2+) can consume this service without
re-deriving its semantics from source.

It complements `docs/star_catalog_ptskcat0_format.md`, which describes the
on-disk PTSKCAT0 binary format. This document covers the runtime service
built on top of it.

## 1. Pipeline overview

`RealStarVisibilityService.select(input: SkyQualityInput)` composes four
pieces, in order:

1. **PTSKCAT0 real catalog** (`PtskCat0Catalog`) — the mag-sorted, HYG-derived
   binary catalog described in `docs/star_catalog_ptskcat0_format.md`. This is
   a separate, purpose-built catalog from PTSKCAT4 (see [Safety
   boundaries](#4-safety-boundaries)).
2. **Provider load** (`RealStarCatalogProvider.load()`) — reads and parses the
   bundled PTSKCAT0 asset (`AssetRealStarCatalogProvider`, default path
   `catalog/stars_real.bin`). Throws `RealStarCatalogLoadException` if the
   asset is missing, unreadable, or malformed.
3. **Limiting magnitude calculation** (`LimitingMagnitudeModel`) — converts
   the caller's `SkyQualityInput` into a single `limitingMagnitude` (see
   [Input semantics](#2-input-semantics)).
4. **Prefix selection** (`RealStarVisibilityFilter.select`) — since PTSKCAT0
   records are sorted ascending by magnitude, "visible up to a limiting
   magnitude" is just the brighter-or-equal prefix
   (`PtskCat0Catalog.countBrighterOrEqual`); no copying, no spatial work.
5. **Debug diagnostics** — `RealStarVisibilityDebugProbe` wraps the service in
   a non-throwing snapshot (`RealStarVisibilityDebugSnapshot.Success` /
   `Failure`) for runtime/debug wiring; see
   [Safety boundaries](#4-safety-boundaries) for what this is and isn't.

As of this writing, `RealStarVisibilityService.select` loads the catalog on
every call via `provider.load()` and does not cache the result itself —
verified by `RealStarVisibilityServiceTest`'s "provider is called exactly
once per select call" test, which asserts the provider's load count
increments on each `select()`. This is current behavior, not a guarantee:
callers must not depend on `select` being cache-free or on any particular
number of `provider.load()` calls, since a future revision may add caching
inside the service without that being a breaking change to this contract.
If a caller needs repeated selections without paying repeated load cost
today, it should cache the `RealStarVisibilityResult` (or the `catalog`)
itself rather than relying on `select` to do so.

## 2. Input semantics

`SkyQualityInput` is a sealed interface with three variants:

- **`Bortle(value: Int)`** — a Bortle dark-sky class `1..9` (1 = darkest, 9 =
  brightest inner-city sky), converted via `LimitingMagnitudeModel.fromBortle`.
  Throws `IllegalArgumentException` if `value` is outside `1..9`.
- **`Sqm(value: Double)`** — a sky-quality-meter reading in mag/arcsec²,
  converted via `LimitingMagnitudeModel.fromSqm`, which delegates to the
  repository's canonical SQM calibration, `darkNelmFromSqm`
  (`core/astro/.../SkyBrightness.kt`), so this model never drifts from the
  light-pollution-grid calibration used elsewhere. Throws
  `IllegalArgumentException` if `value` is `NaN` or infinite.
- **`LimitingMagnitude(value: Double)`** — a limiting magnitude supplied
  directly by the caller, bypassing the Bortle/SQM conversion:
  - **`NaN`** is rejected (`IllegalArgumentException`).
  - **`Double.POSITIVE_INFINITY`** is preserved unchanged and means "select
    all records" (no limit).
  - **`Double.NEGATIVE_INFINITY`** is preserved unchanged and means "select no
    records".
  - Any other, finite value is clamped to
    `LimitingMagnitudeModel.SUPPORTED_RANGE` (`0.0..8.0`) before reaching the
    filter, so a direct limiting magnitude stays within the same outer bound
    as the Bortle/SQM conversions.

All three variants ultimately produce one `limitingMagnitude: Double`, which
`RealStarVisibilityFilter.select` also validates is not `NaN` (defense in
depth — `SkyQualityInput` already rejects `NaN` upstream).

## 3. Output contract

`RealStarVisibilityService.select` returns a `RealStarVisibilityResult`:

```kotlin
data class RealStarVisibilityResult(
    val catalog: PtskCat0Catalog,
    val limitingMagnitude: Double,
    val selection: VisibleStarSelection,
)
```

- **`selection.count`** — the number of visible records. `VisibleStarSelection`
  requires `count >= 0`.
- **Indices are `[0, count)`** — `selection.indices` (`IntRange`) enumerates
  the mag-sorted prefix `0 until count`. Because PTSKCAT0 is sorted ascending
  by magnitude, this prefix is exactly the visible-star set for
  `limitingMagnitude`; no separate index list is produced.
- **No star data copy** — the selection is a bare count/range. Nothing is
  copied out of `catalog`.
- **Caller uses the returned catalog + prefix together** — `catalog` is
  returned alongside `selection` specifically so a caller can resolve
  `selection.indices` into ra/dec/mag/name via `catalog.raDegAt(i)` /
  `decDegAt(i)` / `magAt(i)` / `nameAt(i)` without a second catalog load.
  `catalog` and `selection` from *different* `select()` calls must not be
  mixed — always index into the `catalog` from the same `RealStarVisibilityResult`.

## 4. Safety boundaries

- **No renderer switch yet.** This service and its filter/model are pure
  data-selection logic. Nothing in this pipeline feeds the sky renderer.
- **No PTSKCAT4 replacement.** PTSKCAT4 (`mobile`/`wear`
  `assets/catalog/star.bin`, loaded via
  `dev.pointtosky.core.astro.catalog.PtskCatalogLoader`) is a separate,
  hand-curated catalog used for constellation figure/art rendering and
  remains untouched by this pipeline. PTSKCAT0 is the real, magnitude-complete
  catalog used for brightness-based filtering and future star-pattern
  matching (CAM-1..4) — the two catalogs are not interchangeable.
- **The debug Catalog screen is diagnostic only.** `RealStarVisibilityDebugProvider`
  runs a one-shot probe on app start (default input `SkyQualityInput.Bortle(5)`),
  logs the result, and publishes it as `RealStarVisibilityDebugUiState` for
  display in the mobile Catalog Debug screen. This confirms the pipeline
  loads the bundled asset and computes counts correctly — it is proof the
  pipeline works, not a rendering path. It does not feed the renderer or
  PTSKCAT4.
  - **Off-main.** `ensureLoaded` is called from a `LaunchedEffect` in
    `MainActivity`, but the actual `provider.load()`/`select()` work runs on
    a `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, not the caller's
    thread.
  - **No unwanted cold-start cost.** `ensureLoaded` is idempotent (guarded by
    a `@Volatile started` flag with double-checked locking) and returns
    immediately after launching the coroutine — it does not block app start
    waiting for the catalog to load, and a second call is a no-op.
  - **Never crashes the caller.** `RealStarVisibilityDebugProbe` catches
    `RealStarCatalogLoadException` and surfaces it as a `Failure` snapshot;
    `RealStarVisibilityDebugProvider.applySnapshot` further wraps the whole
    compute/publish/log path in `catch (e: Exception)`, so a failure in
    logging itself is exactly as non-fatal as a failure in `compute()`.
  - **Tested.** `RealStarVisibilityDebugProbeTest` (`:core:catalog`) covers
    the Success/Failure snapshot paths; `RealStarVisibilityDebugProviderTest`
    (`:mobile`) drives `applySnapshot` directly (no `Context`/asset needed)
    to cover Success, Failure, and unexpected-exception state publishing.

## 5. Future integration notes

These are non-binding notes for the next PRs, not commitments made by this
contract:

- A future renderer integration should consume `RealStarVisibilityService`'s
  result through an adapter boundary, rather than the renderer depending on
  `PtskCat0Catalog`/`VisibleStarSelection` directly — keeps the render layer
  decoupled from the catalog's binary representation.
- Camera star-pattern matching (CAM-1..4) may later reuse the same
  visibility-limited prefix (`selection.indices` over `catalog`) as its
  candidate set, rather than re-deriving one.
- Physical effects that would change what's actually visible — Moon
  phase/altitude, twilight/Sun altitude, cloud cover — belong in later
  models layered on top of `LimitingMagnitudeModel`, not in this service's
  contract. `RealStarVisibilityService` and `LimitingMagnitudeModel` are
  deliberately conservative: manual sky-quality input only, no GPS, no
  automatic grid lookup, no camera or renderer wiring.
