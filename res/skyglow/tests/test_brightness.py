"""Tests for res/skyglow/brightness.py (synthetic data only)."""

import numpy as np
import pytest

from res.skyglow.brightness import (
    NATURAL_SKY_MAG,
    artificial_to_bortle,
    bortle_from_sky_brightness_mag,
    combined_sky_brightness_mag,
    mag_to_relative_luminance,
    relative_luminance_to_mag,
)


# ---------------------------------------------------------------------------
# Round-trip tests
# ---------------------------------------------------------------------------

class TestRoundTrip:
    def test_mag_to_lum_at_natural_sky(self):
        assert mag_to_relative_luminance(NATURAL_SKY_MAG) == pytest.approx(1.0)

    def test_round_trip_scalar(self):
        for m in [15.0, 18.5, 21.0, 21.9, 22.5]:
            recovered = relative_luminance_to_mag(mag_to_relative_luminance(m))
            assert recovered == pytest.approx(m, rel=1e-10)

    def test_round_trip_array(self):
        mags = np.linspace(15.0, 22.5, 50)
        lums = mag_to_relative_luminance(mags)
        recovered = relative_luminance_to_mag(lums)
        np.testing.assert_allclose(recovered, mags, rtol=1e-10)

    def test_brighter_sky_higher_luminance(self):
        # lower mag = brighter sky = higher relative luminance
        assert mag_to_relative_luminance(18.0) > mag_to_relative_luminance(21.9)

    def test_returns_scalar_for_scalar_input(self):
        result = mag_to_relative_luminance(21.9)
        # should behave like a float / 0-d array
        assert float(result) == pytest.approx(1.0)


# ---------------------------------------------------------------------------
# Bortle table tests
# ---------------------------------------------------------------------------

class TestBortleTable:
    @pytest.mark.parametrize("mag,expected", [
        (21.9, 1),   # pristine dark sky, above 21.75 threshold
        (21.8, 1),   # still above 21.75
        (21.6, 2),   # 21.50–21.75
        (21.4, 3),   # 21.25–21.50
        (21.0, 4),   # 20.40–21.25
        (20.0, 5),   # 19.10–20.40
        (19.5, 5),   # mid-range of 19.10–20.40
        (18.7, 6),   # 18.50–19.10
        (18.2, 7),   # 18.00–18.50
        (17.7, 8),   # 17.50–18.00
        (17.0, 9),   # below 17.50
        (14.0, 9),   # very bright (city centre)
    ])
    def test_representative_points(self, mag, expected):
        assert bortle_from_sky_brightness_mag(mag) == expected

    def test_boundary_values(self):
        # exact lower-bound values sit in the higher (darker) class
        assert bortle_from_sky_brightness_mag(21.75) == 1
        assert bortle_from_sky_brightness_mag(21.50) == 2
        assert bortle_from_sky_brightness_mag(21.25) == 3
        assert bortle_from_sky_brightness_mag(20.40) == 4
        assert bortle_from_sky_brightness_mag(19.10) == 5
        assert bortle_from_sky_brightness_mag(18.50) == 6
        assert bortle_from_sky_brightness_mag(18.00) == 7
        assert bortle_from_sky_brightness_mag(17.50) == 8

    def test_monotonic(self):
        # lower mag (brighter sky) must give >= Bortle class
        mags = np.linspace(22.0, 14.0, 200)  # decreasing mag = brighter sky
        classes = bortle_from_sky_brightness_mag(mags)
        assert np.all(np.diff(classes.astype(int)) >= 0), \
            "Bortle class must be non-decreasing as mag decreases (sky gets brighter)"

    def test_returns_int_for_scalar(self):
        result = bortle_from_sky_brightness_mag(21.9)
        assert isinstance(result, int)

    def test_returns_uint8_array_for_array(self):
        mags = np.array([21.9, 21.0, 18.7])
        result = bortle_from_sky_brightness_mag(mags)
        assert result.dtype == np.uint8
        assert result.shape == (3,)

    def test_all_values_in_range(self):
        mags = np.linspace(14.0, 22.5, 300)
        classes = bortle_from_sky_brightness_mag(mags)
        assert np.all(classes >= 1)
        assert np.all(classes <= 9)


# ---------------------------------------------------------------------------
# Combined brightness tests
# ---------------------------------------------------------------------------

class TestCombinedBrightness:
    def test_zero_artificial_gives_natural_sky(self):
        mag = combined_sky_brightness_mag(0.0)
        assert mag == pytest.approx(NATURAL_SKY_MAG)

    def test_zero_artificial_array_gives_natural_sky(self):
        art = np.zeros((5, 5))
        mag = combined_sky_brightness_mag(art)
        np.testing.assert_allclose(mag, NATURAL_SKY_MAG)

    def test_increasing_artificial_decreases_mag(self):
        art_values = np.logspace(-3, 3, 50)
        mags = combined_sky_brightness_mag(art_values)
        # more artificial light → brighter sky → lower mag
        assert np.all(np.diff(mags) < 0)

    def test_large_artificial_gives_very_bright_sky(self):
        mag = combined_sky_brightness_mag(1e6)
        assert mag < 17.5  # Bortle 9 territory

    def test_scale_knob_larger_scale_brighter_sky(self):
        art = 1.0
        mag1 = combined_sky_brightness_mag(art, scale=1.0)
        mag2 = combined_sky_brightness_mag(art, scale=2.0)
        # larger scale → more light → brighter → lower mag
        assert mag2 < mag1

    def test_custom_natural_mag(self):
        # with zero artificial light the result equals natural_mag regardless
        for nat in [21.5, 21.9, 22.1]:
            mag = combined_sky_brightness_mag(0.0, natural_mag=nat)
            assert mag == pytest.approx(nat)


# ---------------------------------------------------------------------------
# Monotonicity via scale knob
# ---------------------------------------------------------------------------

class TestScaleKnob:
    def test_larger_scale_gives_higher_or_equal_bortle(self):
        art = 0.5
        scales = np.linspace(0.1, 10.0, 20)
        bortles = [bortle_from_sky_brightness_mag(
            combined_sky_brightness_mag(art, scale=s)) for s in scales]
        # non-decreasing
        assert all(bortles[i] <= bortles[i + 1] for i in range(len(bortles) - 1))

    def test_fixed_art_zero_scale_is_natural(self):
        # scale=0 → only natural sky → Bortle 1
        mag = combined_sky_brightness_mag(999.0, scale=0.0)
        assert mag == pytest.approx(NATURAL_SKY_MAG)
        assert bortle_from_sky_brightness_mag(mag) == 1


# ---------------------------------------------------------------------------
# artificial_to_bortle tests
# ---------------------------------------------------------------------------

class TestArtificialToBortle:
    def _radial_field(self, size=51):
        """Synthetic 2-D field: 0 at edges, peak at centre."""
        y, x = np.ogrid[-size // 2:size // 2 + 1, -size // 2:size // 2 + 1]
        r = np.sqrt(x**2 + y**2)
        return np.exp(-r / 10.0)  # radial Gaussian bump, max ~1 at centre

    def test_returns_uint8(self):
        field = self._radial_field()
        result = artificial_to_bortle(field)
        assert result.dtype == np.uint8

    def test_shape_preserved(self):
        field = self._radial_field(size=31)
        result = artificial_to_bortle(field)
        assert result.shape == field.shape

    def test_all_values_1_to_9(self):
        field = self._radial_field()
        result = artificial_to_bortle(field)
        assert np.all(result >= 1)
        assert np.all(result <= 9)

    def test_zero_field_gives_bortle_1(self):
        field = np.zeros((10, 10))
        result = artificial_to_bortle(field)
        assert np.all(result == 1)

    def test_brighter_centre_higher_bortle(self):
        # The centre of the radial field is brightest; its Bortle should be ≥
        # any edge pixel's Bortle for sufficiently large scale.
        field = self._radial_field(size=51)
        result = artificial_to_bortle(field, scale=5.0)
        centre = result[25, 25]
        edge = result[0, 0]
        assert centre >= edge

    def test_dark_cell_bortle_1_with_tuned_scale(self):
        # A very small artificial value should stay at Bortle 1.
        field = np.full((5, 5), 1e-6)
        result = artificial_to_bortle(field, scale=1.0)
        assert np.all(result == 1)

    def test_very_bright_cell_bortle_9(self):
        # A huge artificial value should reach Bortle 9.
        field = np.full((5, 5), 1e7)
        result = artificial_to_bortle(field, scale=1.0)
        assert np.all(result == 9)

    def test_radial_field_monotonic_from_centre(self):
        # Along the central row of a radial field, Bortle should be
        # non-increasing as we move away from the (brightest) centre.
        size = 51
        field = self._radial_field(size=size)
        result = artificial_to_bortle(field, scale=3.0)
        centre_row = result[size // 2, :]
        left_half = centre_row[:size // 2 + 1][::-1]   # centre → left edge
        right_half = centre_row[size // 2:]              # centre → right edge
        assert np.all(np.diff(left_half.astype(int)) <= 0), \
            "Bortle should be non-increasing away from the bright centre (left)"
        assert np.all(np.diff(right_half.astype(int)) <= 0), \
            "Bortle should be non-increasing away from the bright centre (right)"

    def test_scale_increases_bortle(self):
        # For the same field, a larger scale must give >= Bortle everywhere.
        field = self._radial_field(size=21)
        b_low = artificial_to_bortle(field, scale=0.1)
        b_high = artificial_to_bortle(field, scale=10.0)
        assert np.all(b_high >= b_low)
