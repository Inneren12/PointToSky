"""
res/skyglow/brightness.py — artificial sky brightness → Bortle class mapping.

Unit convention
---------------
All brightness arithmetic is done in luminance **relative to the natural sky
background**.  The natural moonless zenith sky is taken as:

    NATURAL_SKY_MAG = 21.9  V mag/arcsec²

This is the standard pristine dark-sky reference (~21.7–22.0 is the accepted
range; 21.9 is the mid-high value used here).  Its linear luminance is defined
as **1.0** (dimensionless).  Any sky brighter than this has L > 1.0.

Conversion formulae
-------------------
    L   = 10 ** ((NATURAL_SKY_MAG - mag) / 2.5)   # mag  → relative luminance
    mag = NATURAL_SKY_MAG - 2.5 * log10(L)         # relative luminance → mag

Adding artificial light
-----------------------
Total sky luminance = natural (1.0) + artificial contribution:

    L_total = 1.0 + scale * artificial_relative

where ``scale`` is the **single calibration knob** that converts A1's arbitrary
convolution units into natural-background units.  A2 exposes it as a parameter
(default 1.0); A3 will fit it so that a pristine site maps to Bortle 1–2 and a
major city maps to Bortle 8–9.

Bortle / SQM table
-------------------
Standard SQM↔Bortle correspondence from Cinzano et al. (2001) / Schaefer (1990)
as widely tabulated (e.g. IDA / Sky Quality Meter handbook).  Boundaries are
commonly-cited approximate values and may be refined during A3 calibration.

    Bortle 1  ≥ 21.75 mag/arcsec²   (pristine dark sky)
    Bortle 2  21.50 – 21.75
    Bortle 3  21.25 – 21.50
    Bortle 4  20.40 – 21.25
    Bortle 5  19.10 – 20.40
    Bortle 6  18.50 – 19.10
    Bortle 7  18.00 – 18.50
    Bortle 8  17.50 – 18.00
    Bortle 9  < 17.50              (inner-city sky)

NATURAL_SKY_MAG = 21.9 sits above the Bortle-1 floor (21.75), so a pristine
sky with zero artificial light correctly maps to Bortle 1.

Output alphabet
---------------
``artificial_to_bortle`` returns a uint8 array of integers 1–9, which is the
exact PTSKLP01 payload alphabet.  The value 0 (nodata) is A3's concern — A2
maps brightness, not ocean/nodata regions.
"""

import numpy as np

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

NATURAL_SKY_MAG = 21.9
"""Natural moonless zenith sky brightness (V mag/arcsec²).

Standard reference value for a pristine dark sky (~21.7–22.0 is the accepted
range).  Defined as relative luminance 1.0 in this module's unit system.
"""

# Bortle table encoded as (lower_bound_mag, bortle_class) in descending mag
# order (brightest sky = lowest mag = highest Bortle last).
# A pixel with mag >= 21.75 is Bortle 1; mag < 17.50 is Bortle 9.
_BORTLE_THRESHOLDS = [
    (21.75, 1),
    (21.50, 2),
    (21.25, 3),
    (20.40, 4),
    (19.10, 5),
    (18.50, 6),
    (18.00, 7),
    (17.50, 8),
    # anything below 17.50 → Bortle 9 (handled as fallback)
]


# ---------------------------------------------------------------------------
# Luminance ↔ mag conversions
# ---------------------------------------------------------------------------

def mag_to_relative_luminance(mag):
    """Convert sky brightness (mag/arcsec²) to relative luminance.

    Relative luminance is 1.0 at ``NATURAL_SKY_MAG``.  A brighter sky (lower
    mag) gives L > 1.0; a fainter sky gives L < 1.0.

    Vectorised: accepts scalar or any numpy array.
    """
    mag = np.asarray(mag, dtype=float)
    return 10.0 ** ((NATURAL_SKY_MAG - mag) / 2.5)


def relative_luminance_to_mag(lum):
    """Convert relative luminance to sky brightness (mag/arcsec²).

    Inverse of ``mag_to_relative_luminance``.  L = 1.0 maps to
    ``NATURAL_SKY_MAG``.  L must be > 0.

    Vectorised: accepts scalar or any numpy array.
    """
    lum = np.asarray(lum, dtype=float)
    return NATURAL_SKY_MAG - 2.5 * np.log10(lum)


# ---------------------------------------------------------------------------
# Combined brightness
# ---------------------------------------------------------------------------

def combined_sky_brightness_mag(artificial_relative, scale=1.0,
                                natural_mag=NATURAL_SKY_MAG):
    """Total sky brightness (mag/arcsec²) given an artificial luminance field.

    Parameters
    ----------
    artificial_relative:
        Relative artificial-sky luminance from ``convolve_radiance`` (A1).
        Arbitrary units; ``scale`` converts to natural-background units.
        Tiny negative values from FFT round-off are clipped to zero before use.
    scale:
        Calibration knob (set by A3).  Default 1.0.  Must be finite and >= 0.
        Larger values amplify the artificial contribution, pushing the result
        toward higher Bortle.
    natural_mag:
        Natural sky brightness (mag/arcsec²).  Defaults to ``NATURAL_SKY_MAG``.

    Returns
    -------
    mag/arcsec² of the total (natural + artificial) sky, same shape as input.

    Raises
    ------
    ValueError
        If ``scale`` is not finite or is negative, or if ``artificial_relative``
        contains non-finite values.
    """
    if not np.isfinite(scale):
        raise ValueError(f"scale must be finite, got {scale!r}")
    if scale < 0:
        raise ValueError(f"scale must be non-negative, got {scale!r}")
    art = np.asarray(artificial_relative, dtype=float)
    if not np.all(np.isfinite(art)):
        raise ValueError("artificial_relative must contain only finite values")
    # Clip tiny negatives from FFT/convolution round-off so L_total stays > 0.
    art = np.maximum(art, 0.0)
    L_natural = mag_to_relative_luminance(natural_mag)
    L_total = L_natural + scale * art
    return relative_luminance_to_mag(L_total)


# ---------------------------------------------------------------------------
# Bortle classification
# ---------------------------------------------------------------------------

def bortle_from_sky_brightness_mag(mag):
    """Map sky brightness (mag/arcsec²) to Bortle class (1–9).

    Uses the standard SQM↔Bortle table documented in the module header.
    Brighter sky (lower mag) → higher Bortle class.

    Vectorised: accepts scalar or any numpy array.  Returns int for scalar
    input and a uint8 numpy array for array input.

    Raises
    ------
    ValueError
        If ``mag`` contains any non-finite values (NaN or ±inf).  Non-finite
        inputs would silently map to Bortle 9 (inner-city sky) without this
        guard, corrupting the output grid with invalid classifications.
    """
    mag_arr = np.asarray(mag, dtype=float)
    scalar_input = mag_arr.ndim == 0
    mag_arr = np.atleast_1d(mag_arr)
    if not np.all(np.isfinite(mag_arr)):
        raise ValueError("mag must be finite (no NaN or inf)")

    result = np.full(mag_arr.shape, 9, dtype=np.uint8)
    # Iterate from the lowest threshold upward so that each successive
    # assignment overrides only pixels that qualify for a darker (lower)
    # Bortle class.  A pixel with mag=21.9 passes all thresholds and ends at 1.
    for lower_bound, bortle in reversed(_BORTLE_THRESHOLDS):
        result[mag_arr >= lower_bound] = bortle

    if scalar_input:
        return int(result[0])
    return result


# ---------------------------------------------------------------------------
# Top-level pipeline
# ---------------------------------------------------------------------------

def artificial_to_bortle(artificial_field, scale=1.0,
                         natural_mag=NATURAL_SKY_MAG):
    """Convert a 2-D artificial-luminance field to a Bortle-class grid.

    This is the main bridge between A1 (``convolve_radiance`` output) and A3
    (``write_grid`` input).

    Parameters
    ----------
    artificial_field:
        2-D numpy array of relative artificial sky luminance produced by
        ``convolve_radiance``.  Shape (rows, cols).
    scale:
        Calibration knob (fitted by A3).  Default 1.0.
    natural_mag:
        Natural sky brightness.  Defaults to ``NATURAL_SKY_MAG``.

    Returns
    -------
    uint8 numpy array, same shape as ``artificial_field``, with values in 1–9.
    0 (nodata) is NOT produced here; A3 applies the ocean/nodata mask after
    calling this function.
    """
    field = np.asarray(artificial_field, dtype=float)
    mag = combined_sky_brightness_mag(field, scale=scale,
                                      natural_mag=natural_mag)
    return bortle_from_sky_brightness_mag(mag)
