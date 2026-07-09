# Real Bortle Grid — Data Runbook (v3 / SQM)

Produces a real, non-placeholder **PTSKLP01 v3** `bortle.bin` (continuous SQM
payload) from NASA VIIRS Black Marble data. Counterpart to the build code in
`build_bortle_bin.py`. The v3 asset pairs with the `LightPollutionGrid.sqmAt()`
decoder; the app derives fractional Bortle and NELM from the stored SQM.

> **This supersedes the old v2 procedure.** The previous runbook produced an
> integer-Bortle v2 grid via a GeoTIFF/GDAL mosaic at 0.1°, calibrated on
> Tokyo/Manhattan Bortle classes. That path is **deprecated** — it emits a v2
> asset that the current v3 decoder rejects. See *Legacy v2 path* at the end.

**What changed and why (as-built):**
- **Stores SQM, not integer Bortle.** Format v3; the app computes fractional
  Bortle + NELM read-side.
- **Native-resolution convolution, then downsample to 0.0125°** — *not* a 0.1°
  block-average. The resolution experiments showed that 0.1° averaging spreads
  compact sources (towns, greenhouses) over ~11 km and suppresses their
  self-glow, manufacturing a false "too-steep" radiance→SQM slope. At native
  input the slope is flat (dark ≈ atlas, bright ≈ atlas); 0.0125° output keeps
  ~80% of the fidelity at a small asset size.
- **Calibrated on 12 Alberta atlas-SQM points** (lightpollutionmap.app),
  against atlas **SQM**, not atlas Bortle (the two diverge up to ~2 classes at
  the dark end).
- **Direct HDF5 read (h5py)** — no rasterio/GDAL/GeoTIFF mosaic.
- **Regional** (single tile h06v03, Alberta). Multi-tile / global coverage is
  future work (roadmap **L-2**); regional download + graceful null handling is
  **L-4**.

**Generated artifacts stay local.** Raw tiles (`viirs_global/`), build output
(`out/`), `*.h5` and `*.canvas.npy` are gitignored and must not be committed.
Only the final `bortle.bin` asset is committed (Step 6).

**Prerequisites:** Earthdata account; Python with `numpy` and `h5py`
(no rasterio/GDAL needed).

---

## Step 1 — Earthdata login

Register / sign in at <https://urs.earthdata.nasa.gov>. One-time setup that
LAADS downloads require:
- Complete your profile (**User Type / Organization / Study Area**) at
  `urs.earthdata.nasa.gov/profile` — an incomplete profile makes LAADS return
  an HTML stub instead of the file.
- Authorize the **LAADS DAAC** application under *Applications → Authorized Apps*.

---

## Step 2 — Download the VNP46A4 tile

VNP46A4 is the VIIRS/Suomi-NPP Black Marble **annual** moonlight-and-cloud-free
composite; the layer used is `AllAngle_Composite_Snow_Free` (fill −999.9,
2400×2400 float per tile, 15-arcsec grid). For the Alberta build you need one
tile: **`VNP46A4.A<year>001.h06v03.002.*.h5`** (e.g. `A2025001`), placed in
`viirs_global/`.

**Browser (simplest, ~180 MB):** on <https://ladsweb.modaps.eosdis.nasa.gov>
find product **VNP46A4**, filter by tile **h06v03** and year, download the
`.h5` into `viirs_global/`.

**curl with a token** (generate a token at `urs.earthdata.nasa.gov`; keep it in
an env var, never in the shell history):
```bash
# archive set 5200, day-of-year 001; find the exact h06v03 filename:
curl -s -L -H "Authorization: Bearer $EDL_TOKEN" \
  "https://ladsweb.modaps.eosdis.nasa.gov/archive/allData/5200/VNP46A4/2025/001/" | grep h06v03
# then download it:
curl -L --location-trusted -H "Authorization: Bearer $EDL_TOKEN" -o viirs_global/<FILE>.h5 \
  "https://ladsweb.modaps.eosdis.nasa.gov/archive/allData/5200/VNP46A4/2025/001/<FILE>.h5"
```
Sanity-check the download (≈180 MB, not an ~11 KB HTML stub or a 0-byte file):
```bash
ls -la viirs_global/*h06v03*<year>*
```

---

## Step 3 — Build the v3 asset

```bash
PYTHONUTF8=1 python -m res.skyglow.build_bortle_bin viirs_global out/bortle.bin <year>
```

The builder performs the locked pipeline end to end: read the native
2400×2400 radiance → cos-lat area-weight → `convolve_radiance` (300 km cutoff,
regional) → downsample the glow ×3 to 0.0125° → least-squares refit of `scale`
on the 12-point atlas-SQM series (fit uses the scale-regime points,
SQM < 21.7) → `combined_sky_brightness_mag` → encode SQM to the v3 byte
payload → write `bortle.bin` → round-trip self-check.

The 12 calibration points (lat, lon, atlas_SQM) are baked into
`build_bortle_bin.py` (`POINTS`); atlas SQM values come from
lightpollutionmap.app.

---

## Step 4 — Verify the output

The script prints its own diagnostics; a healthy build shows:
- header `v3 800x800 latTop=60 lonLeft=-120 deg=0.01250`;
- `scale (LSQ …) ≈ 2.64` (computed fresh from the data each run);
- a residual table where dark points (Jasper, Kicking Horse, Columbia Icefield,
  Nordegg) land at **Bortle 1** and the mid-dark residual mean is near **0**;
- `round-trip OK: max decode error … ≤ 0.05` (quantization).

Confirm the on-disk header is v3:
```bash
head -c 12 out/bortle.bin | xxd     # ...3031 0300 0000  = "PTSKLP01" + version 3
ls -la out/bortle.bin               # ≈ 54 KB
```
`0300 0000` at bytes 8–11 is mandatory — a `0200 0000` (v2) asset breaks the
v3 decoder.

---

## Step 5 — Calibration reference & acceptance

`scale` is the single knob converting the convolution's relative units into
natural-background units (`L_total = 1 + scale·A`, `mag = 21.9 − 2.5·log10 L`).
It is fit by least squares against the atlas SQM at the scale-regime points.

Acceptance: residuals within roughly **±0.15 mag** across the full range, with a
**flat slope** — dark sites ≈ atlas and bright sites ≈ atlas simultaneously
(the resolution finding; a single scale that fits both is only possible at
native input resolution). Known benign outlier: Kneehill/Acme reads brighter
than its older atlas value because the greenhouse there brightened between data
years (recency, not a model or spectral error).

---

## Step 6 — Commit the asset

```bash
cp out/bortle.bin mobile/src/main/assets/lightpollution/bortle.bin
git add mobile/src/main/assets/lightpollution/bortle.bin
git commit -m "feat(bortle): ship v3 SQM light-pollution grid (VNP46A4 <year>, h06v03, scale=S)"
```

**Ship the v3 asset and the v3 decoder together** — a v3 asset requires the
v3 `sqmAt()` decoder, and vice versa. `placeholder=False` (flags=0) activates
the Auto sky-brightness feature; note that the asset is a regional tile, so
availability is honest per-location (the app reports unavailable outside
coverage and falls back to manual).

---

## Step 7 — Attribution (NOTICE.md)

Keep the data/model attribution block:
```
### NASA VIIRS Black Marble (VNP46A4)
Product: VNP46A4 Annual Moonlight-and-Cloud-Free Night Lights Composite
Provider: NASA EOSDIS / LAADS DAAC
DOI: https://doi.org/10.5067/VIIRS/VNP46A4.001
Public domain (NASA data policy); free for any use including commercial.

### Skyglow propagation model
Kernel in res/skyglow/kernel.py — clean-room from Garstang 1986/1989 and
Duriscoe et al. 2018 (JQSRT 214:133).

### Bortle / SQM thresholds
Standard SQM↔Bortle from Cinzano et al. 2001 (MNRAS 328:689) and
Schaefer 1990 (PASP 102:212).
```

---

## Legacy v2 path (deprecated — do not use for shipping)

`build_real_grid.py` (`radiance_to_bortle_grid` → `artificial_to_bortle` →
`write_grid(placeholder=False)`) and its `calibrate_scale.py` sweep produce an
**integer-Bortle v2** grid at 0.1° calibrated on Tokyo/Manhattan. That output is
**incompatible with the current v3 decoder** and is retained only because its
pure-NumPy helpers are still imported by unit tests. Do not use it to build a
shipping asset; use `build_bortle_bin.py` (v3) above.

## Out of scope (future work)

- Multi-tile / global coverage (roadmap **L-2** — southern Alberta / Lethbridge
  needs tile h06v04).
- Regional on-demand download + "download ahead" + offline fallback (**L-4**).
- On-device validation pass.
