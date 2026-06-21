# CL-PLAT — Platform action effect layer (expect/actual PlatformActions)

**Epic:** `planning/content-detail-epic.md` (the "Platform shims — expect/actual"
CRITICAL cross-cutting item; CL-6 prerequisite) · **ADR:** 0020 (read-only — OS
handoffs only, no backend write) / 0014 (no live-position leak) · **Depends:**
CL-5 (`CardAction` + cards emit via `onAction`, currently no-op) — on `cl-next`.

## Goal

Make the CL-5 card actions actually do something: a per-platform effect layer
that performs a `CardAction` as an **OS handoff** (open URL / dial / SMS /
mail / navigate / copy / share). Closes the "buttons render but tap is a no-op"
gap and unblocks CL-6's detail-action + tap-to-detail wiring.

## Design — mirror the existing `DriverFactory` precedent

The codebase already ships `expect class DriverFactory` with an Android actual
that takes `Context` via its constructor while desktop/iOS are no-arg (common
code never constructs it — each shell does). Use the **same pattern** for
`PlatformActions` (consistency + the epic's "expect/actual" ask):

- `commonMain`: `expect class PlatformActions { fun perform(action: CardAction) }`
- `androidMain`: `actual class PlatformActions(private val context: Context)` —
  `Intent(ACTION_VIEW, Uri.parse(uri))` / `ClipboardManager` / `ACTION_SEND`.
- `desktopMain`: `actual class PlatformActions()` — `java.awt.Desktop.browse` /
  AWT clipboard.
- `iosMain`: `actual class PlatformActions()` — `UIApplication.openURL` /
  `UIPasteboard`.

Each **shell** constructs its instance and passes `onAction = pa::perform` to
`FeedApp` (Android `PlatformActions(applicationContext)`, desktop/iOS no-arg).

## Shared, tested core — `cardActionUri`

A **pure** `cardActionUri(action): String?` in `commonMain` (cards package) builds
the **vetted** URI; each actual just opens it. Centralizes scheme-vetting at one
seam (epic finding: actions are a closed union, not freeform URLs):

- `OpenUrl(url)` → `url` iff scheme ∈ {http, https} (else null — drop)
- `Call(n)` → `tel:<digits/+>` (sanitized)
- `Message(n)` → `sms:<digits/+>`
- `Email(m)` → `m` iff it starts `mailto:`
- `Navigate(q)` → `geo:0,0?q=<percent-encoded q>` (ADR 0014: a *query*, never the
  device's live coordinates)
- `Copy`/`Share`/`OpenDetail` → null (not URI-openable; Copy/Share handled
  directly by the actual, OpenDetail is in-app nav → CL-6, ignored here)

Rejected/unsupported scheme → `null` → `perform` does nothing (fail-safe). Every
OS call is `runCatching`-guarded (no installed handler → no crash).

## Scope / cuts

- `OpenDetail` is **nav, not an OS effect** — `perform` ignores it; CL-6 routes it
  through the redux nav layer. (At M0 pre-CL-6, tapping a card still no-ops for
  detail — that's expected; this slice lights up the *handoff* actions only.)
- `Copy` (all platforms) + `Share` (Android `ACTION_SEND`; desktop/iOS =
  clipboard fallback) implemented but **not yet emitted** by any CL-5 card
  (forward-decl for CL-6 detail). Keep minimal.
- No new UI, no nav state, no detail screen (CL-6).

## Security / privacy

- Read-only (ADR 0020): every effect is an OS handoff; nothing writes our backend.
- Scheme allowlist in `cardActionUri` (http/https/mailto/tel/sms/geo only) —
  rejects `javascript:`/`intent:`/`file:`/`content:` etc. (epic finding). The
  card layer can't construct arbitrary intents; only the union flows here.
- `Navigate` emits a **place query**, never live position (ADR 0014).
- `mailto:` is passed through as authored (CL-5 builds `mailto:<fromAddr>`); no
  unsanitized subject/body injection at M0 (no params built yet).

## Files

- `cards/PlatformActions.kt` (commonMain) — `expect class PlatformActions` +
  pure `cardActionUri` + `sanitizePhone`/`percentEncode` helpers.
- `cards/PlatformActions.android.kt` / `.desktop.kt` / `.ios.kt` — actuals.
- `FeedApp.kt` — `onAction: (CardAction)->Unit = {}` param, passed to FeedScreen.
- `Main.kt` (desktop) / `MainViewController.kt` (iOS) / androidApp `MainActivity`
  — construct `PlatformActions` + pass `onAction = pa::perform`.

## Test plan (`desktopTest`)

1. `cardActionUri` pure unit tests: each variant → expected URI; OpenUrl rejects
   non-http (returns null); Call/Message sanitize phone; Navigate percent-encodes;
   Copy/Share/OpenDetail → null. Scheme allowlist holds (a `OpenUrl("javascript:…")`
   → null).
2. `sanitizePhone` strips spaces/punctuation, keeps leading `+`; empty → null.
3. Desktop `PlatformActions().perform(...)` doesn't throw on an unsupported
   scheme (runCatching) — smoke test (no real browser launch assertion).
4. Existing suite stays green; `FeedApp` signature change is source-compatible
   (default `onAction = {}`).

## DoD

`cardActionUri` vetted + unit-tested; 3 platform actuals compile (desktop test +
Android + iOS-sim); shells wired so CL-5 Open/Call/Text/Navigate/Reply perform a
real handoff on device; no scheme-injection path; OpenDetail cleanly deferred to
CL-6.

## Risks

- iOS `UIApplication.openURL` deprecation (vs `openURL:options:completionHandler:`)
  — use the available API; cosmetic.
- Desktop `Desktop.browse` only supports http/mailto on most OSes — tel/sms/geo
  best-effort (guarded). Acceptable (desktop is the dev/preview target).
