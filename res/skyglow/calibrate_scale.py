#!/usr/bin/env python3
"""
res/skyglow/calibrate_scale.py — sweep the `scale` calibration knob against
known dark/bright reference sites.

This is a runnable extraction of the manual procedure documented in
REAL_GRID_RUNBOOK.md ("Step 5 — Calibrate scale"); it does not change any of
the underlying math in build_real_grid.py / brightness.py, it just automates
the snippet from the runbook into a reusable script.

Usage:
    python -m res.skyglow.calibrate_scale viirs_mosaic_0.1deg.tif \\
        --deg 0.1 --scales 1.0 2.0 3.0 4.0 5.0

Requires rasterio to load the mosaic GeoTIFF; run_sweep() itself is pure
NumPy and importable without rasterio for testing.
"""

from __future__ import annotations

import argparse

from .build_real_grid import radiance_to_bortle_grid, sample_sites

# Reference sites and target Bortle classes, from REAL_GRID_RUNBOOK.md Step 5.
DARK_REFS = [
    ("Cherry Springs SP", 41.66, -77.82),   # target Bortle 2
    ("Death Valley", 36.50, -117.10),       # target Bortle 1-2
    ("NamibRand", -25.00, 16.00),           # target Bortle 1
]
BRIGHT_REFS = [
    ("Manhattan", 40.78, -73.97),           # target Bortle 8-9
    ("Tokyo centre", 35.68, 139.76),        # target Bortle 9
    ("Central London", 51.51, -0.13),       # target Bortle 8-9
]


def run_sweep(radiance, deg_per_cell, scales):
    """Yield (scale, dark_vals, bright_vals) for each scale in *scales*.

    dark_vals / bright_vals are lists of (site_name, bortle_int) pairs, as
    returned by sample_sites().
    """
    for scale in scales:
        grid = radiance_to_bortle_grid(radiance, deg_per_cell=deg_per_cell, scale=scale)
        dark_vals = sample_sites(grid, deg_per_cell, DARK_REFS)
        bright_vals = sample_sites(grid, deg_per_cell, BRIGHT_REFS)
        yield scale, dark_vals, bright_vals


def _load_radiance(path):
    """Read band 1 of *path*, filling nodata/masked pixels with 0.0.

    Mirrors the nodata handling in build_real_grid._cli_main() (which passes
    src_nodata=src.nodata, dst_nodata=0.0 to reproject()): nodata sentinels
    such as 65535 must never be treated as valid radiance, or calibration
    picks a scale against a much brighter field than the one actually
    written by the build CLI.
    """
    import numpy as np
    import rasterio

    with rasterio.open(path) as src:
        band = src.read(1, masked=True).astype(float)
        return np.ma.filled(band, 0.0)


def _cli_main():
    parser = argparse.ArgumentParser(
        description=(
            "Sweep the scale calibration knob against known dark/bright "
            "reference sites. See REAL_GRID_RUNBOOK.md Step 5."
        )
    )
    parser.add_argument("input", help="Mosaicked VIIRS radiance GeoTIFF")
    parser.add_argument("--deg", type=float, default=0.1, help="deg/cell (default 0.1)")
    parser.add_argument(
        "--scales",
        type=float,
        nargs="+",
        default=[1.0, 2.0, 3.0, 4.0, 5.0],
        help="Scale values to sweep (default 1.0 2.0 3.0 4.0 5.0)",
    )
    args = parser.parse_args()

    radiance = _load_radiance(args.input)
    for scale, dark_vals, bright_vals in run_sweep(radiance, args.deg, args.scales):
        print(f"\nscale={scale}")
        print("  Dark refs:  ", {n: b for n, b in dark_vals})
        print("  Bright refs:", {n: b for n, b in bright_vals})


if __name__ == "__main__":
    _cli_main()
