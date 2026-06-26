"""
res/skyglow — clean-room skyglow propagation kernel + FFT convolution.

Public API:
    kernel(distance_km, params)          -- radial propagation kernel
    KernelParams                         -- kernel parameters (aerosol knob, ...)
    build_radial_kernel(km_per_pixel, .) -- discretised 2-D kernel
    convolve_radiance(radiance, km_per_pixel, .) -- radiance -> artificial glow

Clean-room from Garstang (1986 PASP, 1989 PASP) and Duriscoe et al. (2018
JQSRT); see kernel.py for the implemented functional form and full citations.
A1 is shape-validated only; absolute calibration is deferred to A3.
"""

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
]
