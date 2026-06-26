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
``km_per_pixel`` sets the kernel's physical scale.  In an equirectangular grid
the km-per-degree of LONGITUDE shrinks with cos(latitude), so a single
km/pixel is only exact along one parallel.  For A1 a single scalar (or a simple
representative value) is sufficient; the ``latitudes`` argument is accepted and
documented so A3 can refine the geometry against the real VIIRS grid without a
signature change.  FFT convolution is shift-invariant, so a fully
latitude-dependent kernel is out of scope here (flagged for A3).

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


def convolve_radiance(
    radiance: np.ndarray,
    km_per_pixel: float,
    params=None,
    latitudes=None,
    cutoff_km: float | None = None,
    normalize_kernel: bool = False,
    wrap_longitude: bool = False,
) -> np.ndarray:
    """Convolve an upward-radiance raster into an artificial sky-brightness field.

    Args:
        radiance: 2-D array of upward radiance per pixel (row 0 = north,
            matching the PTSKLP01 raster convention).  Arbitrary units.
        km_per_pixel: representative pixel size in km (see PIXEL GEOMETRY above).
        params: kernel parameters (preset / ratio / dict / KernelParams / None).
        latitudes: OPTIONAL per-row latitude in degrees.  Reserved for A3, which
            will use it to apply the cos(latitude) longitude-scale correction of
            the real VIIRS equirectangular grid.  Accepted (signature-ready) but
            NOT applied in A1; passing it has no effect beyond a documented note.
        cutoff_km: override the finite kernel radius in km.
        normalize_kernel: pass through to ``build_radial_kernel``.
        wrap_longitude: if True, circularly pad the column (longitude) axis by
            the kernel radius before convolving so glow crosses the +/-180 deg
            dateline seam (see GLOBAL GRID / DATELINE above).  Rows (latitude)
            stay zero-padded -- the poles do not wrap.  Default False keeps
            regional rasters unchanged; A3 sets True for the global grid.

    Returns:
        A 2-D artificial-brightness array, same shape as ``radiance``.
    """
    radiance = np.asarray(radiance, dtype=float)
    if radiance.ndim != 2:
        raise ValueError("radiance must be a 2-D array")

    # A1: latitudes is accepted for a signature-stable API but not yet applied.
    # A3 will refine the equirectangular km-per-degree(lon) = km * cos(lat)
    # geometry against the real VIIRS grid (per-row scaling / reprojection).
    _ = latitudes

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
