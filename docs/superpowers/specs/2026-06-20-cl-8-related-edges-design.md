# CL-8 — Related-edges (cross-links) (design)

**Epic:** `planning/content-detail-epic.md` (CL-8) · **ADR:** 0020 (read-only) /
0022 · **Depends:** CL-2 (server card storage), CL-4 (client data), CL-6
(DetailScreen + `detailStack` nav) — on `cl-next`.

## Goal

The `related[]` cross-links (same-email/thread/hub/trip, attachment↔email) that
populate the **RELATED** section of the detail screen and navigate detail→detail
(re-entering the CL-7 base transition via the existing nav stack).

## Schema (done — codegen regenerated)

Added to `BriefingCard`: `relatedKicker: string` + `related: array` of edges
`{relation, targetId, targetType(enum), title?, sub?}`. **Denormalized title/sub**
so a row renders without resolving the target; `targetId` resolves client-side
against the family's local cache for navigation. TS (zod) + Kotlin (`Related`)
regenerated.

## Layers

1. **Server** — migration `0006_related.sql`: `briefing_cards` gains `related
   jsonb` + `related_kicker text` (nullable). `repo.upsertCard` carries them;
   `SELECT *` serves them on `/sync` (pg jsonb→object). Validation: the
   regenerated `BriefingCardSchema` already validates `related` (strict edge
   objects) — no app.ts change.
2. **Client data** — `Card` gains `related: List<RelatedRef>?` + `relatedKicker`;
   `RelatedRef` mirrors the generated `Related` (`relation`, `targetId`,
   `targetType: String`, `title?`, `sub?`). `Content.sq`: `related` (JSON TEXT) +
   `related_kicker` columns; `ContentStore` encodes on write, decodes per-field at
   projection (guarded, like CL-4 payload).
3. **Client UI** — `DetailScreen` RELATED section: `relatedKicker` header + a row
   per edge (title/sub + chevron) that calls `onAction(OpenDetail(targetId))` →
   the host's handler dispatches `NavToDetail(targetId)` → pushes onto
   `detailStack` → re-renders detail for the target (base transition; back pops).

## Security / privacy (epic-binding)

- **Tenancy / IDOR:** edges ride the existing `authorizeTenant` write path; the
  card is family-scoped. `targetId` is an **opaque string resolved only
  client-side against the OWN family's cache** — the server never resolves it, and
  a device only holds its own family's cards, so a ref to another family's id
  simply doesn't resolve (no cross-tenant read). No new server-side ref check
  needed at M0; documented. (Cross-family-ref *write* prevention would matter only
  if the server resolved refs — it doesn't.)
- **Read-only (ADR 0020):** related rows only *navigate* (in-app `OpenDetail`); no
  backend write. Attachment↔email "promotion" (an email's attachment becoming its
  own `file` card linked back) is an **authoring-time** decision (CLI/Claude emits
  two cards + the edge) — no client/server mutation; M0 just renders the edge.

## Files

- `apps/api/migrations/0006_related.sql`; `apps/api/src/repo.ts`.
- `apps/client/.../Model.kt` (`RelatedRef` + Card fields); `.../db/Content.sq`;
  `.../ContentStore.kt`.
- `apps/client/.../cards/DetailScreen.kt` (RELATED section).

## Test plan

- **Server** (`typed-content.test.ts` or a new case): a card with `related[]`
  round-trips through upsert → GET/`/sync` (objects, not strings); a bad edge
  (missing `relation`/`targetId`/`targetType`, or unknown `targetType`) → 422;
  tenancy (related rides the family scope).
- **Client** (`ContentStoreTest`): `related` + `relatedKicker` survive
  applyDelta→activeCards; corrupt related JSON → guarded null (card still renders).
- **Client UI** (`FeedSnapshotTest`): a detail with `related[]` renders the
  RELATED section (snapshot); a related row's tap path = `OpenDetail(targetId)`
  (covered by the existing `routeCardAction`→`NavToDetail` + `detailStack`
  chaining tests).

## DoD

Related sections populate per type and navigate to the target detail (stack
chains, back pops); edges round-trip server↔client; bad edges rejected 422;
`:client:desktopTest` + api suite green; Android + iOS-sim compile.

## Risks

- Denormalized title/sub can drift from the target card's real title — accepted at
  M0 (authoring keeps them in sync; the alternative, resolving every row against
  the cache, is more work + fails when the target isn't cached). Tapping still
  navigates by `targetId`.
- A related `targetId` not in the cache → `NavToDetail` resolves null → host shows
  feed (the CL-6 graceful fallback). Acceptable; note it.
