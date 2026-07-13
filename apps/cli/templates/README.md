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

### Hub `timeline` — an axis of time (ADR 0045)

A dated hub can carry a `timeline` — a hub **property** (not a block), authored inside
the hub body and pushed with `--hub`. `template timeline` emits a hub with a reference
timeline (both scales + every attachment kind). You author the **stops**; the client lays
them out on-device and auto-picks the scale (an intraday **day** rail with a live NOW line,
or a multi-month **roadmap**) — no scale field to set.

```
dayfold template timeline > hub.json     # a hub body with a `timeline`
# edit stops; replace the `open` attachment ref ids with real hub/section/block ids
dayfold push <hubId> hub.json --hub      # validated (tz present, stops non-empty, each has a date)
```

Each stop needs `at` (RFC-3339; date-only = all-day, `…THH:MM…` = intraday) + `title`;
optional `sub` / `major` / `done` / `assignee` / `attachments` (`call`/`nav`/`link`/`open`).
Content-blind: the server validates structure only. See the curator skill's content-model
reference for the full field list and the client rules (status, scale, collapse).

The server stays the authority (CL-2) for the rest — parent ids resolving,
ordering, field formats.

### Blocks: structured `payload` OR markdown `body_md`

A block carries either a typed `payload` (for `checklist` `link` `document`
`contact` `location` `milestone` `budget`) **or** plain `body_md` markdown — and a
typed block with only `body_md` and no payload still renders its markdown, so
prose-style authoring is fine. `text` / `markdown` blocks are always `body_md`.
`ord` orders sections within a hub, and blocks within a section (ascending).

> **Payload shape — the schema is canonical** (ADR 0035). Author with the
> `content.schema.json` field names below; both the CLI and server now validate that
> a `payload`, when present, carries its type's core field, and the app renders it.
> (Or just use `body_md`, which always renders.) The **bold** field is required.
>
> | block       | `payload` fields (canonical = schema)                  | also accepted (client alias) |
> |-------------|--------------------------------------------------------|------------------------------|
> | `checklist` | **`items: [{ id, text, done?, doneBy?, doneAt?, ord?, due?, assignee? }]`** — `id` required per item, reuse from `pull` on re-push (ADR 0038) | — |
> | `link`      | **`url`**, `label?`, `source?`                          | `domain?`                    |
> | `document`  | **`ref`** (url \| fileRef), `label?`, `kind?`           | `docRef?`                    |
> | `contact`   | **`name`**, `role?`, `phone?`, `email?`                | —                            |
> | `location`  | **`label`**, `address?`, `mapUrl?`                      | `lat?` / `lng?`              |
> | `budget`    | **`items: [{ label, amount, paid? }]`**                | `total?` / `spent?`          |
> | `milestone` | **`date`**, **`label`** (or just `body_md`)            | —                            |
>
> During the M0→M1 transition the renderer/validators are **tolerant** of the client
> aliases too (so existing render code keeps working); collapsing to one representation
> is an M1 task — see `OQ-block-payload-schema`. New content should use the canonical
> (schema) names.

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

## What the local validator checks

A fast **structural** pre-check, not a full replica of the server gate — see
`.claude/skills/dayfold-curator/references/cli.md` (Push section) for exactly
what it does and doesn't catch, including the `kind`/`provenance.at` codegen
asymmetry. The server (CL-2) remains the authority for format rules
(`url()`, ISO-8601, length/int caps) and is always the final word on a 422.

**Visibility & audience (ADR 0030/0038) aren't in the generated schema.** A
card's `visibility`/`audience` fields are real, server-accepted access
control — not content — so `--type`'s strict decode rejects them as an
unknown field. Push a `restricted`/`audience`-scoped card **without
`--type`**. See `.claude/skills/dayfold-curator/references/content-model.md`
→ "Visibility & audience" for the field shapes.

## Guardrails (binding)

Own-mail-only email content, honest `privacy.storage` chips, propose-confirm,
and the rest of the binding authoring rules live in one place — see
`.claude/skills/dayfold-curator/references/guardrails.md`. Don't restate them
here; that file is canonical.
