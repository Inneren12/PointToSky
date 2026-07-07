"""
Tests for calibrate_scale.py — the runnable extraction of the Step 5
calibration sweep documented in REAL_GRID_RUNBOOK.md.

Uses synthetic radiance so no rasterio / GeoTIFF is needed.
"""

import numpy as np

from res.skyglow.calibrate_scale import BRIGHT_REFS, DARK_REFS, run_sweep

_DEG = 1.0
_CITY_R, _CITY_C = 49, 106   # ~ Manhattan (40.78N, -73.97E) on a 180x360 grid


def _radiance_with_hotspot():
    r = np.zeros((180, 360), dtype=float)
    r[_CITY_R, _CITY_C] = 1e7   # gives Bortle ~4 at scale=1.0, ~9 at scale=100
    return r


def test_run_sweep_yields_one_entry_per_scale():
    radiance = _radiance_with_hotspot()
    scales = [1.0, 2.0, 3.0]
    results = list(run_sweep(radiance, _DEG, scales))

    assert [scale for scale, _, _ in results] == scales
    for _, dark_vals, bright_vals in results:
        assert [name for name, _ in dark_vals] == [name for name, _, _ in DARK_REFS]
        assert [name for name, _ in bright_vals] == [name for name, _, _ in BRIGHT_REFS]


def test_run_sweep_bright_site_non_decreasing_with_scale():
    radiance = _radiance_with_hotspot()
    scales = [1.0, 5.0, 20.0]
    results = list(run_sweep(radiance, _DEG, scales))

    manhattan_by_scale = [
        dict(bright_vals)["Manhattan"] for _, _, bright_vals in results
    ]
    assert manhattan_by_scale == sorted(manhattan_by_scale)
    assert manhattan_by_scale[-1] > manhattan_by_scale[0]
