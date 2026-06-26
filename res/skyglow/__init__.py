"""
res/skyglow — clean-room skyglow propagation kernel + FFT convolution,
and artificial-brightness → Bortle class mapping.

Public API:
    kernel(distance_km, params)          -- radial propagation kernel
    KernelParams                         -- kernel parameters (aerosol knob, ...)
    build_radial_kernel(km_per_pixel, .) -- discretised 2-D kernel
    convolve_radiance(radiance, km_per_pixel, .) -- radiance -> artificial glow
    NATURAL_SKY_MAG                      -- natural sky reference (21.9 mag/arcsec²)
    mag_to_relative_luminance(mag)       -- mag/arcsec² -> relative luminance
    relative_luminance_to_mag(lum)       -- relative luminance -> mag/arcsec²
    combined_sky_brightness_mag(art, .)  -- (natural + artificial) -> mag/arcsec²
    bortle_from_sky_brightness_mag(mag)  -- mag/arcsec² -> Bortle class 1–9
    artificial_to_bortle(field, .)       -- convolve output -> uint8 Bortle grid

Clean-room from Garstang (1986 PASP, 1989 PASP) and Duriscoe et al. (2018
JQSRT); see kernel.py for the implemented functional form and full citations.
A1 is shape-validated only; absolute calibration is deferred to A3.
"""

from .brightness import (
    NATURAL_SKY_MAG,
    artificial_to_bortle,
    bortle_from_sky_brightness_mag,
    combined_sky_brightness_mag,
    mag_to_relative_luminance,
    relative_luminance_to_mag,
)
from .convolve import build_radial_kernel, convolve_radiance
from .kernel import (
    AEROSOL_PRESETS,
    DEFAULT_CUTOFF_KM,
    KernelParams,
    kernel,
    kernel_with,
)

__all__ = [
    "kernel",
    "kernel_with",
    "KernelParams",
    "AEROSOL_PRESETS",
    "DEFAULT_CUTOFF_KM",
    "build_radial_kernel",
    "convolve_radiance",
    "NATURAL_SKY_MAG",
    "mag_to_relative_luminance",
    "relative_luminance_to_mag",
    "combined_sky_brightness_mag",
    "bortle_from_sky_brightness_mag",
    "artificial_to_bortle",
]
