# "PART OF THIS HUB" — show the hub name

**Date:** 2026-07-08
**Status:** Design (approved)
**Related:** ADR 0006/0022 (hub deep-link)

## Problem

The card-detail "PART OF THIS HUB" affordance (`DetailScreen.HubLink`) shows a static
"Open the hub" — the user can't tell *which* hub they'd cross to. `HubLink` receives
only `onOpen`; `DetailScreen` has no access to `state.hubs`, so it can't resolve a name.

## Design

Show the target hub's **`title`** (resolved from state), ellipsized — no schema change.

- **`HubLink(hubName: String?, onOpen)`** — second line = `hubName ?: "Open the hub"`
  (fallback when the hub isn't in the cache), `maxLines = 1`, `TextOverflow.Ellipsis`.
  "PART OF THIS HUB" label + chevron unchanged.
- **`DetailScreen(card, hubName: String? = null, onBack, onAction)`** — new optional param
  (defaulted so snapshot/test callers keep the fallback), threaded to the `HubLink` item.
- **`ContentHost` (`FeedApp.kt`)** resolves it from the hubs it already has:
  `hubLinkTarget(card)?.let { (id, _) -> state.hubs.find { it.id == id }?.title }` and passes
  it into `DetailScreen`.

## Testing
- **Compose/semantics:** `DetailScreen` with a `hubName` renders that text in the hub link
  (and falls back to "Open the hub" when null).
- **Golden:** no change expected (the `detail` snapshot fixtures don't resolve to a cached
  hub, so the link keeps the fallback); if one does, re-record.
- **On-device (Pixel):** the Morton-Finney invite's hub link reads
  "Lillian → Butler · Fall 2026" (ellipsized) instead of "Open the hub".

## Rollout
No ADR, no model change. CHANGELOG (client).
