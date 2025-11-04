# core-catalog

`core:catalog` provides the data-layer APIs for accessing stellar metadata and
constellation boundaries. The goal for S5 is to stabilise a binary-friendly
interface that can be populated from packed catalog files in later milestones.

## Public API

### Stars
- `dev.pointtosky.core.catalog.star.Star` — immutable description of a star.
- `dev.pointtosky.core.catalog.star.StarCatalog` — contract for querying nearby
  stars within an angular radius.
- `dev.pointtosky.core.catalog.star.FakeStarCatalog` — lightweight in-memory
  catalogue containing a curated list of bright stars suitable for demos and
  tests.

### Constellations
- `dev.pointtosky.core.catalog.constellation.ConstellationBoundaries` — service
  that maps equatorial coordinates to three-letter IAU constellation codes.
- `dev.pointtosky.core.catalog.constellation.FakeConstellationBoundaries` —
  simplified rectangular boundaries that emulate a subset of the sky.

### Integration
- `dev.pointtosky.core.catalog.CatalogAdapter` bridges the data contracts to the
  `IdentifySolver` (`core:astro`) by exposing `SkyCatalog` and
  `ConstellationBoundaries` from the same backing data sources.

## Binary formats (S5.B/C preview)

Future milestones will replace the fake providers with readers backed by packed
binary catalogues:

1. **Star catalogue** — compact binary blobs produced by `tools:catalog-packer`
   containing RA/Dec, magnitudes, and designations. The runtime will memory-map
   the data and expose it through `StarCatalog` without materialising every
   entry.
2. **Constellation boundaries** — polygonal meshes encoded with delta-compressed
   vertices. A lightweight point-in-polygon solver will replace the rectangle
   approximation provided by `FakeConstellationBoundaries`.
3. **Extensibility** — adapters will remain the same so downstream modules only
   swap implementations without rebuilding dependants.

This module intentionally has no UI dependencies and can be shared across the
mobile and wear targets.
