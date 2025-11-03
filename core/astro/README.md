# core:astro

This module hosts basic astronomical math utilities and data types.

## Public API overview

- `dev.pointtosky.core.astro.units`
  - Angle conversion helpers between degrees and radians, angle wrapping functions, and numeric clamping utilities. Angles in degrees are wrapped into `0°..360°` or `-180°..180°` ranges depending on the helper.
- `dev.pointtosky.core.astro.coord`
  - Coordinate data classes:
    - `Equatorial`: right ascension in degrees (`0°..360°`), declination in degrees (`-90°..+90°`).
    - `Horizontal`: azimuth in degrees (`0°..360°`, measured clockwise from North), altitude in degrees (`-90°..+90°`).
    - `GeoPoint`: latitude in degrees (`-90°..+90°`, positive north), longitude in degrees (`-180°..+180°`, positive east).
    - `Sidereal`: local sidereal time in degrees (`0°..360°`).
- `dev.pointtosky.core.astro.math`
  - Lightweight immutable 3D vector utilities with double precision, including normalization, dot and cross products, scaling, addition/subtraction, and a minimal 3 × 3 matrix implementation with identity, transpose, and multiplication helpers.

All angles are expressed in decimal degrees unless explicitly stated otherwise in the KDoc of each API.
