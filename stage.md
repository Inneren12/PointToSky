# Stage – PointToSky

As of 2025-05-20:

## 1. Current focus
- Remove blocking calls on UI/binder threads (especially Wear tiles/complications and catalog fetchers); replace with suspending flows.
- Improve performance and memory footprint of star catalog queries and AR/sky map overlays.
- Harden orientation/location pipelines for steadier aim/identify UX and fewer ANRs on Wear.
- Stabilize tests for astro math, catalog lookups, and Wear tiles/complications across CI and local devices.
- Polish UX, logging, and crash export ahead of broader Play release readiness.

## 2. Recently completed
- S0.A – Documented high-level project structure and testing entry points.
- Fixed AR overlay projection to respect display rotation.
- Restored star label rendering with user-facing settings controls.
- Hardened Tonight tile resource/version handling and connected test coverage.

## 3. Near-term backlog
- Performance & battery passes for Wear tiles/complications and AR overlays; trim unnecessary wakeups.
- Replace lingering `runBlocking`/blocking I/O with suspending calls and tighter dispatcher usage.
- Expand ViewModel/unit coverage for catalog queries, aim/identify flows, and data-layer bridges.
- Tighten R8/ProGuard rules and security hardening (services/exported components, FileProvider grants).
- Improve accessibility and UI polish (text scaling, contrast) on mobile and Wear.
- Add diagnostics hooks where missing (structured LogBus events, lightweight traces).

## 4. How to use this file
Contributors should update this file when the main focus or priorities shift; keep it short and current. AI agents should read this before large changes to align with active goals and avoid disrupting near-term milestones.
