"""
Shape / mechanics tests for the skyglow propagation kernel.

These validate qualitative behaviour, NOT absolute photometry (absolute
calibration is deferred to A3's known-site check).
"""

import numpy as np
import pytest

from res.skyglow.kernel import (
    AEROSOL_PRESETS,
    DEFAULT_CUTOFF_KM,
    KernelParams,
    kernel,
    kernel_with,
)


def test_kernel_positive_in_range():
    d = np.linspace(0.5, DEFAULT_CUTOFF_KM - 1.0, 200)
    vals = kernel(d)
    assert np.all(vals > 0.0)


def test_kernel_monotonically_decreasing():
    d = np.linspace(0.5, DEFAULT_CUTOFF_KM - 1.0, 500)
    vals = kernel(d)
    diffs = np.diff(vals)
    # Strictly non-increasing (allow tiny numerical noise).
    assert np.all(diffs <= 1e-12)
    # And genuinely decreasing overall, not flat.
    assert vals[0] > vals[-1] * 10


def test_kernel_zero_at_and_beyond_cutoff():
    assert kernel(DEFAULT_CUTOFF_KM) == 0.0
    assert kernel(DEFAULT_CUTOFF_KM + 50.0) == 0.0
    assert kernel(1e6) == 0.0


def test_kernel_approaches_zero_near_cutoff():
    near = kernel(1.0)
    edge = kernel(DEFAULT_CUTOFF_KM - 1.0)
    # Far-field contribution is a tiny fraction of the near-field value.
    assert edge < near * 1e-3


def test_kernel_scalar_and_array_consistent():
    d = [1.0, 10.0, 100.0]
    arr = kernel(np.array(d))
    for i, di in enumerate(d):
        assert kernel(di) == pytest.approx(arr[i], rel=1e-9)
    # scalar input -> python float
    assert isinstance(kernel(5.0), float)


def test_kernel_custom_cutoff():
    p = KernelParams(cutoff_km=100.0)
    assert kernel(99.0, p) > 0.0
    assert kernel(100.0, p) == 0.0
    assert kernel(150.0, p) == 0.0


@pytest.mark.parametrize("preset", sorted(AEROSOL_PRESETS))
def test_presets_resolve_and_are_positive(preset):
    assert kernel(5.0, preset) > 0.0


def test_aerosol_knob_changes_falloff():
    """Hazier => more near-field glow and a steeper (more concentrated) falloff."""
    near_d, far_d = 1.0, 50.0
    clear_near = kernel(near_d, "clear")
    hazy_near = kernel(near_d, "hazy")
    clear_far = kernel(far_d, "clear")
    hazy_far = kernel(far_d, "hazy")

    # Hazier boosts the near field.
    assert hazy_near > clear_near
    # Hazier suppresses the far field (more extinction).
    assert hazy_far < clear_far
    # => the near/far ratio (concentration) is larger when hazy.
    assert (hazy_near / hazy_far) > (clear_near / clear_far)


def test_aerosol_accepts_numeric_ratio():
    # A larger numeric ratio behaves like a hazier preset at the near field.
    low = kernel(1.0, 0.3)
    high = kernel(1.0, 3.0)
    assert high > low


def test_unknown_preset_raises():
    with pytest.raises(ValueError):
        kernel(5.0, "soup")


def test_observer_altitude_param():
    """A higher observer sits above more of the densest (aerosol) air."""
    sea = kernel_with(5.0, observer_altitude_km=0.0)
    high = kernel_with(5.0, observer_altitude_km=2.0)
    assert sea > 0.0 and high > 0.0
    # Both finite & positive; altitude changes the value (not asserting sign of
    # change strongly since it is geometry-dependent, just that it responds).
    assert sea != high
