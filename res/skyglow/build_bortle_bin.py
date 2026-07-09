#!/usr/bin/env python3
"""
res/skyglow/build_bortle_bin.py — production v3 (SQM) grid builder, tile h06v03
(southern Alberta + Rockies; contains all 12 calibration points).

Canonical build entry point. Replaces the earlier thin v2 alias. Produces a
real non-placeholder PTSKLP01 **v3** asset (continuous SQM payload) that pairs
with the v3 `LightPollutionGrid.sqmAt()` decoder.

Locked architecture (from the resolution experiments — see REAL_GRID_RUNBOOK.md):
  native 0.0042deg VIIRS HDF5 -> cos-lat weight -> convolve 300 km
  -> downsample glow to 0.0125deg -> refit scale on the 12-point Alberta
  atlas-SQM series -> SQM -> PTSKLP01 v3 uint8 -> bortle.bin

Run from the repo root as a module:

    PYTHONUTF8=1 python -m res.skyglow.build_bortle_bin viirs_global out/bortle.bin 2025

Args: <viirs_dir> <out_path> [year].  `year` (e.g. 2025) pins the VNP46A4 data
year; omit to take the newest tile present by filename.

Regional (single tile h06v03). Multi-tile / global coverage is future work
(roadmap L-2); regional download + graceful null handling is L-4.
"""
from __future__ import annotations
import sys, glob, math, pathlib, struct, zlib
import numpy as np
import h5py

from .convolve import convolve_radiance
from .brightness import combined_sky_brightness_mag, bortle_from_sky_brightness_mag

LAYER = "AllAngle_Composite_Snow_Free"
NATIVE = 10.0 / 2400                 # 0.0041667 deg (VNP46A4 15-arcsec grid)
TILE_H, TILE_V = 6, 3
LAT_TOP, LON_LEFT = 90 - TILE_V * 10, -180 + TILE_H * 10   # 60, -120
OUT_FACTOR = 3                       # native -> 0.0125 deg
NAT, ZP_CUTOFF = 21.9, 21.7          # natural sky mag; scale-regime cutoff for the LSQ fit
SQM_MIN, SQM_STEP = 16.0, 0.1        # MUST match LightPollutionGrid.kt (SQM_MIN / SQM_STEP)
MAGIC, VERSION = b"PTSKLP01", 3      # MUST match EXPECTED_VERSION in the decoder

POINTS = [  # (name, lat, lon, atlas_SQM) — scale fit + verification.
    # atlas_SQM from lightpollutionmap.app (VIIRS-consistent atlas product);
    # fit is against atlas SQM, NOT atlas Bortle (the two diverge at the dark end).
    ("Calgary (Downtown)", 51.044848, -114.061432, 17.38),
    ("Kneehill/Acme",      51.496862, -113.512180, 18.21),
    ("Edmonton",           53.4316,   -113.6137,   18.07),
    ("Lacombe",            52.463174, -113.723581, 19.19),
    ("Wetaskiwin",         52.976969, -113.379765, 19.58),
    ("Banff",              51.175,    -115.5638,   20.53),
    ("Millet",             53.094230, -113.470917, 20.55),
    ("Lake Louise",        51.425651, -116.178274, 21.23),
    ("Nordegg",            52.494069, -115.886879, 21.84),
    ("Jasper",             52.998256, -118.059769, 21.94),
    ("Columbia Icefield",  51.758490, -116.523743, 21.99),
    ("Kicking Horse BC",   51.945958, -117.100525, 22.00),
]


def find_layer(hf):
    """Locate the AllAngle_Composite_Snow_Free dataset path inside a VNP46A4 file."""
    hits = []
    hf.visititems(lambda n, o: hits.append(n)
                  if isinstance(o, h5py.Dataset) and LAYER in n else None)
    return hits[0] if hits else None


def encode_v3(sqm_grid, lat_top, lon_left, deg):
    """Encode an SQM (mag/arcsec^2) grid into a PTSKLP01 v3 byte string.

    Byte semantics (must match the Kotlin decoder):
        0            -> nodata
        1..255       -> SQM = SQM_MIN + (byte - 1) * SQM_STEP
    Here every cell gets a value (regional land tile); nodata (0) is not emitted.

    Returns the full asset bytes (52-byte header + zlib payload).
    """
    grid = np.asarray(sqm_grid, dtype=float)
    nr, nc = grid.shape
    byte = np.clip(np.rint((grid - SQM_MIN) / SQM_STEP).astype(np.int32) + 1,
                   1, 255).astype(np.uint8)
    payload = zlib.compress(byte.tobytes(), 9)
    head = struct.pack("<8s i i i d d d i i", MAGIC, VERSION, nr, nc,
                       float(lat_top), float(lon_left), float(deg), 0, len(payload))
    return head + payload


def _decode_v3(raw):
    """Inverse of encode_v3 (used by the round-trip self-check and tests)."""
    (mg, ver, rr, cc, lt, ll, dd, fl, cl) = struct.unpack("<8s i i i d d d i i", raw[:52])
    grid = np.frombuffer(zlib.decompress(raw[52:52 + cl]), dtype=np.uint8).reshape(rr, cc)
    return dict(magic=mg, version=ver, rows=rr, cols=cc, lat_top=lt,
                lon_left=ll, deg=dd, flags=fl), grid


def main(argv):
    vdir = argv[1] if len(argv) > 1 else "viirs_global"
    outpath = pathlib.Path(argv[2] if len(argv) > 2 else "bortle.bin")

    tile = f"h{TILE_H:02d}v{TILE_V:02d}"
    cands = sorted(set(glob.glob(f"{vdir}/*{tile}*.h5")
                       + glob.glob(f"{vdir}/**/*{tile}*.h5", recursive=True)))
    cands = [f for f in cands if pathlib.Path(f).stat().st_size > 0]   # skip failed/empty downloads
    if not cands:
        print(f"no non-empty tile *{tile}*.h5 in {vdir}"); return 1
    year = argv[3] if len(argv) > 3 else None            # optional: pin a data year
    pick = [f for f in cands if f".A{year}001." in f] if year else cands
    f = sorted(pick or cands)[-1]                         # newest by filename (A2025 > A2023)
    print(f"tile {f}")
    print(f"  years available: {[pathlib.Path(x).name.split('.')[1] for x in cands]}")

    with h5py.File(f, "r") as hf:
        ds = hf[find_layer(hf)]
        fv = float(np.asarray(ds.attrs.get("_FillValue", -999.9)).ravel()[0])
        sf = np.asarray(ds.attrs.get("scale_factor", 1.0)).ravel()
        sf = float(sf[0]) if sf.size else 1.0
        fine = ds[:].astype(float)
    fine = np.where(fine <= fv + 1e-3, 0.0, fine * sf)   # fill -> 0 (no light)

    # --- convolve at native (the heavy step; ~same as test_resolution native) ---
    lats_n = LAT_TOP - (np.arange(2400) + 0.5) * NATIVE
    aw = np.cos(np.radians(np.clip(lats_n, -85, 85)))[:, None]
    print("convolving native 2400x2400 (cutoff 300 km, regional) ...")
    art = convolve_radiance(fine * aw, 111.32 * NATIVE, params="average",
                            latitudes=lats_n, cutoff_km=300.0, wrap_longitude=False)

    # --- downsample convolved glow (linear) to 0.0125 deg ---
    F = OUT_FACTOR
    n = (2400 // F) * F
    art_out = art[:n, :n].reshape(n // F, F, n // F, F).mean(axis=(1, 3))
    deg = F * NATIVE
    nr = art_out.shape[0]
    print(f"output grid {art_out.shape} @ {deg:.5f} deg ({deg*111.32:.2f} km)")

    # --- refit scale on the stored (downsampled) grid at the calib points ---
    recs = []
    for name, lat, lon, sqm in POINTS:
        r, c = int((LAT_TOP - lat) / deg), int((lon - LON_LEFT) / deg)
        A = float(art_out[r, c]); L = 10.0 ** ((NAT - sqm) / 2.5) - 1.0
        recs.append((name, sqm, r, c, A, L))
    fit = [(A, L) for _, sqm, _, _, A, L in recs if sqm < ZP_CUTOFF]
    scale = sum(A * L for A, L in fit) / sum(A * A for A, L in fit)
    print(f"scale (LSQ, {len(fit)} scale-regime pts) = {scale:.6g}\n")

    # --- SQM grid + verification table ---
    sqm_grid = combined_sky_brightness_mag(art_out, scale=scale)
    bor_grid = bortle_from_sky_brightness_mag(sqm_grid)
    hdr = f"{'point':<20}{'aSQM':>7}{'mSQM':>7}{'resid':>7}{'mB':>4}"
    print(hdr); print("-" * len(hdr))
    md = []
    for name, sqm, r, c, A, L in recs:
        m, b = float(sqm_grid[r, c]), int(bor_grid[r, c])
        print(f"{name:<20}{sqm:>7.2f}{m:>7.2f}{m-sqm:>+7.2f}{b:>4}")
        if name in ("Lacombe", "Wetaskiwin", "Banff"):
            md.append(m - sqm)
    print(f"\nmid-dark resid mean = {np.mean(md):+.2f}  (expect ~ 0 at native input)")

    # --- encode SQM -> uint8 (v3) and write ---
    asset = encode_v3(sqm_grid, LAT_TOP, LON_LEFT, deg)
    outpath.parent.mkdir(parents=True, exist_ok=True)
    outpath.write_bytes(asset)
    print(f"\nwrote {outpath}  ({len(asset)} bytes)")
    print(f"  header: v{VERSION} {nr}x{nr} latTop={LAT_TOP} lonLeft={LON_LEFT} deg={deg:.5f}")

    # --- round-trip self-check: decode our own file at the calib points ---
    meta, dec = _decode_v3(outpath.read_bytes())
    assert meta["magic"] == MAGIC and meta["version"] == VERSION, "header mismatch"
    assert meta["rows"] == nr and meta["cols"] == nr, "dim mismatch"
    worst = 0.0
    for name, sqm, r, c, A, L in recs:
        b = int(dec[r, c])
        sqm_dec = SQM_MIN + (b - 1) * SQM_STEP if b > 0 else float("nan")
        worst = max(worst, abs(sqm_dec - float(sqm_grid[r, c])))
    print(f"  round-trip OK: max decode error at points = {worst:.3f} mag "
          f"(quantization <= {SQM_STEP/2})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
