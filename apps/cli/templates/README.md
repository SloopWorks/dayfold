# Dayfold typed-content authoring (CLI + Claude)

The MVP wedge: the operator + Claude Code author typed content cards and `push`
them through the content API. The dashboard renders intelligence produced here —
it is not a chatbot. (ADR 0022; content epic.)

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
