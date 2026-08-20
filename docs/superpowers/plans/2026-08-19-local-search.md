# Local Search End-to-End Implementation Plan

**Date:** 2026-08-19
**Status:** Implemented and verified on 2026-08-19
**Goal:** Ship fast, private, on-device search over every navigable item already
available to the active Dayfold member, then verify the complete flow on Android
and iOS.

## Product and architecture decisions

1. **Shared-Kotlin in-memory v1.** Build an immutable derived corpus from one
   writer-consistent SQLDelight snapshot. Use a linear lexical scan plus bounded
   fuzzy fallback. Do not add FTS5, a search table, embeddings, or a model.
2. **Queries never enter durable or observable state.** Raw queries, snippets,
   highlight ranges, and result titles stay out of Redux, DevTools, SWIP,
   analytics, logs, SQLite, outbox, and saved search history. `FeedApp` owns one
   plain, non-saveable in-memory search session so query, results, and scroll survive
   search → canonical destination → Back, then disappear on explicit exit,
   tenant change, sign-out, or host disposal.
3. **Runtime-owned corpus.** A `SearchEngine` belongs to the replaceable family
   child. Its corpus builds and searches on the injected background dispatcher,
   is fenced by `FamilySessionContext`, clears admission and content before a
   family DB wipe, and rejects stale results after replacement.
4. **Permission safety by construction.** The engine reads only the already
   member-filtered local cache. It adds no family ID and no second ACL evaluator.
   Hidden content and orphan descendants are removed; archived Hubs and their
   visible children remain searchable with an `Archived` disclosure.
5. **Canonical navigation.** Search is a temporary tier-1 Push route from Now or
   Hubs, not a tab. Cards open the normal Feed detail. Hubs, sections, and blocks
   open the normal Hub destination. Every visible arrow plus system/predictive
   Back restores the in-memory search session.
6. **Current platform reality.** Android and iOS share the complete Compose UI.
   The Gradle graph exposes Android, desktop JVM, `iosArm64`, and
   `iosSimulatorArm64`; it has no browser, Wasm, or www target. A browser client
   remains blocked on the separately scoped SQLDelight sync-to-async migration,
   so this slice contains no browser implementation or browser verification.
   All matcher/corpus code stays common-Kotlin and avoids JVM/Apple APIs so a
   later web target can reuse it.
7. **ADR 0008 sign-off is explicit.** The operator's 2026-08-19 directive to
   implement the reviewed hi-fi is the build authorization. The durable design,
   research, and backlog records are updated in this change before product code.

## Non-negotiable behavior

- Minimum meaningful query: two non-space characters.
- 75 ms cancellable UI debounce; all corpus construction and scans off-main.
- Default plain mixed list, maximum 50 results, no filters in v1.
- Cap a normalized query at 128 UTF-16 code units and 16 tokens. Ignore later
  query content deterministically rather than allocating without bound.
- Index at most 65,536 UTF-16 code units and 8,192 tokens per document. Always
  retain title and breadcrumb first, then spend the remaining budget on body and
  structured payload in stable order. Content beyond that v1 budget may not
  match; this is an explicit safety/performance tradeoff, not silent data loss.
- Cap each excerpt at 240 UTF-16 code units and the total display text returned
  from one search at 20,000 UTF-16 code units. Select a bounded top 50 while
  scanning; never materialize and sort every hit.
- Check coroutine cancellation at least every 64 documents and every 4,096
  source characters while tokenizing or scanning long content.
- Exact phrase/token/title/prefix/body/breadcrumb/substring matches first;
  every query token must match somewhere.
- Fuzzy runs only when lexical results are sparse: no fuzzy under four
  characters, distance 1 for lengths 4–7, distance 2 for 8–64, at most one
  fuzzy query token per document, whole-token comparisons only, exact always
  ahead of fuzzy.
- Use one bounded common-Kotlin case/diacritic fold table. Fuzzy comparison is
  limited to Latin letters and digits; non-Latin text receives exact and prefix
  matching only. Highlight ranges always refer to the original UTF-16 source,
  expand away from partial surrogate pairs and combining sequences, and never
  claim JVM `Normalizer`, Apple, ICU, or SQLite `unicode61` equivalence.
- Exact highlights use Dayfold coral. Close matches highlight the actual stored
  target token in secondary teal and carry both the `Close matches for…`
  heading and `Close match` row label. Highlighting never fabricates characters.
- No-query, exact, phrase, fuzzy, no-result, offline, empty-cache, archived, long
  content, dark theme, dynamic type, keyboard, and reduced-motion states.
- One result per navigable entity. Section/block breadcrumbs identify Hub and
  section before a tap.
- Arrival cue is source-aware and temporary. Search never reuses the persistent
  `FROM YOUR BRIEFING` badge unchanged.

## Work breakdown

### Phase 1 — shared search model, corpus, and matcher

**New files** under
`apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/search/`:

- `SearchModel.kt`: document/destination/result/response types, source ranges,
  archived state, readiness, and bounded limits. No family ID or visibility
  fields.
- `SearchText.kt`: deterministic common-Kotlin tokenization, Markdown boundary
  cleanup, whitespace/punctuation handling, bounded Latin diacritic folding, and
  normalized tokens carrying their original UTF-16 token ranges. Do not build a
  per-character offset map or claim full ICU/`unicode61` parity.
- `SearchCorpus.kt`: flatten Card, Hub, Section, Block, timeline, checklist,
  file/link/invite/contact/location/email/milestone/budget fields; cascade hidden
  and orphan filtering; one document per destination.
- `SearchMatcher.kt`: staged lexical scoring, top-K stable ordering, excerpt
  selection, truthful exact/fuzzy ranges, and banded early-exit
  Damerau/optimal-string-alignment fallback.

**Tests:** one table-driven matcher/text suite plus `SearchCorpusTest` in
`apps/client/src/desktopTest`. Cover precomposed/decomposed accents, Turkish
dotted I, curly/straight apostrophes, hyphens, emoji/surrogate adjacency, CJK,
combining marks, exact-vs-fuzzy ranking, multi-token requirements, stable ties,
and actual-source highlight ranges.

### Phase 2 — writer-consistent snapshot and family-owned engine

- Add `SearchContent` and `searchContentFlow()` to `ContentStore.kt`. Merge table
  invalidations, conflate them, and read cards, Hubs, sections, blocks, hidden
  IDs, responses, and the existing cursor together under `writeGate` before
  building off-main. The cursor is used only for first-sync copy, never as a
  content revision. A process-local monotonic `searchRevision` belongs to
  `ContentStore`; every search-affecting upsert, tombstone, local content
  creation/update, hide/unhide, response completion change, resync wipe, and
  tenant wipe increments it inside the same `writeGate`. `searchSnapshot()`
  atomically returns revision, readiness, content, hidden IDs, and completed
  subject refs. No `Content.sq` or migration change is needed.
- Add `SearchEngine.kt`. Bind one collector from `DayfoldRuntimeFactory` to the
  runtime's existing replaceable family scope; maintain one immutable corpus
  reference and an in-memory monotonic corpus generation. The runtime's
  composite Hub+Search admission closes and clears synchronously before it
  cancels/joins the family scope or wipes the DB. Corpus publication and search
  response delivery re-check both the snapshot revision and opaque
  `FamilySessionContext`; stale builds and results are discarded. Do not create
  a second lifecycle/admission hierarchy.
- Add `suspend fun search(query: String): SearchResponse` to
  `DayfoldCommandPort`/`DayfoldCommands`. It must not dispatch a query-bearing
  action or log query text.
- Expose one query-free `SearchStatus` stream through the command port. It
  contains only an opaque family-binding generation, corpus generation, and
  readiness. Every accepted rebuild advances the corpus generation; every
  family-child bind advances the binding generation even if the family ID is
  unchanged. The UI clears on binding-generation change and reruns the current
  in-memory query on corpus-generation change, so tombstones, hides, completion,
  sync mutations, and wipes cannot leave a displayed stale result indefinitely.
- Route taps through `openSearchResult(SearchResultHandle)`, where the handle
  carries only destination IDs/kind plus the corpus generation. The engine
  revalidates both current family context and destination visibility/existence
  at tap time before issuing canonical navigation. A stale or completed target
  clears/reruns results instead of opening cached content.
- Let the existing family scope own search cancellation; the tenant-boundary
  close hook only clears the corpus synchronously.

**Tests:** `ContentStoreSearchTest`, `SearchEngineTest`, and one targeted native
concurrency addition. Prove one revision per snapshot, initial/empty readiness,
tombstone, completion, hide, resync, and tenant-wipe invalidation, archived
handling, stale revision/family rejection, displayed-result invalidation,
tap-time destination rejection, same-ID family rebind clearing, synchronous
admission close, and immutable cross-thread publication.

### Phase 3 — query-free navigation and canonical return paths

- Add `Route.Search`, `SearchOrigin { NOW, HUBS }`,
  `DetailReturnDestination { FEED, SEARCH }`, and query-free atomic navigation
  actions in `Model.kt`. `OpenDetailFromSearch` sets the detail and return target
  together. Related-card pushes preserve that target; the final detail pop
  returns to `Route.Search`. The visible arrow and Android system/predictive Back
  all dispatch the same `Back` action.
- Update root/navigation/hub reducers and `BackNav.kt` for:
  - Now → Search → close → Now;
  - Hubs → Search → close → Hubs;
  - Search → card detail → Back → Search;
  - Search → Hub/section/block → Back → Search.
- Replace Hub's boolean return-source representation with a typed
  `HubReturnDestination` and add `SEARCH`, so each Hub close path is unambiguous.
  Represent arrival as a typed level plus ID (`HUB`, `SECTION`, or `BLOCK`) and
  origin; do not overload an optional block ID.
- Route card detail arrow and predictive Back through the same pure `Back`
  resolver so search-origin navigation cannot fall through to Feed.
- Classify Search as tier-1 `Push` in exhaustive `RouteMotion.kt`; add Search
  targets to shell selectors and Reachability coverage.

**Tests:** reducer/back matrix for both origins and every destination kind,
route-motion exhaustiveness, dangling target fallback, and Hub-engine cleanup.

### Phase 4 — shared Compose search UX

**New files** under
`apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/search/`:

- `SearchSession.kt`: plain `remember(searchStatus.bindingGeneration)` state for query, response,
  selection, request generation, and list position. It is never
  `rememberSaveable`, placed in a `Bundle`, Redux, or a state-restoration
  registry, and clears synchronously on explicit close, sign-out, family
  replacement, and host disposal.
- `SearchScreen.kt`: focused field, clear/back, local/offline trust line, all
  empty/result states, cancellable 75 ms search call, keyboard actions, and
  semantics.
- `SearchResultRow.kt`: compact one-action row, destination glyph, breadcrumb,
  excerpt, honest exact/fuzzy marks, archived/close-match labels, and >=48 dp
  target.

**Integration:** hoist the session in `FeedApp`; mount Search in `RouteHost`; add
labelled Search actions to Now and Hub-list top bars; map result taps to canonical
card/Hub commands; preserve search list position while the canonical route is
open; clear keyboard focus before navigation.

`SearchResponse` contains only request generation, binding/corpus generation/readiness,
and results. It never echoes the query. Raw query, snippets, ranges, and result
objects remain in the non-saveable session only.

Do not add `imePadding` inside Search: the existing `SafeArea` already includes
IME insets on iOS. Always render a visible back button because the iOS host is a
direct `ComposeUIViewController`, not a `UINavigationController`.

**UI tests:** a compact table of field/clear semantics, minimum input, mixed order, exact and teal
fuzzy marks, no result, offline, empty cache, archived, whole-row click, back
restoration, dark theme, long/dynamic text, keyboard focus, and reduced motion.
Add one search snapshot scene and inspect the generated macOS image. Do not turn
this slice into an adaptive/desktop-input project; wider layouts only need to
remain safe, and filter chips stay out of v1.

### Phase 5 — source-aware one-shot arrival

- Reuse the existing Hub focus/arrival path, adding only origin (`briefing` or
  `search`) and destination level (`hub`, `section`, `block`) without carrying
  the raw query.
- Scroll section/block matches into view. Show coral/teal source-aware outline
  and `SEARCH MATCH` disclosure once, then clear it after the existing arrival
  interval. With Reduce Motion, show the temporary static outline without a
  pulse. Hub-title results open at the top with no fabricated interior mark.
- Preserve the existing briefing deep-link behavior and labels.

**Tests:** section and block scroll index, temporary cue disappearance,
briefing/search label separation, reduced-motion fallback, and Back restoration.

### Phase 6 — privacy and performance gates

- Add four explicit privacy proofs: (1) the Redux/SWIP recorder sees no query or
  result text during an actual search; (2) the analytics mapper exposes no search
  event; (3) a capture logger/error-reporter receives only fixed,
  content-free error codes even when matching fails; and (4) spy writer,
  transport, API, and outbox dependencies observe zero calls. Architectural
  guards prove query/result types are not Redux actions or `AppState` fields and
  `SearchEngine` has no writer, network, analytics, or logger dependency. Search
  exceptions are mapped to fixed content-free failures.
- Add non-flaky diagnostic probes at 500, 2,000, and 10,000 documents, including
  long Markdown/checklists. Measure corpus build, warm exact,
  prefix, and zero-hit fuzzy worst case.
- Acceptance target: 2,000-document corpus build <250 ms and warm query p95
  <50 ms after debounce on the dogfood Android device and iOS simulator. Record
  numbers; do not make shared CI fail on host wall-clock variance. A real 10,000
  document or dogfood miss triggers an FTS5 production-driver capability spike.

## Parallel implementation ownership

- **Core agent:** new common search model/text/corpus/matcher files and pure
  desktop tests only.
- **UI agent:** new shared Compose search files and their isolated UI tests only,
  against the agreed core interfaces.
- **Platform/test agent:** Android instrumentation, iOS-native concurrency/perf
  probes, snapshot fixture/semantics coverage only.
- **Integration owner:** `ContentStore`, runtime/factory/commands, Redux model and
  reducers, `FeedApp`, top bars, Hub return/arrival integration, and all junction
  tests. Junction files are intentionally single-owner.

## Verification gates

### Shared and packaging

```sh
cd apps
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest :ui:desktopTest :swip-wiring:desktopTest \
    :androidApp:assembleDebug \
    :client:iosSimulatorArm64Test :ui:iosSimulatorArm64Test \
    :ui:compileKotlinIosArm64 \
    :ui:linkDebugFrameworkIosSimulatorArm64
```

### Android

Run the complete connected suite on an API <=36 emulator, then install the debug
APK on the connected Pixel 10 Pro and exercise exact, fuzzy, card, nested-block,
keyboard, system/predictive Back, dark theme, and offline-saved-content states.

```sh
/Users/patrick/Library/Android/sdk/emulator/emulator -list-avds
```

If `fad_atd35` is not running, launch it in a separate shell and resolve its
serial with `adb devices` plus `adb -s <serial> shell getprop ro.build.version.sdk`:

```sh
/Users/patrick/Library/Android/sdk/emulator/emulator -avd fad_atd35 \
  -no-window -no-audio -no-snapshot -gpu swiftshader_indirect
```

```sh
ANDROID_SERIAL=<resolved-api35-serial> ANDROID_HOME=/Users/patrick/Library/Android/sdk \
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :androidApp:connectedDebugAndroidTest
```

### iOS

Generate the Xcode project, build the actual Swift host against the shared debug
framework, install it on the booted iOS 26.3 simulator, and exercise exact,
fuzzy, card, nested-block, keyboard, back restoration, dark theme, background/
foreground, and Reduce Motion states. The debug host already uses the
`busy-family` fake backend.

Physical iPhone verification is not currently available: `project.yml` sets
`CODE_SIGNING_ALLOWED: NO` and the release/signing pipeline remains gated.
Simulator verification is required and this limitation must be reported rather
than implied away.

```sh
cd apps/iosApp
xcodegen generate
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,id=8638BE44-26C3-44A3-9815-901395CA8D84' \
  -derivedDataPath build/DerivedData build
xcrun simctl install 8638BE44-26C3-44A3-9815-901395CA8D84 \
  build/DerivedData/Build/Products/Debug-iphonesimulator/iosApp.app
xcrun simctl launch 8638BE44-26C3-44A3-9815-901395CA8D84 \
  com.sloopworks.dayfold
xcodebuild test -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,id=8638BE44-26C3-44A3-9815-901395CA8D84' \
  -derivedDataPath build/DerivedData \
  -only-testing:iosAppUITests/LocalSearchUITests/testExactFuzzyAndCanonicalBackPaths
```

Record verification evidence before sign-off. “Automated” below means the final
shared/native or Android instrumentation suite exercised the behavior; it does
not imply a manual device session.

| Platform/device | OS | Build/install | Exact | Fuzzy | Card | Nested block | Back restored | Offline | Dark | Keyboard | Reduce Motion |
|---|---|---|---|---|---|---|---|---|---|---|---|
| API 35 emulator | 35 | pass | pass | pass | pass | pass | pass | pass (no network dependency) | pass (19/19 suite in dark) | pass (Compose input) | n/a |
| Pixel 10 Pro | 37 | not connected; not claimed | — | — | — | — | — | — | — | — | n/a |
| `tf-ios-test` simulator | iOS 26.3 | pass: Swift host built, installed, launched | pass (XCUITest) | pass (XCUITest) | pass (XCUITest) | pass (XCUITest) | pass (XCUITest) | pass (in-process fake; no network) | pass (XCUITest in dark) | pass (XCUITest text input) | native/shared automated |

## Review gates

Before implementation, run two fresh adversarial passes over this plan. The
first pair returned `NEEDS CHANGES` and `SIMPLIFY`; the revision/content fence,
typed return model, non-saveable state, bounded Unicode matching, explicit
privacy proofs, and runnable device evidence above are their applied findings.
A final fresh gate must return `PASS` before code begins:

1. **Correctness:** tenant fences, query privacy, result truthfulness, all mobile
   return paths, native compilation, and verification realism.
2. **Simplification:** remove schema, state, platform, and test work that does not
   materially improve the v1 retrieval experience or protect a stated invariant.

After implementation, repeat both passes against the diff and evidence before
declaring the goal complete.

## Final implementation evidence

- Full shared/package gate passed after the final fixes: client, UI, and SWIP
  desktop tests; Android debug assembly and privacy unit tests; client and UI
  iOS-simulator tests; iOS device compilation; and iOS-simulator framework link.
- The complete API 35 connected Android suite passed 19/19 in dark mode. Its
  local-search flow covers exact and close matching, card navigation, nested
  block arrival, Android text input, and query restoration after Back using the
  real shared Compose shell and Redux navigation.
- The actual Swift iOS host rebuilt against the final framework, installed, and
  launched on `tf-ios-test`. Its dark-mode XCUITest passed the complete local
  Search smoke: fake sign-in; exact and close matching; card open/Back with
  `socer` restored; nested block open with `SEARCH MATCH`; and Back with
  `balloons` restored. Native tests additionally cover concurrent immutable
  publication, Unicode ranges, and diagnostic performance. Physical iPhone
  signing remains unavailable and is not claimed.
- The final iOS-simulator diagnostic sample at 2,000 documents measured 99.92 ms
  to build, 28.75 ms exact, 31.14 ms prefix, and 44.93 ms zero-hit. The 500- and
  10,000-document probes also completed. These are diagnostic single samples,
  not p95 measurements and do not establish the separate dogfood-device p95
  target; every 2,000-document measurement was below the corresponding numeric
  build/query threshold.
- Final correctness and simplification reviews both returned `PASS` after fixes
  for hidden Hub timelines, typed section/block arrival, Unicode-safe excerpt
  bounds, and late punctuation-delimited matches.
