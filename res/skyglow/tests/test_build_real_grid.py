"""
Tests for build_real_grid.py — the A1+A2 bridge that produces real bortle.bin.

All tests use synthetic radiance so no Earthdata credentials or GDAL are needed.
"""

import math
import pathlib
import struct
import zlib

import numpy as np
import pytest

from res.skyglow.build_real_grid import (
    radiance_to_bortle_grid,
    sample_sites,
    write_real_grid,
    _validate_global_deg,
)
from res.build_lightpollution_grid import MAGIC, write_grid


# ---------------------------------------------------------------------------
# PTSKLP01 v2 parser — Python mirror of the Kotlin reader
# ---------------------------------------------------------------------------

_HEADER_FMT = "<8s i i i d d d i i"
_HEADER_SIZE = struct.calcsize(_HEADER_FMT)


def _parse_ptsklp01(data: bytes) -> dict:
    """Parse a PTSKLP01 v2 binary blob.

    Returns keys: magic, version, rows, cols, lat_top, lon_left, deg,
    flags, is_placeholder, payload (2-D uint8 array).
    """
    (magic, version, rows, cols, lat_top, lon_left,
     deg, flags, comp_len) = struct.unpack_from(_HEADER_FMT, data)
    assert magic == MAGIC, f"bad magic: {magic!r}"
    assert version == 2, f"unexpected version: {version}"
    payload_bytes = zlib.decompress(data[_HEADER_SIZE:_HEADER_SIZE + comp_len])
    assert len(payload_bytes) == rows * cols, "payload length mismatch"
    payload = np.frombuffer(payload_bytes, dtype=np.uint8).reshape(rows, cols)
    return {
        "magic": magic,
        "version": version,
        "rows": rows,
        "cols": cols,
        "lat_top": lat_top,
        "lon_left": lon_left,
        "deg": deg,
        "flags": flags,
        "is_placeholder": bool(flags & 1),
        "payload": payload,
    }


# ---------------------------------------------------------------------------
# Radiance grids used by tests
# ---------------------------------------------------------------------------

# Structural tests (nodata mask, dtype) — coarse grid, Bortle values not checked.
_DEG_COARSE = 10.0
_NROWS_COARSE = 18
_NCOLS_COARSE = 36


def _coarse_radiance(city_value=1.0):
    """18×36 at 10°/cell — kernel degenerate but grid structure is valid."""
    r = np.zeros((_NROWS_COARSE, _NCOLS_COARSE), dtype=float)
    r[9, 18] = city_value
    return r


# Physics tests (Bortle levels, monotonicity) — 1°/cell so the 300 km kernel
# spans ~2 pixels.  City radiance is deliberately extreme (1e9) so scale=1.0
# pushes the city to Bortle 9 without any calibration knob gymnastics.
_DEG_FINE = 1.0
_CITY_R_FINE, _CITY_C_FINE = 90, 180   # equator centre of 180×360 global grid
_CITY_VALUE_FINE = 1e7   # gives Bortle ~4 at scale=1.0, ~9 at scale=100


def _fine_radiance():
    """180×360 at 1°/cell — kernel works, city Bortle deterministic."""
    r = np.zeros((180, 360), dtype=float)
    r[_CITY_R_FINE, _CITY_C_FINE] = _CITY_VALUE_FINE
    return r


# ---------------------------------------------------------------------------
# Test 1 — end-to-end round-trip through the PTSKLP01 reader
# ---------------------------------------------------------------------------

def test_end_to_end_roundtrip(tmp_path):
    """radiance_to_bortle_grid -> write_grid(placeholder=False) -> parse.

    Validates:
    - Clean parse with PTSKLP01 magic and version 2.
    - is_placeholder == False (flags == 0).
    - City pixel has higher Bortle than a distant dark pixel.
    - sample_sites returns the same value as direct payload indexing.
    """
    deg = _DEG_FINE
    nrows, ncols = 180, 360
    city_r, city_c = _CITY_R_FINE, _CITY_C_FINE

    radiance = _fine_radiance()
    grid = radiance_to_bortle_grid(radiance, deg)
    assert grid.shape == (nrows, ncols)
    assert grid.dtype == np.uint8

    # Write with placeholder=False (the critical flag).
    out_path = tmp_path / "bortle.bin"
    write_grid(
        out_path,
        nrows, ncols,
        90.0, -180.0, deg,
        grid.tobytes(),
        placeholder=False,
    )

    raw = out_path.read_bytes()
    parsed = _parse_ptsklp01(raw)

    assert parsed["is_placeholder"] is False, "flags bit0 must be 0 for real grid"
    assert parsed["rows"] == nrows
    assert parsed["cols"] == ncols
    assert parsed["lat_top"] == pytest.approx(90.0)
    assert parsed["lon_left"] == pytest.approx(-180.0)
    assert parsed["deg"] == pytest.approx(deg)

    payload = parsed["payload"]

    city_bortle = int(payload[city_r, city_c])
    far_r, far_c = 0, 0   # north pole corner, far from equator city
    dark_bortle = int(payload[far_r, far_c])

    assert 1 <= city_bortle <= 9, f"city Bortle out of range: {city_bortle}"
    assert 1 <= dark_bortle <= 9, f"dark Bortle out of range: {dark_bortle}"
    assert city_bortle > dark_bortle, (
        f"city ({city_bortle}) should be higher Bortle than dark corner ({dark_bortle})"
    )

    # sample_sites must match direct payload indexing (PTSKLP01 math equivalence).
    # City: lat≈0°S lon≈0°E; dark: lat≈85°N lon≈175°W.
    lat_top, lon_left = 90.0, -180.0
    sites = [("city", -0.5, 0.5), ("dark", 85.0, -175.0)]
    samples = dict(sample_sites(grid, deg, sites, lat_top=lat_top, lon_left=lon_left))

    for name, lat, lon in sites:
        lon_off = ((lon - lon_left) % 360 + 360) % 360
        col = int(math.floor(lon_off / deg))
        row = int(math.floor((lat_top - lat) / deg))
        assert samples[name] == int(payload[row, col]), (
            f"{name}: sample_sites={samples[name]}, payload={payload[row,col]}"
        )


# ---------------------------------------------------------------------------
# Test 2 — write_real_grid sets placeholder=False (flags == 0)
# ---------------------------------------------------------------------------

def test_write_real_grid_placeholder_false(tmp_path):
    """write_real_grid must write flags == 0 (non-placeholder)."""
    out = tmp_path / "real.bin"
    write_real_grid(out, _coarse_radiance(), _DEG_COARSE)
    parsed = _parse_ptsklp01(out.read_bytes())
    assert parsed["is_placeholder"] is False
    assert parsed["flags"] == 0


# ---------------------------------------------------------------------------
# Test 3 — scale monotonicity
# ---------------------------------------------------------------------------

def test_scale_monotonicity():
    """Higher scale => same-or-higher Bortle at every pixel.

    Uses the 1°/cell grid so the kernel is nonzero and at least the city pixel
    demonstrates a real increase.
    """
    radiance = _fine_radiance()
    grid_lo = radiance_to_bortle_grid(radiance, _DEG_FINE, scale=1.0)
    grid_hi = radiance_to_bortle_grid(radiance, _DEG_FINE, scale=100.0)

    assert np.all(grid_hi >= grid_lo), (
        "higher scale must not decrease any Bortle value"
    )
    # The city pixel must actually get brighter with more scale.
    assert grid_hi[_CITY_R_FINE, _CITY_C_FINE] > grid_lo[_CITY_R_FINE, _CITY_C_FINE]


# ---------------------------------------------------------------------------
# Test 4 — nodata mask
# ---------------------------------------------------------------------------

def test_nodata_mask_wrong_shape_raises():
    """A nodata_mask with the wrong shape raises ValueError with a useful message."""
    radiance = _coarse_radiance()          # shape (18, 36)
    bad_mask = np.zeros((10, 20), dtype=bool)
    with pytest.raises(ValueError, match="nodata_mask shape"):
        radiance_to_bortle_grid(radiance, _DEG_COARSE, nodata_mask=bad_mask)


def test_nodata_mask():
    """Cells in nodata_mask become 0; all other cells stay 1–9."""
    radiance = _coarse_radiance()
    mask = np.zeros(radiance.shape, dtype=bool)
    mask[0, 0] = True
    mask[0, 1] = True

    grid = radiance_to_bortle_grid(radiance, _DEG_COARSE, nodata_mask=mask)

    assert int(grid[0, 0]) == 0
    assert int(grid[0, 1]) == 0
    unmasked = grid[~mask]
    assert np.all(unmasked >= 1)
    assert np.all(unmasked <= 9)


def test_no_nodata_without_mask():
    """Without a nodata_mask every cell is in 1–9 (no zeros)."""
    grid = radiance_to_bortle_grid(_coarse_radiance(), _DEG_COARSE)
    assert np.all(grid >= 1)
    assert np.all(grid <= 9)


# ---------------------------------------------------------------------------
# Test 5 — sample_sites index math
# ---------------------------------------------------------------------------

def test_sample_sites_index_math():
    """sample_sites uses the exact PTSKLP01 bortleAt index arithmetic."""
    deg = 10.0
    nrows, ncols = 18, 36
    # Grid with known per-cell Bortle (1 + row_index, capped at 9).
    row_idx = np.arange(nrows, dtype=np.uint8)[:, None] * np.ones(ncols, dtype=np.uint8)
    grid = np.clip(1 + row_idx, 1, 9).astype(np.uint8)

    lat_top, lon_left = 90.0, -180.0
    sites = [
        ("n_pole",    89.0,   0.0),
        ("equator",    0.0,   0.0),
        ("s_pole",   -89.0,   0.0),
        ("dateline",   0.0, 179.0),
        ("west",       0.0, -179.0),
    ]
    results = dict(sample_sites(grid, deg, sites, lat_top=lat_top, lon_left=lon_left))

    for name, lat, lon in sites:
        lon_off = ((lon - lon_left) % 360 + 360) % 360
        col = int(math.floor(lon_off / deg))
        row = int(math.floor((lat_top - lat) / deg))
        expected = int(grid[row, col])
        assert results[name] == expected, (
            f"{name}: expected {expected}, got {results[name]}"
        )


def test_sample_sites_out_of_bounds():
    """Out-of-grid coordinates return 0 (nodata)."""
    grid = np.ones((18, 36), dtype=np.uint8) * 3
    results = dict(sample_sites(grid, 10.0, [("oob", 95.0, 0.0)]))
    assert results["oob"] == 0


# ---------------------------------------------------------------------------
# Test 6 — output dtype and range
# ---------------------------------------------------------------------------

def test_output_dtype_and_range():
    """radiance_to_bortle_grid always returns uint8 with values 1–9."""
    radiance = np.random.default_rng(42).uniform(0, 1e3, size=(18, 36))
    grid = radiance_to_bortle_grid(radiance, _DEG_COARSE)
    assert grid.dtype == np.uint8
    assert grid.ndim == 2
    assert np.all(grid >= 1)
    assert np.all(grid <= 9)


# ---------------------------------------------------------------------------
# Test 7 — cos(lat) source-area weighting
# ---------------------------------------------------------------------------

def test_area_weight_high_lat_not_brighter_than_equator():
    """A uniform radiance field should not produce higher Bortle near the poles.

    Without cos(lat) area weighting, high-latitude cells each contribute the
    same raw radiance as equatorial cells despite covering less physical area,
    causing spurious over-brightening toward the poles.  With the fix, a
    uniform-radiance field must give equal or lower Bortle at 60° N compared to
    the equator (cos(60°)=0.5 → half the source flux per cell).
    """
    # 1°/cell global grid so the kernel is non-degenerate.
    nrows, ncols = 180, 360
    radiance = np.ones((nrows, ncols), dtype=float)

    grid = radiance_to_bortle_grid(radiance, _DEG_FINE, scale=1.0)

    # Row indices (lat_top=90, deg=1):  row = floor((90 - lat) / 1)
    row_equator = 90   # lat=0°: row=floor((90-0)/1)=90 → centre at -0.5°
    row_60n = 30       # lat=60°: row=floor((90-60)/1)=30 → centre at 59.5°

    bortle_equator = int(grid[row_equator, ncols // 2])
    bortle_60n = int(grid[row_60n, ncols // 2])

    assert bortle_60n <= bortle_equator, (
        f"High-latitude Bortle ({bortle_60n}) should be <= equator ({bortle_equator}) "
        f"for a uniform radiance field with area weighting"
    )


# ---------------------------------------------------------------------------
# Test 8 — _validate_global_deg
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("deg", [0.1, 0.25, 1.0, 2.0, 10.0])
def test_validate_global_deg_valid(deg):
    """Valid deg values must not raise."""
    _validate_global_deg(deg)   # should not raise


@pytest.mark.parametrize("deg", [
    7.0,         # 360/7 is not an integer
    0.0,         # zero
    -1.0,        # negative
    float("nan"),
    float("inf"),
])
def test_validate_global_deg_invalid(deg):
    """Invalid deg values must raise ValueError."""
    with pytest.raises(ValueError):
        _validate_global_deg(deg)


def test_validate_global_deg_non_divisor():
    """7.0 does not evenly divide 360 (360/7 ≈ 51.43)."""
    with pytest.raises(ValueError, match="does not evenly divide"):
        _validate_global_deg(7.0)
