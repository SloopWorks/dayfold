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
- `related[]` — cross-links to other cards in THIS family:
  `{ relation, targetId, targetType, title?, sub? }` — `relation` is free text
  (e.g. `same-email | same-thread | same-hub | same-trip | attachment | contact-of`),
  `targetId` is another card's id, `targetType` ∈ the same `type` enum as above.
  `title`/`sub` are author-denormalized (a row renders without resolving the
  target) — keep them in sync with the target card's own title/subtitle.
- `relatedKicker` — section header shown above `related[]` rows (e.g. `"FROM THE
  SAME EMAIL"`).
- `importance` — optional `0..1` hint to the on-device Priority & Ordering Engine
  (ADR 0043 §2b). The device clamps and re-ranks — this is a weight hint, NOT an
  author-controlled position; don't treat it as a way to pin a card to the top.
- `not_before` / `expires_at` — show/hide window (ISO-8601).
- `privacy.storage` — honest chip (see guardrails).
- `provenance` — `{ "source": "claude", "at": <ISO-8601> }`.
- `visibility` / `audience` — see **Visibility & audience** below.

Per-type payload keys (all optional; every field is nullable in the generated
schema — this list is exhaustive as of `packages/schema/kotlin-gen/Content.kt`,
not just "the common ones"):
- `file`: `{ filename, mime, size, pages, source, modified, docRef, owner, sharedWith }`
- `link`: `{ url, domain, title, ogDesc, kind, fieldCount, closesAt, savedAt, favicon }`
- `invite`: `{ eventName, host, startAt, place, rsvpBy, rsvpState, guestCount, confirmedCount, notes }`
- `contact`: `{ name, company, role, phone, email, address, deliveryWindow, hours, linkedEventId }`
- `geo`: `{ label, address, lat, lng, etaMin, distance, travelMode, parking, leaveBy, linkedEventId }`
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
  - `link`: `{ url, label?, source?, thumbnailAlt? }`
  - `checklist`: `{ items: [{ id, text, done?, doneBy?, doneAt?, ord?, due?, assignee? }] }`
    — `id` is **required** per item (26-char ULID); `doneBy`/`doneAt`/`ord` are
    member-write fields (ADR 0038) the server/client set on toggle. On a
    re-push, reuse every item's `id` (and leave `doneBy`/`doneAt` as pulled) —
    a hand-authored fresh `id` looks like a new item and drops a member's
    prior checked/unchecked state.
  - `document`: `{ ref, label?, kind? }`
  - `milestone`: `{ date, label }`
  - `contact`: `{ name, role?, phone?, email? }`
  - `location`: `{ label, address?, mapUrl? }`
  - `budget`: `{ items: [{ label, amount, paid? }] }`

## Hub `timeline` — an axis of time (ADR 0045)

Optional hub property (a sibling of `sections`, **not** a block). Gives a dated hub an
axis of time: the client lays the stops out on-device and picks the scale — an intraday
**day** rail (live NOW line) or a multi-month **roadmap** — so the author never sets a
scale. Author the irreducible **stops**; the client computes status / NOW / grouping /
collapse. Start from `dayfold template timeline` (a hub body with a reference timeline),
edit, then `push <hubId> hub.json --hub` (the same content-blind validation as any hub).

```
timeline: {
  title?: string,               // detail header ("Move-in day")
  tz: string,                   // REQUIRED IANA zone, e.g. "America/New_York" — the NOW line
                                //   + day boundaries are evaluated in this zone (travels with the stops)
  stops: [ {                    // REQUIRED, non-empty; author in any order (the client sorts)
    at: string,                 // REQUIRED RFC-3339. Date-only "YYYY-MM-DD" = all-day (roadmap);
                                //   with a time "…THH:MM:SS±offset" = intraday (day rail)
    title: string,              // REQUIRED, one line
    sub?: string,               // one supporting line ("Room 214 · 20-min window")
    major?: boolean,            // a headline milestone → larger, starred in the detail
    done?: boolean,             // author-complete; also auto-done once `at` < now
    assignee?: string,          // free text ("Pat", "Pat + Maya") → initials avatar
    attachments?: [ {           // OS-handoff or in-app jump chips
      kind: "call"|"nav"|"link"|"open", label: string,
      tel?: string,             // kind=call  → tel: (E.164)
      query?: string,           // kind=nav   → maps search
      url?: string,             // kind=link  → https
      ref?: { hubId, sectionId?, blockId? }  // kind=open → in-app jump to another hub/section/block
    } ]
  } ]
}
```

Rules the client applies (do **not** pre-compute these): a stop is *done* if `done` or
its `at` is in the past; scale is auto-selected (day if the focal day has intraday stops,
else roadmap when stops span >14 days or ≥3 date-only); the day view shows only the focal
day; a roadmap with >6 month-nodes collapses a leading done-run into one `✓N`. One timeline
per hub. Content-blind: the server stores + structurally validates only (never reads prose).
Provenance is **authored** ("Added to this hub") — do not imply on-device derivation.

## Visual enrichment — `media` (ADR 0036)

Optional, decorative, fail-safe (a card/hub renders fine without it). **Card and hub
`media` are DIFFERENT shapes — don't cross-apply fields, the server `.strict()`s both:**
- **card** `media: { thumbnailUrl?, imageFit?, imageAlt?, icon?, accentColor? }` — no
  `heroUrl`/`heroFit`; a card's only image slot is the thumbnail.
- **hub** `media: { heroUrl?, heroFit?, thumbnailUrl?, imageAlt?, icon?, accentColor? }`
  — hubs get the hero banner slot too (`heroFit` only applies to `heroUrl`).

Block `link`/`document` may carry `thumbnailUrl`; block `contact` may carry `avatarUrl`.

- **Image URLs** (`heroUrl` / `thumbnailUrl` / `avatarUrl`) MUST be `https` on an allowlisted
  host — currently **`upload.wikimedia.org`** only. Anything else is rejected at author AND
  render time (no parser-differential bypass). **`.svg` is always rejected regardless of
  host** — pick a raster (PNG/JPG) Wikimedia file instead. Always surface the chosen image
  to the operator before pushing.
- `icon` ∈ `school | luggage | medical | move | party | baby | calendar | location | link |
  document | contact | budget | travel | car | food | pet | sport | list` — a curated glyph,
  shown as the fallback tile when no image loads.
- `accentColor` — `#RRGGBB`, decorative only (harmonized to the light/dark theme at render).
- `heroFit` (hub only) / `imageFit` (card only) ∈ `cover | contain`; `imageAlt` —
  accessibility text for the image.
- Lowest-risk enrichment: `icon` + `accentColor` (no URL → nothing to allowlist). See the
  worked example in `apps/cli/examples/hub-college/hub.json`.

## Choosing card vs hub content

- **BriefingCard** = surfaces NOW in the feed (time/place-relevant, short-lived).
- **Hub block** = the durable reference body the card deep-links into.
- A good pattern: author the hub (the dossier), then a few cards that point into it
  at the right moment ("RSVP by Thursday" card → invite section of the party hub).

## Visibility & audience (ADR 0030/0038) — a privacy-relevant choice

The API accepts `visibility` ∈ `family | restricted` (default `family` — visible to
every member) on both cards and hubs. `restricted` requires `audience: [<userId>, ...]`
— only those member ids (plus the author) can see it; anyone else gets a uniform 404
(they can't tell it exists). `dayfold pull`/`dayfold template hub` show the current
value.

**Hub pushes and card pushes are checked differently locally, and this matters:**
- **Hubs** — `dayfold push --hub`'s local pre-check (`Validate.kt::validateHubTree`)
  parses leniently (unknown keys pass through unvalidated), so `visibility`/`audience`
  on a hub reach the server whether or not you get them right — a typo is caught
  server-side only.
- **Cards** — `dayfold push --type <t>`'s local pre-check (`Validate.kt::validateCard`)
  **strictly** decodes against the generated `BriefingCard` schema, which currently has
  **no `visibility`/`audience` property at all**. Putting either field in a card JSON
  makes the CLI hard-reject the push locally with `invalid card JSON: Unknown key
  'visibility'…` — before it ever reaches the server. **Card-level visibility/audience
  is not currently authorable through the CLI/skill** (schema gap, not a formatting
  rule); scope a card's visibility by putting it in a `restricted` hub instead, or by
  authoring it directly against the API.

Treat `restricted`/`audience` as a **privacy decision, not a formatting one** — propose
it explicitly and name exactly which members will and won't see the content (same
propose-confirm bar as guardrail 1) before pushing anything scoped narrower than the
whole family.

## ids

26-char Crockford base32 ULIDs (`^[0-9A-HJKMNP-TV-Z]{26}$`). New content → new id;
update → reuse the id from `dayfold pull`.
