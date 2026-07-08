"""
Tests for calibrate_scale.py — the runnable extraction of the Step 5
calibration sweep documented in REAL_GRID_RUNBOOK.md.

run_sweep() tests use synthetic radiance so no rasterio / GeoTIFF is needed.
The _load_radiance() nodata test requires rasterio and is skipped if it is
not installed.
"""

import numpy as np
import pytest

from res.skyglow.calibrate_scale import BRIGHT_REFS, DARK_REFS, _load_radiance, run_sweep

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


def test_load_radiance_fills_nodata_with_zero(tmp_path):
    """_load_radiance() must not feed nodata sentinels (e.g. 65535) as radiance."""
    rasterio = pytest.importorskip("rasterio")
    from rasterio.transform import from_origin

    nodata_value = 65535.0
    data = np.array([[10.0, nodata_value], [30.0, 40.0]], dtype=np.float32)
    transform = from_origin(-180, 90, 1.0, 1.0)
    profile = dict(
        driver="GTiff",
        height=2,
        width=2,
        count=1,
        dtype="float32",
        crs="EPSG:4326",
        transform=transform,
        nodata=nodata_value,
    )

    tif_path = tmp_path / "nodata.tif"
    with rasterio.open(tif_path, "w", **profile) as dst:
        dst.write(data, 1)

    radiance = _load_radiance(tif_path)

    assert radiance[0, 1] == 0.0, "nodata sentinel must be filled with 0.0, not passed through"
    assert radiance[0, 0] == 10.0
    assert radiance[1, 0] == 30.0
    assert radiance[1, 1] == 40.0
