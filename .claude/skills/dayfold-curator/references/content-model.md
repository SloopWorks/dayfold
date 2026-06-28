# dayfold content model (authoring reference)

Source of truth: `specs/domain-model/schemas/content.schema.json`. This is a
condensed authoring view. When in doubt, `dayfold template <type>` prints a valid
starter; edit that rather than hand-writing.

## BriefingCard — the "Now" feed surface

Required: `id`, `kind`, `title`, `provenance`.
- `kind` ∈ `action | info | weather | countdown` (default `info`).
- `type` ∈ `file | link | invite | contact | geo | email` — drives the card
  layout. Payload is `payload.<type>` (single variant key == `type`).
- `body_md` — limited inline markdown (snippet/embed). On `push`, bare phone numbers and
  emails in ANY body_md are auto-linked to tappable `tel:`/`mailto:` links — write them as
  plain text, NOT hand-rolled markdown links (`dayfold push … --no-linkify` opts out).
- `media` — optional visual enrichment (icon / accent / hero image; see Visual enrichment).
- `target` — deep link `{hubId, sectionId?, blockId?}` into a hub.
- `hubRef` — parent hub id (the "PART OF THIS HUB" pane).
- `triggers[]` — relevance: `{ "when": { "at": <ts>, "alert_offset": "-PT1H" } }`
  or `{ "geo": { "lat","lng","radius_m","label" } }` (geo matched on-device).
- `related[]` — cross-links to other cards in THIS family.
- `not_before` / `expires_at` — show/hide window (ISO-8601).
- `privacy.storage` — honest chip (see guardrails).
- `provenance` — `{ "source": "claude", "at": <ISO-8601> }`.

Per-type payload keys (the common ones):
- `file`: `{ filename, mime, size, pages, source, modified, docRef }`
- `link`: `{ url, domain, title, ogDesc, kind, fieldCount, closesAt, savedAt }`
- `invite`: `{ eventName, host, startAt, place, rsvpBy, rsvpState, guestCount, confirmedCount, notes }`
- `contact`: `{ name, company, role, phone, email, address, deliveryWindow, linkedEventId }`
- `geo`: `{ label, address, lat, lng, etaMin, distance, travelMode, parking, leaveBy }`
- `email`: `{ from, fromAddr, subject, date, threadLen, bodyExcerpt, attachments, labels }` (own mail only)

## Hub → Section → Block — project/event containers

**Hub** — required `id`, `type`, `title`.
- `type` ∈ `vacation | starting-college | move | party-event | new-baby | medical | school-year`.
- `status` ∈ `planning | active | archived` (default `active`).
- `start_at` / `end_at` / `countdown_to` (ISO-8601). `sections[]`.
- `media` — optional visual enrichment (hero banner icon/accent; see Visual enrichment).
- `dayfold template hub` also emits `visibility` (e.g. `"family"`). Hub-tree
  shape is server-authoritative (no CLI generated schema) — start from the
  template rather than a hand-written stub.

**Section** — required `id`. `title`, `ord`, `blocks[]`. Body carries `hubId`.

**Block** — required `id`, `type`, `provenance`. Body carries `sectionId`.
- `type` ∈ `text | markdown | link | checklist | document | milestone | contact | location | budget`.
- `text`/`markdown` use `body_md` (no payload). Others use `payload`:
  - `link`: `{ url, label?, source? }`
  - `checklist`: `{ items: [{ text, done?, due?, assignee? }] }`
  - `document`: `{ ref, label?, kind? }`
  - `milestone`: `{ date, label }`
  - `contact`: `{ name, role?, phone?, email? }`
  - `location`: `{ label, address?, mapUrl? }`
  - `budget`: `{ items: [{ label, amount, paid? }] }`

## Visual enrichment — `media` (ADR 0036)

Optional, decorative, fail-safe (a card/hub renders fine without it). On a **card** or
**hub**: `media: { heroUrl?, thumbnailUrl?, heroFit?, imageAlt?, icon?, accentColor? }`.
Block `link`/`document` may carry `thumbnailUrl`; block `contact` may carry `avatarUrl`.

- **Image URLs** (`heroUrl` / `thumbnailUrl` / `avatarUrl`) MUST be `https` on an allowlisted
  host — currently **`upload.wikimedia.org`** only. Anything else is rejected at author AND
  render time (no parser-differential bypass). Always surface the chosen image to the operator
  before pushing.
- `icon` ∈ `school | luggage | medical | move | party | baby | calendar | location | link |
  document | contact | budget | travel | car | food | pet | sport | list` — a curated glyph,
  shown as the fallback tile when no image loads.
- `accentColor` — `#RRGGBB`, decorative only (harmonized to the light/dark theme at render).
- `heroFit` ∈ `cover | contain`; `imageAlt` — accessibility text for the image.
- Lowest-risk enrichment: `icon` + `accentColor` (no URL → nothing to allowlist). See the
  worked example in `apps/cli/examples/hub-college/hub.json`.

## Choosing card vs hub content

- **BriefingCard** = surfaces NOW in the feed (time/place-relevant, short-lived).
- **Hub block** = the durable reference body the card deep-links into.
- A good pattern: author the hub (the dossier), then a few cards that point into it
  at the right moment ("RSVP by Thursday" card → invite section of the party hub).

## ids

26-char Crockford base32 ULIDs (`^[0-9A-HJKMNP-TV-Z]{26}$`). New content → new id;
update → reuse the id from `dayfold pull`.
