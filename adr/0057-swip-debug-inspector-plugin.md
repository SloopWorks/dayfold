# ADR 0057: SWIP Debug Inspector Plugin — Live Analytics Timeline in the Debug Drawer, Mask-by-Default

## Status

**Proposed** 2026-07-12 (agent-drafted with the `swip-inspector-plugin` PR;
accept on merge). Composes with ADR 0054 (bug reporter) + ADR 0055
(analytics) — shares the `:swip-wiring` / debug-glue seam.

## Context

ADR 0055 wired live product analytics through the published SWIP KMP SDK,
but the only way to see what an event actually carried (schema, tier,
props, drop reason) was a PostHog dashboard round-trip — no on-device,
offline view of the capture engine's own stream. SloopWorks' Phase-1 SWIP
capture engine now publishes `works.sloop.swip:swip-debug:0.1.0`
(`RingDebugSink`, `entries: StateFlow<List<DebugEntry>>`, SWIP PR #45) — an
in-memory ring of everything the engine enqueues, sends, batches, or drops.
Wiring a UI over it into Dayfold's existing debug drawer (ADR 0019) raises
the same two forces ADR 0054/0055 already resolved:

1. **Privacy:** the stream can carry raw event prop values and identifiers
   (`distinctId`, `sessionId`, `eventId`) — an inspector that renders them
   plainly turns the debug drawer into a PII viewer, and a screen capture of
   an open panel (OS screenshot or the ADR-0054 bug-report bundle) could leak
   them.
2. **Release hygiene:** like the bug reporter and analytics, this is a
   dogfood/debug-only tool — the Play-shipped APK must not grow `swip-debug`
   bytes.

## Decision

1. **New KMP module `apps/debugdrawer-swip`** (mirrors `apps/debugdrawer-
   redux`), holding a Compose `DebugPlugin` (`SwipInspectorPlugin`, `id="swip"`,
   `title="SWIP"`) that renders `RingDebugSink.entries` as a flat, live,
   newest-first timeline in the existing debug drawer (host owns list→detail
   nav, chrome, and insets — the plugin does not self-apply `safeDrawing`,
   same contract every other panel follows). Wired
   `debugImplementation("...")` from `apps/androidApp` **only** →
   **zero `swip-debug` bytes in the release APK**, verified by the release
   compile (`:androidApp:compileReleaseKotlin`, part of this change's
   regression gate).
2. **swip-core bumped 0.1.2 → 0.1.3** to pick up the `debugSink` install
   seam (`SwipPlatformDeps.debugSink: SwipDebugSink? = null`, injected via
   `.copy(debugSink = sink)` on the codegen'd `DayfoldSwip.platformDeps(...)`
   return value — the codegen has no `debugSink` passthrough of its own).
   The `RingDebugSink` is created **once** in the debug glue holder and
   shared between plugin registration (`sink.entries`) and `Swip.init
   (debugSink = sink)`, so the plugin renders exactly what the engine fed
   the sink. **`schema-dayfold` was co-bumped 0.1.2 → 0.1.3**: adding
   `logging`/`debugSink` to `SwipPlatformDeps` in swip-core 0.1.3 changed its
   primary-constructor JVM descriptor, so `schema-dayfold` 0.1.2's generated
   `platformDeps()` (compiled against 0.1.2) hit a runtime `NoSuchMethodError`
   under 0.1.3 — a binary (not source) incompatibility the compile gate
   could not catch, only the on-device smoke did. Fix: republish
   `schema-dayfold` 0.1.3 rebuilt against swip-core 0.1.3 (source unchanged)
   and pin it in `:swip-wiring`.
3. **Install-gate: an allowlist on `BuildConfig.DEBUG`**, not a `!=` prod
   blocklist — a blocklist would silently light up in beta, which ships to
   real users. Carries a `// TODO: gate on channel ∈ {dev,ci} once a real
   channel signal exists`, the same shape ADR 0054/0055 already use.
4. **Privacy: mask-all-by-default.** Every prop value and every identifier
   (`distinctId`, `sessionId`, `eventId`, `eventIds`) renders as `••••` with
   reveal-on-tap; `scope.copy` honors the current mask state (copying a
   masked field copies the mask, not the value). There is **no per-field
   `privacy_class`** on `DebugRecord` in v1 — that needs a field→class
   codegen map SWIP doesn't emit yet — so v1 masks indiscriminately rather
   than trying to guess which fields are safe.
5. **Capture isolation via `FLAG_SECURE`, not a bug-reporter change.** The
   host sets `FLAG_SECURE` on the Activity window for the detail dialog's
   whole open lifetime — before the row tap opens the dialog, so the
   dialog's own window inherits the flag at creation, and cleared on
   dismiss (a `SecureWindow` seam — `{ fun set(); fun clear() }` — provided
   by the host, which owns the Activity, keeping the plugin module itself
   Android-window-free at the type level). Values are still masked-by-
   default until reveal-on-tap. The ADR-0054 bug reporter already captures
   via `PixelCopy` on the Activity window, and `PixelCopy` **honors
   `FLAG_SECURE`** — so the dialog is blanked in both the OS screenshot and
   the dogfood bug bundle for free, without a race on the reveal-toggle's
   transition frame.
6. **Scope: flat live timeline, Android debug only, v1.** Deferred:
   per-event journey folding (grouping by `eventId`), per-`privacy_class`
   chips, free-text search, iOS/desktop parity.
7. **Memory-only, never transmits** — inherited from the Phase-1 capture
   engine; the inspector adds no egress path of its own.

## Rationale

- Reuses the proven `debugImplementation`-from-`:androidApp`-only,
  inert-in-release idiom (ADR 0054/0055) rather than inventing a third
  wiring pattern for the third SWIP surface.
- Mask-by-default is the calmer privacy floor given there's no
  `privacy_class` signal yet — the alternative (guess which fields look
  like PII and mask those) is exactly the kind of review-dependent judgment
  call the mapper-table/allowlist pattern in ADR 0054/0055 was designed to
  avoid.
- `FLAG_SECURE` piggybacking on the existing `PixelCopy` capture path closes
  the screenshot-leak risk with **no bug-reporter code change** — the
  cheapest correct fix, verified by reading the capture path (`PixelCopy`
  honors `FLAG_SECURE` at the OS level) rather than assumed.
- An allowlist gate (not a blocklist) matches ADR 0054/0055's stated reason:
  beta ships to real users, so "not prod" is not a safe proxy for "debug
  tool."
- Alternatives rejected: per-field `privacy_class` masking now (no codegen
  source of truth yet — would be guesswork); a bug-reporter-side blur/redact
  pass keyed to the inspector's reveal state (couples two independent
  debug tools for no gain once `FLAG_SECURE` already solves it at the
  window level); shipping to release behind a flag (no operator ask, and it
  would carry `swip-debug` bytes for no benefit — the inspector has no value
  once analytics isn't visible to the person holding the device).

## Consequences

Positive:
- On-device, offline visibility into exactly what SWIP enqueued, sent,
  batched, or dropped — closes the PostHog-dashboard-round-trip gap from
  ADR 0055.
- Privacy is enforced by two independent fences: mask-by-default rendering,
  and `FLAG_SECURE` window capture isolation — neither depends on the other.
- Release APK is unaffected (`debugImplementation` only, verified by the
  release compile task).

Negative:
- v1 masks indiscriminately (no `privacy_class` granularity) — every reveal
  is all-or-nothing per field, which is coarser than a mature inspector
  would want.
- Flat timeline only — no per-`eventId` journey folding, so following one
  event's enqueue→batch→send lifecycle means visually correlating entries
  by eye.
- Android-debug-only; iOS/desktop dogfooders get no inspector.
- The mandatory on-device smoke test (host chrome insets, `FLAG_SECURE`
  screenshot blanking) is **not yet run** — it needs a physical device and
  is deferred to the operator.

## Revisit Trigger

SWIP ships a field→`privacy_class` codegen map (unblocks graduated masking),
per-event journey folding is needed for real debugging work, or iOS/desktop
dogfood parity is requested — each requires revisiting the v1 scope.
