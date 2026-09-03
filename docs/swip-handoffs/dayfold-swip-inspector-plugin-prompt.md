Build the **SWIP debug-drawer inspector** — a `DebugPlugin` in the **Dayfold repo** that surfaces the live SWIP analytics event lifecycle (events, drops, batches, sends, health/mode/consent) in the existing debug drawer. Work in the Dayfold repo. The SWIP capture engine is already built + published; this session builds only the UI plugin + host wiring.

## Read first
- Design of record (in the SWIP repo): `~/workspace/sloopworksinstrumentationplatform/docs/superpowers/specs/2026-07-12-swip-debug-inspector-phase2-ui.md`.
- The **precedent to mirror almost exactly**: `apps/debugdrawer/src/commonMain/kotlin/com/sloopworks/debugdrawer/panels/LogsPanel.kt` (live newest-first `LazyColumn(key=seq)`, segmented filter, tap→`AlertDialog` detail, `scope.copy`). Also read `DebugPlugin.kt` (the `DebugPlugin`/`DebugScope` contract) and `DebugDrawerConfig.kt` (how plugins register) in the same module, and `theme/` (`LocalDebugDrawerColors`/`DrawerColors`).
- Your Dayfold project memory + the analytics-integration work already in flight (the swip analytics wiring session).

## What SWIP already gives you (published, no SWIP-repo work)
`works.sloop.swip:swip-debug` (publish it via the SWIP `publish-kmp` workflow after PR #45 merges — trim `modules` to `:swip-debug:...`) exposes:
- `class RingDebugSink(scope, nowMs, maxEntries) : SwipDebugSink` — async-worker bounded ring, memory-only. `.entries: StateFlow<List<DebugEntry>>`, `.close()`.
- `data class DebugEntry(seq: Long, ts: Long, rec: DebugRecord)`.
- `sealed interface DebugRecord` with: `Enqueued(eventId, schema, propsRaw, propsStripped?, distinctId, sessionId, tier, critical)`, `Dropped(eventId?, schema, reason: DropReason{CONSENT,MODE,OVERFLOW,DEAD_LETTER})`, `Batched(batchId, eventIds)`, `Sent(batchId, status, count)`, `SendFailed(batchId, attempt, willRetry)`, `Purged(reason)`, `HealthSnapshot(queued, dropsConsentDenied, dropsOverflow, dropsDeadLetter, flushFailures, storageErrors)`, `ModeChanged(from, to, purged)`, `ConsentChanged(consent)`, `IdentityChanged(kind)`, `SessionRotated(reason)`, `FlushInvoked(manual)`, `ChannelInfo(channel, internal, transportKind)`.
- The install seam: `Swip.init(..., SwipPlatformDeps(debugSink = <your RingDebugSink>), scope)` — null in release = dormant, zero-cost.

## Build

**1. New Dayfold module** (e.g. `apps/swip-inspector` or a debug-only source set beside the drawer) depending on `works.sloop.swip:swip-debug` + the `com.sloopworks.debugdrawer` module. Android debug variant only (v1).

**2. `SwipInspectorPlugin(entries: StateFlow<List<DebugEntry>>) : DebugPlugin`** — `id="swip"`, `title="SWIP"`. Mirror `LogsPlugin.Content(scope)`:
- `val list by entries.collectAsState()` (simpler than LogsPanel's poll bridge — it's already a StateFlow; collect on main).
- Segmented filter `All / Events / Dropped / State` (type + dropped-only) — like LogsPanel's `LevelFilter`.
- `LazyColumn`, newest-first (`list.asReversed()`), `key = { it.seq }`. A `SwipRow(entry, colors, onTap)` renders by `entry.rec` type: `Enqueued`→schema + tier chip; `Dropped`→schema + reason badge (colors.err); `Sent`/`Batched`/`HealthSnapshot`/`ModeChanged`/`ConsentChanged`/…→compact line. **Flat timeline v1** (folding a per-event journey by eventId is a follow-up).
- Tap → `AlertDialog` detail (like LogsPanel): the record's full data — props (monospace `FontFamily.Monospace`), ids, status, wire preview — with a **Copy** button (`scope.copy(...)`).
- Colors from `LocalDebugDrawerColors.current` (muted/warn/err/accent); **labeled** privacy/status chips (text always visible — never a bare colored dot; resolves the direct_pii-red vs SendFailed-red collision). material3 is fine (LogsPanel uses `AlertDialog`/`Text`/`TextButton`).

**3. Privacy/security wiring (host):**
- **Install-gate (allowlist):** construct the `RingDebugSink` and pass it to `Swip.init(debugSink = sink)` ONLY when `BuildConfig.DEBUG` AND the resolved channel ∈ `{dev, ci}`. Never a `!= prod` blocklist — `beta` reaches real users. Register `SwipInspectorPlugin(sink.entries)` in `DebugDrawerConfig(plugins = [...])`.
- **Mask-all-by-default:** in the detail view, prop VALUES render masked (e.g. `••••`) with reveal-on-tap. v1 masks everything (the `DebugRecord` carries no per-field `privacy_class`; a per-class chip is a follow-up that can consume a field→class codegen map like `generatePseudonymousStripKotlin`).
- **Capture isolation:** while the SWIP panel shows unmasked data, the drawer window must set `FLAG_SECURE` (Android) — AND confirm the existing bug-reporter's window/screenshot capture (`PixelCopy`/`drawHierarchy`) EXCLUDES the drawer window, so an open inspector's raw PII can't land in a dogfood bug bundle (which transmits). If the drawer already sets FLAG_SECURE or the bug-reporter already excludes overlays, verify it; else add it.

## Tests (mirror the drawer's existing panel tests)
- Interaction tests with a fake `DebugScope` + a seeded `entries` StateFlow: filter narrows the list; tap opens detail; Copy invokes `scope.copy`; mask hides values until revealed.
- Per-OS goldens if the drawer module golden-tests panels (reuse its harness).
- **Mandatory on-device check** (Android edge-to-edge): panel renders correctly under host chrome insets (host owns insets — do NOT self-apply `safeDrawing`; LogsPanel doesn't), and a screenshot with the panel open is blanked by `FLAG_SECURE`. Goldens can't catch either.

## Conventions
- New git worktree + branch off latest `origin/main`; TDD; one coherent PR; Dayfold build/test/lint gates before commit; commit trailer `Co-Authored-By: Claude <model> <noreply@anthropic.com>`; push, open PR, poll CI green, merge on the user's say-so.
- Bind facades only; the plugin depends on `swip-debug` (the debug artifact) — that's expected (it's debug-only tooling, not `:client`).
- Write a Dayfold ADR for the inspector; update Dayfold memory + the SWIP `swip-project-state.md` when done.

## Scope check before you start
Small + well-precedented (LogsPanel). Propose a short plan: (a) module + plugin skeleton rendering a flat list; (b) filter + tap-detail + copy; (c) mask-by-default + reveal; (d) install-gate + register + FLAG_SECURE/capture-exclusion; (e) tests + on-device check. Confirm the drawer's current FLAG_SECURE/bug-reporter-capture behavior with the user before relying on it. Deferred (not v1): per-event journey folding, per-privacy_class chips, free-text search, iOS/desktop.
