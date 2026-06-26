#!/usr/bin/env python3
"""
res/skyglow/convolve.py — upward radiance raster -> artificial sky-brightness.

Builds a discretised radial propagation kernel (from ``kernel.py``) on the
raster's pixel spacing and FFT-convolves an upward-radiance raster with it to
produce an artificial V-band sky-luminance field.  This is the convolution step
of the Duriscoe et al. (2018) approach; A3 feeds it real NASA VIIRS radiance.

Clean-room (Garstang 1986/1989 + Duriscoe 2018); see kernel.py header for the
full citation list and the implemented functional form.

OUTPUT
------
An artificial-brightness array in the SAME relative units as the kernel times
the input radiance.  It is NOT calibrated to mag/arcsec^2 and the natural sky
background is NOT added here -- that mapping (artificial + natural background ->
mag/arcsec^2 -> Bortle) is A2, and absolute calibration against known sites is
A3.  A1 covers kernel + convolution only.

PIXEL GEOMETRY
--------------
``km_per_pixel`` sets the kernel's N-S physical scale (= 111.32 * deg_per_cell,
constant for any equirectangular row).  The E-W physical scale varies with
latitude: km_ew = km_per_pixel * cos(lat).  Pass the ``latitudes`` array (one
value per row, in degrees) to ``convolve_radiance`` to activate the cos-latitude
correction: an anisotropic kernel is built per latitude band so that physical
distances and the glow halo are correct at all latitudes.  When ``latitudes``
is None (the default) the original isotropic kernel is used unchanged -- regional
rasters and all A1 callers are unaffected.

GLOBAL GRID / DATELINE
----------------------
On the global equirectangular grid the longitude axis wraps (lonLeft = -180,
360 cols), but ``fftconvolve(..., mode="same")`` zero-pads every edge -> a
source near +/-180 deg contributes nothing across the dateline, leaving an
artificial dark seam.  Pass ``wrap_longitude=True`` to ``convolve_radiance`` so
the column (longitude) axis is circularly padded by the kernel radius before
convolving (rows/latitude stay zero-padded -- the poles do not wrap).  Default
is ``False`` so non-global (regional) rasters are unaffected; A3 sets it True.
"""

from __future__ import annotations

import numpy as np
from scipy.signal import fftconvolve

from .kernel import DEFAULT_CUTOFF_KM, KernelParams, _resolve_params, kernel


def build_radial_kernel(
    km_per_pixel: float,
    params=None,
    cutoff_km: float | None = None,
    min_distance_km: float | None = None,
    normalize: bool = False,
) -> np.ndarray:
    """Discretise the radial skyglow kernel onto a square pixel grid.

    Args:
        km_per_pixel: physical size of one pixel in km.
        params: kernel parameters (see ``kernel.KernelParams``); preset name,
            ratio, dict, KernelParams, or None for defaults.
        cutoff_km: finite kernel radius in km; defaults to the params' cutoff.
            The kernel array spans +/- cutoff in each direction (bounded).
        min_distance_km: distance floor applied to the centre pixel to tame the
            r->0 single-scatter singularity (an observer directly under a point
            source).  Defaults to half a pixel diagonal.
        normalize: if True, scale the kernel to sum to 1 (useful for testing
            flux conservation); off by default since A1 returns relative units.

    Returns:
        A 2-D, odd-sized, radially-symmetric, non-negative kernel array.
    """
    if km_per_pixel <= 0:
        raise ValueError("km_per_pixel must be positive")
    p = _resolve_params(params)
    if cutoff_km is None:
        cutoff_km = p.cutoff_km
    if min_distance_km is None:
        # Half a pixel diagonal: a representative source<->observer distance
        # for the self-pixel, keeping the centre value finite.
        min_distance_km = 0.5 * np.sqrt(2.0) * km_per_pixel

    radius_px = int(np.floor(cutoff_km / km_per_pixel))
    radius_px = max(radius_px, 1)
    coords = np.arange(-radius_px, radius_px + 1)
    yy, xx = np.meshgrid(coords, coords, indexing="ij")
    dist_km = np.sqrt(xx * xx + yy * yy) * km_per_pixel
    dist_km = np.maximum(dist_km, min_distance_km)

    # Ensure the kernel's own cutoff matches the requested array extent.
    kp = p if cutoff_km == p.cutoff_km else KernelParams(
        aerosol=p.aerosol,
        observer_altitude_km=p.observer_altitude_km,
        h_rayleigh_km=p.h_rayleigh_km,
        h_aerosol_km=p.h_aerosol_km,
        tau_rayleigh=p.tau_rayleigh,
        aerosol_g=p.aerosol_g,
        cutoff_km=cutoff_km,
    )

    k = kernel(dist_km, kp)
    k = np.asarray(k, dtype=float)
    k[dist_km >= cutoff_km] = 0.0  # hard-bound the support

    if normalize:
        total = k.sum()
        if total > 0:
            k = k / total
    return k


def _build_anisotropic_kernel(
    km_ns: float,
    km_ew: float,
    params=None,
    cutoff_km: float | None = None,
    min_distance_km: float | None = None,
    normalize: bool = False,
) -> np.ndarray:
    """Discretise the skyglow kernel onto a rectangular grid with anisotropic spacing.

    Used by the cos-latitude correction path in ``_convolve_with_lat_correction``.
    When km_ew < km_ns (i.e. toward the poles) the kernel is wider in the column
    (longitude) direction in pixel space, correctly reflecting that each pixel
    covers fewer physical km E-W.

    Args:
        km_ns: physical size of one pixel in the N-S (row) direction, in km.
        km_ew: physical size of one pixel in the E-W (column) direction, in km.
            At latitude phi: km_ew = km_ns * cos(phi).
        params: kernel parameters (preset name, ratio, dict, KernelParams, None).
        cutoff_km: hard kernel radius in km; defaults to params cutoff.
        min_distance_km: distance floor for the centre pixel.
            Defaults to half the pixel diagonal.
        normalize: scale kernel to sum=1 if True.

    Returns:
        2-D non-negative kernel array, shape
        (2*radius_ns_px+1, 2*radius_ew_px+1).
    """
    if km_ns <= 0 or km_ew <= 0:
        raise ValueError("km_ns and km_ew must be positive")
    p = _resolve_params(params)
    if cutoff_km is None:
        cutoff_km = p.cutoff_km
    if min_distance_km is None:
        min_distance_km = 0.5 * np.sqrt(km_ns ** 2 + km_ew ** 2)

    radius_ns_px = max(int(np.floor(cutoff_km / km_ns)), 1)
    radius_ew_px = max(int(np.floor(cutoff_km / km_ew)), 1)

    row_coords = np.arange(-radius_ns_px, radius_ns_px + 1)
    col_coords = np.arange(-radius_ew_px, radius_ew_px + 1)
    yy, xx = np.meshgrid(row_coords, col_coords, indexing="ij")

    # Physical distance on the ground: rows contribute km_ns per pixel, cols km_ew.
    dist_km = np.sqrt((yy * km_ns) ** 2 + (xx * km_ew) ** 2)
    dist_km = np.maximum(dist_km, min_distance_km)

    kp = p if cutoff_km == p.cutoff_km else KernelParams(
        aerosol=p.aerosol,
        observer_altitude_km=p.observer_altitude_km,
        h_rayleigh_km=p.h_rayleigh_km,
        h_aerosol_km=p.h_aerosol_km,
        tau_rayleigh=p.tau_rayleigh,
        aerosol_g=p.aerosol_g,
        cutoff_km=cutoff_km,
    )

    k = kernel(dist_km, kp)
    k = np.asarray(k, dtype=float)
    k[dist_km >= cutoff_km] = 0.0

    if normalize:
        total = k.sum()
        if total > 0:
            k = k / total
    return k


def _convolve_with_lat_correction(
    radiance: np.ndarray,
    km_per_pixel: float,
    params,
    latitudes: np.ndarray,
    cutoff_km: float | None,
    normalize_kernel: bool,
    wrap_longitude: bool,
    lat_band_deg: float,
) -> np.ndarray:
    """Convolve radiance with per-latitude-band anisotropic kernels.

    Groups rows into latitude bands narrow enough that cos(lat) is approximately
    constant across each band, builds an anisotropic kernel for the band's
    representative latitude, and convolves the full radiance array with that
    kernel.  Only the band's own output rows are kept, so each output row is
    produced by its latitude's correctly-scaled kernel.

    The approximation — using the observer-row's latitude to set the E-W scale
    for all sources that illuminate it — is valid because sources within the
    <=300 km kernel radius are always at a similar latitude to the observer.
    """
    p = _resolve_params(params)
    if cutoff_km is None:
        cutoff_km = p.cutoff_km

    km_ns = km_per_pixel
    nrows, ncols = radiance.shape
    if len(latitudes) != nrows:
        raise ValueError(
            f"latitudes length {len(latitudes)} must match radiance rows {nrows}"
        )

    # Clamp to ±85° so cos(lat) stays above ~0.087 and the E-W kernel radius
    # stays finite.  There is no light pollution at the poles, so this is harmless.
    lats = np.clip(np.asarray(latitudes, dtype=float), -85.0, 85.0)

    out = np.zeros_like(radiance)

    row = 0
    while row < nrows:
        band_start = row
        rep_lat = lats[row]

        # Grow the band while successive rows stay within lat_band_deg of the
        # first row's latitude.
        band_end = band_start
        while band_end + 1 < nrows:
            if abs(lats[band_end + 1] - rep_lat) < lat_band_deg:
                band_end += 1
            else:
                break

        # Use the mid-row latitude as the band representative.
        phi = lats[(band_start + band_end) // 2]
        cos_phi = np.cos(np.radians(phi))
        km_ew = km_ns * cos_phi

        k = _build_anisotropic_kernel(
            km_ns, km_ew,
            params=p,
            cutoff_km=cutoff_km,
            normalize=normalize_kernel,
        )

        # Convolve the full radiance so that sources in rows outside this band
        # still contribute their glow to the band's observer rows.
        if wrap_longitude:
            r = k.shape[1] // 2
            padded = np.pad(radiance, ((0, 0), (r, r)), mode="wrap")
            full = fftconvolve(padded, k, mode="same")
            conv = full[:, r:r + ncols]
        else:
            conv = fftconvolve(radiance, k, mode="same")

        conv = np.clip(conv, 0.0, None)
        out[band_start:band_end + 1] = conv[band_start:band_end + 1]
        row = band_end + 1

    return out


def convolve_radiance(
    radiance: np.ndarray,
    km_per_pixel: float,
    params=None,
    latitudes=None,
    cutoff_km: float | None = None,
    normalize_kernel: bool = False,
    wrap_longitude: bool = False,
    lat_band_deg: float = 2.0,
) -> np.ndarray:
    """Convolve an upward-radiance raster into an artificial sky-brightness field.

    Args:
        radiance: 2-D array of upward radiance per pixel (row 0 = north,
            matching the PTSKLP01 raster convention).  Arbitrary units.
        km_per_pixel: N-S pixel size in km (= 111.32 * deg_per_cell for an
            equirectangular grid).  Used as km_ew too when ``latitudes`` is None.
        params: kernel parameters (preset / ratio / dict / KernelParams / None).
        latitudes: OPTIONAL 1-D array of per-row centre latitudes in degrees,
            length == radiance.shape[0].  When provided, activates the
            cos(latitude) E-W scale correction: an anisotropic kernel is built
            per latitude band so that the glow halo reflects true physical
            distances at each latitude (wider in longitude-degrees toward the
            poles).  When None (the default), the original isotropic single-kernel
            path is used unchanged -- all A1 tests and regional callers hit this
            path and see byte-identical results.
        cutoff_km: override the finite kernel radius in km.
        normalize_kernel: pass through to ``build_radial_kernel`` /
            ``_build_anisotropic_kernel``.
        wrap_longitude: if True, circularly pad the column (longitude) axis by
            the kernel radius before convolving so glow crosses the +/-180 deg
            dateline seam (see GLOBAL GRID / DATELINE above).  Default False.
        lat_band_deg: width of each latitude band in degrees when the
            ``latitudes`` correction is active.  Rows within this range of a
            band's representative latitude share one anisotropic kernel.
            Smaller -> more bands -> more FFTs but higher fidelity.  Default 2.0.

    Returns:
        A 2-D artificial-brightness array, same shape as ``radiance``.
    """
    radiance = np.asarray(radiance, dtype=float)
    if radiance.ndim != 2:
        raise ValueError("radiance must be a 2-D array")

    if latitudes is not None:
        # Cos-latitude correction path: per-band anisotropic kernels.
        return _convolve_with_lat_correction(
            radiance, km_per_pixel, params, latitudes,
            cutoff_km, normalize_kernel, wrap_longitude, lat_band_deg,
        )

    # ------------------------------------------------------------------ A1 path
    # latitudes is None: original single isotropic kernel (byte-identical to A1).
    k = build_radial_kernel(
        km_per_pixel,
        params=params,
        cutoff_km=cutoff_km,
        normalize=normalize_kernel,
    )

    # 'same' keeps the output aligned with the input raster; the finite-radius
    # kernel keeps the convolution bounded.
    if wrap_longitude:
        # Circularly extend longitude by the kernel radius so sources near the
        # dateline contribute across it, then crop back to the original cols.
        r = k.shape[1] // 2
        padded = np.pad(radiance, ((0, 0), (r, r)), mode="wrap")
        full = fftconvolve(padded, k, mode="same")
        out = full[:, r:r + radiance.shape[1]]
    else:
        out = fftconvolve(radiance, k, mode="same")
    # FFT round-off can produce tiny negatives where the true value is ~0.
    return np.clip(out, 0.0, None)


__all__ = [
    "build_radial_kernel",
    "convolve_radiance",
    "DEFAULT_CUTOFF_KM",
]
