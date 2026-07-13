# Design — `analytics.events.enabled` config key (first SWIP config key in Dayfold)

Date: 2026-07-12 · Status: approved · Scope: debug-only

## Purpose

Give the config-debug panel (`SwipConfigPlugin`, ADR-0057 family) a real key to show, and
exercise the whole SWIP config path end-to-end: compiled defaults → targeting rule →
read-tracking → panel row → override → observable behavior change.

Dayfold reads zero config keys today (`configDefaults` empty, nothing calls the typed
getters), so the panel renders "No config keys read yet" no matter what. This is the
smallest honest key that fixes that: a boolean that actually gates something, with a
targeting rule that resolves differently across devices.

**Testing/dogfood instrument, not a product feature.** It lives entirely in the debug
source set; release keeps its inert analytics mirror (ADR 0055), so there is no
product/release behavior change and no ADR is required.

## The key

```json
{
  "key": "analytics.events.enabled",
  "type": "boolean",
  "default": false,
  "rules": [
    { "if": { "platform": "android", "os_version": { "gte": 15 } }, "value": true }
  ]
}
```

- Android 17 emulator / Android 15+ device → `TARGETING_MATCH`, `true` (analytics behave
  exactly as today).
- Android 14 and below → `DEFAULT`, `false` (analytics off).
- Reason is `TARGETING_MATCH`/`DEFAULT`, never `SPLIT` → **no exposure is ever recorded**
  for this key, so it cannot pollute experiment data.

Authored as JSON and parsed with `ConfigKeySpec.fromJson` rather than hand-constructing the
data class: it is the same shape a registry rule will have, so it ports to codegen
(`registry/products/dayfold.yaml`) without a rewrite.

## Components

**`:swip-wiring` (commonMain) — `DayfoldConfig`**
The key's home. It owns the spec JSON, the `configDefaults` map, and one pure accessor:

```kotlin
object DayfoldConfig {
  const val ANALYTICS_EVENTS = "analytics.events.enabled"
  fun defaults(): Map<String, ConfigKeySpec>
  fun analyticsEnabled(config: SloopConfig): Boolean   // config.boolean(ANALYTICS_EVENTS)
}
```

Why here and not in the androidApp debug glue: `:swip-wiring` is already the SWIP glue
module, is consumed `debugImplementation` only, and is the only one of the two with a test
source set (`desktopTest`) — `androidApp` has none. `:client` stays SWIP-free.

**`:androidApp` (src/debug) — `SwipAnalyticsGlue`**
Two changes at the `Swip.init` call site plus one at the gate:

```kotlin
DayfoldSwip.platformDeps(...).copy(
  debugSink = SwipInspectorGlue.debugSink(),
  debuggable = BuildConfig.DEBUG,
  configDefaults = DayfoldConfig.defaults(),
  osVersion = Build.VERSION.RELEASE,      // REQUIRED — see below
)
```

```kotlin
consentGate = {
  requireSwip().analytics.collectionMode() in TRACKING_MODES &&
    DayfoldConfig.analyticsEnabled(requireSwip().config)
}
```

## The `osVersion` trap

`SwipPlatformDeps.osVersion` defaults to `null` and `DayfoldSwip.platformDeps()` does not
expose it, so `EvaluationContext.osVersion` is null today. A condition against a null
attribute resolves to `Match.ERROR`, and an ERROR rule falls back to the compiled default —
which here is `false`. **Forgetting to thread `osVersion` would not merely fail to match the
rule; it would silently switch analytics off.** Hence the explicit `.copy(osVersion = ...)`
and a test that pins the ERROR case.

Note `os_version` comparisons with `gte` are numeric (`toDoubleOrNull`), not semver —
`Build.VERSION.RELEASE` ("17", "15") parses cleanly. (`eq` on a `*_version` attribute is the
semver-range path; we don't use it.)

## Data flow

The gate is read once per dispatched redux action (`consentGate` inside `swipMiddleware`).
Each read funnels through `resolveInternal` → `trackRead`, so the key becomes read-tracked
on the first action and appears in the Config panel. Overriding it to `false` in the panel
makes `consentGate` return false → redux actions stop producing analytics events, visible
in the SWIP inspector panel as the absence of new `Enqueued` records.

Read cost per dispatch is a map lookup plus rule evaluation — no IO, never throws
(INVARIANT-13).

## Testing (`:swip-wiring` desktopTest)

Evaluate the real spec through a real `SwipConfig` against constructed contexts:

- android + os_version 17 → `true`, reason `TARGETING_MATCH`
- android + os_version 14 → `false`, reason `DEFAULT`
- ios + os_version 17 → `false`, reason `DEFAULT` (platform gate holds)
- android + **null** os_version → `false`, reason `ERROR` (pins the trap above)
- override `true` while the gate is open → `true`, reason `OVERRIDE`

## Out of scope

Registry codegen for config defaults (the spec is hand-authored until swip ships it);
release-build analytics (still inert per ADR 0055); any second key.
