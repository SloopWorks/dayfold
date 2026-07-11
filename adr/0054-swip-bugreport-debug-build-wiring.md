# ADR 0054: SWIP Bug Reporter in Debug Builds — Shake → Capture → Annotate → Review, Zero Release Footprint

## Status

**Proposed** 2026-07-10 (agent-drafted with the `swip-bugreport-wiring` PR;
accept on merge). Composes with ADR 0019 (in-app devtools drawer), ADR 0026
(package naming), ADR 0034 (release pipeline — untouched: release variant
carries zero swip bytes).

## Context

Dogfooding on physical devices has no structured bug-capture path: a glitch
seen on-device becomes a vague verbal report with no state, no logs, no
screenshot. SloopWorks now publishes the SWIP bug reporter
(`works.sloop.swip:{swip-bugreport,swip-rk-recorder,swip-bugreport-ui}:0.1.0`,
GitHub Packages) — shake/edge-tab entry, screenshot + annotate/blur, consent
toggles, a redux state-timeline journal, and an on-device durability lane
(no upload yet; the SWIP gateway is its Phase 1). Wiring it raises two forces:

1. **Privacy:** the redux store holds session JWTs, member names, emails-ish
   strings — a naive state recorder would journal them.
2. **Release hygiene:** the reporter is a debug/dogfood tool; the Play-shipped
   APK must not grow bytes, permissions, or attack surface.

## Decision

Wire the SWIP bug reporter into `:androidApp` **debug builds only**, following
the existing per-variant idiom (`debugDrawerPlugins()`): `src/main` calls three
variant-provided functions (`bugReporterEnhancer` / `bugReporterInstall` /
`BugReporterWrapped`); `src/debug` wires the real SDK via `debugImplementation`
deps; `src/release` is an inert same-signature mirror.

- `:client` stays swip-free: `createAppStore` gains an optional `extraEnhancer`
  composed rightmost/innermost — the recorder's required slot.
- A new KMP module `:swip-wiring` owns the **slice registry** (allowlist:
  `route`, `syncing`, `detailStack` ids, derived `cardsCount`, `hubFilter`
  chip), the **`dayfoldSanitizer`** (drops any journaled value containing a
  JWT-shaped `eyJ` or `@`; truncates `hubFilter` to 32 chars), and the
  **mandatory product-owned leak test** (salted real `AppState`; asserts salts
  never reach the journal). `:swip-wiring:desktopTest` is a CI gate.
- Never registered: `session`, `members`, `families`, `devices`, `myDisplayName`,
  card content, `error`/`authError` strings. Registry changes require extending
  the leak test with salts for the new slice.
- Consent posture: gate = `BuildConfig.DEBUG`; identity = anonymous (null,
  null); no upload — reports land in the on-device lane
  (`noBackupFilesDir/swip-reports`), visible via the reporter's pending badge.
- CI/credentials: two GitHub Packages repo blocks in `apps/settings.gradle.kts`;
  local dev uses `gpr.user`/`gpr.token`, CI uses the `SLOOPWORKS_PACKAGES_TOKEN`
  repo secret.

## Rationale

- The `debugDrawerPlugins()` idiom is proven in this repo for debug-only
  capability with an inert release mirror; reusing it keeps `src/main`
  variant-agnostic and makes "release = zero swip" enforceable by a compile
  gate (`assembleRelease` with no swip on the classpath) instead of review
  vigilance.
- A separate `:swip-wiring` module (rather than code in `:androidApp`) exists
  because androidApp has no JVM test source set — the leak test needs a
  hermetic desktop home, and the registry/sanitizer are platform-neutral.
- Alternatives rejected: vendor SDKs (Instabug/Shake — vendor lock-in, network
  SDKs in a family-data app); screenshots-only (loses the state timeline that
  makes reports actionable); wiring in release behind a flag (violates the
  zero-footprint bar for no benefit while there is no upload path).

## Consequences

Positive:
- Structured dogfood reports: screenshot + annotations + breadcrumbs + a
  sanitized redux timeline, durable on device.
- Privacy is enforced by three fences: allowlist registry → sanitizer →
  CI leak test.
- Release APK is byte-identical in swip terms (debugImplementation only).

Negative:
- Builds now require GitHub Packages credentials (first resolve) — a new
  secret in dayfold CI and a PAT for local dev.
- Reports accumulate on device until the SWIP gateway lands (3-pending /
  15 MB / 7-day TTL lane budget caps this).
- swip-bugreport 0.1.0 leaks okio types through `ReportLane`'s public ctor
  while declaring okio as `implementation` — dayfold must add okio explicitly
  (SWIP follow-up filed to make it `api` in 0.1.1).

## Revisit Trigger

SWIP Phase 1 gateway ships (upload path + real channel/identity wiring), an
iOS bug-reporter pass, or a dogfood (non-debug internal) channel is introduced
— each requires revisiting the gate/consent posture.
