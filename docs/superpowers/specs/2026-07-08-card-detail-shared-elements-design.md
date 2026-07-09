# Card → Detail Shared Elements — design

**Date:** 2026-07-08
**Status:** design (approved to spec) — implementation plan follows
**Composes / refines:** ADR 0022 (D3 fold gesture), ADR 0050 (container transform = the base morph), ADR 0051 (Hero tier). Not itself ADR-class (a visual refinement within the existing container-transform decision).

## Problem

Tapping a Now card opens its detail via a **container transform** (`cardSharedBounds("card-$id")` on the whole card + whole detail; ADR 0050). The card and detail share several elements — the **accent tile** (monogram), the **kicker chip**, the **title**, and the primary **"Open" button** — but the container transform just cross-fades all of them inside the morphing bounds, so nothing visually connects. The Open button also renders in **different colors** (card = teal `FilledTonalButton`, detail = coral filled `Button`), so even a naive share would change color mid-morph.

## Goal

Promote the common elements to **shared elements** that travel/resize into their detail positions, so the header + button glide into place while the body cross-fades. Do it in a way that is **correct across all current types and self-scaling to future types**, degrading gracefully where content differs.

## Non-goals

- Sharing type-specific affordances (invite RSVP, contact Call/Text, geo map) — they live in the card's `extra` vs the detail's `HeroMedia` (different positions); they cross-fade. Out of scope.
- Sharing the description — card shows a one-line summary (`bodySummaryFor`), detail shows full `body_md` (different text); it cross-fades.
- Unifying the contact monogram or giving invite/contact a real shared primary — noted as optional follow-ups, not this pass.

## The correctness core: content-equality gating

An element is shared **only when its content is identical in the card and the detail**; otherwise it cross-fades. This is data-driven — no per-type branching — so new types get shared elements for whatever matches and safely degrade for whatever doesn't.

Per-type reality that forces the gate (verified against `primaryActionFor` / `detailActions` / `typeMonogram` / card monograms):

| Element | Rule | file/link/email | geo | invite | contact |
|---|---|---|---|---|---|
| **title** | always (identical text) | ✅ | ✅ | ✅ | ✅ |
| **kicker** | when `kickerFor(card)` non-blank | ✅ | ✅ | ✅ | ✅ |
| **tile** (monogram) | when `cardMonogram == typeMonogram` | ✅ | ✅ | ✅ | ❌ (card = name initials, detail = "C") → cross-fades |
| **Open button** | when `primaryActionFor(card).second == detailActions(card).firstOrNull()?.action` | ✅ (with URL) | ✅ | ❌ (card "Details" filler ≠ detail "Directions") → cross-fades | ❌ (same) → cross-fades |

Content dependence is covered by the same gate: a card with no valid URL falls back to the "Details" pill while the detail drops that action → actions differ → button not shared (cross-fades). Correct by construction.

## Design

### 1. `sharedTransitionKeys(card): CardSharedKeys` — pure, unit-tested (apps/client)

A pure function returning which of `{tile, kicker, title, button}` are shareable for a card, applying the rules above. `title = true` always; `kicker = kickerFor(card).isNotBlank()`; `tile = cardMonogram(card) == typeMonogram(card)`; `button = primaryActionFor(card).second == detailActions(card).firstOrNull()?.action`. Lives beside `primaryActionFor`/`detailActions` (`apps/client/.../cards`). This is the correctness surface and gets unit tests per type.

Requires extracting **`cardMonogram(card): String`** (pure) from the inline monogram logic currently in `StandardCard`/`InviteCard`/`ContactCard`/`GeoCard`, so the render sites AND the predicate agree on one definition. `typeMonogram(card)` already exists (DetailScreen).

### 2. `Modifier.cardSharedElement(key: String)` — Compose helper (apps/ui)

Mirrors the existing `cardSharedBounds` (`SharedScopes.kt`): applies `sharedBounds(rememberSharedContentState(key), animatedVisibilityScope = avs)` when both `LocalSharedTransitionScope`/`LocalAnimatedVisibilityScope` are present; **no-op (returns `this`) otherwise** — so snapshot tests (which render without the scopes) are unaffected, exactly like `cardSharedBounds`. Keys are namespaced distinct from the container: `tile-$id`, `kicker-$id`, `title-$id`, `action-$id`.

`sharedBounds` (not `sharedElement`) so elements that resize between card and detail (the title font scales; the button width changes) morph cleanly.

### 3. Apply at both call sites, gated

Both `BaseCard` (card) and `DetailScreen`/`HeroHeader`/`ActionsRow` (detail) compute `sharedTransitionKeys(card)` and apply `cardSharedElement("<elem>-$id")` to the tile / kicker / title / primary-button **only when that element's flag is set**. Because the flag is computed identically on both sides, either both apply the key (→ morph) or neither does (→ cross-fade). No orphan source/target.

### 4. Open-button unification (teal tonal)

Where the button IS shared, both ends must be the same color. The card's `PrimaryActionPill` is already `FilledTonalButton` (teal). Change the detail's **primary** action to render as `FilledTonalButton` (teal) too when it is the shared primary (today it's `ActionStyle.Filled` → coral). Secondary detail actions (Copy link, Directions, etc.) keep their styles and just fade in. Net: the one teal button glides card→detail with no color change.

## Risks / verification

- **Visual busyness** — tile + kicker + title + button + container morph + body cross-fade all at once. Keep every traveling element on the same `NavMotion.HeroMs` + easing; tune on-device. Main quality risk.
- **Nested shared elements inside a container transform** — z-order/clipping is finicky in Compose. Verify the traveling elements render above the morphing card; add `Modifier.renderInSharedTransitionScopeOverlay(zIndexInOverlay = 1f)` / `skipToLookaheadSize()` if clipped.
- **Detail button color change (coral→teal)** alters the detail's static render — a card-detail golden (if one exists) re-records (macos + linux). Check for a `DetailScreen`/card-detail golden scene; if present, re-record macos and flag linux.
- **Verification:** on-device on the Pixel — frame-capture the card→detail morph for a link card (shares tile/kicker/title/button) AND an invite or contact card (shares title/kicker only; tile/button cross-fade) to confirm both the share and the graceful degradation. Shared-element modifiers no-op in `:ui:desktopTest`, so it stays green except the detail-button-color golden.

## Files

- New: `sharedTransitionKeys` + `cardMonogram` (extract) in `apps/client/.../cards` (+ unit test); `Modifier.cardSharedElement` in `apps/ui/.../cards/SharedScopes.kt`.
- Changed: `TypedCards.kt` (`BaseCard`/`AccentTile`/`KickerChip`/title/`PrimaryActionPill` gated keys; use `cardMonogram`), `DetailScreen.kt` (`HeroHeader` tile/kicker/title keys; `ActionsRow` primary → teal tonal + `action-$id` key, gated).
