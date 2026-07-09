# PTSKCAT0 Real Star Catalog Binary Format

This document describes the layout produced by
`catalog-packer --format=ptskcat0 --source=hyg` and consumed by
`PtskCat0Catalog` (`:core:catalog`). It packs the real HYG v4.2 star catalog,
mag-limited and mag-sorted, for the visibility filter (VF-1) and camera
matcher (CAM-1..4).

This is a separate, purpose-built format from `PTSKCAT4` (`mobile`/`wear`
`assets/catalog/star.bin`), which is a small, hand-curated set of stars used
for constellation figure/art rendering and is produced by the Python scripts
in `res/`. PTSKCAT0 is the *real*, magnitude-complete catalog used for
brightness-based filtering and star-pattern matching.

## Header (28 bytes, little-endian)

| Offset | Size | Type | Description |
| ------ | ---- | ---- | ----------- |
| 0 | 8 | `char[8]` | Magic string `"PTSKCAT0"` |
| 8 | 4 | `i32` | Version (currently `1`) |
| 12 | 4 | `i32` | Record count |
| 16 | 4 | `i32` | Magnitude limit × 100 (e.g. `800` for 8.0, `650` for 6.5) — self-check |
| 20 | 4 | `i32` | Record size in bytes (`16`) |
| 24 | 4 | `i32` | Epoch (`2000`, J2000) |

## Records (`count` × 16 bytes)

Sorted ascending by magnitude (brightest first) using the stable sort key
`(mag, hip, id)`, so a run of the packer against the same input always
produces byte-identical output. Sorting by brightness is what makes a
"brightest N" prefix (matcher anchors, render LOD) and a magnitude-limit
binary search boundary free at read time.

| Offset | Size | Type | Description |
| ------ | ---- | ---- | ----------- |
| 0 | 4 | `f32` | Right ascension, degrees `[0, 360)` |
| 4 | 4 | `f32` | Declination, degrees `[-90, +90]` |
| 8 | 2 | `i16` | Apparent magnitude (V) × 100 |
| 10 | 2 | `i16` | Johnson B−V color index × 1000; sentinel `-32768` = unknown |
| 12 | 4 | `u32` | Hipparcos number; `0` = none |

## Names (sparse — only named stars)

| Offset | Size | Type | Description |
| ------ | ---- | ---- | ----------- |
| 0 | 4 | `i32` | Number of name entries |

Followed by that many variable-length entries:

| Size | Type | Description |
| ---- | ---- | ----------- |
| 4 | `i32` | Key: `> 0` is a Hipparcos number; `< 0` is `-(recordIndex + 1)` for stars without a HIP |
| 1 | `u8` | UTF-8 byte length of the name (max 255) |
| `len` | `utf8` | Name — `proper` (common name) if present, else `bf` (combined Bayer/Flamsteed designation) |

The `-(recordIndex + 1)` encoding (rather than `-recordIndex`) exists so index
`0` doesn't collapse into `-0`.

## Deliberately excluded (see `SPEC_catalog_CAT0.md`)

Proper motion, spectral type, and constellation membership are not carried in
this format — matching CAT-1's scope. Curated asterisms/constellation-art
data live entirely in the separate `PTSKCAT4` pipeline.
