# Dayfold SWIP inspector plugin — execution spec

Date: 2026-07-12 · Branch: `feat/swip-inspector-plugin` · Refs: SWIP design-of-record
`~/workspace/sloopworksinstrumentationplatform/docs/superpowers/specs/2026-07-12-swip-debug-inspector-phase2-ui.md`,
handoff `docs/swip-handoffs/dayfold-swip-inspector-plugin-prompt.md`, ADR 0055 (analytics), ADR 0054 (bug reporter) · Status: approved

## Summary

Phase 2 of the SWIP debug inspector: the **UI plugin** that renders the Phase-1 capture
engine's stream in Dayfold's debug drawer. Phase-1 engine (`works.sloop.swip:swip-debug`,
`RingDebugSink`, `entries: StateFlow<List<DebugEntry>>`) is merged (SWIP PR #45). This
session builds only the Dayfold-side plugin + host wiring. No SWIP-repo code change.

## Grounded facts (verified against real source, not assumptions)

- **Mount contract** (`apps/debugdrawer/.../DebugPlugin.kt`): `DebugPlugin { val id; val title;
  @Composable fun Content(scope: DebugScope) }`. `DebugScope` exposes `copy(text)`, `store`,
  `logs`, backend actions. Host owns list→detail nav, chrome, **and insets** — panels do NOT
  self-apply `safeDrawing` (`LogsPanel` uses bare `fillMaxSize()`).
- **Precedent** = `LogsPanel.kt` (live newest-first `LazyColumn(key=seq)`, segmented filter,
  tap→`AlertDialog` detail, `scope.copy`). material3 allowed in drawer panels.
- **Colors** = `LocalDebugDrawerColors.current` (`DrawerColors`: text/muted/accent/accentSoft/
  surface2/border/warn/err/ok). Privacy/status render as **labeled chips** (text always visible)
  — resolves the direct-PII-red vs SendFailed-red collision. Never a bare colored dot.
- **Install seam** = `SwipPlatformDeps.debugSink: SwipDebugSink? = null` (swip-core `Swip.kt:39`,
  **data class**). The codegen'd `DayfoldSwip.platformDeps(...)` has **no** `debugSink` passthrough,
  so inject via `.copy(debugSink = sink)` on its return value. `null` in release = dormant.
- **swip-core version**: dayfold pins **0.1.2**; `debugSink` shipped in **0.1.3** (SWIP #47) →
  bump required.
- **Cross-capture (resolved)**: the bug reporter captures via `PixelCopy` on the **Activity
  window** (`swip-bugreport/.../AndroidScreenshot.kt:16,45`). `PixelCopy` **honors `FLAG_SECURE`**
  → securing the Activity window while a value is revealed blanks BOTH the OS screenshot AND the
  dogfood bug bundle. No bug-reporter change needed.

## Module

New `apps/debugdrawer-swip` (KMP, mirrors `apps/debugdrawer-redux`). Wired
`debugImplementation("...")` from `apps/androidApp` **only** → zero release footprint (same idiom
as `ReduxDevToolsDebugPlugin`, which is registered from `src/debug/DebugDrawerPlugins.kt`).
Deps: `works.sloop.swip:swip-debug:<published>` + project `:debugdrawer`. Android debug variant only (v1).

## The plugin

`SwipInspectorPlugin(entries: StateFlow<List<DebugEntry>>, secure: SecureWindow) : DebugPlugin`,
`id="swip"`, `title="SWIP"`. `Content(scope)`:

- `val list by entries.collectAsState()` — StateFlow, collect on main (simpler than LogsPanel's
  poll bridge).
- **Filter**: segmented `All / Events / Dropped / State` (record-type + dropped-only), like
  `LevelFilter`.
- **List**: `LazyColumn`, `list.asReversed()` (newest first), `key = { it.seq }`. `SwipRow(entry,
  colors, onTap)` renders by `entry.rec` type: `Enqueued` → schema + tier chip; `Dropped` →
  schema + reason badge (`colors.err`); `Sent`/`Batched`/`HealthSnapshot`/`ModeChanged`/
  `ConsentChanged`/… → compact line. **Flat timeline v1.**
- **Detail**: tap → `AlertDialog` — full record data, props monospace, ids, status, wire preview,
  **Copy** button (`scope.copy`). **Values masked (`••••`) by default; reveal-on-tap.** v1 masks
  everything (no per-field `privacy_class` on `DebugRecord`).
- **Capture isolation**: on first value-reveal call `secure.set()`; on dialog dismiss / last
  value re-masked call `secure.clear()`.

## Host wiring (`apps/androidApp/src/debug/.../SwipAnalyticsGlue.kt`)

1. In `swipInit`, build `RingDebugSink(scope, nowMs = { System.currentTimeMillis() },
   maxEntries = <N>)` and inject: `DayfoldSwip.platformDeps(...).copy(debugSink = sink)`.
   **Gate (allowlist):** install the sink ONLY when `BuildConfig.DEBUG`. Shaped as an explicit
   allowlist with `// TODO: gate on channel ∈ {dev,ci} once a real channel signal exists — never
   a != prod blocklist (beta ships to real users)`. Hold `sink` in `SwipAnalyticsHolder`.
2. Register `SwipInspectorPlugin(sink.entries, secureWindow)` in `debugDrawerPlugins()`.
3. **`SecureWindow` seam**: interface `{ fun set(); fun clear() }`; Android impl toggles
   `activity.window` `FLAG_SECURE`. Provided by the host (has the Activity); keeps the plugin
   module Android-window-free at the type level.

## Tests

- Interaction (mirror `LogsPanel` tests) with a fake `DebugScope` + seeded `entries` StateFlow:
  filter narrows the list; tap opens detail; Copy invokes `scope.copy`; masked values hidden until
  reveal; reveal calls `secure.set`, dismiss calls `secure.clear`.
- Per-OS goldens if the drawer module golden-tests panels (reuse harness).
- **Mandatory on-device** (Android edge-to-edge, goldens can't catch): panel renders under host
  chrome insets (no self-applied `safeDrawing`); a screenshot with a value revealed is blanked by
  `FLAG_SECURE`.

## Sequence

(a) publish `swip-debug` (SWIP `publish-kmp`, trim modules to `:swip-debug:…`) + bump dayfold
swip-core 0.1.2→0.1.3, add swip-debug dep · (b) module + plugin skeleton (flat list) · (c) filter +
tap-detail + copy · (d) mask-by-default + reveal + `SecureWindow` · (e) install-gate + register ·
(f) tests + on-device check. TDD throughout.

## Deferred (not v1)

Per-event journey folding by eventId; per-`privacy_class` chips (needs field→class codegen map);
free-text search; iOS/desktop parity.

## Governance

Dayfold ADR for the inspector (next number after 0056 → 0057). Update dayfold memory + SWIP
`swip-project-state.md` (Dayfold-integration line) on completion.
