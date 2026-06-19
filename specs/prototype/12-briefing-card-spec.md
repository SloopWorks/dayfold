# 12 — Briefing Card: content & render contract

The card is the unit of the **Now feed** (M0 surface). This is what the operator
+ Claude Code author via the CLI in the interim, and what the render layer
implements. Data model = `BriefingCard` in `content.schema.json`; this spec is
the **meaning + presentation**.

## Kinds (`kind`)
| kind | meaning | M0 render treatment |
|---|---|---|
| `info` | a neutral heads-up | title + optional body; no accent |
| `action` | something the family should *do* | title + body + (M0) inline action link(s); subtle accent |
| `weather` | weather-derived nudge ("rain at soccer 4pm") | title + body; weather affordance later |
| `countdown` | a dated milestone ("Maya starts college · 12 days") | title + a relative-time/countdown line (emphasized type) |

Render is **kind-aware but graceful** — an unknown/unstyled kind falls back to the
`info` treatment (forward-compatible as kinds grow).

## Fields → render
- `title` (required) — the headline; always shown.
- `body_md` — optional inline markdown (≤1 MB, F8). Rendered via the
  multiplatform-markdown-renderer; **images off at MVP**; **link schemes
  allowlisted** (OQ-markdown-render).
- `not_before` / `expires_at` — time window (below).
- `provenance.source` — drives the **source chip** ("Added by Claude" / "From
  your email" / "On device"). Honest, small, non-anxious.
- `version` — server-owned; not rendered.

## Actions & deep-links — the layered model (important)
The product's value prop is the *actionable* nudge — "party Saturday — ordered
groceries? **[list]**", "school email needs an RSVP **[reply]**". Today the schema
has **three** distinct things, and they are **not** interchangeable:

1. **`target` (internal nav)** — `{hubId, sectionId, blockId}` → deep-links into
   a Hub/Section/Block *inside the app*. **Dormant at M0** (feed-only; Hub render
   deferred). Lights up when Event Hubs render ships.
2. **`actions[]` (ADR 0016, RESERVED)** — structured 2-way buttons / asks that a
   pull-loop processes. **Not built at MVP** — reserved so the wire shape is
   stable; the render layer ignores it for now.
3. **External deep-links** (open Instacart / a mail composer / a maps URL) — the
   *"[list]"/"[reply]"* of the vision. **GAP: these have no structured home on
   the card.** `target` is internal-only; `actions[]` is 2-way-reserved.

**M0 decision (proposed):** external actions are authored as **markdown links in
`body_md`** (`[list](https://…)`, `[reply](mailto:…)`), rendered inline, with the
client **allowlisting schemes** (https, mailto, geo, + vetted app links). This
ships the value prop **now**, needs **no schema change**, and matches how Claude
authors text. 
**Flagged for a post-feed design (OQ-card-actions):** do we want a *structured*
tappable action layer (chips/buttons distinct from inline links — clearer
affordance, analytics, consistent placement)? If yes, that's a card-level
`links[]` (external, non-2-way) separate from `actions[]` (2-way). Decide before
the action surface matters for WTP.

## Time-windowing
- **`not_before`** — don't surface before this time (e.g., a "leave by 3:30"
  card scheduled to appear that afternoon).
- **`expires_at`** — drop from the feed after this time (stale nudges vanish).
- **Feed query (M0):** `active` = `not_before IS NULL OR not_before <= now()`
  AND `expires_at IS NULL OR expires_at > now()`; **order = `not_before` NULLS
  LAST, then `id`** (already in the `feedCards` selector + the `listCards`/`GET
  ?active=true` API). Time-windowing is currently **client-render-only**; the
  `?active=true` server filter exists but the client should also filter (a
  background-synced card may cross its window between syncs).

## States (the feed itself)
- **Empty** — "Nothing yet" (calm; never an error).
- **Syncing** — subtle, non-blocking (the cache renders underneath; ADR 0020).
- **Error** — soft, with the cached feed still shown (offline-first: never a
  blank error screen).
- **Offline** — renders the cached feed; an unobtrusive "offline" affordance.

## M0 render scope (what's built / next)
- **Built:** title + body_md + feed order + empty/syncing/error states
  (`FeedScreen`/`FeedApp`).
- **Next (with TASK-SYNC + this spec):** kind-aware treatments, the source chip,
  markdown action-links (allowlisted), time-window filtering on the client.
- **Deferred:** `target` internal deep-link (needs Hub render), `actions[]` 2-way
  (ADR 0016), structured external action layer (OQ-card-actions), images.
