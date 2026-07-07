#!/usr/bin/env python3
"""
res/skyglow/build_bortle_bin.py — CLI entry point for producing bortle.bin.

This is a thin alias for the Step 4 command documented in
REAL_GRID_RUNBOOK.md ("Run the build script"). All raster I/O and grid
assembly logic lives in build_real_grid.py; this module exists so the
runbook's output artifact (bortle.bin) has a matching, discoverable script
name at the top of `python -m res.skyglow.build_bortle_bin --help`.

Usage:
    python -m res.skyglow.build_bortle_bin <mosaic.tif> \\
        --deg 0.1 --scale 3.5 --out bortle.bin

See REAL_GRID_RUNBOOK.md for the complete offline data-preparation procedure.
"""

from __future__ import annotations

from .build_real_grid import _cli_main

if __name__ == "__main__":
    _cli_main()
