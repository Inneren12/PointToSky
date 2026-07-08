# Real Bortle Grid — Data Runbook (Part B)

This document describes the offline data-preparation procedure that produces a
real, non-placeholder `bortle.bin` from NASA VIIRS Black Marble data.  It is
the counterpart to the Python code in `build_real_grid.py` (Part A).

**Prerequisites:** Earthdata account, Python environment with
`earthaccess rasterio numpy scipy`, and a machine with enough disk space for the
raw tiles (≈15 GB for the global VNP46A4 annual composite).

**Generated artifacts stay local.** Downloaded/merged VIIRS data and build
output are large and must not be committed. Keep them under `out/` and
`viirs_global/` (both gitignored), and note that raw tiles (`*.h5`) and any
cached diagnostic canvases (`*.canvas.npy`) are also gitignored repo-wide.

---

## Step 1 — Earthdata login

Register at <https://urs.earthdata.nasa.gov> if you do not already have an
account.

```bash
pip install earthaccess rasterio numpy scipy
```

Run the login wizard once to cache credentials:

```python
import earthaccess
earthaccess.login(strategy="interactive")   # prompts once; stores ~/.netrc
```

---

## Step 2 — Download VNP46A4 tiles

VNP46A4 is the VIIRS/Suomi-NPP Black Marble Annual Moonlight-and-Cloud-Free
Composite.  Use the `*_Composite_Snow_Free` radiance layer (nanowatts per
cm² per steradian).  Tiles are 10°×10° and cover land only; absent ocean tiles
are treated as zero local upward radiance; coastal glow is still introduced
by convolution from nearby land pixels.

```python
import earthaccess, pathlib

results = earthaccess.search_data(
    short_name="VNP46A4",
    temporal=("2023-01", "2023-12"),   # pick a recent full year
    count=10000,
)
# Optionally filter by bounding box for a regional run:
#   bounding_box=(-180, -90, 180, 90)   # global
download_dir = pathlib.Path("viirs_tiles")
download_dir.mkdir(exist_ok=True)
earthaccess.download(results, local_path=str(download_dir))
```

Each tile is an HDF5 file.  The key dataset is
`/HDFEOS/GRIDS/VNP_Grid_DNB/Data Fields/AllAngle_Composite_Snow_Free`.

---

## Step 3 — Mosaic and aggregate to working resolution

Convert tiles to GeoTIFF, merge into a single global raster, and aggregate
(mean) to the working resolution.  The output must follow the PTSKLP01
convention: **row 0 = north** (lat\_top = +90), **col 0 = west**
(lon\_left = −180).

```bash
# Extract the snow-free radiance band from every tile and convert to GeoTIFF.
for f in viirs_tiles/*.h5; do
    gdal_translate NETCDF:"$f":AllAngle_Composite_Snow_Free \
        "${f%.h5}.tif" -a_srs EPSG:4326
done

# Merge all tiles into one global raster.
gdal_merge.py -o viirs_global_raw.tif -n 65535 -a_nodata 65535 \
    viirs_tiles/*.tif

# Aggregate (mean) to 0.1°/cell equirectangular, north-up.
# Adjust --tr to change the output resolution knob (see Resolution note below).
gdalwarp -r average -tr 0.1 0.1 \
    -te -180 -90 180 90 \
    -t_srs EPSG:4326 \
    viirs_global_raw.tif viirs_mosaic_0.1deg.tif
```

**Resolution knob.**  The suggested default is **0.1°/cell** (1800×3600 grid).
The glow kernel is smooth over tens of km so 0.1° captures the halo well, and
zlib compresses the mostly-uniform ocean/rural world to a small asset.  Finer
(0.05°) = sharper cities but a larger file; coarser (0.25°) = smaller file.

---

## Step 4 — Run the build script

```bash
python -m res.skyglow.build_real_grid viirs_mosaic_0.1deg.tif \
    --deg 0.1 --scale S --out out/bortle.bin
```

`build_bortle_bin.py` is a thin CLI alias for the same command, named after
the output artifact:

```bash
python -m res.skyglow.build_bortle_bin viirs_mosaic_0.1deg.tif \
    --deg 0.1 --scale S --out out/bortle.bin
```

Replace `S` with your initial scale estimate.  Start with `scale=3.0` and
iterate (see Step 5).

---

## Step 5 — Calibrate `scale`

`scale` is the single calibration knob introduced in A2.  It converts the
A1 convolution's arbitrary relative units into the natural-sky-background
unit system.  Higher scale → brighter/higher Bortle everywhere.

Run the calibration helper against a set of known dark and bright sites:

```python
from res.skyglow.build_real_grid import radiance_to_bortle_grid, sample_sites
import numpy as np, rasterio

with rasterio.open("viirs_mosaic_0.1deg.tif") as src:
    radiance = src.read(1).astype(float)
    # Ensure north-up orientation (rasterio returns row 0 = north if geotiff
    # has a negative y-pixel size, which gdal_merge produces by default).

dark_refs = [
    ("Cherry Springs SP",  41.66, -77.82),   # target Bortle 2
    ("Death Valley",       36.50, -117.10),  # target Bortle 1–2
    ("NamibRand",         -25.00,  16.00),   # target Bortle 1
]
bright_refs = [
    ("Manhattan",          40.78, -73.97),   # target Bortle 8–9
    ("Tokyo centre",       35.68, 139.76),   # target Bortle 9
    ("Central London",     51.51,  -0.13),   # target Bortle 8–9
]

for scale in [1.0, 2.0, 3.0, 4.0, 5.0]:
    grid = radiance_to_bortle_grid(radiance, deg_per_cell=0.1, scale=scale)
    dark_vals  = sample_sites(grid, 0.1, dark_refs)
    bright_vals = sample_sites(grid, 0.1, bright_refs)
    print(f"\nscale={scale}")
    print("  Dark refs:  ", {n: b for n, b in dark_vals})
    print("  Bright refs:", {n: b for n, b in bright_vals})
```

`calibrate_scale.py` runs the same sweep as a script, so this no longer needs
to be typed out by hand:

```bash
python -m res.skyglow.calibrate_scale viirs_mosaic_0.1deg.tif \
    --deg 0.1 --scales 1.0 2.0 3.0 4.0 5.0
```

**Acceptance criterion (within ±1 Bortle class at each reference):**

| Site               | Target    |
|--------------------|-----------|
| Cherry Springs SP  | B2        |
| Death Valley       | B1–2      |
| NamibRand          | B1        |
| Manhattan          | B8–9      |
| Tokyo centre       | B9        |
| Central London     | B8–9      |

Scale ↑ brightens everything (cities approach 9, but dark refs creep up).
Scale ↓ keeps dark refs at 1 (but cities may not reach 9).  Find the value
that satisfies both simultaneously.

---

## Step 6 — Validate on a wider spot-check

Run the calibration helper on ≥ 12 geographically diverse sites spanning the
full Bortle range.  Record the chosen `scale` and the validation table in the
commit message.

---

## Step 7 — Commit the real asset

```bash
cp out/bortle.bin mobile/src/main/assets/lightpollution/bortle.bin
git add mobile/src/main/assets/lightpollution/bortle.bin
git commit -m "feat(bortle): ship real VIIRS-derived Bortle grid (scale=S)

Replaces the synthetic placeholder with a calibrated non-placeholder grid.
placeholder=False (flags=0) activates the Auto-Bortle feature: AR + sky-map
show the Auto/Manual toggle and auto-detected Bortle drives visibility filter.

Validation (scale=S, VNP46A4 2023 composite, 0.1°/cell):
Cherry Springs SP: B2, Death Valley: B1, NamibRand: B1
Manhattan: B9, Tokyo: B9, London: B8"
```

Because `placeholder=False` → `flags = 0` → `LightPollutionGrid.isPlaceholder
= false` → `LightPollutionProvider.realGrid` becomes non-null →
`lightPollutionAvailable = true`, the AR overlay and sky-map Auto/Manual
toggle self-activate from the asset alone.  **No app or Kotlin code changes
are needed.**

---

## Step 8 — Attribution (NOTICE.md)

Add the following attribution block to `NOTICE.md`:

```
## Data Sources

### NASA VIIRS Black Marble (VNP46A4)
Product: VNP46A4 Annual Moonlight-and-Cloud-Free Night Lights Composite
Provider: NASA EOSDIS / LAADS DAAC
DOI: https://doi.org/10.5067/VIIRS/VNP46A4.001
Description: VIIRS/Suomi-NPP Black Marble annual composite using the
  AllAngle_Composite_Snow_Free radiance layer.

VNP46A4 is a U.S. Government work in the public domain (NASA data policy:
https://science.nasa.gov/earth-science/earth-science-data/data-information-policy/).
It is free for any use including commercial; attribution is courteous rather
than legally required. VIIRS was chosen over the Falchi et al. (2016) atlas
specifically because the atlas is CC BY-NC-SA, whereas VIIRS imposes no
commercial restriction.

### Skyglow propagation model
The kernel in res/skyglow/kernel.py is a clean-room implementation derived from:

- Garstang, R.H. 1986, "Model for Artificial Night-Sky Illumination",
  PASP 98:364.
- Garstang, R.H. 1989, "Night-sky brightness at observatories and sites",
  PASP 101:306.
- Duriscoe, D.M. et al. 2018, "A simplified model of all-sky artificial
  sky glow derived from VIIRS Day/Night band data", JQSRT 214:133–145.

### Bortle scale thresholds
Standard SQM ↔ Bortle correspondence from:
- Cinzano, P. et al. 2001, MNRAS 328:689.
- Schaefer, B.E. 1990, PASP 102:212.
```

---

## Out of scope

The following are explicitly deferred and do not need to be done here:

- Land / water mask (genuinely dark ocean correctly maps to Bortle 1).
- Per-region tiles or Pro-tier gating (deferred to feature 5a-ii-D).
- On-device validation pass.
- App / Kotlin changes (the asset activates the feature by itself).
- A standalone `test_resolution.py` (comparing output at different `--deg`
  values) and `diag_*.py` diagnostics (e.g. visualizing/inspecting a built
  `bortle.bin`). Neither exists in the repo or its history, and there is no
  documented usage to reconstruct them from; add them here if/when a concrete
  need and spec exists.
