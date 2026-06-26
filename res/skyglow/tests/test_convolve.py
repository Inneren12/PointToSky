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


def test_latitudes_arg_accepted_noop():
    shape = (41, 41)
    src = _point_source(shape, (20, 20))
    lats = np.linspace(60.0, 20.0, shape[0])
    base = convolve_radiance(src, KM_PER_PIXEL)
    with_lat = convolve_radiance(src, KM_PER_PIXEL, latitudes=lats)
    # A1: latitudes is signature-ready but a no-op; result is unchanged.
    assert np.allclose(base, with_lat)
