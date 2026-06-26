# Dayfold typed-content authoring (CLI + Claude)

The MVP wedge: the operator + Claude Code author typed content cards **and
event/project hubs** and `push` them through the content API. The dashboard renders
intelligence produced here — it is not a chatbot. (ADR 0022; content epic.)

Cards are the **feed** — short, time-bound briefing items. Hubs are the standing
**event/project tree** — a multi-member briefing page (the defensible wedge). The
card loop is next; **hub authoring has its own section below.**

## The 6 content types

`file` · `link` · `invite` · `contact` · `geo` · `email` — each is a
`BriefingCard` with `type` + a matching `payload.<type>` object. The canonical
field set is the **generated schema** (`packages/schema/kotlin-gen/Content.kt`,
from `specs/domain-model/schemas/content.schema.json`); the CLI validates against
it locally before the server does.

## Loop

1. **Start from a template** (printed from the classpath starters; the source of
   truth for these lives in `apps/cli/src/main/resources/templates/`):

   ```
   dayfold template invite > card.json
   ```

2. **Edit** `card.json` — set the real fields; replace `REPLACE_WITH_CARD_ID`'s
   role is filled by the push `<cardId>` arg (the body `id` is overwritten by the
   path id server-side, ADR mass-assignment).

3. **Push with local validation** (`--type` runs `validateCard` first — catches a
   wrong payload variant, an unknown field, or a type/payload mismatch *before*
   the network, with field errors instead of a server 422):

   ```
   dayfold push <cardId> card.json --type invite
   ```

   Without `--type`, `push` sends the file unchanged (no local validation).

## Hubs — authoring the event/project tree (the defensible wedge)

A **hub** is a standing page for a family event or project (starting college, a
move, a party) — the multi-member briefing no native OS ships. It's a 3-level tree,
authored top-down with author-chosen slug IDs:

```
hub      id: lillian-butler-2026     (type + title + status + countdown_to)
└─ section   hubId → lillian-butler-2026,  id: dates
   └─ block      sectionId → dates,        id: ebill-due
```

Each level has its own starter + `push` resource flag. The `<id>` you pass to
`push` becomes the resource id (a slug you choose); the body's parent field
(`hubId` / `sectionId`) wires it into the tree:

| Level   | starter            | push                                    | parent field |
|---------|--------------------|-----------------------------------------|--------------|
| hub     | `template hub`     | `push <hubId> hub.json --hub`           | —            |
| section | `template section` | `push <secId> section.json --section`   | `hubId`      |
| block   | `template block`   | `push <blockId> block.json --block`     | `sectionId`  |

Push the hub first, then its sections (each referencing the hub id), then each
section's blocks (each referencing the section id). Unlike cards, hub-tree pushes
run an **always-on** structural pre-check (`validateHubTree`, no `--type` flag):

- **hub** — `title` required; `type` ∈ `vacation` `starting-college` `move`
  `party-event` `new-baby` `medical` `school-year`; `status` (if set) ∈ `active`
  `planning` `archived`.
- **section** — `hubId` required.
- **block** — `sectionId` required; `type` ∈ `text` `markdown` `checklist` `link`
  `document` `contact` `location` `milestone` `budget`.

The server stays the authority (CL-2) for the rest — parent ids resolving,
ordering, field formats.

### Blocks: structured `payload` OR markdown `body_md`

A block carries either a typed `payload` (for `checklist` `link` `document`
`contact` `location` `milestone` `budget`) **or** plain `body_md` markdown — and a
typed block with only `body_md` and no payload still renders its markdown, so
prose-style authoring is fine. `text` / `markdown` blocks are always `body_md`.
`ord` orders sections within a hub, and blocks within a section (ascending).

> **Payload shape — use the render contract, not the generated schema.** The server
> stores `payload` as JSON **verbatim**, so what renders is the client model. It
> currently **diverges** from `content.schema.json` (see `OQ-block-payload-schema`),
> so author with these field names (or just use `body_md`, which always renders):
>
> | block       | `payload` fields (client render contract)                          |
> |-------------|--------------------------------------------------------------------|
> | `checklist` | `items: [{ text, done, due?, assignee? }]`                          |
> | `link`      | `url`, `label?`, `domain?`                                          |
> | `document`  | `url?`, `label?`, `docRef?`                                         |
> | `contact`   | `name?`, `role?`, `phone?`, `email?`                               |
> | `location`  | `address?`, `lat?`, `lng?`                                          |
> | `budget`    | `total?`, `spent?`, `items: [{ text, done }]`                       |
> | `milestone` | (use `body_md`; `date?` optional)                                  |
>
> Do **not** use the schema's `ref` / `mapUrl` / `source` / item `amount`/`paid` —
> the renderer doesn't read them yet. Reconciliation is tracked in the OQ.

### Markdown that renders in `body_md`

The app renders this subset (anything else shows as plain text; a network image is
never inline-loaded):

- `**bold**`, `_italic_`
- `- ` bullets · `- [ ]` / `- [x]` checkboxes · `1.` ordered lists
- `# ` / `## ` headings
- `| a | b |` tables (with a `|---|` separator row; the first row is the header)
- `[label](https://…)` links and bare `https://…` autolinks — schemes limited to
  **https / mailto / tel / geo / sms** (others render as plain text)
- `![alt](url)` images degrade to a `🖼 alt` link (tapped out, never inline-loaded)

### Worked example

```
dayfold template hub > hub.json
#   set title / type / status / countdown_to
dayfold push lillian-butler-2026 hub.json --hub

dayfold template section > sec.json
#   "hubId": "lillian-butler-2026", "title": "Dates & Deadlines", "ord": 0
dayfold push dates sec.json --section

dayfold template block > blk.json
#   "sectionId": "dates", "type": "milestone", "ord": 0,
#   "body_md": "**Aug 1** — E-Bill due in full"
dayfold push ebill-due blk.json --block

dayfold pull --hub lillian-butler-2026     # confirm the rendered tree
```

## What the local validator checks (STRUCTURAL — the server is the authority)

It is a fast structural pre-check, **not** a full replica of the server gate
(CL-2). It catches:

- `type` and `payload` appear together or not at all; the payload's single
  variant key must equal `type`.
- Unknown/mistyped payload fields, and wrong JSON value types (strict decode).
- A legacy kind-only card (no `type`/`payload`) is still valid (back-compat).

It does **not** check the server's format rules — `link.url` well-formedness,
ISO-8601 `*At`/`date` fields, string length caps, integer-ness. Those still 422
at the server, which **remains the authority** (CL-2). Two known asymmetries from
codegen: the validator **requires** `kind` and `provenance.at` even though the
server defaults/relaxes them — so author from `dayfold template` (the starters
include both) rather than a bare hand-written stub.

## Guardrail 3 — email content (binding)

`email.bodyExcerpt` (and any sender/body content) is authored over **the
operator's OWN mail** via the CLI / Claude reasoning. There is **no server-side
Gmail restricted-scope read** — that line keeps M0 clear of CASA. Changing this
posture is an ADR, not a template tweak.

## Privacy chips (honesty)

`privacy.storage` must name a boundary the code/schema actually enforces. A `geo`
card that carries coordinates uses `on_device` ("a copy is cached"), **not**
`location_local` — only *live position* stays local (ADR 0014).
