# Research: "Everything is an action" — UI = f(state) taken to the extreme

**Date:** 2026-07-10 · **Status:** synthesis (adversarial review rounds
recorded at bottom) · **Requested by:** operator ("deep thought and research
… taking this concept to the extreme").

**Method.** Four parallel research agents: (1) prior art of single-store /
explicit-action architectures at scale, (2) platform performance mechanics
at 10³–10⁴ action types (Android/iOS/Web/backend), (3) event-sourcing +
sync-engine + hexagonal-architecture literature, (4) a quantitative snapshot
of dayfold's own redux usage today. Raw memos with full citations:
`redux-extreme-state-2026-07-agent-outputs/`. Labels: `[fact:source]` /
`[estimate]` / `[assumption]`. This report is evidence + analysis; it makes
**no decisions** — anything action-shaped at the end is a recommendation for
the operator/ADR process.

---

## 1. The thesis under test

The operator's hypothesis, decomposed into six testable claims:

- **H1 — UI = f(state).** A mobile/web app's UI can be *entirely* a pure
  function of one redux store's state; every UI change is caused by an
  action.
- **H2 — Finite loading abstractions.** All data loading (sync + async)
  collapses to a small, definable set of interfaces/paradigms.
- **H3 — Simplification dividend.** H1+H2 severely simplify application
  state, maximize code sharing across Android/iOS/Web, and shrink test +
  verification time.
- **H4 — Hexagonal boundary.** Platform specifics (and anything else
  exterior to the pure core) interface through a ports-and-adapters seam,
  as in hexagonal architecture.
- **H5 — The extreme.** Taken to the limit, *every* event and data change
  is an action with represented state in the store.
- **H6 — The scaling question.** As the app grows to thousands or tens of
  thousands of action types / events, what happens to threading,
  concurrency, performance, memory, class loading, code indexing — and can
  the bottlenecks be engineered away?

## 2. Verdict up front

**H1, H2, and H4 are confirmed** — not just viable but industry-convergent:
the newest generation of client architectures (LiveStore, Replicache/Zero)
independently re-derived "named replayable actions + derived state + ports
at the edge" as the way to build offline-first, multi-device apps
[fact:https://dev.docs.livestore.dev/evaluation/event-sourcing/,
https://doc.replicache.dev/concepts/how-it-works]; Linear's sync engine is
adjacent (a persisted intent-transaction queue, though over a mutable MobX
graph rather than derived state)
[fact:https://github.com/wzhudev/reverse-linear-sync-engine]. H4's mapping
is worked in §4.4. H2 is confirmed with a specific answer: **five loading
primitives + one read-state type** (§4.2).

**H3 is supported in mechanism, unmeasured in outcome** [assumption]. The
per-interface test collapse (§4.2) and the determinism dividend (§4.3) are
real mechanisms, but no memo measures an actual test-time reduction.
NoRedInk's ~1:1 production-to-test LOC ratio
[fact:https://juliu.is/elm-at-noredink/, figures in §4.1] shows the
paradigm does not shrink test *volume*; the claim is cheaper verification
per unit of confidence, not less test code.

**H5 is refuted in its literal form.** Every mature practitioner community
independently drew the same line: *durable intent* belongs in the store;
*high-frequency ephemeral signals* (keystrokes, scroll, drag frames, live
sensor/location) must stay out and enter only as settled results — with a
second, softer qualification that logs should carry intent, not
intermediate deltas (both in §4.3, §5.1). Twitter Lite measured
the cost of ignoring this: moving per-keystroke composer state out of the
global store "reduced overhead by over 50%"
[fact:https://medium.com/@paularmstrong/twitter-lite-and-high-performance-react-progressive-web-apps-at-scale-d28a00e780a3].

**H6's answer:** no engineering blog documents a healthy app with 1,000+
action types in one flat typed store [estimate — extensive search found
none]. The largest documented healthy single-store deployment is ~500
action types — and *untyped* (string constants, so no compile/IDE cost)
[fact:https://redux.js.org/faq/performance]; typed evidence between ~100
and 1,000 is simply absent in either direction, and the best-documented
typed case at app-wide-reducer scale (a TCA app built by 8 teams) broke the
IDE and required stack-size increases
[fact:https://rodschmidt.com/posts/composable-architecture-experience/]. But the
bottlenecks are **structural, not fundamental**: every one of them has a
known fix (sharding, layered selectors, batching, snapshot+tail-replay,
compaction), and with those fixes the paradigm itself scales into the
thousands of action types (§5–§7). The refined thesis — the version worth
holding — is in §7.

Dayfold-specific grounding: §3. Dayfold-specific recommendations: §8
(R1–R6).

## 3. Grounding: where dayfold stands today

From the codebase snapshot (memo 4; commit `d8dd25f`), dayfold is a real,
shipping instance of the thesis at small scale:

| Dimension | Today | Notes |
|---|---|---|
| Action types | **99** (one flat `sealed interface Action`, one file) | ~66 of 99 are auth/membership/device flows, not content |
| AppState | **57 flat fields**, nesting ≤3 | one `Model.kt` |
| Reducers | **1** hand-written 167-line `when` | deliberate: no `combineReducers` |
| Middleware | 1 (dev action log) + debug-only devtools enhancer | both stripped in release because they serialize full AppState per dispatch |
| Compose subscription | **1** — whole `AppState` via `store.selectorState { it }` | narrowing flagged in code as the unpulled lever (`FeedApp.kt:64-66`) |
| Effects | 4 imperative engine classes + 6 sole-writer DB→store Flow bridges | no thunk/saga; SQLDelight is source of truth (ADR 0020) |
| High-frequency state | **kept out of the store** | scroll/text/animation local; live location injected into selectors, never dispatched (ADR 0014) |
| Loading paradigms in use | **6** (§4.2 maps them to the canonical 5) | |
| LOC | 15.8k main + 11.1k test Kotlin across `:client`/`:ui` | |

Two things stand out. First, dayfold **already practices the two rules the
literature says are load-bearing** (ephemeral-out-of-store; effects at the
edge) — by instinct, not by written convention. Second, its known gaps are
exactly the ones this research predicts will bite in order: whole-state
subscription (recomposition scoping), a monolithic action file (compile
blast radius), and full-state devtools serialization (why devtools is
debug-only) [fact:memo 4, file refs therein].

## 4. Evidence review

### 4.1 Prior art: how the paradigm actually scaled

The pattern across every documented large deployment (memo 1):

- **Elm / NoRedInk** (~212k LOC production Elm, ~200k LOC tests): runs
  **100+ separate TEA programs**, one per page — not one global store. The
  community's hard-won rule is that nesting mini update-functions
  ("components") is the failure mode; scale by flattening into plain
  functions and sharding programs
  [fact:https://juliu.is/elm-at-noredink/,
  https://guide.elm-lang.org/webapps/structure].
- **Redux/JS**: the official FAQ's largest cited healthy production shape
  is ~500 action types / ~400 reducer cases / 5 middlewares
  [fact:https://redux.js.org/faq/performance]. Jira never achieved one
  global store — consolidating its many per-app Redux providers was judged
  "a herculean effort," and Atlassian built react-sweet-state (federated
  small stores) instead
  [fact:https://medium.com/@albertogasparin/react-sweet-state-redux-and-context-the-yummy-parts-f55f49503635].
  Redux Toolkit's own answer to action explosion was codegen +
  per-slice co-location (`createSlice`), making action types an
  implementation detail [fact:https://redux-toolkit.js.org/api/createSlice].
- **re-frame/CLJS** — the one ecosystem that kept a literal single store
  at scale (500-developer companies, 40k+ LOC apps) — survived by making
  dispatch an **async FSM event queue** and by enforcing a **layered,
  memoized subscription DAG** (trivial layer-2 extractors that run on every
  db change; layer-3 materialized views that recompute only when inputs
  change) [fact:https://day8.github.io/re-frame/subscriptions/,
  https://github.com/day8/re-frame/blob/master/src/re_frame/router.cljc].
  Its events are namespaced keywords, so there is no type-level action
  explosion at all — the cost moves to runtime discoverability.
- **Swift TCA** is the best-documented *typed* single-store failure case:
  a 3-year, 8-team production app's root reducer grew until "Xcode had
  problems scrolling through it, and it wouldn't give you valid compiler
  errors"; action sends traverse the whole reducer tree; they raised thread
  stack sizes to avoid overflows
  [fact:https://rodschmidt.com/posts/composable-architecture-experience/].
  Point-Free's own performance doc: action sends are not method-call cheap,
  and "high-frequency actions … should be avoided"
  [fact:https://pointfreeco.github.io/swift-composable-architecture/1.1.0/documentation/composablearchitecture/performance/].
  Their reference app (isowords, ~50k LOC) scales by **86 SPM modules**
  with per-feature action enums
  [fact:https://github.com/pointfreeco/isowords — README verified during
  review; memo 1's "91" was wrong].
- **Android MVI**: Airbnb (Mavericks), Spotify (Mobius), Bumble (MVICore),
  and Slack (Circuit) all independently chose **per-screen/per-feature
  state machines composed by a navigator**, not one app-wide store
  [fact:https://github.com/spotify/mobius/wiki/Concepts,
  https://slackhq.github.io/circuit/] — driven by mobile lifecycles,
  process death, and team-ownership boundaries [assumption].

**Reading.** The paradigm (unidirectional, action-driven, derived UI)
scaled everywhere. The *specific shape* "one flat typed action space, one
monolithic reducer, one undifferentiated subscription" scaled nowhere.
Survivors either sharded (programs, stores, modules) or de-typified and
invested in a runtime dispatch/subscription engine. That distinction — the
paradigm vs. the flat shape — is the central finding of this report.

### 4.2 H2 confirmed: five loading primitives + one read-state type

Convergent evidence that client data loading is a *closed* set (memo 3):
GraphQL's spec hard-codes exactly three operation types (query / mutation /
subscription) [fact:https://spec.graphql.org/October2021/#sec-Language.Operations];
TanStack Query serves most of the React ecosystem with two primitives plus
policies (cached stale-while-revalidate reads; optimistic mutations with
rollback/invalidation) [fact:https://tanstack.com/query/latest]; SWR is
literally named after HTTP RFC 5861
[fact:https://datatracker.ietf.org/doc/html/rfc5861]; offline-first sync
engines add pagination and the persisted outbox
[fact:https://doc.replicache.dev/concepts/how-it-works]. The read-side
state shape is also closed: Elm's RemoteData
(`NotAsked | Loading | Failure e | Success a`) eliminates the
boolean-flag antipattern and has been ported everywhere
[fact:https://blog.jenkster.com/2016/06/how-elm-slays-a-ui-antipattern/].

**The canonical five** (universality is [estimate] — strong convergent
evidence, not a theorem):

1. **Query** — cached read with staleness policy (stale-while-revalidate).
2. **Mutation** — write with optimistic apply + rollback *or* rebase.
3. **Subscription** — server push / reactive local source.
4. **Pagination** — cursor windows over a query.
5. **Outbox / background sync** — persisted pending-intent queue drained
   with retry/backoff/conflict classification.

Each maps mechanically to redux: an action triple
(`Requested/Succeeded/Failed`), a RemoteData-shaped slice, and one port
interface in middleware/effects. Dayfold's six observed paradigms (memo 4
§5) are these five plus one composition: cold-start hydration = Query
served from the local materialization; poll-sync = Subscription (poll
transport); pull-refresh = Query revalidate; checklist-toggle/delete =
Mutation + Outbox; headless notification pass = Query (sync snapshot);
auth flows = Mutations (plus reads like whoami, i.e. Queries, in the same
engine). **Nothing in the app required a sixth primitive** — dayfold reads
as a confirming instance [this report's analysis over memo 4 §5; two
mappings — poll-loop as Subscription-over-poll-transport, auth flows as
Mutations+Queries — stretch the definitions].

The practical consequence: since every feature's loading is an instance of
five interfaces, correctness arguments and tests are *per-interface*, not
per-feature — write property tests once per primitive (e.g. "optimistic
apply then rollback restores prior state"), instantiate per feature. This
is the concrete mechanism behind H3's "reduces test time" claim
[assumption, supported by fast-check's model-based-testing design
[fact:https://fast-check.dev/docs/advanced/model-based-testing/]].

### 4.3 H5's *disciplined* form is client-side event sourcing — validated, with two boundaries

"Every data change is an action with represented state" is precisely event
sourcing with the store as a materialized view (memo 3). Backend ES
literature contributes four directly transferable mechanics: **snapshots**
(persisted materialized state; replay only the tail — Greg Young wouldn't
bother below ~1k events/stream
[fact:https://codeopinion.com/greg-young-answers-your-event-sourcing-questions/]);
**log compaction** (keep last-record-per-key, Kafka-style — LiveStore is
adopting exactly this for client eventlogs
[fact:https://docs.confluent.io/kafka/design/log_compaction.html,
https://github.com/livestorejs/livestore/issues/136]); **upcasting** (old
event versions converted at read time — schema evolution is the pain
practitioners most underestimate
[fact:https://www.sciencedirect.com/science/article/pii/S0164121221000674]);
and **CQRS** (the action log is the write model; selectors/materialized
views are rebuildable read models
[fact:https://martinfowler.com/bliki/CQRS.html]).

The client sync engines then show the *spectrum of log lifetime*:

| System | What's logged | Log lifetime |
|---|---|---|
| LiveStore | every domain event (intent) | full history = source of truth; compaction being added because it hurts [fact:https://dev.docs.livestore.dev/evaluation/event-sourcing/] |
| Replicache/Zero | named mutators (intent) | **pending-window only** — confirmed history discarded; rebase on pull, like git [fact:https://doc.replicache.dev/concepts/how-it-works] |
| Linear | transactions (intent) queued in IndexedDB | pending-window only; server-authoritative deltas update the model [fact:https://github.com/wzhudev/reverse-linear-sync-engine] |
| Figma | property-level **state deltas**, no intent log | none — LWW registers per (object, property) [fact:https://www.figma.com/blog/how-figmas-multiplayer-technology-works/] |

Two boundaries recur in every one of these systems:

1. **Ephemeral never enters the log.** Yjs keeps cursors/presence in a
   separate non-persisted Awareness protocol; Automerge has "ephemeral
   messages"; LiveStore splits synced vs client-only events; Figma keeps
   live cursors out of document sync entirely
   [fact:https://docs.yjs.dev/getting-started/adding-awareness,
   https://automerge.org/docs/reference/repositories/ephemeral/]. This is
   the same line Redux's style guide and TCA's performance doc draw
   (per-keystroke dispatch is an anti-pattern)
   [fact:https://redux.js.org/style-guide/].
2. **Intent, not intermediate deltas.** An event log's distinguishing
   value is captured *intent* (Greg Young)
   [fact:https://www.kurrent.io/blog/transcript-of-greg-youngs-talk-at-code-on-the-beach-2014-cqrs-and-event-sourcing].
   During a drag, the intent is the final position; 300 intermediate
   `MOVED_TO` events record noise [assumption]. Corollary storage math:
   naive JSON op logs run ~55 bytes/op vs ~1.1 bytes/op in Automerge's
   purpose-built columnar encoding — a JSON action log is ~50× larger
   than a purpose-built encoding of the same history [estimate, from
   https://automerge.org/blog/automerge-2/]; how fast the log outgrows
   the *state* depends entirely on the workload's ops-to-state ratio
   (Automerge 2's full-history text document is only ~30% larger than
   the raw text).

**Determinism is the payoff, and it's earned at the edge.** rr achieves
whole-program replay by recording every nondeterministic input at the
syscall boundary [fact:https://robert.ocallahan.org/2014/03/introducing-rr.html];
the redux equivalent is: clocks, random IDs, network payloads, and location
enter *only* as action payloads / selector inputs, never read inside
reducers. Dayfold already follows this (redux-kotlin Rule G "mint at edge",
ADR 0013; location injected into `nowFeed()` at call time). This is what
makes time travel, golden-snapshot CI (`f(state) → PNG`), model-based
testing over reducers, and record/replay of sessions all *cheap* — they are
the same property exploited four ways [assumption, well-grounded].

### 4.4 H4 confirmed: the hexagonal mapping is direct

Cockburn's ports-and-adapters
[fact:https://alistair.cockburn.us/hexagonal-architecture], Bernhardt's
functional-core/imperative-shell
[fact:https://www.destroyallsoftware.com/talks/boundaries], and Elm's
managed effects (Cmd/Sub) [fact:https://guide.elm-lang.org/effects/] read
as one design at three levels of strictness — that identity claim is this
report's synthesis [assumption]. In redux terms: **reducers +
selectors are the functional core; middleware/effects are the adapters;
each of the five loading primitives is a port.** The test-harness-as-adapter
point is Cockburn's original motivation and is dayfold's lived experience:
the headless `BackgroundNotify` path reuses the same pure `nowFeed()`
selector with no store at all, and the snapshot CI drives `f(state)` with
fake adapters — the core is already drivable by tests as "just another
adapter" [fact:memo 4].

One honest caveat to H1's purity: dayfold (per ADR 0020) is really
**UI = f(store-state), store-state = g(SQLite), SQLite = h(event stream)**
— the store is a *projection of a projection*, with the DB as source of
truth. That's not a violation of the thesis; it's the LiveStore
architecture (eventlog → materialized SQLite → reactive queries) arrived
at independently [assumption]. But it means "the store" in H5 should be
read as *the reactive materialization layer*, not necessarily one
in-memory object.

## 5. What breaks at the extreme — bottleneck inventory

At 10³–10⁴ action types and store-mediated event flow, the documented
failure modes, per axis (memo 2 for all platform mechanics):

### 5.1 Threading & concurrency

- **Dispatch is a serialization point by definition.** Every dispatch runs
  the root reducer + notifies subscribers, serialized (JS event loop;
  redux-kotlin's concurrent store = lock-free reads, serialized writes
  [fact:https://reduxkotlin.org/introduction/threading]). Throughput is
  bounded by (reducer cost + subscriber-notification cost) × dispatch rate.
  This is *fine* at dashboard rates (a few dispatches/s) and pathological
  at input/frame rates — the Twitter Lite 50%, the TCA "avoid dozens of
  actions per second," the react-redux pre-batching slowdowns are all this
  one bottleneck [fact:memo 1, memo 2 §4].
- **Main-thread budget**: 16 ms at 60 Hz (8 ms at 120 Hz); reducer +
  notification must fit in what measure/layout/draw leaves over
  [fact:https://developer.android.com/topic/performance/vitals/render].
- **Event cascades**: actions that trigger effects that dispatch more
  actions can starve the UI unless the queue is scheduled
  [fact:https://github.com/day8/re-frame/blob/master/src/re_frame/router.cljc]
  (fix: §6 row 3).

### 5.2 Rendering (the other half of "UI = f(state)")

- **Whole-state subscription defeats skipping.** Compose strong skipping
  compares unstable params by *reference* — a root AppState that changes
  reference on every dispatch means nothing skips unless every child param
  is stable-and-equal
  [fact:https://developer.android.com/develop/ui/compose/performance/stability/strongskipping].
  SwiftUI's old `ObservableObject` has the same storm (any change
  invalidates every subscriber); iOS 17's Observation fixed it with
  property-level tracking — which a monolithic `var state: AppState`
  re-defeats [fact:https://fatbobman.com/en/posts/mastering-observation/].
  The general law: **O(subscribers notified) must scale with the delta, not
  with the store** (fix: §6 row 2; the DAG design is in §4.1).

### 5.3 Memory & GC

- **Immutable-copy churn**: ART's generational concurrent-copying GC makes
  short-lived action objects and shallow `copy()`s nearly free; the danger
  is deep O(state) copies at high dispatch rates [estimate]. **Kotlin/Native
  on iOS is the weak point**: its GC is non-generational and non-moving, so
  allocation-heavy dispatch loops cost more there than on ART — the same
  code has different GC economics per platform [fact:https://kotlinlang.org/docs/native-memory-manager.html].
- **History retention**: in-memory action history with structural sharing
  costs O(N × delta); the moment it's *serialized* (devtools JSON,
  persistence) sharing is destroyed and it becomes O(N × full state) —
  which is why Redux DevTools is the first thing to choke on big stores,
  and why dayfold already ships devtools debug-only
  [fact:https://github.com/zalmoxisus/redux-devtools-extension/issues/658,
  memo 4].

### 5.4 Class loading, binary size, code shape (the 10⁴-types costs)

- **Android**: 10k action data classes ≈ 50–100k method refs — over the
  64k single-dex limit on their own, though multidex makes that a size
  problem, not a wall; R8 class merging exists to collapse exactly this
  long tail, *but* devtools/persistence reflection keeps rules defeat it
  for precisely the classes you have most of [estimate, memo 2 §1].
  Class-loading itself is tens of ms at 10k classes, not seconds
  [estimate].
- **Kotlin `when` over a sealed hierarchy compiles to a linear
  `instanceof` chain** (no jump table) — at 10⁴ flat cases that's ~N/2
  type-checks per dispatch, and one 10⁴-branch method can exceed the JVM's
  64KB-per-method bytecode limit [fact/estimate:
  https://discuss.kotlinlang.org/t/does-when-is-with-sealed-classes-encourage-a-performance-antipattern/18044].
- **iOS/Swift**: the type-checker has known super-linear pathologies on
  large expressions, and Swift 5.6 shipped a (since-addressed) exponential
  regression on enums with associated values
  [fact:https://forums.swift.org/t/large-increase-in-compilation-time-in-swift-5-6-on-enums-with-associated-values/56115].
  TCA's *documented* compile pains are the macro-era ~8× build regression
  and type-check timeouts on large views; "a flat giant action enum is the
  type-checker's worst case" is an extrapolation [estimate] (fix: §6 rows
  4–6).
- **Web/Wasm**: many distinct action *shapes* make generic
  `action.payload` reads megamorphic in V8 (optimizer gives up >4 shapes)
  [fact:memo 2 §3] — a uniform action envelope (`{type, payload, meta}`)
  keeps envelope-level reads monomorphic (reads *into* payloads stay
  polymorphic) [estimate:memo 2 §3]. Kotlin/Wasm binary size
  grows linearly with class count, and a central exhaustive `when`
  references every case, defeating dead-code elimination by construction
  [estimate, memo 2 §3].

### 5.5 Build time & IDE indexing

- **The central Action type is the worst build-cache key in the
  codebase**: adding one case is an ABI change to a type every feature
  depends on and exhaustively matches ⇒ whole-app recompilation blast
  radius [estimate, memo 2 §6]. Kotlin IDE analysis is already a
  documented pain point at scale
  [fact:https://youtrack.jetbrains.com/issue/KTIJ-8163]; that a
  10⁴-subtype sealed hierarchy puts enumeration cost on the *interactive*
  path (every completion popup, every exhaustiveness inspection) is an
  extrapolation from it [estimate].
- Human variant of the same failure: the TCA retrospective's unscrollable
  root reducer and zero encapsulation across 8 teams (§4.1).

### 5.6 Backend & contract

- The moment actions are **persisted or transmitted** (outbox, sync,
  session replay), their schemas become a versioned public contract:
  upcasting machinery, additive-only discipline, and two-app-versions-
  against-one-log compatibility all arrive at once — the empirically
  most-underestimated cost of event sourcing
  [fact:https://www.sciencedirect.com/science/article/pii/S0164121221000674].
  Note dayfold has *already quietly crossed this line*: outbox ops are
  persisted in SQLite [fact:memo 4 §5.4] and therefore survive — and get
  drained after — app upgrades [assumption, sound inference from
  persistence; memo 4 does not state upgrade-replay explicitly].
- Backend mirror-image: an event-sourced backend accepting client intent
  needs command-vs-event discipline (commands can be rejected; events are
  facts), server reconciliation for optimistic clients (Replicache's
  rebase), and log compaction/retention policy [fact:memo 3].

## 6. Bottleneck → solution matrix

Every bottleneck above has a known, documented fix. None requires
abandoning the paradigm; all require abandoning the *flat* shape.

| # | Bottleneck | Solution | Precedent |
|---|---|---|---|
| 1 | Dispatch serialization at high frequency | Two-tier events: ephemeral signals stay in platform-local state; dispatch only settled intent. Batch/coalesce the rest (rAF-aligned notification, batched actions) | Yjs Awareness, Redux style guide, RTK autoBatch, React 18 batching |
| 2 | Notify-the-world subscriptions | Layered memoized selector DAG (cheap extractors → materialized derivations); per-field/narrow bindings with value equality (`fieldState`/`selectorState` narrow slices) | re-frame layers 2/3; Redux many-small-connects; Compose `derivedStateOf` |
| 3 | Event cascades starving UI | Scheduled event queue (FSM) with frame-yield points, instead of synchronous recursive dispatch | re-frame router FSM |
| 4 | O(N) `when` dispatch + 64KB method limit | Hierarchical action space: `sealed interface FeatureAction : Action` per feature; root reducer matches feature groups (tens of them even in a 10³-action app [estimate]), delegates to per-feature reducers (O(features + feature-actions)) | isowords module tree; RTK slices |
| 5 | Whole-app recompile on any new action | Same sharding, but as *module* boundaries: per-feature action types live in the feature module; the root composes interfaces. Codegen'd action modules as rarely-changing leaf deps | isowords (86 modules); Kotlin classpath-snapshot IC |
| 6 | Swift/Kotlin type-checker + IDE cost on giant hierarchies | Never one flat enum: nested per-feature types keep every exhaustive match small; explicit types on hot expressions | TCA nested actions; JetBrains guidance |
| 7 | GC churn (worst on Kotlin/Native iOS) | Structural sharing (copy only the changed path — data-class `copy` already does this); keep per-dispatch allocation O(delta); profile K/N GC signposts before raising dispatch rates | ART TLAB economics; K/N memory-manager docs |
| 8 | Devtools/history memory + serialization | Ring buffer (`maxAge`), state sanitizers, in-memory-only history with structural sharing; recompute-by-replay instead of storing snapshots; production = bounded action ring, no snapshots | Redux DevTools `maxAge`; dayfold's debug-only devtools |
| 9 | Unbounded persisted event log | Materialized state *is* the snapshot (replay tail only); compaction by key for latest-value facts; pending-window-only logs where server is authoritative | LiveStore, Kafka compaction, Replicache |
| 10 | Action-schema drift once persisted | Version + upcast at the deserialization boundary; additive-only changes; treat persisted action schemas as API contracts with codegen from one schema source | ES upcasting literature; dayfold's `packages/schema` codegen posture |
| 11 | Megamorphic dispatch / Wasm bloat (web) | Uniform action envelope (`type` + payload) on the hot path; map-lookup reducers; measure Wasm size before enabling the web target | FSA convention; `createSlice` handler maps |
| 12 | Multi-team coupling on one action tree | Feature-module ownership of action namespaces; cross-feature communication only via a small, deliberately-public set of "domain events" | Schmidt retrospective (negative example); Mobius loop-per-feature |

## 7. The refined thesis (what survives the extreme)

> **A mobile/web app should be built as an event-sourced functional core:
> all durable state lives in one logical store materialized from explicit
> intent actions; all I/O flows through a closed set of ~5 port
> interfaces; UI is a pure projection. The extreme is taken on
> *durability and explicitness*, not on *frequency and flatness*: every
> durable state change is an action; no high-frequency ephemeral signal
> is; and the action space is a sharded hierarchy of small per-feature
> types, never one flat enum. Under those two disciplines the
> architecture scales to thousands of action types with bounded — and
> mostly linear-in-delta — runtime, memory, build, and tooling costs.**
>
> *(The scaling clause is [assumption] — projected from the
> per-bottleneck fixes in §6, each individually documented; no
> end-to-end existence proof of a healthy multi-thousand-typed-action
> deployment exists, sharded or not.)*

Its load-bearing components, each evidence-backed above:

1. **Two-tier action space.** Tier 1 *durable intent events*: serialized,
   versioned, upcast-able, sync-able, replay-able — the event-sourced
   part. Tier 2 *ephemeral UI actions/signals*: in-memory only, never
   serialized, often never dispatched (platform-local state that commits a
   Tier-1 action when settled). The tier decision is per-signal:
   "would replaying this in six months mean anything?" If no, Tier 2.
2. **Sharded, hierarchical typed actions.** Per-feature sealed
   sub-hierarchies (~tens of actions each), composed at a root interface;
   per-feature reducers; feature modules once count/team-size warrants
   (§6 rows 4–6).
3. **Layered subscription DAG.** Narrow, memoized, value-equality
   selectors; derivation cost scales with the delta, not the store (§6
   row 2). Recomposition/re-render counts are a *CI-guardable* metric
   because UI = f(state) makes them deterministic.
4. **Five loading ports.** Query, Mutation, Subscription, Pagination,
   Outbox — implemented once each as adapters, instantiated per feature,
   property-tested per-interface. New features add *instances*, not
   *paradigms*.
5. **Determinism at the edge.** Clock/random/network/location enter as
   payloads or selector inputs. This one property funds the whole reward
   side: time travel, golden-snapshot CI, model-based reducer tests,
   session record/replay, and headless reuse of the core (background
   tasks, CLI, server-side rendering of the same logic).
6. **Snapshot + tail + compaction** for anything persisted; pending-window
   logs where a server is authoritative; versioned schemas with upcasting
   from day one of persistence (§6 rows 9–10).
7. **Backend symmetry.** The same grammar spans the wire: client Tier-1
   actions are commands to the backend; accepted ones become events; sync
   deltas are the backend's actions dispatched into the client. One schema
   source generates both sides (dayfold's codegen posture already does
   this for content). The store discipline is thus not a client pattern
   but a *system* pattern — which is what makes the CLI/skill/agent
   authoring loop able to drive the product without a chatbot runtime
   [assumption; consistent with the content-API wedge].

### Pros (what the extreme buys, when disciplined)

- **Verification collapses in dimensionality.** Testing UI = testing
  `f(state)` over a state corpus (dayfold's 131 goldens); testing logic =
  reducer tables + per-port properties; reproducing any bug = replaying
  its action prefix. Test cost scales with features, verification *depth*
  with the five ports [assumption, grounded §4.2/§4.3 — and note the
  NoRedInk counter-signal in §2: test *volume* can still be large].
- **Maximal code sharing** — the core is platform-free by construction;
  platform surface = adapters + rendering (dayfold: ~1.7k platform-source
  LOC — Android+iOS+desktop shims across `:client`/`:ui` — of 15.8k main).
- **Agent-buildability.** Explicit actions + pure reducers + a text
  action log + deterministic snapshots is the most machine-legible app
  architecture available: an agent can observe (log), hypothesize
  (reducer), verify (replay/golden) without a device in the loop. This is
  ADR 0012/0013's bet, and this research strengthens it [assumption].
- **Free upgrades**: offline-first, multi-device sync, undo, audit, and
  time-travel debugging are all *reads of the same log* rather than
  features.

### Cons (what it costs, even disciplined)

- **Ceremony floor**: every durable interaction is an action + reducer
  case + selector touchpoint (~3× the code sites of a mutable-MVVM
  one-liner) — mitigated by codegen/slices, never to zero [estimate].
- **Schema gravity**: persisted action schemas are forever (§5.6) — and
  dayfold has already crossed that line (R5).
- **The discipline itself is the risk**: every documented blow-up (TCA
  retrospective, Twitter composer, DevTools crashes) is a team letting
  one of the two tier/shape rules slip. The architecture does not fail
  gracefully under indiscipline; it fails at the worst spot (input
  latency, build times) [assumption from §4.1 cases].
- **Platform-asymmetric costs** need watching, not assuming: K/N's
  non-generational GC (iOS), Wasm binary scaling (web), R8 keep-rule
  interactions (Android release builds).

## 8. What this means for dayfold (recommendations, not decisions)

Dayfold is at 99 actions — comfortably inside every documented ceiling,
and already following the two load-bearing disciplines informally. The
value of this research is knowing **which lever to pull at which
threshold** instead of discovering them as regressions:

- **R1 (cheap, soon).** Pull the already-specified selector lever: narrow
  `selectorState`/`fieldState` bindings for the hot screens
  (narrowest-slice rule: `specs/prototype/08-mobile-client.md`) + the
  spec'd-but-unbuilt recomposition-count CI guard
  (`specs/prototype/01-architecture.md:145` — memo 4 misattributed it to
  08), before Hubs/Now content volume grows. This is the top predicted
  first regression (§5.2).
- **R2 (threshold trigger).** When `Action` approaches ~150–200 subtypes
  or `Reducer.kt` ~400 lines (thresholds are judgment calls [estimate],
  interpolated between dayfold-today and the documented failure cases),
  shard: per-feature `sealed interface XxxAction : Action` + per-feature
  reducer functions, root reducer matching feature groups. File-level
  first; module-level only if build times demand (solo-dev repo —
  team-coupling pressure doesn't apply).
- **R3 (write it down).** Promote the implicit tier rule to a written
  convention (one paragraph in `processes/agent-dev-loop.md`): durable
  intent → store; high-frequency/ephemeral → platform-local, commit on
  settle; new exceptions need a stated reason. Record R2's sharding
  threshold in the same paragraph — that's where agents actually look, so
  the trigger becomes self-executing instead of buried in this report.
  Agents build to written rules; this one is currently only enforced by
  example [fact:memo 4 §3].
- **R4 (name the ports).** The five loading primitives already exist in
  the engines but anonymously, with refresh-on-401 duplicated 4×.
  When next touching the engines, extract the port interfaces (Query /
  Mutation / Subscription / Outbox already have de-facto implementations)
  and the shared token-refresh adapter. Makes the "limited abstractions"
  claim structural rather than aspirational.
- **R5 (contract hygiene).** The outbox already persists action-shaped
  ops across upgrades: give those payloads an explicit schema version +
  upcast-on-read now, while there are two op types (`toggle`, `delete` —
  `ContentStore.kt:259,276`) and zero migration debt (§5.6).
- **R6 (watch, don't build).** Before enabling `wasmJs`: measure bundle
  scaling with class count. On iOS: profile K/N GC signposts if dispatch
  rates ever rise above the current calm-feed cadence. No action today.

None of these change product scope, pricing, data handling, or platform
choices — they are architecture practice within ADR 0013's existing
decision, so no new ADR is required *until* R2's sharding actually lands
(a Proposed ADR then would ratify R3's written paragraph — thresholds +
two-tier rule — as durable convention rather than restating it)
[assumption re: ADR-class boundary — flag to operator if disagreed].

## 9. Open questions

- **OQ: redux-kotlin granular at width.** `fieldState` per-field
  subscription cost vs. one whole-state subscription is unmeasured in
  this codebase; the alpha's granular module is also the one with
  packaging sharp edges (B1 in `reduxkotlin-1.0-feedback.md`). Measure
  when R1 lands.
- **OQ: replay-in-CI.** "Record a session's action log, replay in CI" is
  proven as a primitive but has no mainstream maintained tool (memo 3
  §3). Dayfold's `rk devtools` jsonl logs are ~80% of the way there —
  worth a spike only if a real regression class emerges that goldens
  don't catch.
- **OQ: the store-vs-DB seam.** Dayfold's "SQLite is truth, store is
  projection" is LiveStore's architecture hand-rolled. If LiveStore-class
  KMP tooling matures (or redux-kotlin grows a materialized-view story),
  revisit whether the 6 hand-written bridge jobs should become generated
  materializers.

---

## Adversarial review record

Per `CLAUDE.md` process rules (two rounds before acceptance):

- **Round 1 (correctness), 2026-07-10:** ACCEPT-WITH-FIXES — 16 findings
  (6 P1, 10 P2), all applied. Load-bearing corrections: platform-LOC
  figure fixed (1.2k→1.7k); the H6 ceiling restated to what the evidence
  supports (no documented healthy 1,000+ typed store; largest documented
  healthy deployment ~500 *untyped*; typed evidence between absent); H3
  downgraded from "confirmed" to "supported in mechanism, unmeasured in
  outcome" with the NoRedInk ~1:1 test-LOC counter-signal added; the 6→5
  loading-paradigm mapping relabeled as this report's own analysis, not
  memo fact; the Automerge 50× figure corrected to a log-vs-log (not
  log-vs-state) comparison; the §7 thesis's "scales to thousands" clause
  explicitly tagged [assumption — no end-to-end existence proof]; isowords
  module count pinned at 86 from the README (memo 1's 91 was wrong, left
  uncorrected in the archived memo per never-silently-edit); outbox op
  count fixed (two types, not one); recomposition-CI-guard spec citation
  corrected to `specs/prototype/01-architecture.md:145`; plus 10 P2
  corrections, mostly fact→estimate/assumption label demotions, and Linear
  softened out of the "derived state" triad.
- **Round 2 (optimization/simplification), 2026-07-10:**
  ACCEPT-WITH-FIXES — 14 findings (6 P1, 8 P2), all applied: §5/§6
  division of labor cleaned (solutions now live only in the §6 matrix,
  §5 cross-refs them); duplicate TCA quote and H5 double-statement
  removed; H3 verdict de-run-on'd; §2 gained forward pointers to §3/§8
  (agent-context ergonomics); R2's sharding threshold given a home in
  R3's written convention so the trigger is where agents look; the
  future-ADR relationship to R3 clarified (ratifies, not restates);
  hedge-bracket readability fixes in §4.2/§4.4; §4.3 heading aligned
  with §2's verdict; §7 items cross-ref §6 rows instead of restating
  them. Reviewer's length verdict: beyond these cuts the report is
  genuinely tight.
