#!/usr/bin/env python3
"""
Build script for the light-pollution Bortle grid asset (PTSKLP01 format).

IMPORTANT: The grid produced by build_synthetic_placeholder() is a SYNTHETIC
PLACEHOLDER — not real observational data.  It is seeded with a few city
"hotspots" and a handful of nodata (ocean) cells solely to exercise the full
format and resolver code paths during development.

The real asset will be produced by a separate offline packer that reads
NASA VIIRS/NPP VNP46A4 annual composites, applies the Duriscoe et al. (2018)
skyglow model, and writes the result via write_grid().

Binary format (little-endian, equirectangular):
  off  type        field
   0   8 bytes     magic = "PTSKLP01" (ASCII)
   8   int32       version = 2
  12   int32       rows
  16   int32       cols
  20   float64     latTopDeg   (north edge of row 0)
  28   float64     lonLeftDeg  (west edge of col 0)
  36   float64     degPerCell
  44   int32       flags       (bit 0 = placeholder/synthetic data)
  48   int32       compLen     (bytes in the following zlib stream)
  52   compLen     zlib-compressed payload

Payload = rows*cols bytes, row-major, row 0 = northernmost, col 0 = westernmost.
  0        = nodata / ocean
  1..9     = Bortle class
"""

import math
import pathlib
import struct
import zlib

MAGIC = b"PTSKLP01"
_HEADER_FMT = "<8s i i i d d d i i"


def write_grid(
    path, rows, cols, lat_top, lon_left, deg_per_cell, values_bytes, placeholder=False
):
    """Write a PTSKLP01 light-pollution grid binary file.

    Args:
        path: output file path (str or Path)
        rows: number of latitude rows
        cols: number of longitude columns
        lat_top: latitude of the NORTH edge of row 0 (e.g. 90.0 for global)
        lon_left: longitude of the WEST edge of col 0 (e.g. -180.0 for global)
        deg_per_cell: degrees per cell (e.g. 1.0 for 1°×1° global)
        values_bytes: flat bytes, len == rows*cols, row-major north-first;
                      0 = nodata/ocean, 1..9 = Bortle class
        placeholder: if True, set flags bit 0 to mark the grid as synthetic
                     placeholder data (the app then disables auto-detection)
    """
    assert len(values_bytes) == rows * cols, (
        f"values_bytes length {len(values_bytes)} != rows*cols {rows * cols}"
    )
    compressed = zlib.compress(values_bytes, level=9)
    flags = 1 if placeholder else 0
    header = struct.pack(
        _HEADER_FMT,
        MAGIC,
        2,                  # version
        rows,
        cols,
        float(lat_top),
        float(lon_left),
        float(deg_per_cell),
        flags,
        len(compressed),
    )
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as f:
        f.write(header)
        f.write(compressed)
    total = len(header) + len(compressed)
    print(
        f"Wrote {path}  ({rows}×{cols} grid, "
        f"payload {rows * cols} B → compressed {len(compressed)} B, "
        f"file {total} B)"
    )


# ── Synthetic placeholder ─────────────────────────────────────────────────────

def build_synthetic_placeholder():
    """Return (rows, cols, lat_top, lon_left, deg, values_bytes) for the sample.

    SYNTHETIC PLACEHOLDER — NOT real data.

    1°×1° equirectangular global grid (180 rows × 360 cols).
    Baseline Bortle 3 everywhere (rural dark sky), overlaid with radial decay
    around a few major cities (up to Bortle 8–9 at the core), plus two nodata
    (ocean) patches so the 0-byte code path is exercised.
    """
    rows, cols = 180, 360
    lat_top, lon_left, deg = 90.0, -180.0, 1.0

    # Baseline: rural dark sky (Bortle 3)
    values = bytearray(b"\x03" * rows * cols)

    # ── nodata / ocean patches (0 = nodata) ──────────────────────────────────
    # Central Pacific (far from land, ~0°N, 160°W → row 90, col 20)
    for r in range(85, 96):
        for c in range(15, 26):
            values[r * cols + c] = 0
    # South Atlantic (~30°S, 15°W → row 120, col 165)
    for r in range(115, 126):
        for c in range(160, 171):
            values[r * cols + c] = 0

    # ── city hotspots with radial decay ──────────────────────────────────────
    # Each entry: (name, lat°N, lon°E, core_bortle, decay_radius_deg)
    # Core Bortle decays linearly to Bortle 3 at decay_radius_deg.
    cities = [
        ("New York",    40.7,  -74.0,  8, 4.0),
        ("Los Angeles", 34.0, -118.2,  8, 3.5),
        ("London",      51.5,   -0.1,  8, 3.0),
        ("Paris",       48.9,    2.3,  8, 2.5),
        ("Tokyo",       35.7,  139.7,  9, 4.0),
        ("Beijing",     39.9,  116.4,  8, 3.5),
        ("Mumbai",      19.1,   72.9,  8, 2.5),
        ("Sao Paulo",  -23.5,  -46.6,  8, 3.5),
    ]

    for _name, clat, clon, core_bortle, radius in cities:
        r0 = int((lat_top - clat) / deg)
        c0 = int((clon - lon_left) / deg) % cols
        rad_cells = int(math.ceil(radius / deg)) + 2
        for dr in range(-rad_cells, rad_cells + 1):
            for dc in range(-rad_cells, rad_cells + 1):
                r = r0 + dr
                c = (c0 + dc) % cols
                if not (0 <= r < rows):
                    continue
                dist = math.sqrt(dr * dr + dc * dc) * deg
                if dist > radius * 1.5:
                    continue
                t = max(0.0, 1.0 - dist / radius)
                bortle = int(round(3 + (core_bortle - 3) * t))
                bortle = max(1, min(9, bortle))
                idx = r * cols + c
                if bortle > (values[idx] & 0xFF):
                    values[idx] = bortle

    return rows, cols, lat_top, lon_left, deg, bytes(values)


if __name__ == "__main__":
    rows, cols, lat_top, lon_left, deg, values = build_synthetic_placeholder()
    out = (
        pathlib.Path(__file__).parent.parent
        / "mobile/src/main/assets/lightpollution/bortle.bin"
    )
    write_grid(out, rows, cols, lat_top, lon_left, deg, values, placeholder=True)
