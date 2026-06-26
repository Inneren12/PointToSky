#!/usr/bin/env python3
"""
res/skyglow/kernel.py — clean-room skyglow propagation kernel.

A radially-symmetric kernel giving the *relative* artificial sky-brightness
contribution measured by an observer looking at (or near) the zenith, per unit
of upward radiance emitted by a source at horizontal ground distance ``d``.

This is a CLEAN-ROOM re-implementation derived purely from the published
single-scattering atmosphere model; no NOSA-licensed NASA SET code was used.

References (cited per term below):
  - Garstang, R.H. 1986, "Model for Artificial Night-Sky Illumination",
    PASP 98:364.  Single-scattering geometry, exponential Rayleigh + aerosol
    density profiles, the aerosol clarity parameter (here called ``aerosol``),
    and the molecular/aerosol phase functions.
  - Garstang, R.H. 1989, "Night-sky brightness at observatories and sites",
    PASP 101:306.  Extinction along the emit->scatter->observe path and the
    scale-height parameterisation.
  - Duriscoe, D.M. et al. 2018, "A simplified model of all-sky artificial sky
    glow derived from VIIRS Day/Night band data", JQSRT 214:133-145.  The
    overall "convolve an upward-radiance raster with a propagation kernel"
    approach that A3 will drive with real VIIRS data.
  - BigBendNP/nightskyquality (MIT) was read as a reference for the discretised
    single-scattering bookkeeping.

CALIBRATION NOTE
----------------
A1 validates SHAPE, not absolute photometry.  The kernel returns a *relative*
weight (arbitrary units); absolute calibration (dark-sky park -> Bortle 1-2,
major city -> Bortle 8-9) is deferred to A3's known-site check.  The ``aerosol``
parameter is the primary tunable knob.

--------------------------------------------------------------------------------
IMPLEMENTED FUNCTIONAL FORM
--------------------------------------------------------------------------------
Geometry (observer at the origin at altitude ``A``, looking straight up):

  - Observer       O = (0, 0, A)
  - Line of sight  the vertical column above O; a scattering point is
                   P = (0, 0, z) for z in [A, z_max]
  - Source         S = (d, 0, 0) on the ground at horizontal distance d
  - Slant range    s = sqrt(d^2 + z^2)         (source -> scattering point)

Single-scattering integral along the zenith line of sight:

    kernel(d) = INTEGRAL_{z=A}^{z_max}
                  (1 / s^2)                      # inverse-square spreading, S->P
                * exp(-tau_src(d, z))            # extinction along S->P  (Garstang'89)
                * [ kR(z) * P_R(Theta)           # Rayleigh scatter + phase (Garstang'86)
                  + kA(z) * P_A(Theta) ]         # aerosol scatter + phase  (Garstang'86)
                * exp(-tau_obs(z))               # extinction along P->O  (Garstang'89)
                dz

with, for vertical optical depths tau_R (Rayleigh) and tau_A (aerosol):

    kR(z) = (tau_R / H_R) * exp(-z / H_R)        # Rayleigh scattering coeff [1/km]
    kA(z) = (tau_A / H_A) * exp(-z / H_A)        # aerosol  scattering coeff [1/km]
    tau_A = aerosol * tau_R                       # << the clarity knob (Garstang K-style)

Extinction (scattering-dominated atmosphere; single-scatter albedo ~1 assumed,
a documented A1 simplification):

    tau_src(d, z) = (s / z) * [ tau_R (1 - exp(-z/H_R))
                              + tau_A (1 - exp(-z/H_A)) ]   # slant path S->P
    tau_obs(z)    = tau_R (exp(-A/H_R) - exp(-z/H_R))
                  + tau_A (exp(-A/H_A) - exp(-z/H_A))       # vertical path P->O

Scattering angle Theta between the incoming (S->P) and outgoing (P->O, straight
down) rays:  cos(Theta) = -z / s   (so distant sources side-scatter near 90 deg,
an overhead source back-scatters near 180 deg).

Phase functions:
    P_R(Theta) = (3 / (16 pi)) (1 + cos^2 Theta)                    # Rayleigh
    P_A(Theta) = (1 / (4 pi)) (1 - g^2) / (1 + g^2 - 2 g cosT)^1.5  # Henyey-Greenstein

A finite hard cutoff radius (``cutoff_km``) forces the kernel to 0 beyond a few
hundred km so the discretised kernel used by convolve.py is bounded.

SIMPLIFICATIONS (documented; refined later, not in A1):
  - Upward emission is treated as isotropic into the upper hemisphere
    (E(psi) = 1).  Garstang's fuller two-lobe (Lambertian + near-horizontal)
    emission function is a refinement.
  - Single scattering only (no double scattering / multiple-scatter halo).
  - Observer looks exactly at the zenith; only Theta >= 90 deg scattering is
    captured.  Off-zenith pointing is an A3 concern.
  - Single-scatter albedo = 1 (extinction == scattering).
"""

from __future__ import annotations

import math
from dataclasses import dataclass, replace

import numpy as np

# ── Default physical constants ────────────────────────────────────────────────
# Scale heights (km): molecules ~8 km, aerosol ~1.5 km (Garstang 1986/1989).
H_RAYLEIGH_KM = 8.0
H_AEROSOL_KM = 1.5

# V-band vertical Rayleigh optical depth at sea level (~0.1 near 550 nm).  This
# only sets the relative weighting between the two scattering terms together with
# the ``aerosol`` knob; the kernel is returned in arbitrary (relative) units.
TAU_RAYLEIGH_V = 0.10

# Henyey-Greenstein asymmetry for the aerosol term (forward-peaked, g in (0,1)).
AEROSOL_ASYMMETRY_G = 0.6

# Atmospheric-clarity presets -> aerosol/Rayleigh vertical-optical-depth ratio
# (a Garstang K-style clarity parameter).  Hazier => more low-altitude aerosol
# scattering AND more extinction (steeper, more near-field-weighted kernel).
AEROSOL_PRESETS = {
    "clear": 0.5,
    "average": 1.0,
    "hazy": 2.5,
}

# Finite cutoff radius (km): contribution is forced to 0 beyond this distance.
DEFAULT_CUTOFF_KM = 300.0

# Top of the integrated atmospheric column (km).  Above this the air density is
# negligible (exp(-30/8) ~ 0.02 for Rayleigh, aerosol long gone).
_Z_MAX_KM = 30.0
# Number of line-of-sight integration samples (trapezoidal).
_N_Z = 400

# Max number of distance values evaluated per block. Caps the live (chunk, _N_Z)
# intermediates so memory stays bounded no matter how large the distance input
# is (A3 passes whole global VIIRS distance grids -> millions of values).
# ~8k x 400 x 8 bytes is ~26 MB per intermediate; with the ~8 live arrays in the
# block this peaks around ~200 MB regardless of input size.
MAX_DIST_CHUNK = 8_192


@dataclass(frozen=True)
class KernelParams:
    """Parameters for the skyglow kernel.

    Attributes:
        aerosol: atmospheric-clarity knob (THE primary tunable).  Either a
            preset name ("clear" / "average" / "hazy") or a float giving the
            aerosol/Rayleigh vertical-optical-depth ratio directly (Garstang
            K-style).  Larger => hazier.
        observer_altitude_km: observer height above sea level (default 0).
        h_rayleigh_km: Rayleigh (molecular) scale height.
        h_aerosol_km: aerosol scale height.
        tau_rayleigh: V-band vertical Rayleigh optical depth.
        aerosol_g: Henyey-Greenstein asymmetry for the aerosol phase function.
        cutoff_km: finite hard cutoff radius; kernel == 0 beyond it.
    """

    aerosol: object = "average"
    observer_altitude_km: float = 0.0
    h_rayleigh_km: float = H_RAYLEIGH_KM
    h_aerosol_km: float = H_AEROSOL_KM
    tau_rayleigh: float = TAU_RAYLEIGH_V
    aerosol_g: float = AEROSOL_ASYMMETRY_G
    cutoff_km: float = DEFAULT_CUTOFF_KM

    @property
    def aerosol_ratio(self) -> float:
        """Resolve ``aerosol`` (preset name or float) to a numeric ratio."""
        a = self.aerosol
        if isinstance(a, str):
            try:
                return AEROSOL_PRESETS[a.lower()]
            except KeyError:
                raise ValueError(
                    f"unknown aerosol preset {a!r}; "
                    f"choose one of {sorted(AEROSOL_PRESETS)} or pass a float"
                )
        return float(a)


def _resolve_params(params) -> KernelParams:
    if params is None:
        return KernelParams()
    if isinstance(params, KernelParams):
        return params
    if isinstance(params, dict):
        return KernelParams(**params)
    # Allow passing the aerosol knob directly, e.g. kernel(d, "hazy").
    if isinstance(params, (str, int, float)):
        return KernelParams(aerosol=params)
    raise TypeError(f"unsupported params type: {type(params)!r}")


def _phase_rayleigh(cos_theta: np.ndarray) -> np.ndarray:
    """Rayleigh molecular phase function (Garstang 1986)."""
    return (3.0 / (16.0 * math.pi)) * (1.0 + cos_theta * cos_theta)


def _phase_aerosol(cos_theta: np.ndarray, g: float) -> np.ndarray:
    """Henyey-Greenstein aerosol phase function (Garstang 1986)."""
    return (1.0 / (4.0 * math.pi)) * (1.0 - g * g) / np.power(
        1.0 + g * g - 2.0 * g * cos_theta, 1.5
    )


def kernel(distance_km, params=None) -> np.ndarray:
    """Relative skyglow contribution at the zenith observer.

    Args:
        distance_km: horizontal ground distance(s) from source to observer, in
            km.  Scalar or array-like; the return shape matches the input.
        params: a ``KernelParams``, a dict of its fields, a preset name / ratio
            for the aerosol knob, or None for defaults.

    Returns:
        Relative (arbitrary-unit), non-negative weight(s).  Monotonically
        decreasing in ``distance_km`` and exactly 0 at/beyond ``cutoff_km``.
    """
    p = _resolve_params(params)
    d = np.asarray(distance_km, dtype=float)
    scalar_in = d.ndim == 0
    orig_shape = d.shape
    # Flatten to 1-D for the (distance x height) broadcast; reshape on return so
    # callers can pass arbitrarily-shaped distance arrays (e.g. a 2-D grid).
    d = np.atleast_1d(d).ravel()

    tau_R = p.tau_rayleigh
    tau_A = p.aerosol_ratio * tau_R
    H_R = p.h_rayleigh_km
    H_A = p.h_aerosol_km
    A = p.observer_altitude_km

    # Line-of-sight samples: vertical column from observer altitude upward.
    # These height-dependent terms are independent of distance, so compute once.
    z = np.linspace(A + 1e-3, A + _Z_MAX_KM, _N_Z)  # (M,)

    # Per-height scattering coefficients [1/km] and their vertical-OD prefactors.
    kR = (tau_R / H_R) * np.exp(-z / H_R)            # (M,)
    kA = (tau_A / H_A) * np.exp(-z / H_A)            # (M,)

    # Extinction along the vertical P->O path (independent of d).
    tau_obs = (
        tau_R * (math.exp(-A / H_R) - np.exp(-z / H_R))
        + tau_A * (math.exp(-A / H_A) - np.exp(-z / H_A))
    )  # (M,)
    ext_obs = np.exp(-tau_obs)                       # (M,)
    zz = z[None, :]                                  # (1, M)
    # Per-height slant-path column fraction (the d-independent part of tau_src).
    col_frac = (
        tau_R * (1.0 - np.exp(-zz / H_R))
        + tau_A * (1.0 - np.exp(-zz / H_A))
    )                                                # (1, M)

    # Evaluate block-wise over the flattened distance axis so the (chunk, M)
    # intermediates stay bounded regardless of N (P1: avoids OOM at A3's VIIRS
    # pixel scales, where N can reach millions). Values are identical to the
    # whole-array computation; only the working-set size changes.
    out = np.empty(d.shape[0], dtype=float)
    for start in range(0, d.shape[0], MAX_DIST_CHUNK):
        dd = d[start:start + MAX_DIST_CHUNK, None]   # (n, 1)
        s = np.sqrt(dd * dd + zz * zz)               # (n, M) slant range S->P
        cos_theta = -zz / s                          # (n, M) scattering angle

        # Slant-path extinction S->P (Garstang 1989). (s/z) converts the vertical
        # column fraction to the slant path.
        tau_src = (s / zz) * col_frac                # (n, M)

        scatter = (
            kR[None, :] * _phase_rayleigh(cos_theta)
            + kA[None, :] * _phase_aerosol(cos_theta, p.aerosol_g)
        )                                            # (n, M)

        integrand = (
            (1.0 / (s * s))
            * np.exp(-tau_src)
            * scatter
            * ext_obs[None, :]
        )                                            # (n, M)

        out[start:start + MAX_DIST_CHUNK] = np.trapezoid(integrand, z, axis=1)

    # Finite hard cutoff: force exactly 0 at/beyond cutoff_km.
    out = np.where(d >= p.cutoff_km, 0.0, out)
    # Guard against any tiny negative round-off.
    out = np.clip(out, 0.0, None)

    if scalar_in:
        return float(out[0])
    return out.reshape(orig_shape)


def kernel_with(distance_km, **overrides) -> np.ndarray:
    """Convenience: evaluate the kernel with default params overridden by kwargs.

    Example:
        kernel_with(d, aerosol="hazy", observer_altitude_km=2.0)
    """
    return kernel(distance_km, replace(KernelParams(), **overrides))


if __name__ == "__main__":  # pragma: no cover - quick visual sanity check
    ds = np.array([0.5, 1, 2, 5, 10, 20, 50, 100, 200, 300, 400.0])
    for preset in ("clear", "average", "hazy"):
        vals = kernel(ds, preset)
        print(f"{preset:>8}: " + "  ".join(f"{v:.3e}" for v in vals))
