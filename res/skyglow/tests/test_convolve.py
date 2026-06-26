"""
Shape / mechanics tests for the radiance -> artificial-brightness convolution.

Validates convolution behaviour (radial falloff, finite range, superposition,
uniformity, proportionality), NOT absolute calibration.
"""

import numpy as np
import pytest

from res.skyglow.convolve import build_radial_kernel, convolve_radiance


KM_PER_PIXEL = 5.0  # coarse grid so the cutoff spans a modest number of pixels.


def _point_source(shape, rc, value=1.0):
    a = np.zeros(shape, dtype=float)
    a[rc] = value
    return a


def test_build_radial_kernel_shape_and_properties():
    k = build_radial_kernel(KM_PER_PIXEL)
    # Odd, square, symmetric.
    assert k.ndim == 2
    assert k.shape[0] == k.shape[1]
    assert k.shape[0] % 2 == 1
    assert np.allclose(k, k[::-1, :])
    assert np.allclose(k, k[:, ::-1])
    assert np.allclose(k, k.T)
    # Non-negative, centre is the maximum.
    assert np.all(k >= 0.0)
    c = k.shape[0] // 2
    assert k[c, c] == k.max()
    # Corners (beyond the circular cutoff) are zero.
    assert k[0, 0] == 0.0


def test_point_source_radially_decreasing():
    shape = (81, 81)
    src = _point_source(shape, (40, 40))
    out = convolve_radiance(src, KM_PER_PIXEL)

    c = 40
    assert out[c, c] == out.max()
    # Sample rings of increasing radius along a row; brightness must decrease.
    prev = out[c, c]
    for r in range(1, 20):
        val = out[c, c + r]
        assert val <= prev + 1e-9
        prev = val
    # Strictly dimmer at distance than at the core.
    assert out[c, c + 10] < out[c, c]


def test_source_beyond_cutoff_contributes_zero():
    # Two far-apart points; the glow from one must not reach the other.
    cutoff_px = int(np.floor(build_radial_kernel(KM_PER_PIXEL).shape[0] / 2))
    far = cutoff_px + 5
    shape = (2 * far + 21, 2 * far + 21)
    mid = shape[0] // 2
    src = _point_source(shape, (mid, mid))
    out = convolve_radiance(src, KM_PER_PIXEL)
    # A pixel beyond the kernel radius from the source gets ~0.
    assert out[mid, mid + far] == pytest.approx(0.0, abs=1e-12)


def test_superposition():
    shape = (101, 101)
    a = _point_source(shape, (30, 30))
    b = _point_source(shape, (70, 75))
    out_a = convolve_radiance(a, KM_PER_PIXEL)
    out_b = convolve_radiance(b, KM_PER_PIXEL)
    out_ab = convolve_radiance(a + b, KM_PER_PIXEL)
    assert np.allclose(out_ab, out_a + out_b, atol=1e-9)


def test_proportionality():
    shape = (81, 81)
    src = _point_source(shape, (40, 40), value=1.0)
    bright = _point_source(shape, (40, 40), value=7.0)
    out = convolve_radiance(src, KM_PER_PIXEL)
    out_bright = convolve_radiance(bright, KM_PER_PIXEL)
    assert np.allclose(out_bright, 7.0 * out, atol=1e-9)


def test_uniform_field_uniform_interior():
    # Use a smaller cutoff so the kernel fits well inside the grid, leaving a
    # genuine interior region unaffected by edge truncation.
    cutoff = 80.0
    shape = (121, 121)
    field = np.ones(shape, dtype=float)
    out = convolve_radiance(field, KM_PER_PIXEL, cutoff_km=cutoff)
    # Interior (well away from edges, beyond the kernel radius) is ~uniform.
    krad = build_radial_kernel(KM_PER_PIXEL, cutoff_km=cutoff).shape[0] // 2
    lo = krad + 2
    hi = shape[0] - krad - 2
    interior = out[lo:hi, lo:hi]
    assert interior.size > 0
    assert np.std(interior) < 1e-6 * np.mean(interior)


def test_brighter_source_brighter_glow():
    shape = (81, 81)
    dim = _point_source(shape, (40, 40), value=1.0)
    bright = _point_source(shape, (40, 40), value=3.0)
    out_dim = convolve_radiance(dim, KM_PER_PIXEL)
    out_bright = convolve_radiance(bright, KM_PER_PIXEL)
    # Brighter everywhere the glow reaches.
    reached = out_dim > 0
    assert np.all(out_bright[reached] > out_dim[reached])


def test_aerosol_param_passthrough():
    shape = (81, 81)
    src = _point_source(shape, (40, 40))
    clear = convolve_radiance(src, KM_PER_PIXEL, params="clear")
    hazy = convolve_radiance(src, KM_PER_PIXEL, params="hazy")
    c = 40
    # Hazier => brighter core glow (more near-field scattering).
    assert hazy[c, c] > clear[c, c]


def test_wrap_longitude_crosses_dateline():
    """A source in the last column glows into the first column when wrapping (P2)."""
    # Tiny 1-row strip whose width is within the kernel cutoff, so the two ends
    # are "close" in wrapped longitude.
    cutoff = 60.0
    krad = build_radial_kernel(KM_PER_PIXEL, cutoff_km=cutoff).shape[1] // 2
    # Strip wider than the kernel radius so the source does NOT reach the first
    # column directly (last->first direct distance = 2*krad px > cutoff), but the
    # wrapped seam distance is just 1 px (well within the cutoff).
    ncols = 2 * krad + 1
    strip = np.zeros((1, ncols), dtype=float)
    strip[0, -1] = 1.0  # source in the LAST column

    wrapped = convolve_radiance(
        strip, KM_PER_PIXEL, cutoff_km=cutoff, wrap_longitude=True
    )
    zeroed = convolve_radiance(
        strip, KM_PER_PIXEL, cutoff_km=cutoff, wrap_longitude=False
    )

    # Wrapping: glow reaches the first column (no dark seam).
    assert wrapped[0, 0] > 0.0
    # Default: zero-padded edges leave the first column ~0 (current behaviour).
    assert zeroed[0, 0] == pytest.approx(0.0, abs=1e-15)
    # Output shape is preserved by the crop-back.
    assert wrapped.shape == strip.shape


def test_latitudes_none_is_byte_identical():
    """latitudes=None (default) produces exactly the same result as omitting it."""
    shape = (41, 41)
    src = _point_source(shape, (20, 20))
    base = convolve_radiance(src, KM_PER_PIXEL)
    with_none = convolve_radiance(src, KM_PER_PIXEL, latitudes=None)
    assert np.array_equal(base, with_none)


def _nonzero_extent(profile, threshold=1e-12):
    """Return the half-width in pixels from the peak where profile > threshold."""
    center = int(np.argmax(profile))
    extent = 0
    for i in range(1, len(profile)):
        lo = center - i
        hi = center + i
        if lo < 0 or hi >= len(profile):
            break
        if profile[lo] <= threshold and profile[hi] <= threshold:
            break
        extent = i
    return extent


def test_cos_lat_halo_isotropic_at_equator():
    """At the equator (lat=0) the glow halo is ~isotropic in pixel space."""
    # 0.2 deg/cell → kernel spans ~13 px in each direction (cutoff=300 km).
    deg = 0.2
    km_ns = 111.32 * deg          # ~22.3 km/pixel N-S
    cutoff = 300.0
    nrows, ncols = 41, 41
    center_r, center_c = nrows // 2, ncols // 2
    src = np.zeros((nrows, ncols))
    src[center_r, center_c] = 1.0
    lats = (center_r - np.arange(nrows)) * deg   # row 0 ≈ +4°, last row ≈ −4°

    out = convolve_radiance(src, km_ns, latitudes=lats, cutoff_km=cutoff,
                            lat_band_deg=1.0)

    ns_ext = _nonzero_extent(out[:, center_c])
    ew_ext = _nonzero_extent(out[center_r, :])

    assert ns_ext > 0 and ew_ext > 0, "no detectable halo"
    ratio = ew_ext / ns_ext
    # At equator km_ew == km_ns → isotropic kernel → both extents identical.
    assert 0.7 <= ratio <= 1.3, f"equator halo ratio {ratio:.2f} not ~1"


def test_cos_lat_halo_wider_at_60deg():
    """At lat=60° the E-W halo is ~2× wider in pixels than the N-S halo.

    cos(60°)=0.5 → km_ew = 0.5·km_ns → the kernel spans 2× as many pixels
    in the E-W direction as in N-S, falsifying a missing/incorrect correction.
    """
    deg = 0.2
    km_ns = 111.32 * deg          # ~22.3 km/pixel N-S
    cutoff = 300.0
    nrows, ncols = 41, 81         # extra columns for the 2× wider E-W kernel
    center_r, center_c = nrows // 2, ncols // 2
    src = np.zeros((nrows, ncols))
    src[center_r, center_c] = 1.0
    lats = 60.0 + (center_r - np.arange(nrows)) * deg

    out = convolve_radiance(src, km_ns, latitudes=lats, cutoff_km=cutoff,
                            lat_band_deg=1.0)

    ns_ext = _nonzero_extent(out[:, center_c])
    ew_ext = _nonzero_extent(out[center_r, :])

    assert ns_ext > 0 and ew_ext > 0, "no detectable halo"
    ratio = ew_ext / ns_ext
    # Accept [1.5, 2.5] to tolerate ±1 px discretisation at the boundary.
    assert 1.5 <= ratio <= 2.5, f"lat-60 halo ratio {ratio:.2f} not ~2"
