# Local Search Exploration — Fast, Private Content Retrieval

**Date:** 2026-08-19
**Status:** Exploratory recommendation; no architecture or feature-scope decision
has been accepted.
**Question:** How should Dayfold let a member quickly find any content already
available to that member on their device, including useful typo tolerance and a
possible later semantic layer?

## Verdict

**GO for an on-device lexical-search prototype. DEFER semantic search until the
lexical UX is dogfooded and measured.**

The best first version is smaller than a traditional search stack:

1. Build a permission-safe `SearchDocument` corpus from the existing SQLDelight
   cache.
2. Normalize/tokenize each document once when cached content changes.
3. Search that corpus in shared Kotlin with exact, prefix, substring, and bounded
   typo matching.
4. Render one compact ranked list of navigable results with a breadcrumb and the
   best matching excerpt. Highlight exact target text; identify a typo-only hit
   as a **Close match** rather than pretending the typed term appears verbatim.
5. Open the canonical card, Hub, section, or block and reuse Dayfold's existing
   deep-link arrival highlight.

This ships the user value without a server endpoint, a second durable database,
a model download, or a platform-specific search engine. It also composes cleanly
with a future E2EE posture: the server never receives the query or an index.

SQLite FTS5 is the right **scale-up path**, not the safest baseline. FTS5 has
excellent Unicode tokenization, prefix indexes, BM25 ranking, and built-in
highlight/snippet helpers [fact:[SQLite FTS5 documentation](https://www.sqlite.org/fts5.html)].
Dayfold's pinned SQLDelight 2.3.2 also added parser support for synthesized FTS5
columns [fact:[SQLDelight 2.3.2 changelog](https://sqldelight.github.io/sqldelight/2.3.2/changelog/)].
But Android explicitly documents that FTS5 availability depends on the database
driver and names the AndroidX bundled driver as the supported Android path
[fact:[Android FTS5 API reference](https://developer.android.com/reference/kotlin/androidx/room3/Fts5)].
Dayfold currently uses SQLDelight's framework-backed `AndroidSqliteDriver`, not
that bundled driver [fact:repo `apps/client/build.gradle.kts` and
`DriverFactory.android.kt`]. Making FTS5 mandatory would therefore begin with a
driver/packaging compatibility spike, not with search UX.

## Product boundary

Search is a **retrieval surface**, not another feed, inbox, or assistant.

- It searches content the member has already synced and may already read.
- It never broadens visibility or discovers the existence of restricted content.
- It does not author, summarize, answer, or infer new family facts.
- It works offline over the saved cache.
- It does not send queries, clicks, or snippets to the API or analytics.
- It is not a new top-level tab. A search action in the Now/Hubs chrome opens a
  temporary search route; back returns to the prior place.

The mobile conventions line up with this posture. Android describes a search bar
as the primary field when search is the current focus, with results shown as the
query changes [fact:[Android Compose SearchBar guidance](https://developer.android.com/develop/ui/compose/components/search-bar)].
Apple likewise recommends beginning search as the person types, putting the most
relevant results first, keeping results simple, and offering scope controls when
they materially narrow the collection [fact:[Apple search-field guidance](https://developer.apple.com/design/human-interface-guidelines/search-fields)].

## What is searchable

Index **navigable product content**, not every row in the client database. A
search hit must have a useful destination.

| Destination | Indexed fields | Result title / context | Tap behavior |
|---|---|---|---|
| Briefing card | title, body Markdown text, kind/type labels, relevant typed payload fields, provenance/source label | Card title · `Now` · best excerpt | Open card detail |
| Hub | title, type/status labels, authored timeline stop labels, dates | Hub title · `Hubs` · matching stop/date if applicable | Open Hub |
| Section | title plus Hub title as breadcrumb | Section title · Hub title | Open Hub at section |
| Block | body Markdown text, relevant structured payload fields, provenance/source label; checklist item text is flattened into the block document | Best block heading/item · Hub › section · excerpt | Open Hub at block and pulse-highlight it |

Typed payload fields worth flattening include file names/owners, link title and
domain, invite event/host/place/notes, contact name/company/address, location
label/address, email sender/subject/body excerpt/attachment names, checklist item
text, milestone labels/dates, and budget labels. IDs, coordinates, raw visibility
metadata, auth state, outbox entries, notification logs, local calendar
observations, and debug data are not search text.

One navigable entity produces at most one result. If three checklist items in one
block match, return one block hit with the strongest excerpt rather than three
near-duplicates that all open the same place.

### Visibility and lifecycle

The local cache already contains only content the active member may read; ADR
0030 makes that filtered sync and cache wipe on tenant revocation load-bearing
[fact:repo `adr/0030-per-member-hub-and-card-visibility.md`]. Search must build
from that cache and wipe/rebuild on the same family/session epoch. Do not add a
separate family identifier or permission evaluator inside ranking.

Default scope should be live, non-deleted content. Archived Hubs are still useful
retrieval targets and may appear with a quiet `Archived` label. Device-hidden
content should stay out of ordinary results; a later **Include hidden** control is
possible only if dogfooding shows people use Hide as temporary decluttering rather
than a durable preference.

## Recommended v1 index shape

### Not a persistent inverted index yet

`ContentStore` already exposes consistent snapshots of cards, Hubs, sections,
and blocks from the SQLDelight source of truth [fact:repo
`apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ContentStore.kt`].
Use that snapshot to build an immutable, in-memory corpus off the UI thread:

```text
SearchDocument
  key: destination kind + entity id
  destination: card | hub | section | block
  title: display title
  breadcrumb: display path
  body: plain display text used for snippets
  fields: pre-normalized weighted fields
  state: archived / hidden / active
```

Rebuild the corpus when the content snapshot revision or active-family epoch
changes. It is derived state, like `deriveNow` and `deriveTimeline`: disposable,
never synced, and never authoritative.

At expected household scale, a linear scan over pre-tokenized documents is the
leanest implementation [estimate]. Establish the boundary with a benchmark
instead of arguing from intuition:

- fixtures: 500, 2,000, and 10,000 documents, including long Markdown and large
  checklists;
- devices: the dogfood Pixel and an iPhone simulator/device;
- target: query-to-results p95 under 50 ms after a 75 ms debounce, no UI-thread
  work, corpus rebuild under 250 ms for 2,000 documents [estimate];
- escalation: if 10,000 documents or real dogfood misses the target, run the
  FTS5 driver spike and persist `search_document` plus its FTS table.

### Normalization

Apply the same pure function to indexed fields and queries:

1. Unicode-aware lowercase/case fold.
2. Latin diacritic folding where the platform/common implementation can do so
   deterministically.
3. Convert punctuation and Markdown structure to token boundaries while keeping
   the original display string for excerpts.
4. Collapse whitespace.
5. Keep both tokens and a normalized full string for phrase/substring matches.

SQLite's `unicode61` tokenizer is the reference behavior for the later FTS path:
it is Unicode-aware, case-insensitive, and removes Latin diacritics by default
[fact:[SQLite unicode61 tokenizer](https://www.sqlite.org/fts5.html#unicode61_tokenizer)].
The Kotlin prototype needs cross-platform golden tests for names, apostrophes,
hyphens, emoji boundaries, accents, and non-Latin scripts before claiming parity.

## Matching and ranking

Use a staged matcher so typo tolerance improves recall without making ordinary
results feel random.

### Stage 1 — lexical candidates

For every query token, score matches in this order:

1. exact phrase in title;
2. exact whole token in title;
3. title prefix;
4. exact phrase/token in body or structured payload;
5. breadcrumb/Hub/section match;
6. substring match for tokens of at least three characters.

All query tokens should match somewhere by default. A phrase bonus rewards
adjacent tokens. Field weights should dominate modest state/date boosts; a newer
weather card must not outrank the exact old Hub title the person typed.

### Stage 2 — bounded fuzzy fallback

Run typo matching only when Stage 1 yields fewer than three plausible results
[assumption to dogfood]:

- token length below 4: no fuzzy match;
- length 4–7: Damerau–Levenshtein distance at most 1;
- length 8+: distance at most 2;
- compare whole tokens, not every substring;
- require the rest of a multi-token query to match lexically;
- apply a strong score penalty so a true lexical hit always wins.

This catches `socer` → `soccer` and transpositions without letting `rain` match a
large share of unrelated family prose. When the winning excerpt depends on that
fallback, show **Close match** and highlight the actual matched target token.
Never visually mark characters that are not present in the result.

### Stable result order

Suggested starting weights (implementation constants, not accepted product
constants): title phrase 100; title token 80; title prefix 65; body phrase 55;
body token 40; breadcrumb 30; structured alias 25; fuzzy version of any match
multiplies its contribution by 0.55 [estimate]. Tie-break by destination kind,
display title, then stable ID so results do not jump between keystrokes.

The FTS5 scale-up can replace most of this candidate generation with weighted
BM25. FTS5 supports per-column weights, prefix indexes, and `snippet()`/
`highlight()` extraction [fact:[SQLite FTS5 auxiliary functions](https://www.sqlite.org/fts5.html#the_highlight_function)].
Keep Dayfold's explicit fuzzy fallback and UX semantics above it; FTS relevance
does not itself solve misspellings.

## Result presentation

Use **one ranked list, not a feed of full content cards**.

Full Now cards carry priority, response, provenance, and action affordances that
mean something in a briefing. Repeating them inside search would be visually
heavy and would imply Now ranking. A result row needs only:

- destination icon and title;
- one breadcrumb such as `Hubs › Orcas soccer › Packing`;
- one or two lines containing the strongest match;
- matched text with a quiet tonal highlight;
- an optional state label (`Close match`, `Archived`), never a
  confidence percentage;
- a single whole-row tap target; the gallery deliberately leaves the chevron as
  an operator-review question.

Result kinds can mix in a single relevance order. Show scope chips (`All`,
`Hubs`, `Cards`, `Lists & notes`) only after results exist and only if the corpus
has enough variety to make them useful. Do not permanently group results by type:
group headers fragment relevance and force extra scrolling on a phone. Adaptive
layouts may show results in a left pane and the selected canonical detail on the
right.

### Core states

- **Entry / no query:** focused field; “Search cards, Hubs, lists, files, and
  notes”; no persisted recent-query history in v1; quiet “On this device” line.
- **Typing:** update after two non-space characters; keep keyboard open; preserve
  list scroll only while narrowing the same query.
- **Results:** top results visible under the field; count is secondary.
- **Typo fallback:** “Close matches for `socer`” above only the fallback set.
- **No results:** “No saved content matches `…`”; suggest another word or a Hub
  name, not an AI action.
- **Offline:** fully functional; a subtle “Offline · searching saved content”
  label prevents a false freshness claim.
- **Cold/empty cache:** “Search will be ready after your first sync.”
- **Arrival:** open canonical content and pulse the matched block/section once;
  do not leave permanent search highlighting inside normal content.

Search-query history is behavioral family data. Keeping it session-only avoids
a new retention, wipe, and disclosure surface. If dogfooding establishes that
recents are valuable, persist a small local-only ring behind a separate explicit
decision and wipe it at the same tenant boundary [assumption].

## Semantic matching — feasible, not first

Semantic retrieval is technically feasible on both platforms today.

- Google's MediaPipe Text Embedder accepts text and returns float or quantized
  vectors, includes cosine-similarity support, and has Android and iOS guides
  [fact:[MediaPipe Text Embedder overview](https://developers.google.com/edge/mediapipe/solutions/text/text_embedder/index)].
- The small Universal Sentence Encoder artifact linked by Google is about 6.1 MB
  by its current `Content-Length`, and Google reports roughly 10 ms CPU latency on
  its current Samsung S26 Ultra benchmark [fact:[official model artifact](https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite)]
  [fact:[MediaPipe benchmark](https://developers.google.com/edge/mediapipe/solutions/text/text_embedder/index#task_benchmarks)].
- The same MediaPipe task has platform guides for Android and iOS, but iOS uses a
  CocoaPods `MediaPipeTasksText` integration [fact:[Android guide](https://developers.google.com/edge/mediapipe/solutions/text/text_embedder/android)]
  [fact:[iOS guide](https://developers.google.com/edge/mediapipe/solutions/text/text_embedder/ios)].
- Apple also supplies built-in word and sentence embeddings via `NLEmbedding`,
  including arbitrary-sentence vectors for similarity/retrieval
  [fact:[Apple NLEmbedding](https://developer.apple.com/documentation/naturallanguage/nlembedding)]
  [fact:[Apple similarity guide](https://developer.apple.com/documentation/naturallanguage/finding-similarities-between-pieces-of-text)].

The best semantic experiment is a **reranker/fallback**, not a replacement:

1. Keep lexical hits first and exact matches inviolable.
2. Chunk long block/card text into bounded passages and embed off-main.
3. Persist quantized vectors locally with `(modelVersion, contentHash,
   destination)`; rebuild lazily after sync or model changes.
4. Embed the query, brute-force cosine over family-scale vectors, and merge with
   lexical results using reciprocal-rank fusion or a conservative capped bonus.
5. Label results normally; do not show “AI confidence.” Highlight lexical overlap
   when present, but do not fabricate highlights for purely semantic hits.

For a first cross-platform spike, use the same pinned small TFLite model on both
platforms so quality does not silently diverge. Apple `NLEmbedding` is attractive
as an iOS-only zero-bundle experiment, but it produces a different ranking system
from Android. The 300M EmbeddingGemma task is roughly 184 MB by its current
artifact and Google's current benchmark is about 200 ms CPU, so it is
disproportionate for Dayfold search [fact:[EmbeddingGemma artifact](https://storage.googleapis.com/mediapipe-models/text_embedder/embedding_gemma/int4int8/latest/embedding_gemma.task)]
[fact:[MediaPipe benchmark](https://developers.google.com/edge/mediapipe/solutions/text/text_embedder/index#task_benchmarks)].

### Semantic acceptance gate

Do not ship semantic ranking because the demo feels magical. Build a small,
privacy-safe evaluation set from synthetic Dayfold fixtures:

- 30–50 queries with an expected top destination;
- exact/name/date/place queries, typos, paraphrases, and adversarial near-topics;
- measure MRR/Recall@5 plus false-positive review;
- require a material recall gain over lexical+fuzzy without displacing exact
  targets or adding unacceptable cold-start/app-size cost [estimate].

Embedding models encode topical similarity, not truth or intent. Google's own
example notes that opposite-sentiment sentences can score highly because they
share a topic [fact:[MediaPipe model notes](https://developers.google.com/edge/mediapipe/solutions/text/text_embedder/index#universal_sentence_encoder_model)].
That failure mode matters for “did RSVP” versus “did not RSVP,” so semantic-only
ranking must never drive actions or completion state.

## Architecture sketch

```text
/sync
  → SQLDelight cache (already permission-filtered; source of truth)
  → consistent content snapshot
  → SearchCorpusBuilder (off-main, family-epoch fenced)
  → immutable SearchDocument[]
  → SearchMatcher(query)
      lexical → bounded fuzzy fallback → stable rank → excerpt/highlight ranges
  → Search route
  → canonical card/Hub/section/block destination + one-shot arrival pulse
```

Later, only if measured:

```text
SearchCorpusBuilder
  ├─ SQLite FTS5 index (driver spike first)
  └─ local quantized embedding vectors (semantic rerank/fallback)
```

## Phased feasibility plan

### S0 — hi-fi exploration (this session)

Design compact phone/adaptive states, exact vs fuzzy highlighting, offline/no
result behavior, and destination arrival. This clears understanding, not build.

### S1 — pure matcher spike

- Shared-Kotlin `SearchDocument`, normalizer, matcher, ranker, and excerpt model.
- Synthetic corpus/query fixtures; Unicode and typo goldens.
- 500/2k/10k benchmark on representative Android/iOS hardware.
- No UI, schema, telemetry, server, or persisted search history.

### S2 — local-search product slice (only after ADR 0008 sign-off)

- Content snapshot → corpus engine with family/session fencing.
- Search route and navigation entry points.
- Compact mixed result rows, with the plain list as the default; add scope chips
  only if dogfood shows they improve retrieval, plus empty/offline states.
- Canonical deep-link arrival and accessibility/snapshot coverage.
- Privacy guards: query strings never reach logs, analytics, bug reports, sync,
  or outbox.

### S3 — scale trigger only

If benchmarks or dogfood justify it, spike FTS5 on the exact shipped Android and
iOS drivers. Either keep the current drivers with proven compile options, or
propose a driver change through an ADR; do not maintain divergent FTS4/FTS5
behavior by platform.

### S4 — semantic experiment only

Run the evaluation gate with the small shared model. Ship only if it materially
improves retrieval and remains operationally boring.

## Risks and kill criteria

| Risk | Mitigation / stop condition |
|---|---|
| Fuzzy noise makes results feel untrustworthy | Fuzzy only as low-result fallback; strong penalty; dogfood kill if false-positive rate stays noticeable |
| Search index leaks revoked content | Derive only from filtered cache; family-epoch fence; cache/index/query-history wipe tests |
| Full cards turn search into a second feed | Compact navigable rows only; no feed priority/actions/response chrome |
| Highlighting lies on fuzzy/semantic hits | Highlight actual target text only; `Close match` label; no synthetic semantic highlight |
| Long Markdown makes rebuilds or scans janky | Off-main build, excerpt/chunk bounds, benchmark thresholds, FTS escalation |
| FTS5 compiles but fails on a platform runtime | Capability test on production drivers before schema adoption; bundled-driver change is ADR-class |
| Semantic model bloats or complicates iOS packaging | Lexical ships independently; same-model spike; reject if app-size/tooling cost exceeds retrieval gain |
| Search becomes a telemetry leak | No query/result text in analytics, Sentry, logs, SWIP, or server calls; count-only UX metrics would require a separate consent decision |

## Decisions for operator review

1. **Surface:** approve a global temporary search route from Now/Hubs chrome,
   rather than a third tab? **Recommended: yes.**
2. **Result form:** approve compact mixed rows rather than re-rendered feed cards?
   **Recommended: yes.**
3. **History:** keep recent searches session-only in v1? **Recommended: yes.**
4. **Archived/hidden:** include archived Hubs with a label; exclude device-hidden
   content by default? **Recommended: yes.**
5. **Semantic:** treat embeddings as a later measured reranker/fallback, not an
   MVP requirement? **Recommended: yes.**

None of these recommendations authorizes implementation. The new surface still
requires ADR 0008 mockup sign-off; adopting FTS5 as a hard dependency, changing
SQLite drivers, or adding an embedding runtime/model is ADR-class platform and
maintenance work.
