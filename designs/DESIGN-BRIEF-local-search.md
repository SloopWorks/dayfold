# Design Brief / Claude Code Prompt — Local Search

**Hand this whole file to a fresh Claude Code session. Use the
`frontend-design` skill.** This is an exploratory ADR 0008 design pass, not build
authorization. Produce hi-fi HTML/CSS prototypes only; do not edit app code,
schemas, ADR statuses, backlog, or source-of-truth product docs.

Authoritative references:

- `research/2026-08-19-local-search-exploration.md` — feasibility, result model,
  lexical/fuzzy/semantic boundaries, and unresolved decisions.
- `adr/0008-design-first-hifi-mockups.md` — mockup and operator sign-off gate.
- `adr/0009-design-system-m3-expressive-adaptive.md` and
  `designs/Family AI dashboard design brief/designs/Design-System.dc.html` —
  Dayfold Material 3 Expressive system.
- `designs/now-derived/Now-Derived-Phone.dc.html` and
  `designs/Family AI dashboard design brief/designs/Hubs-Phone.dc.html` — current
  Now/Hubs phone chrome.
- `designs/content/Detail-Phone.dc.html` and
  `designs/now-derived/Deep-Link.dc.html` — canonical content detail and arrival
  highlight language.
- `designs/content/adaptive/` — medium/expanded pane behavior.
- `adr/0030-per-member-hub-and-card-visibility.md` — search never broadens what
  the active member may see.
- `adr/0020-offline-first-client-data-and-freshness.md` — local DB is the source
  of truth and saved content works offline.

## Prompt 1 — Explore the core search experience

> Design a complete hi-fi local-search experience for Dayfold, a calm family
> dashboard. Search runs over content already saved on the member's device:
> briefing cards, Hubs, sections, blocks, checklists, files, links, contacts,
> event details, and notes. It is a retrieval surface, not an assistant, inbox,
> or second feed.
>
> Produce interactive `.dc.html` prototypes in `designs/local-search/` with a
> gallery `Index.dc.html`. Match Dayfold's M3 Expressive coral/teal/violet system,
> Outfit/Figtree typography, tonal surfaces, current phone chrome, and adaptive
> behavior. Light and dark must both be reviewable. Mobile first at ~390–430 px.
> Use realistic family content, no real user data. Visuals only: no API calls,
> database, app code, embeddings, telemetry, or persisted search history.

### Product truths every state must preserve

1. **On-device and permission-safe.** Results come only from the already-filtered
   local cache. Restricted content the member cannot read never appears. Search
   works offline. A quiet “On this device” trust line is useful; do not turn every
   row into a privacy warning.
2. **A temporary route, not a third tab.** Add a search icon to Now and Hubs app
   bars. Tapping it opens a focused search route. Back returns to the originating
   surface. Keep the existing Now/Hubs bottom navigation visible only where it
   supports the current route; do not invent a Search tab.
3. **One compact ranked list.** Do not render full Now cards. Search results mix
   Cards, Hubs, Sections, and Blocks in relevance order. Each result has a title,
   one breadcrumb, one or two excerpt lines, a destination icon, and only the
   state metadata that helps retrieval.
4. **Honest matching.** Exact words/phrases receive a quiet tonal highlight. A
   typo-only result gets a `Close match` label and highlights the actual target
   word, never characters that are not present. A purely semantic result is not
   part of the primary design; show it only on a clearly labelled exploration
   board, with no fabricated highlight or confidence percentage.
5. **Canonical destination.** Tapping a result opens its normal card/Hub/
   section/block destination and pulses the matched content once using Dayfold's
   existing deep-link arrival language. The normal detail does not stay in a
   permanent search mode.
6. **Calm behavior.** No red badges, trending queries, engagement prompts,
   celebratory empty states, voice-assistant chrome, sparkle icons, chat input,
   answer cards, or “Ask Dayfold.” Search helps someone leave search quickly.
7. **No persisted recent searches in v1.** The no-query state teaches scope with
   examples/categories, not a stored history of sensitive family queries.

### Primary phone states

Produce a reusable `Search-Phone.dc.html` component with light/dark and an
interactive state switcher in the gallery. Include:

1. **Entry from Now:** previous Now screen visible for the transition, then a
   full focused search route with back, clear, keyboard-ready field, placeholder
   “Search cards, Hubs, lists, files, and notes,” and a quiet `On this device`
   line. No results before meaningful input.
2. **Exact mixed results — query `soccer`:**
   - Hub: `Orcas soccer season` · `Hubs` · excerpt/date/location.
   - Checklist block: `Pack cleats, water, and rain jackets` · `Orcas soccer
     season › Packing`.
   - Authored card: `Rain at soccer — pack jackets` · `Now › Weather`.
   Highlight `soccer` in the actual text. Preserve a visibly coherent relevance
   order without grouping everything into separate vertical sections.
3. **Deep phrase — query `permission slip`:** the strongest result is a block
   inside `School › Forms`, with the best excerpt around both terms. Show that a
   result can be found even when its Hub title does not contain the query.
4. **Typo fallback — query `socer`:** a small heading `Close matches for
   “socer”`; the target word `soccer` is highlighted and the row carries a quiet
   `Close match` label. Do not silently rewrite the field to `soccer`.
5. **No results — query `trombone`:** `No saved content matches “trombone”` and
   one compact suggestion: `Try another word, a person, or a Hub name.` No AI or
   authoring action.
6. **Offline:** same useful results with `Offline · searching saved content` in
   the trust/status line. Do not gray out results or show a blocking banner.
7. **Empty cache:** `Search will be ready after your first sync.` Keep back
   available; no broken primary action.
8. **Archived result:** an old `Lake Tahoe trip` Hub may appear with an
   `Archived` label when it is genuinely relevant. Do not let the label overpower
   the match.
9. **Scope filters:** show the considered design for `All`, `Hubs`, `Cards`, and
   `Lists & notes` as M3 filter chips that appear only after results exist. The
   default is `All`. Make the no-filter baseline visually viable; the gallery
   should let the operator compare both densities.

### Result-row visual hierarchy

- Leading destination glyph in a small tonal shape: Hub, card, checklist/note,
  file/link/contact as appropriate. Icons are functional, not decorative.
- Title is dominant; breadcrumb is secondary and uses `Hub › Section` when
  possible.
- Excerpt is one or two lines and contains the strongest actual match.
- Highlight uses a soft tertiary/primary tonal mark with readable text in light
  and dark; it must work without relying only on hue.
- State labels are short: `Close match`, `Archived`, `Offline copy` only where
  truthful. Never show a relevance score, “93% match,” or model label.
- The whole row is one >=48dp target; avoid per-row action menus. Search is for
  opening content.

### Arrival flow

Produce `Result-Arrival.dc.html` with a click-through storyboard/prototype:

1. Search result row for the matching checklist block.
2. Container/push transition to the canonical Orcas soccer Hub.
3. Scroll to `Packing`; pulse-highlight the matched block once.
4. After the pulse, the Hub looks exactly normal. Back returns to search with the
   query and result scroll restored for that session.

If a Hub title result is tapped, open the Hub at the top without inventing an
interior highlight. If a section result is tapped, land at that section header.

### Adaptive states

Produce `Adaptive.dc.html`:

- Compact: full search route and push-to-detail.
- Medium/expanded: search field in the top toolbar, results list in the left
  pane, selected canonical detail in the right pane. Keep the selected detail
  visible while the query narrows when still present; otherwise clear it calmly.
- Keyboard/pointer: arrow through results, Enter opens, Escape clears or closes
  according to focus; show focus treatment and tooltips where platform-appropriate.
- Do not create a dashboard of search metrics or a dense desktop table.

### Optional semantic comparison board

Produce `Match-Modes.dc.html` as an explicitly exploratory comparison, not a
committed product screen:

- lexical exact (`permission slip`);
- fuzzy typo (`socer` → target `soccer`, `Close match`);
- semantic-only paraphrase (`things to bring for Saturday's game` → Packing
  checklist).

For the semantic-only row, use a neutral `Related result` label at most and no
highlight when no actual query text appears. Include a small design annotation
outside the phone explaining why “AI match” and confidence percentages were
rejected. The production-default board remains lexical + bounded fuzzy.

## Copy guardrails

Prefer:

- `Search cards, Hubs, lists, files, and notes`
- `On this device`
- `Offline · searching saved content`
- `Close matches for “socer”`
- `No saved content matches “…”`
- `Try another word, a person, or a Hub name`

Never use:

- `Ask Dayfold`
- `AI search` / `Smart search`
- `Search the cloud`
- `We found an answer`
- `Synced results`
- `Everything is searchable` (not true for hidden/non-navigable/local operational
  state)
- confidence percentages
- “No data leaves your phone” as an absolute product claim; the search operation
  is local, but the saved Dayfold content was already synced.

## Accessibility and motion

- Search field has a spoken label and clear action; results announce destination
  type, title, breadcrumb, and match context without reading markup tokens.
- Highlights preserve WCAG contrast and are not the only match cue.
- Dynamic type can grow without clipping the field, chips, or excerpts.
- Native keyboard traversal and focus order; minimum 48dp touch targets.
- Search→result and result→destination use Dayfold's existing motion taxonomy.
  Arrival pulse runs once, honors reduced motion, and has a static outline
  fallback.

## Output

Create only these files under `designs/local-search/`:

- `Index.dc.html` — gallery, state controls, light/dark mounting, decision board.
- `Search-Phone.dc.html` — reusable compact search route and all core states.
- `Result-Arrival.dc.html` — interactive result→canonical-content journey.
- `Adaptive.dc.html` — compact/medium/expanded behavior.
- `Match-Modes.dc.html` — lexical/fuzzy/semantic comparison board.
- `support.js` — only if shared helpers are needed.
- `NOTES.md` — state map, M3→Compose mapping, motion/a11y notes, design decisions,
  unresolved questions, and confirmation that fixtures are synthetic.

Do not modify existing galleries in Prompt 1. Make every page open locally with
the same conventions as the neighboring Dayfold `.dc.html` designs.

The gallery must make these questions answerable:

1. Does search feel like a fast way out, not another feed or assistant?
2. Are exact, fuzzy, and semantic-only matches visually honest?
3. Can someone understand where every result lives before tapping it?
4. Does the compact row carry enough context without becoming a full card?
5. Does offline search feel fully useful without making a false freshness claim?
6. Is result arrival obvious and then gracefully temporary?
7. Are filters useful, or does the simpler mixed list win?

## Prompt 2 — Adversarial completeness pass

After Prompt 1, run a fresh review against this brief and the research report:

> Review the local-search gallery for correctness. Do not redesign the visual
> language. Find missing states, privacy/visibility leaks, misleading fuzzy or
> semantic highlighting, hidden feed semantics, inaccessible highlight/focus
> treatments, broken narrow layouts, and destination-arrival ambiguity. Add or
> correct only what the brief requires. Verify light and dark, compact and
> adaptive, exact/fuzzy/no-result/offline/empty-cache/archived states, and that no
> prototype performs network, persistence, telemetry, or app-code work.

## Prompt 3 — Simplification pass

Then run a fresh optimization pass:

> Simplify the gallery while preserving every required state. Prefer the mixed
> ranked list, remove any filter or label that does not improve retrieval, reduce
> explanatory copy, and make the first useful result visible sooner. Keep the
> result row compact, the match semantics honest, and the canonical destination
> obvious. Record in NOTES.md what was removed and why.
