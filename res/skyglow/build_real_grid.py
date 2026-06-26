#!/usr/bin/env python3
"""
res/skyglow/build_real_grid.py — assemble real VIIRS radiance into a Bortle grid.

Core functions are pure NumPy (no I/O, importable without rasterio/GDAL).
The CLI wrapper conditionally imports rasterio; if it is absent the module still
loads cleanly for unit tests.

Usage (Part B — offline data step, requires rasterio + a mosaicked VIIRS GeoTIFF):

    python -m res.skyglow.build_real_grid <mosaic.tif> \\
        --deg 0.1 --scale 3.5 --out bortle.bin

See REAL_GRID_RUNBOOK.md for the complete offline data-preparation procedure.
"""

from __future__ import annotations

import math
import pathlib
import sys

import numpy as np

from .convolve import convolve_radiance
from .brightness import artificial_to_bortle
from res.build_lightpollution_grid import write_grid


# ---------------------------------------------------------------------------
# Validation helpers
# ---------------------------------------------------------------------------

def _validate_global_deg(deg: float) -> None:
    """Raise ValueError if *deg* does not evenly tile the global 360×180 grid.

    Valid values must be finite, positive, and evenly divide both 360 and 180
    (e.g. 0.1, 0.25, 1.0, 2.0, 10.0).
    """
    if not np.isfinite(deg) or deg <= 0:
        raise ValueError(f"deg must be finite and positive, got {deg!r}")
    ncols = 360.0 / deg
    nrows = 180.0 / deg
    if not np.isclose(ncols, round(ncols), rtol=0.0, atol=1e-9) or \
       not np.isclose(nrows, round(nrows), rtol=0.0, atol=1e-9):
        raise ValueError(
            f"deg={deg!r} does not evenly divide both 360 and 180; "
            f"got {ncols} cols and {nrows} rows"
        )


# ---------------------------------------------------------------------------
# Core (pure-NumPy, I/O-free — unit-testable without GDAL)
# ---------------------------------------------------------------------------

def radiance_to_bortle_grid(
    radiance,
    deg_per_cell,
    lat_top=90.0,
    lon_left=-180.0,
    scale=1.0,
    params="average",
    cutoff_km=300.0,
    nodata_mask=None,
):
    """Convert an upward-radiance raster to a uint8 Bortle-class grid.

    Parameters
    ----------
    radiance:
        2-D array, row 0 = northernmost, col 0 = westernmost (PTSKLP01
        convention).  Values are mean upward radiance per unit area (as
        provided by VIIRS VNP46A4); each cell is weighted by cos(lat) before
        convolution so that the total emitted flux per cell is correct.
    deg_per_cell:
        Cell size in degrees (same for lat and lon in an equirectangular grid).
    lat_top:
        Latitude (degrees) of the north edge of row 0.  Default 90.0.
    lon_left:
        Longitude (degrees) of the west edge of col 0.  Default −180.0.
    scale:
        Calibration knob passed to ``artificial_to_bortle``.  Default 1.0.
        Increase to push dark sites toward higher Bortle; calibrate against
        known sites (see REAL_GRID_RUNBOOK.md).
    params:
        Atmospheric kernel preset or ``KernelParams`` (passed to
        ``convolve_radiance``).  Default ``"average"``.
    cutoff_km:
        Kernel cutoff radius in km.  Default 300.0.
    nodata_mask:
        Optional boolean array, same shape as ``radiance``.  Cells where
        ``nodata_mask`` is True are set to 0 (nodata) in the output.
        All other cells receive a Bortle value 1–9.  Default None (no nodata).

    Returns
    -------
    uint8 numpy array, same shape as ``radiance``.  Values 1–9 (Bortle class);
    0 only where ``nodata_mask`` is True.
    """
    rad = np.asarray(radiance, dtype=float)
    if rad.ndim != 2:
        raise ValueError("radiance must be a 2-D array")

    km_per_pixel = 111.32 * deg_per_cell  # N-S pixel size (constant)
    nrows = rad.shape[0]
    # Row-centre latitudes: row 0 centre = lat_top − 0.5·deg, decreasing south.
    lats = lat_top - (np.arange(nrows) + 0.5) * deg_per_cell

    # Weight each source cell by cos(lat) to convert mean radiance per unit area
    # (as VIIRS provides) to total emitted flux per cell.  Without this, cells at
    # high latitudes contribute the same raw radiance as equatorial cells even though
    # they cover a smaller ground area, overstating glow near the poles.
    area_weight = np.cos(np.radians(np.clip(lats, -85.0, 85.0)))[:, None]
    source_flux = rad * area_weight

    art = convolve_radiance(
        source_flux,
        km_per_pixel,
        params=params,
        latitudes=lats,
        cutoff_km=cutoff_km,
        wrap_longitude=True,
    )
    grid = artificial_to_bortle(art, scale=scale)   # uint8, values 1..9

    if nodata_mask is not None:
        mask = np.asarray(nodata_mask, dtype=bool)
        if mask.shape != grid.shape:
            raise ValueError(
                f"nodata_mask shape {mask.shape} must match radiance shape {grid.shape}"
            )
        grid = grid.copy()
        grid[mask] = 0

    return grid


def write_real_grid(
    path,
    radiance,
    deg_per_cell,
    scale=1.0,
    lat_top=90.0,
    lon_left=-180.0,
    params="average",
    cutoff_km=300.0,
    nodata_mask=None,
):
    """Build and write a non-placeholder PTSKLP01 v2 Bortle grid to *path*.

    Calls ``radiance_to_bortle_grid`` then ``write_grid(placeholder=False)``.
    ``placeholder=False`` sets flags bit 0 = 0, causing the app to treat this
    as a real data asset and activate the Auto-Bortle feature.
    """
    grid = radiance_to_bortle_grid(
        radiance,
        deg_per_cell,
        lat_top=lat_top,
        lon_left=lon_left,
        scale=scale,
        params=params,
        cutoff_km=cutoff_km,
        nodata_mask=nodata_mask,
    )
    rows, cols = grid.shape
    write_grid(
        path,
        rows, cols,
        lat_top, lon_left,
        deg_per_cell,
        grid.tobytes(),
        placeholder=False,
    )
    return grid


# ---------------------------------------------------------------------------
# Calibration helper
# ---------------------------------------------------------------------------

def sample_sites(grid, deg_per_cell, sites, lat_top=90.0, lon_left=-180.0):
    """Read Bortle values off *grid* at named geographic sites.

    Uses the exact PTSKLP01 index arithmetic so the sampled value equals what
    the app's ``bortleAt`` will return.

    Parameters
    ----------
    grid:
        2-D uint8 array as returned by ``radiance_to_bortle_grid``.
    deg_per_cell:
        Degrees per cell (same value used when building the grid).
    sites:
        Iterable of (name, lat, lon) tuples.  Coordinates in decimal degrees,
        positive north / positive east.
    lat_top:
        North edge of row 0 (default 90.0).
    lon_left:
        West edge of col 0 (default −180.0).

    Returns
    -------
    List of (name, bortle_int) pairs in the same order as *sites*.
    ``bortle_int`` is 0 for nodata cells or cells outside the grid bounds.
    """
    g = np.asarray(grid)
    nrows, ncols = g.shape
    results = []
    for name, lat, lon in sites:
        # Mirror of PTSKLP01 bortleAt / LightPollutionGrid.kt index math.
        lon_offset = ((lon - lon_left) % 360 + 360) % 360
        col = int(math.floor(lon_offset / deg_per_cell))
        row = int(math.floor((lat_top - lat) / deg_per_cell))
        if 0 <= row < nrows and 0 <= col < ncols:
            results.append((name, int(g[row, col])))
        else:
            results.append((name, 0))   # out-of-grid → treat as nodata
    return results


# ---------------------------------------------------------------------------
# CLI wrapper (rasterio required; import guarded so tests load without GDAL)
# ---------------------------------------------------------------------------

def _cli_main():
    import argparse

    try:
        import rasterio
    except ImportError:
        print(
            "rasterio is required for the CLI.  "
            "Install it with:  pip install rasterio",
            file=sys.stderr,
        )
        sys.exit(1)

    parser = argparse.ArgumentParser(
        description=(
            "Build a real Bortle grid from a mosaicked VIIRS radiance GeoTIFF.\n"
            "See REAL_GRID_RUNBOOK.md for the complete offline data procedure."
        )
    )
    parser.add_argument("input", help="Input GeoTIFF (mosaicked VIIRS radiance)")
    parser.add_argument("--deg", type=float, default=0.1,
                        help="Output resolution in deg/cell (default 0.1)")
    parser.add_argument("--scale", type=float, default=1.0,
                        help="Calibration scale knob (default 1.0)")
    parser.add_argument("--cutoff", type=float, default=300.0,
                        help="Kernel cutoff radius in km (default 300)")
    parser.add_argument("--params", default="average",
                        help="Atmospheric kernel preset: clear/average/hazy (default average)")
    parser.add_argument("--out", default="bortle.bin",
                        help="Output path (default bortle.bin)")
    args = parser.parse_args()

    deg = args.deg
    _validate_global_deg(deg)
    lat_top = 90.0
    lon_left = -180.0

    # Read the input GeoTIFF and aggregate to the working resolution.
    with rasterio.open(args.input) as src:
        # Target transform: equirectangular, row0=north, col0=west.
        ncols_out = int(round(360.0 / deg))
        nrows_out = int(round(180.0 / deg))
        from rasterio.transform import from_bounds
        out_transform = from_bounds(-180, -90, 180, 90, ncols_out, nrows_out)

        # Reproject + aggregate (mean resampling) into the target grid.
        from rasterio.warp import reproject, Resampling as RS
        data = np.zeros((nrows_out, ncols_out), dtype=np.float64)
        reproject(
            source=rasterio.band(src, 1),
            destination=data,
            src_transform=src.transform,
            src_crs=src.crs,
            dst_transform=out_transform,
            dst_crs="EPSG:4326",
            resampling=RS.average,
            src_nodata=src.nodata,
            dst_nodata=0.0,
        )

    out_path = pathlib.Path(args.out)
    write_real_grid(
        out_path,
        data,
        deg,
        scale=args.scale,
        lat_top=lat_top,
        lon_left=lon_left,
        params=args.params,
        cutoff_km=args.cutoff,
    )
    print(f"Done.  Output: {out_path}")


if __name__ == "__main__":
    _cli_main()
