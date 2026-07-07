# Backlog — Next

Queued behind the validation gates (`context/goals-and-constraints.md`).
Populated at bootstrap and by loop close-outs.

> **Tracking convention:** build/work items = `TASK-<slug>` here (`next.md`),
> promoted to `now.md` when active, `later.md` when deferred. Operator decisions
> = `INB-N` in `operator-inbox.md`. High-level phases = `planning/workstreams.md`.
> No issue tracker yet (workstream D2 deferred).

## TASK-CLIENT-MODULARIZE — split `:client` into `:model` / `:ui` / `:data`

**Status: PARTIALLY SUPERSEDED — see the `✅ DONE 2026-07-02` entry below.** The
`:ui` extraction this section proposed shipped (ADR 0047, ~line 733) as a
2-module split (`:client` core + `:ui` Compose), not the original 3-module
`:model`/`:ui`/`:data` shape. The further `:model`/`:data` split described below
is **still queued** (ADR 0047 §Remaining) if the DONE entry's measured payoff
isn't enough on its own. Kept for that follow-up context; read the DONE entry
first for current state.

**Why (measured, not theorized):** `:client` is one monolithic KMP module —
79 `commonMain` files (UI + state/logic + data/sync + fake backend) + 24
generated SQLDelight files, with sqldelight/ktor/coil on the compile classpath.
In the CL-SNAP session the inner loop measured **~5s recompile + ~2s
fork/render**, and the recompile was **the same ~5s whether editing a UI file
(`FeedScreen`) or an unrelated data file (`SyncClient`)** → the whole module is
one compilation unit. A 1-line body change pays the full ~5s floor (Kotlin
incremental analysis + Compose compiler plugin over the module + test-compile
depends on main). The render itself is already fast (~150ms/shot in-process).

**Goal:** shrink the compile unit a UI edit touches.
- `:model` — `Model`/`AppState`, `Reducer`, `Selectors`, `*Engine`, pure logic
  (kotlinx-serialization + datetime; **no** Compose, sqldelight, or ktor).
- `:ui` — Compose composables (`FeedScreen`, `cards/`, `theme/`, screens) →
  depends on `:model` + Compose only. **The snapshot registry renders from here.**
- `:data` — `SyncClient`, `*Client`, `ContentStore`, `OutboxSender`, SQLDelight,
  ktor.
- `:client` (app) — wires them + `expect/actual` drivers + fake backend.

**Payoff:** a UI edit recompiles `:ui` only (the 24 generated SQLDelight files +
ktor/sync leave the UI compile unit) → est. **~5s → ~2–3s** (unverified — needs
the actual split to confirm), plus per-module build-cache + parallelism. Beyond
speed: it isolates the deferred **web-target (wasmJs) + async-DB migration**
(`OQ-web-target`) to `:data`, and makes `:model`/`:ui` independently testable.
The CL-SNAP snapshot loop is a direct beneficiary.

**Caveats:** the Compose compiler plugin is a per-changed-UI-file floor — the
split shrinks the analyzed set + kills cross-concern recompiles, it doesn't
remove Compose's cost. For tight *visual* iteration, Compose Hot Reload is the
complementary tool (snapshots stay the verification gate). Cheaper adjacent win
to bank first: enable `org.gradle.configuration-cache` (the `snapshotUi` task is
already config-cache-safe) — attacks the ~2s fork/config overhead.

**Risks / scope:** large refactor — the redux store wiring, `expect/actual`
driver boundaries, and the debug-only fake backend all cross the new module
lines; android/desktop/iOS targets must all still build; snapshots + the full
`:client:desktopTest` suite must stay green. **Module architecture is ADR-class**
(structure/platform) → short Proposed ADR or a design-first pass before the split.

**DoD:** editing a `:ui` composable recompiles `:ui` (+ snapshot test) only, not
`:data`/sqldelight; the measured UI-edit recompile improvement is recorded; all
targets compile; snapshots + tests green. **Reference:** measured in the CL-SNAP
session (PR #277); `specs/cl-snap-agent-snapshot-loop-design.md`.

## TASK-HEADLESS-RENDER-DAEMON — persistent headless Compose render engine (PNG + layout tree, code-reload) (NEXT — spike)

**Status: NEXT (queued 2026-07-02, from the CL-SNAP session).** Supersedes the
earlier "add Compose Hot Reload" framing — hot-reload was a *means*; a headless
render daemon is the *end*. The agent-optimal render engine: a warm long-lived
JVM exposing `render(scene | stateJson) → {png, semanticsTree, bounds}`, that also
absorbs *code* edits without a JVM restart or full Gradle build. Collapses three
follow-ups into one engine: persistent render + layout-info (the inspector) +
code-reload (escapes the ~5s recompile for composable edits too). Ideal backend
for **AI design-matching** (render → diff vs mockup → region → owning composable
→ edit → re-render, ~1s, mostly text).

**Why this shape (measured):** CL-SNAP's inner loop is ~5s recompile + ~2s
fork/render; batch-in-one-process already gets **~150ms/shot** warm. Two axes:
- **State iteration (same code, new state)** — EASY, no hot-reload needed. Extend
  CL-SNAP's batch to a daemon (stdin/socket loop) holding a warm JVM. Sub-second
  headless renders today.
- **Code iteration (edit a composable)** — the frontier (below).

**Key insight — hot-reload's Compose half is UNUSED headlessly.** `ImageComposeScene`
renders a **fresh composition per shot** (create → render → close). So there is no
long-lived composition to invalidate → hot-reload's `DevelopmentEntryPoint`
recompose-in-place hooks don't apply. The daemon needs only "the next `render()`
runs the new code." Two routes to get recompiled classes into the live process:
- **Route B — classloader swap (LOWER RISK, evaluate FIRST; no plugin, no JBR):**
  a watching/incremental compile of the changed module → render each shot through a
  fresh child `URLClassLoader` that loads the new classes (each shot is a throwaway
  composition anyway → drop the old loader). No experimental dependency. Risk =
  Compose runtime *global* state resisting reload across classloaders — the real
  thing to prove.
- **Route A — JBR hotswap (FASTER, riskier, fallback):** reuse `org.jetbrains.
  compose.hot-reload`'s JBR enhanced class-redefinition to swap classes in place →
  next render uses them. Fastest (everything stays warm) but off hot-reload's paved
  path (built to drive a *window*, not a headless server → likely reaching under
  the plugin API) + needs the JBR (~200MB) and a CMP 1.9.3 / Kotlin 2.3.20-compatible
  plugin version (tight matrix — verify first).

**Layout-info output** = the inspector Level-1 work: `SemanticsNode.boundsInRoot`
(+ role/text/testTag, unmerged) as JSON per render; optionally the LayoutNode tree
for every composable (see the `later.md` pixel↔composable inspector task — this
daemon is its natural host).

**Spike steps:**
1. **Persistent render daemon (low-risk core):** batch → long-lived process,
   `render(scene|stateJson) → {png, layout}` on stdin/socket, seeded from the
   existing `SnapshotStates`/`FakeScenarios` fixtures. Confirm ~150ms/shot warm.
2. **Add layout-info output:** bounds + semantics tree as text per render (needs
   the reduxkotlin dump to carry bounds — coordinate with the inspector task).
3. **Code-reload experiment — Route B first:** watching compile + fresh-classloader
   render; prove Compose global state survives a reload. If it fights, evaluate
   **Route A** (hot-reload/JBR) — after the CMP/Kotlin version-matrix check.
4. **Measure** edit→(headless re-render + layout dump) latency vs the ~7s baseline.

**Relation to siblings:** `TASK-CLIENT-MODULARIZE` shrinks the ~5s recompile for
*every* consumer (also feeds this daemon's Route B); this daemon is the
**agent/CI-facing** engine (headless, text-first). Live *human* visual iteration
(watch-a-window, state-preserved) remains hot-reload's actual sweet spot — a
smaller, separate dev-convenience if wanted, not this task.

**Caveats / risk:** Route B's classloader hygiene vs Compose runtime globals is the
core unknown; Route A depends on undocumented hot-reload internals + JBR. This is
R&D — the persistent-render + layout-output core is low-risk/high-value on its own;
the code-hot-reload half is the experiment. All dev-only → zero prod/CI blast radius.

**DoD:** a warm daemon rendering `{png, layout-tree}` sub-second per state; a
recorded verdict on Route B (classloader swap) — works / Compose-globals-block-it /
needs Route A; and the measured edit→render+layout latency vs ~7s. **Reference:**
CL-SNAP session (PR #277); pairs with the `later.md` pixel↔composable inspector.

## CONTENT LIBRARY + DETAIL + FOLD GESTURE (ADR 0022 — Accepted 2026-06-19)

> **STATUS 2026-06-21 — M0 build order EXHAUSTED + MERGED TO MAIN** (PR #7
> `cl-integrate`: CL-0…CL-8 + CL-PLAT + CL-3, with auth S1–S5). The
> per-task `NEXT = …` breadcrumbs below are **historical** (state when each
> task closed), not the live front. Remaining content items are all
> deferred/blocked: **CL-9** spike done → decision recorded (impl = M0
> placeholder; **CL-9b** real map deferred to M1, ADR-gated); **CL-10** /
> **CL-NAV** blocked on operator design sign-off (INB-16) + a Claude-Design
> adaptive-detail pass; **CL-0b** (fonts + MaterialExpressive) gated on the
> material3-expressive artifact @1.9.3. Next non-content buildable = **TASK-KMP**
> (but it contends with the live auth lane + needs the operator's Mac for iOS).

From the Claude Design import (`designs/content/*`, `designs/Brand.dc.html`).
**Full breakdown + DoD + file touchpoints: `planning/content-detail-epic.md`.**
**Gates CLEARED (INB-15/16/17/18):** ADR 0022 accepted · **D2 = extend
`briefing_cards` in place** (unify→M1) · phone mockups signed off (ADR 0008) ·
name **Dayfold** confirmed · **M0 ships all 6 content types**. Ready to promote
to `now.md` and build (order in the epic). **Only CL-10 (adaptive) stays
blocked** behind a queued Claude-Design expanded-detail pass.

- **TASK-CL-0** — Dayfold M3 theme. ✅ **CORE DONE + MERGED** 2026-06-19
  (`apps/client/.../theme/` — light+dark `ColorScheme` from Brand hex, `Shapes`
  8/12/16/26/32, type scale, `DayfoldExtendedColors` privacy/provider/map; `FeedApp`
  wrapped; 7 unit tests + light/dark feed snapshots green, verified). **Follow
  `CL-0b`:** bundle real Outfit/Figtree TTFs (composeResources; currently
  `FontFamily.Default`), adopt `MaterialExpressiveTheme`+`MotionScheme.expressive()`
  (coupled to CL-7; gated on the material3-expressive artifact at 1.9.3), Android
  `dynamicColorScheme` (androidMain). Seam = the one `DayfoldTheme` function body.
- **TASK-CL-1** — Schema + codegen. ✅ **DONE + MERGED** 2026-06-19. BriefingCard
  gained `type` (file/link/invite/contact/geo/email) + an **inline-oneOf typed
  `payload`** (6 variants, no `z.any` — kills the payload/`$defs` gap), + `hubRef`
  (adaptive supporting pane) + `privacy.storage` (honesty chip). All optional →
  back-compat (D2 extend-in-place). Regenerated TS (zod) + Kotlin; 6 new schema
  tests + full api suite (73/1-skip) green. **Follows:** (a) `type`↔payload-key
  **cross-validation** → CL-2 server `superRefine` (M0 authoring is trusted); (b)
  **static** payload typing (`z.infer`=`any`) → a codegen pass to emit
  `z.discriminatedUnion`; (c) pre-existing `$ref`→`z.any` for id/version/provenance
  (separate codegen issue, not CL-1).
- **TASK-CL-2** — Server: typed storage + nested validation + keyset sync. ✅
  **DONE** (branch `cl-2-server-typed-storage` off `cl-next`) 2026-06-20.
  Migration `0005_typed_content.sql` extends `briefing_cards` IN PLACE (D2):
  nullable `type`/`payload`(jsonb)/`privacy`(jsonb)/`hub_ref` + a `type`-enum
  CHECK. `repo.upsertCard` carries all 4 (wire `hubRef`→`hub_ref`); `SELECT *`
  serves them on GET/`/sync` (pg auto-parses jsonb→object). New
  `content-validation.ts :: crossValidateCard` resolves the **CL-1 follow (a)**:
  zod validates `type`+`payload` *independently*, so the key↔type tie is
  enforced ONLY here — typed-iff-payload + payload-key === `type`, legacy
  kind-only cards still valid; mismatch/orphan → 422. Keyset/tombstone/cursor
  invariants untouched (no index/trigger change). New `typed-content.test.ts` (7
  tests: 6-type round-trip incl. sync, mismatch 422, orphan 422, back-compat,
  tombstone, tenancy-404, cursor-stability); 0005 added to api/auth-e2e/
  device-approve harnesses. **Full api suite 80 pass / 1 pre-existing skip
  (14 files); codegen idempotent; `deploy-m0.md` migration step updated to apply
  all `000*.sql`.** Twice-reviewed (pre-impl adversarial spec review caught the
  auth-e2e/device-approve harness breakage; final whole-branch review = SHIP).
  Spec: `docs/superpowers/specs/2026-06-20-cl-2-server-typed-storage-design.md`.
  **CL-1 follows still open:** (b) static payload typing (`z.infer`=`any`) →
  codegen `z.discriminatedUnion`; (c) `$ref`→`z.any` for id/version/provenance.
  **Integrated into `cl-next`** (ff-merge `8f11301`, local; not pushed). **NEXT =
  CL-4** (client data: typed model + SQLDelight + store).
- **TASK-CL-3** — CLI typed authoring (content-API wedge). ✅ **DONE**
  (branch `cl-3-cli-typed-authoring` → integrated into `cl-next`) 2026-06-20.
  **Operator-authorized** (was INB-18-deferred). CLI now consumes the generated
  `com.sloopworks.dayfold.schema.*` types (srcDir `kotlin-gen` — one source of truth).
  `dayfold push <id> <file> --type <t>` runs **local structural validation**
  (`validateCard`: strict decode + type↔payload-key cross-check + `--type` assert)
  and fails fast with field errors before the server; `dayfold template <type>`
  emits a valid starter (6 templates in `src/main/resources/templates/`).
  Authoring doc `apps/cli/templates/README.md` (incl. Guardrail-3 own-mail
  constraint + geo `on_device` privacy honesty). **Validator is STRUCTURAL only**
  — the server (CL-2) stays the authority for `url()`/ISO-datetime/length/int
  rules; two codegen asymmetries (`kind`/`provenance.at` required locally)
  documented. **CLI test green** (ValidateTest 8/0, CredentialsTest 2/0; build +
  `template` smoke verified). Reviewed (final: doc-honesty fix on the
  "mirrors server" claim → softened). Spec:
  `docs/superpowers/specs/2026-06-20-cl-3-cli-typed-authoring-design.md`.
  **Follow:** the deeper-validation (codegen-emitted refinements) + a Claude skill
  wrapper are later; M0 authoring works now.
- **TASK-CL-4** — Client data: typed model + SQLDelight + store. ✅ **DONE**
  (branch `cl-4-client-typed-data` → integrated into `cl-next`) 2026-06-20.
  `Card` gains `type`/`payload`/`privacy`/`hubRef`; new wrapper `Payload` + 6
  variant data classes (mirrors generated `BriefingCardPayload` — externally-
  tagged `{"file":{…}}`, not a sealed interface: matches wire + codegen +
  simpler). `Content.sq` `card` table + `upsertCard` + `activeCards` carry
  `type`/`payload`(JSON TEXT)/`privacy`(JSON TEXT)/`hub_ref`. `ContentStore`
  encodes on write, **guarded per-field decode at the DB→store projection**
  (off the recomposition path; corrupt JSON → null, card still renders). Wire
  `@SerialName("hub_ref")` (server `/sync` returns DB-shaped snake rows).
  ADR 0020 preserved (no new network/store path; cold-start instant). **36
  desktop tests green** (ContentStoreTest 8: 6-variant round-trip, kind-only
  back-compat, corrupt-payload guard, wire-decode); **Android + iOS-sim
  compile**. Twice-reviewed (pre-impl adversarial: caught the activeCards-SELECT
  omission + decode-once overclaim, fixed; final whole-branch: SHIP). Spec:
  `docs/superpowers/specs/2026-06-20-cl-4-client-typed-data-design.md`.
  **Follows (out of scope, filed):** (i) wire-to-`kotlin-gen` — note the
  server/codegen drift: server emits `hub_ref` but generated `BriefingCard.hubRef`
  has no `@SerialName`, so the deferred codegen-typing follow must align it; (ii)
  M0 cache has **no SQLDelight migration** → clear-app-data on schema change
  (post-M0). **NEXT = CL-5** (6 typed Now cards, light+dark) — gated on CL-0
  theme (done) + this.
- **TASK-CL-5** — Client UI: 6 typed Now cards. ✅ **DONE** (branch
  `cl-5-typed-now-cards` → integrated into `cl-next`) 2026-06-20. `cards/`
  package: `CardAction` (closed union, **no backend-mutating variant** — read-only
  ADR 0020), pure `TypedCardLogic` (accent/kicker/body/primary-action
  derivations, unit-tested), `TypedCards` (6 composables + shared chrome +
  `TypedCardItem` dispatcher). `FeedScreen` dispatches `type!=null`→typed else
  legacy `CardItem`; unknown type → safe generic. Visuals run off MaterialTheme
  **roles** (light+dark correct); invite = coral `primaryContainer` + **solid**
  accent + **display-only** Yes/No RSVP; contact = avatar + inline Call/Text +
  Details primary; geo = stylized map strip (no SDK/key/leak). a11y: 48dp
  targets, decorative tiles `clearAndSetSemantics`, RSVP `contentDescription`.
  **46 desktop tests green** (TypedCardLogic 5, snapshots 8 incl. 6-type
  light+dark + 3 RSVP states — PNGs visually verified); **Android + iOS-sim
  compile**. Twice-reviewed (pre-impl: dropped write-affordances/unknown-type
  crash risk/a11y; final: caught invite tile-vanish → solid-accent fix). Spec:
  `docs/superpowers/specs/2026-06-20-cl-5-typed-now-cards-design.md`.
  **Cut to follows (M0):** per-card loading-skeleton / urgent / dismissed-on-
  answer states; Material-Symbols glyphs (CL-0b); date-relative kickers.
  **NEXT = CL-6** (DetailScreen + redux nav). **CL-6 prerequisite:** the
  `expect/actual PlatformActions` effect layer (perform `CardAction`) — wire
  `onAction` (currently no-op) through middleware (ADR 0013 Rule E) in each shell.
- **TASK-CL-6** — Client UI: DetailScreen + redux nav. ✅ **DONE** (branch
  `cl-6-detail-screen` → integrated into `cl-next`) 2026-06-20. **Nav as app
  state** (ADR 0013): `AppState.detailStack: List<String>` + `NavToDetail`(push,
  dedup-top)/`NavBack`(pop); `CardsLoaded` prunes synced-away ids; selector
  `currentDetailCard` (null→feed). `FeedApp` host: one **remembered** handler
  routes `OpenDetail`→`dispatch(NavToDetail)`, all other `CardAction`s→shell
  `PlatformActions`; renders DetailScreen when a card is open else FeedScreen.
  **DetailScreen**: colored hero header (back/share, solid accent tile+kicker,
  title) + per-type hero media + safe **actions row** (no Add-to-Hub/Save/RSVP-
  write — read-only ADR 0020) + **DETAILS** meta list + provenance/**honest
  privacy** chips. Pure `detailMeta`/`detailActions` (unit-tested). Reuses CL-5
  chrome (promoted `private`→`internal`). **69 desktop tests green** (Reducer 8
  nav, DetailMeta 4, snapshots 16 incl. 6 detail types light+dark — invite+contact
  PNGs visually verified); **Android + iOS-sim compile**. Twice-reviewed (pre-impl:
  remembered handler + stack-prune + process-death/geo-honesty wording; final:
  hardware-back + InfoPanel divergence). Spec:
  `docs/superpowers/specs/2026-06-20-cl-6-detail-screen-design.md`.
  **M0 cuts → CL-7/follows:** hardware/gesture back→NavBack (no plain BackHandler
  at compose-MP 1.9.3 → folds into CL-7's PredictiveBackHandler; **interim: Android
  hardware-back exits the app from detail**); distinct per-type hero media (M0 =
  generic InfoPanel + geo MapStrip; avatar/date-block/OG/page-preview = fidelity
  follow); `selectorState` recomposition scoping (perf follow). **NEXT = CL-7**
  (fold gesture / container transform + wires hardware-back).
- **TASK-CL-7** — Fold gesture (M0 = **base transition**, per INB-18). ✅ **DONE**
  (branch `cl-7-base-transition` → integrated into `cl-next`) 2026-06-20. **Spike
  (recorded):** at Compose-MP **1.9.3** `SharedTransitionLayout` is in the
  *animation* module and `BackHandler`/`PredictiveBackHandler` are in the separate
  **`org.jetbrains.compose.ui:ui-backhandler`** artifact (not pulled by
  `compose.ui` — that's why CL-6's BackHandler didn't resolve). **No ≥1.10 upgrade
  needed** (the old risk note is wrong). Shipped: added `ui-backhandler` dep;
  **hardware/gesture back → `NavBack`** in DetailScreen (`BackHandler`, fixes the
  CL-6 app-exit-from-detail wart); **base feed↔detail transition** via
  `AnimatedContent` (asymmetric fade+slide, open 320ms / back 240ms). Extracted
  testable `routeCardAction` (OpenDetail→store nav vs everything→PlatformActions).
  **72 desktop tests green** (FeedAppHost 3: host renders feed/detail + the
  route-split branch); **Android + iOS-sim compile**. Reviewed (spike + final =
  SHIP). Spec: `docs/superpowers/specs/2026-06-20-cl-7-base-transition-design.md`.
  **→ CL-7b v1 ✅ DONE** (folded into branch `cl-7b-container-transform` →
  integrated into `cl-next`) 2026-06-20. **SharedTransitionLayout container
  transform**: feed card ↔ detail share bounds keyed `card-$id`
  (`cards/SharedScopes.kt` CompositionLocals + `@Composable Modifier.
  cardSharedBounds`, no-op when scopes absent → snapshots unaffected); `FeedApp`
  host wraps the `AnimatedContent` swap in `SharedTransitionLayout`; the morph
  source = `BaseCard` ElevatedCard, target = `DetailScreen` root. Plus a
  **debug card-seed** (`SampleData` + `MainActivity` gated `BuildConfig.DEBUG &&
  FAMILY_ID empty`) so the on-device UI is exercisable without an API. **Verified:
  compiles 3 targets; 76 desktop tests (FeedAppHostTest renders FeedApp WITH
  SharedTransitionLayout in feed+detail, no crash); base feed→detail→back +
  hardware-back + seeded feed + RELATED nav all verified LIVE on the emulator.**
  Reviewed = SHIP. **CL-7b-remaining (spec-sanctioned, on-device iteration):**
  corner-morph 26→0 + scrim 0→0.18 + content-fade-after-grow tuning;
  **predictive-back scrub** (PredictiveBackHandler); live mid-transition frame
  capture (shared emulators were occupied by another agent's app this session).
  type==null legacy cards fall back to plain crossfade (no morph). Spec:
  `docs/superpowers/specs/2026-06-20-cl-7-base-transition-design.md`.
- **TASK-CL-8** — Related-edges (cross-links / attachment↔email). ✅ **DONE**
  (branch `cl-8-related-edges` → integrated into `cl-next`) 2026-06-20. Schema:
  `BriefingCard` gains `relatedKicker` + `related[]` edges
  `{relation, targetId, targetType, title?, sub?}` (denormalized title/sub →
  renders without resolving; codegen regen TS+Kotlin). **Server:** migration
  `0006_related.sql` (`related` jsonb + `related_kicker`) + `repo.upsertCard`;
  `/sync` serves them; regenerated `BriefingCardSchema` strict-rejects bad edges
  (422). **Client:** `Card.related: List<RelatedRef>?` (+ `@SerialName
  ("related_kicker")`); `Content.sq` cols + `upsertCard`/`activeCards`;
  `ContentStore` guarded encode/decode (corrupt → null, card still renders).
  **UI:** `DetailScreen` RELATED section (header + rows + chevron, 56dp + a11y
  labels) → `OpenDetail(targetId)` → host `NavToDetail` (detail→detail chaining;
  **dangling targetId not in cache = no-op**, not a feed dump). **Tenancy:** edges
  ride `authorizeTenant`; targetId resolved client-side vs OWN cache only (no
  server resolution → no cross-tenant leak). **76 client tests + 82 api
  (1-skip)**; detail-related snapshot visually verified; Android + iOS-sim
  compile. Twice-reviewed (pre-impl caught the `@SerialName`/strict-enum/sq-edit/
  tenancy-test items; final = SHIP + the dangling-ref no-op fix). Spec:
  `docs/superpowers/specs/2026-06-20-cl-8-related-edges-design.md`. **Follow
  (minor):** unbounded A→B→A stack chaining (acceptable M0); resolving live
  title/sub vs denormalized.
- **TASK-CL-9** — Map-render strategy spike (ADR 0014 privacy posture). ✅
  **SPIKE DONE + DECISION RECORDED** 2026-06-21
  (`docs/superpowers/specs/2026-06-21-cl-9-map-render-spike.md`). **Decision:
  M0 = keep the stylized `MapStrip()` placeholder + Navigate handoff** — no
  key, no cost, no third-party coord leak; the impl + handoff already shipped
  (CL-5/CL-6/CL-PLAT), so the DoD is met with **no code change** (navigate
  test re-run green). Evaluated static-image (Geoapify/Stadia cheapest +
  caching-allowed; Google forbids image caching) vs embedded SDK (rejected:
  heavy/per-platform/leaks viewport) vs placeholder (chosen). **Key finding:**
  a static-map call transmits the authored *place coordinate* to a **third
  party** (and into its request logs) — a data flow ADR 0014 never authorized
  → **ADR-class, operator-gated.** Twice-reviewed (pre-impl adversarial +
  fixes folded). **Follow `CL-9b` (deferred, M1):** author-time-stamped static
  map image (CL-2 OG-unfurl pattern: no server/client render-time fetch),
  behind a **new ADR** for third-party map-provider disclosure + provider-
  logging exposure + chip-honesty audit.
- **TASK-CL-10** — Adaptive two-pane detail — **BLOCKED** on a Claude-Design
  expanded-detail pass (design gap; phone-only designed).

- **TASK-CL-PLAT** — Platform action effect layer (CL-6 prerequisite, epic
  "Platform shims"). ✅ **DONE** (branch `cl-platform-actions` → integrated into
  `cl-next`) 2026-06-20. `expect class PlatformActions { perform(CardAction) }`
  (mirrors the `DriverFactory` Context-ctor precedent) + 3 actuals (android
  `ACTION_VIEW`/clipboard/`ACTION_SEND`; desktop `Desktop.browse`/AWT clipboard;
  iOS `openURL`/`UIPasteboard`). Pure **`cardActionUri`** vets at one seam —
  **shared allowlist with `CardRender.ALLOWED_SCHEMES`** (now `internal`, **`sms`
  added**, https-only); mailto **address-only** (rejects params/CRLF/multi-
  recipient/`%`); phone allowlist `+`+digits (drops DTMF/USSD); geo `%`-encoded
  UTF-8 place query (ADR 0014 — never live coords). `OpenDetail` = no-op here
  (in-app nav → CL-6). All 3 shells construct + pass `onAction = pa::perform`;
  `FeedApp` gained the param. Read-only (ADR 0020) — every effect is an OS
  handoff. **54 desktop tests green** (PlatformActions 8: scheme/mailto/phone/geo
  vetting + desktop smoke); **androidApp + iOS-sim compile**. Twice-reviewed
  (pre-impl caught 4 vetting holes — all fixed; final = SHIP). Spec:
  `docs/superpowers/specs/2026-06-20-cl-platform-actions-design.md`. **Now CL-5's
  Open/Call/Text/Navigate/Reply perform real handoffs on device.** **NEXT = CL-6**
  (DetailScreen + redux nav — route `OpenDetail` through the nav layer).

## AUTH (ADR 0021 — S1→S3→S2→S4→S5/S6)

### TASK-AUTH-S6-D — CLI device-approval UI + scan/deep-link (building)
**Status:** Phase-1 backend + CLI QR ✅ built/tested/pushed 2026-06-23 (branch
`claude/cli-login-flow-review-aq9lp0`): step 1 `GET /device/pending` + datacenter
classifier (`1ff5f5e`), step 1b central `requireScope` read-gate (api 153 green),
step 7 CLI login QR (`76c42c8`, 13 CLI tests). **Steps 2–6 (Compose approval UI),
7b (keychain), 8 (E2E) deferred to a full-mobile-toolchain session** (no Android
SDK in the remote env). **NEXT = client AuthClient→reducer→AuthEngine→screens
against the shipped `/device/pending` + reused approve/deny.** ADR 0029 Accepted. Closes the **CLI login loop** — S3 shipped
the API grant + a text CLI, but the **mobile approval UI never existed**
(`DevicesScreen` only lists/revokes). Spec:
`docs/superpowers/specs/2026-06-23-auth-s6d-device-approval-design.md`;
plan: `docs/superpowers/plans/2026-06-23-auth-s6d.md`.
- **Phase 1** (platform-agnostic, closes loop on desktop): `GET /device/pending`
  lookup (+ **no-vendor datacenter-origin classifier**, ADR 0011 §7 intent),
  central `requireScope` gate (fixes read-enforcement gap), the 4 approval screens,
  CLI terminal QR, **CLI refresh-token → OS keychain** (closes the plaintext
  long-lived-secret gap).
- **Phase 2** (scanner + deep-link): in-app QR scanner (`expect/actual`) + App/
  Universal Links on the existing API origin. **Gated on** the scan/viewfinder
  mockups (`designs/DESIGN-BRIEF-device-scan.md`, ADR 0008) + operator sign-off.
- **Review findings folded (2026-06-23):** scope is display-only today (→ ADR 0029);
  geo/ASN was deferred but ADR 0011 §7 mandates it (→ datacenter heuristic now);
  plaintext refresh token (→ keychain); read scope unenforced (→ `requireScope`).

### TASK-AUTH-CONTENT — content-API + CLI content verbs + per-hub scoping (ACTIVE)
**Status:** **gates cleared — in build** (worktree `claude/auth-content-slice`, off
`main` 2026-06-24). ADR 0029 **Accepted** + operator re-approved 2026-06-24; ADR 0030
(per-member visibility) **Accepted** 2026-06-23 — this slice now carries both. The
CLI today can only `PUT` one card — **no hub endpoints, no `pull`/`status`/`diff`,
no content read**. This slice makes the CLI a real content read+write client and
lands **per-hub/resource scope selection** (ADR 0029) + **per-member hub visibility**
(ADR 0030).
- API: hub/section/block read+write endpoints, each behind `requireScope`.
- ADR 0029: `credential_grants` table + resource-qualified scope resolution.
- ADR 0030: hub `visibility`/`created_by` + hubs-only `resource_visibility` +
  `→hubs.updated_at` touch-trigger + card `visibility`/`audience[]` + read-path
  filter + visibility-aware `/sync` + client cache-wipe on tenancy 401/404.
- CLI: `pull` / `hub get|archive|rm` / `status` / `push --dry-run|--diff`; `whoami`
  shows family + scope + label (07-cli.md).
- Approval UI: per-hub read/write picker on `AuthorizeDevice` (replaces the interim
  informational scope row). **Design/toolchain-gated** (Compose UI; not in the
  first agent slice).
- Hub **render** surface = **design-gated** (ADR 0008 hi-fi Hubs mockups + sign-off)
  — out of this slice; this slice is API + data + CLI only.
- Specs: `specs/domain-model/scope-and-access-model.md`, ADR 0029, ADR 0030. Own
  spec → plan → build cycle.


**AUTH-S4 (owner-approved invites + family-agnostic cred fix) — ✅ DONE (branch
`auth-s4`, pending merge) 2026-06-19.** `invites` table; app creds family-agnostic
(`family_scope=NULL`, membership-gated) — **clears the S1 two-family limit** (that
test un-skipped); `/auth/whoami`→`{family_id, families}` (S3 CLI compat kept);
mint / redeem (atomic single-use FOR-UPDATE claim) / approve / decline / revoke /
remove (≥1-owner **row-lock**) / list-queue (invitee identity for the approver);
owner+`kind='app'` gate; uniform-404 + per-account lockout; never-owner role.
Spec twice-reviewed (5-dim multi-agent) + 7 TDD tasks each task-reviewed + clean
final whole-branch security review (no Critical/Important, no fail-open seam). 96
API tests / 0 skips. Legacy household token still works.
- **AUTH-S4 follow tickets (deferred, non-blocking):** (1) **S6-facing:** dedupe the
  approval-queue `user_identities` LEFT JOIN (a multi-identity user fans out to N
  rows — surfaces at Firebase S2) — note on the S6 task; (2) cleanup: drop dead
  `clientIp` import (mint) + dead `RETURNING role` (approve); mint `expires_at` via
  `RETURNING`; (3) soft pending-cap is racy across distinct invites of one family
  (anti-abuse, non-security); (4) the expiry **sweep** (shared with the S3 m-2
  follow) for `invites`/`rate_limits`/terminal rows.
- **AUTH-S4 ✅ MERGED** to `main` 2026-06-20 (PR #4, `66c783d`). Branch `auth-s4`
  == origin/main (no diff).
- **A8b auth/family/invite mockups — ✅ DELIVERED 2026-06-20 (pending operator
  sign-off, ADR 0008).** `designs/Family AI dashboard design brief/designs/
  Auth-Phone.dc.html` extended 6→18 views — all 9 spec screen-groups incl. the
  previously-missing **authorize-device (RFC 8628)**, **enter-code**, **members +
  pending approvals**, **connected devices**, **provider-link-conflict**,
  **account export/delete**, plus offline / OTP-error+resend-limit / waiting-for-
  approval / invite expired·revoked·exhausted / already-member. Light+dark;
  rebranded **HEARTH→Dayfold** (turned-corner mark, per Brand.dc.html). `Auth.dc.html`
  gallery refreshed (23 frames; header ADR 0010→0011, "auto-join" removed); stale
  Index footer "(no auth)" fixed. Verified outside the dc runtime (extension was
  offline): tag-balance, 36 render-combos through `renderVals()`, all 32 `c.*`
  tokens defined w/ light/dark parity, all frame views ∈ enum. **GATE: operator
  opens the dc files + signs off → unblocks S5/S6.** A8b merged to `main`
  2026-06-20 (PR #5, `f399583`); operator merged = sign-off. **✅ ADR 0008 sign-off
  explicitly recorded 2026-07-07 (resolves the merge-vs-signoff ambiguity) — the
  owner invite-mint UI (code + QR share) is cleared to build.**
- **S2 vendor/cost gate CLEARED — ADR 0023 (operator-directed 2026-06-20):**
  Firebase **Google + Apple only, Phone-OTP deferred** → no Blaze, no SMS spend
  ceiling, no SMS-fraud/SIM-swap surface; ADR 0011 architecture intact. S2 is now
  buildable (recovery-floor counsel gate smaller without phone). **S5/S6 sign-in
  renders Google + Apple only** — the phone button + OTP/OTP-error screens stay
  designed-not-built (A8b mockups unchanged).
- **AUTH-S5 slice-1 (authenticated session + onboarding gate) — ✅ DONE 2026-06-20
  (branch `auth-s5`, PR pending).** Firebase-stubbed via dev-token (operator-chosen).
  Introduced the app's **first navigation** (pure `when(route)` gate, ADR 0013) +
  the **session/token layer**. T1 route gate · T2 `AuthClient` (ktor) · T3
  `TokenStore` (desktop 0600 / Android prefs / iOS NSUserDefaults) · T4 `AuthEngine`
  (mutex orchestrator + 401 refresh-and-retry) · T5 Dayfold screens (sign-in
  Google/Apple, create-family, family-null) + 9 snapshots vs mockups · T6 wired
  all 3 shells + `SyncClient`→token/family providers. **Verified:** 74 desktopTest
  green, android compiles, iOS framework links, **LIVE ROUND-TRIP PASS**
  (`apps/api/scripts/s5-roundtrip.mjs`: dev-token→whoami→create-family→push→sync).
  No `HOUSEHOLD_SECRET` on the JWT path. Spec/plan in `docs/superpowers/{specs,
  plans}/2026-06-20-auth-s5*`.
  - **S5 slice-1 follows (non-blocking):** (1) `SyncEngine` 401→`AuthEngine.refresh`
    hook (mid-session access-expiry mid-poll; restore already refreshes); (2) secure
    token stores (EncryptedSharedPreferences / Keychain); (3) immediate post-create
    sync polish; (4) a Feed sign-out affordance.
  - **NEXT: AUTH-S5 slice-2** (invitee-join: invited/waiting/invite-error/
    already-member + provider-link-conflict) · **S6** (invite gen, authorize-device,
    members+approvals, devices, account) · **S2** (real Firebase Google/Apple behind
    the same buttons — gate cleared by ADR 0023).

### AUTH-S5/S6 — full status as of 2026-06-21 (post slice-1)

Built across a /loop run; **the client auth/account/family surface is
comprehensive and e2e-tested on a real emulator** (`fad_atd35`, API-35 AOSP ATD
— provisioned because the on-hand emulators were API 37, which espresso can't
drive). **4 instrumented `AuthFlowE2ETest` cases pass on-device:** sign-in →
create-family → feed → account → **sign-out (confirm)** · **join-by-invite** →
waiting · owner **approve + remove** · **connected-device revoke**. Mirror desktop
`AuthFlowUiTest` (runComposeUiTest) is the default-loop e2e.

**MERGED to `main`:**
- S5 slice-1 (PR #6); A8b gap designs (#8); ADR 0025 auth rate-limit constants (#10);
  members/approvals 3c+4a+4b backend (#12); **data-export `GET /auth/me/export`**
  + **connected-devices backend** `GET`/`DELETE /auth/me/credentials` (#13).
- **Slice A** AccountScreen + sign-out · **B** e2e harness + fixed inert AuthButton
  bug · **C** sign-out confirm · **2a-2c** invitee-join (transport/UI/e2e) ·
  **3a-3c** owner approvals (queue + approve/decline + screen) · **4a-4c** member
  roster (GET /members + render + remove).

**OPEN PRs (awaiting operator review/merge):**
- **#15** connected-devices client (`DevicesScreen` + revoke, e2e on emulator).
- **#16** profile endpoints (`GET`/`PATCH /auth/me` display name).
- **#17** retention sweep (`sweep()` expired rate_limits/device-codes/orphan invites;
  resolves the S3/S4 sweep follow).

**GATED — needs the operator (not agent-decidable):**
- **Account-delete** — the inert AccountScreen button + designed `deleteconfirm`/
  `transferowner`. Permanent data deletion + the schema needs a policy call:
  `credentials`/`family_scope` have no ON-DELETE cascade; sole-owner = block-and-
  transfer vs auto-delete-family; soft (`users.deleted_at`) vs hard. **Escalated;
  not built pending the approach decision.**
- **AUTH-S2 Firebase** — real Google/Apple behind the stubbed dev-token buttons
  (ADR 0023 cleared the vendor scope; needs the Firebase project/console step).
  Editable-name client (#16's UI) + provider display names land with S2.

**Resolved earlier follows:** sign-out affordance (Slice A/C); invitelocked
constant (ADR 0025); the retention sweep (#17). **Still open:** `SyncEngine`
401→refresh hook (mid-session); secure token stores (EncryptedSharedPreferences/
Keychain); the instrumented e2e needs a ≤API-36 emulator (CI note).
- **A8b failure/destructive design gaps — ✅ CLOSED + IMPORTED 2026-06-21** (Claude
  Design pass from `designs/DESIGN-BRIEF-auth-gaps.md`, pulled via the claude_design
  MCP). `Auth-Phone.dc.html` now **25 views** (18 → +7): **slice-2 invitee
  failures** `invitedeclined` / `invitelocked` (429) / `joinerror` (transient) and
  **S6 destructive** `deleteconfirm` (type-DELETE + Apple-disconnect) /
  `transferowner` (≥1-owner member picker; also the members-409 path) /
  `devicedenied` / `deviceexpired`. Gallery = 37 frames (light+dark). Verified
  render-valid (tags 29/29·7/7·277/277, all views through `renderVals()`, token
  parity, frames ∈ enum). **Slice-2 now has full happy+failure design coverage;
  S6 destructive-action screens designed.** **✅ Operator signed off (ADR 0008)
  2026-06-21 — design gate CLEARED for slice-2 + the S6 screens; build may
  proceed.** Invitelocked cooldown constant resolved: **5 fails / 15 min →
  15-min lock** (matches S4 `app.ts:286`), recorded in **ADR 0025** (auth
  abuse-control constants) + the screen copy says "~15 min".

**AUTH-S3 (CLI device grant, RFC 8628) — ✅ DONE + MERGED** to `main` 2026-06-19
(PR #2, all CI green). `/device/{authorize,token}` + `/families/:fid/device/{approve,deny}`
+ `/auth/whoami` + the refresh ~20s reuse-grace (resolves the S1 carried debt) +
Kotlin CLI `login`/`logout`/`whoami` + device-granted `push` (0600 file,
cross-process refresh lockfile, legacy env fallback). Owner+`kind='app'` approve
gate (stolen-CLI + legacy both 403), PATH-resolved tenancy (anti-IDOR), lazy-mint
at redeem (one-time, atomic), DB-backed rate-limit + per-account lockout, audit
log. Spec twice-reviewed (7-dim + 4-dim multi-agent) + 7 TDD tasks each
task-reviewed + a clean final whole-branch security review (no Critical/Important,
no fail-open seam). 67 API tests + CLI CredentialsTest + live round-trip green.
- **AUTH-S3 follow tickets (deferred, non-blocking):** (1) retention sweep for
  `rate_limits` / `audit_log` / terminal `device_authorizations` (unbounded growth
  — land before non-dogfood traffic); (2) drop the vestigial `genuineReuse` var in
  refresh.ts; (3) align `/device/deny` already-denied → 204 (vs current 404) +
  tighten the lockout test to the exact 6th-attempt 429; (4) `genUserCode` modulo
  bias (cosmetic; device_code is the secret); (5) `slow_down` interval cap (CLI is
  wall-clock-bounded already).
- **⚠ Governance note:** ADR 0021 §3 says the legacy household-token branch is
  "removed in S3." This slice **deliberately KEPT it** (the S3 brainstorm chose
  non-breaking coexistence; removal gated to a follow once the device-granted CLI
  is deployed + the operator migrates). Intentional spec-over-ADR narrowing — the
  legacy-removal cutover remains a tracked follow (the `TODO(S3-cutover)` in
  `middleware.ts`). ADR 0021's "removed in S3" should not be read as done.
- **NEXT after S3 merge: AUTH-S2** (Firebase identity) or **S4** (invites) per ADR
  0021. S3 fully kills CLI hardcoding once deployed (operator-gated prod deploy +
  `AUTH_*` env in Vercel).

**AUTH-S1 (Tenancy & token backbone) — ✅ DONE + MERGED** to `main` 2026-06-19
(branch `auth-s1`). Backend-only, Firebase-stubbed, non-breaking. EdDSA token
service + refresh lineage + `authorizeTenant` middleware (JWT + legacy household
path, default-deny, fail-closed, per-request membership re-resolution, cross-tenant
404) + `/auth/{refresh,signout}` + `POST /families` + JWKS + **gated local-only
dev-token** (kills LOCAL build/test hardcoding) + content routes migrated. 51 tests
+ 1 skipped, vs live PG; final whole-branch security review passed (no Critical,
no fail-open seam). Spec/plan in `docs/superpowers/{specs,plans}/2026-06-19-auth-s1*`.
- **Carried debt (from the final review):**
  - **→ S3:** refresh **~20s reuse-grace not implemented** — a client that retries a
    refresh (timeout+retry) presents the same token twice → loser hits reuse-detect →
    **revokes its own credential**. Fails closed; harmless at S1 (single test client),
    but a real CLI/mobile client at S3 will need the grace re-serve. (Spec §token model.)
  - **→ S4:** `POST /families` binds only the user's first null-`family_scope`
    credential → one user creating a **2nd family** gets fail-closed 404s on it
    (documented by a skipped E2E test). S4 (invites/multi-family) redesigns
    cred→family binding (per-family creds).
  - Cleanup (S3 cutover / pass): `:any` typing on the middleware boundary; dev-token
    `Math.random` cred id → crypto + reuse `mintCredentialFor`; dup `content:*` scope
    literal; lazy-import = first-request (not boot) detection of missing `AUTH_*`.
- **NEXT: AUTH-S3** (CLI device grant, RFC 8628) — fully kills cloud/device
  hardcoding + triggers the legacy household-token cutover. Then S2 (Firebase), S4
  (invites), S5/S6 (UI, ADR 0008 design-gated).
- **Deploy note:** the live API still runs the household token until a prod deploy of
  this branch (operator-gated); the regenerated `api/index.js` carries the auth surface.

## TASK-KMP — Restructure apps/client into a true KMP module (prerequisite)

**Status:** ready (next session). **Blocks:** TASK-SYNC step 2+ (Android offline
DB) and the **iOS** shell. **Why:** today `apps/androidApp` borrows `apps/client`
source via `srcDir` — which **can't carry SQLDelight's per-variant generated
code** (proven in TASK-SYNC step 1), and there's no iOS target. The fix is to
make `apps/client` a real Compose-Multiplatform module: `commonMain` (shared
logic + UI) + `androidTarget` / `jvm("desktop")` / iOS targets.

**Scope:**
1. Convert `apps/client` to `kotlin("multiplatform")` + `com.android.library` +
   `org.jetbrains.compose` + `kotlin.plugin.compose`. Source sets: `commonMain`
   (Model, Reducer, Selectors, CardRender, FeedScreen, FeedApp, ContentStore,
   SyncClient), `androidMain` (driver + WorkManager), `desktopMain` (Main.kt +
   JdbcSqliteDriver), `iosMain` (NativeSqliteDriver + BGTaskScheduler glue).
2. **SQLDelight in commonMain** (`generateAsync`? no — sync drivers); remove the
   `srcDir` borrow + the `ContentStore`/`Main.kt` excludes in `apps/androidApp`
   (which becomes a thin `:androidApp` depending on `:client`, or fold the
   Android entry into `androidMain` + an `application` module).
3. **HTTP cross-platform:** `SyncClient` currently uses `java.net.HttpURLConnection`
   (works on desktop+Android, **NOT iOS**). Swap to **ktor-client** (`cio`/`okhttp`
   desktop+android, `darwin` iOS) in commonMain — or keep an `expect/actual`
   HTTP fn. ktor is the clean call.
4. iOS app target (needs the operator's Mac/Xcode — escalate that part).

**Gotchas already solved (don't re-derive — see `processes/agent-dev-loop.md`):**
redux-kotlin alpha01 on Kotlin **2.3.20**; `store.selectorState{}` is an
**extension**; `redux-kotlin-granular` added explicitly; SQLDelight **2.3.2** +
**sqlite-3-38 dialect** (UPSERT); devtools `debugImplementation` inapp /
`releaseImplementation` inapp-noop; JDK 17; compose-MP **1.9.3** (watch the
AGP↔Kotlin↔compose-MP matrix when it becomes a KMP+android-library build).

**DoD:** one `:client` KMP module; `commonMain` holds all shared code incl.
SQLDelight + sync; android/desktop build from it (no srcDir, no excludes); tests
+ snapshots still green; iOS target compiles (run gated on Mac).

## TASK-SYNC — Persistence & Sync (offline-first client) · ADR 0020

**Status:** ✅ DONE + MERGED to `main` 2026-06-19 (merge `13db28b`). Steps 1–4 +
foreground poll shipped: SQLDelight DB-as-SoT, `SyncClient`→transport, `SyncEngine`
(mutex drain + `activeCardsFlow`→`CardsLoaded` bridge + start/resume/pause/poll),
instant offline cold-start, unidirectional `network→DB→store→UI`, crash-safe cursor.
24 desktop tests green, Android APK assembles, iOS framework links. Spec+plan in
`docs/superpowers/{specs,plans}/2026-06-19-task-sync*`. **REMAINING (deferred,
new slices):** **R3 background** — Android `WorkManager` `PeriodicWorkRequest` +
iOS `BGTaskScheduler` `BGAppRefreshTask` (both call the shared `SyncEngine.syncNow`;
iOS needs the Xcode iosApp shell first); **push** (FCM/APNs/SSE → `syncNow` hook);
**iOS sync-config** plumbing (api/family/secret, the BuildConfig analogue);
`payload`/`$defs` richer card fields. **Why it mattered:** the M0 client was
in-memory (network round-trip every open, no offline/cursor) — now fixed.

**Scope (build slice):**
1. **SQLDelight (KMP)** as source of truth — drivers per platform
   (`AndroidSqliteDriver` / `NativeSqliteDriver` iOS / `JdbcSqliteDriver` desktop);
   tables = content (cards at M0) + `sync_meta(cursor, last_synced_at)`; WAL.
2. **Sync engine** (`commonMain`) — rewrite `SyncClient` to write the DB in ONE
   transaction (upsert + tombstones + advance cursor); drain `has_more`
   (network → DB, not network → store).
3. **DB→store bridge** — SQLDelight reactive `Flow` → hydrate the redux store;
   `selectorState`/`FeedApp` unchanged (store = projection of DB).
4. **Cold-start** — hydrate store from DB first (instant, offline), then sync.
5. **Foreground poll loop** (~30–60 s, paused on background) + **Android
   `WorkManager`** + **iOS `BGTaskScheduler`** glue — all calling the shared engine.
6. **Tests** — offline-open (DB only), sync→DB→UI, background-sync writes DB,
   cursor survives restart. Verify via the snapshot/test loop + on-device.

**DoD:** opens instantly offline from cache; a foreground push reflects within one
poll interval; background sync keeps the next open fresh; `network→DB→store→UI`
holds. **Push (FCM/APNs/SSE) out of scope** (later milestone; same dataflow).
**Milestone:** next build slice after the M0 render.

## DEFERRED (from hub-sync PR2 / migration 0010)

- **hub-visibility-flip child fan-out trigger** — add with the visibility-toggle authoring slice (no M0 actor flips hub visibility; authoring is ADR-0016/0029-deferred).

## TASK-E2E — Investigate end-to-end encryption (privacy differentiator)

**Why now:** the server is a **dumb store that never processes content** (ADR
0004/0007), so E2E is structurally feasible: **CLI encrypts → server stores
blind ciphertext → device decrypts**. Privacy is a top selling point and this
would make it architectural, not policy. Investigation kicked off
2026-06-18 → `research/e2e-encryption-investigation.md` (agent in progress).

**Scope of the investigation:**
- What can be E2E (body_md, payload, titles, triggers, place coords) vs what
  must stay cleartext for routing (family_id, IDs, versions, timestamps).
- **Key management/distribution across the multi-member family + owner-approved
  invite + RFC 8628 device-grant flows** — how a family content key reaches
  each member device + each CLI credential **without the server seeing it**
  (passphrase-derived vs per-member public-key-wrapped vs sealed-sender).
- **Features sacrificed:** server-side `tsvector` FTS (→ client-side search),
  any server validation. Quantify the loss.
- **Recovery / key-loss** (E2E = lost key → lost data): recovery-phrase /
  key-backup UX + escrow tradeoffs.
- **Perf:** decrypt-each-time vs store-decrypted in the SQLDelight cache
  (on-device cache security).
- **KMP libraries** (libsodium/lazysodium, Tink, age) + maturity.
- **Threat model:** protects server breach; not device compromise; metadata
  leakage (sizes/timing/which-family).
- **Milestone:** likely **M0 E2E is easy** (single household, operator-only
  key); the hard part (multi-member key distribution) is M1. Recommend split.
- **ADR recommendation** (this is ADR-class — privacy posture + architecture).

DoD: a feasibility report the operator can decide go/no-go + milestone from;
if go, a Proposed ADR.

## TASK-license-strategy — Licensing & open-source strategy (research) · ADR-class

**✅ RESEARCH DONE 2026-06-25 → Proposed ADR 0032** (awaiting operator + [pending-counsel]). Report: `research/2026-06-25-licensing-open-source-strategy.md`. Verdict: open-source-for-showcase GO; Apache client/CLI/schema + AGPL server + closed G1; hosted-SaaS monetization. Original brief below.

Deep research + brainstorming on **how to license & publish Dayfold (apps + CLI)**:
can it be open source **safely** (security + business-strategy), which license, can it
still be **monetized** if OSS, and the business-strategy tradeoffs. Ideal = OSS (for
showcase / resume / free tooling) **and** a monetization path. Triggered by ADR 0031's
deferred license gate. **Brief:** `research/licensing-open-source-strategy-brief-2026-06.md`
(scope, the 5 questions, method, the AGPL-server + Apache-client + closed-brains
hypothesis). Method: deep research (cited) + the `solo-business-strategist` agent +
a security open/closed-split analysis + two adversarial-review rounds. **DoD:** a
research report (A–D answered + recommendation) + a Proposed ADR (per-component license
+ closed surface + monetization model) that closes ADR 0031's gate and `OQ-license`.
**Final license is legal/business → operator-gated + `[pending-counsel]`; research
informs, operator decides.**

## MOBILE RELEASE PIPELINE (ADR 0034 — Proposed 2026-06-25)

**✅ PIPELINE BUILT + locally-verified** (signed `bundleRelease`, versionCode/Name from
env, unsigned-without-secrets all confirmed on a local SDK). `release-android.yml`
(merge→`internal`, `android-beta-v*`→`beta`, `android-v*`→`production` draft) +
`:androidApp` signing/versioning + a PR `assembleDebug` smoke job in `ci.yml`. Inert
until the operator gates (**INB-23** / ADR 0034 G1–G5). Follow-on tasks:

- **TASK-mobile-promote-artifact** (ADR 0034 G6) — switch beta/prod from rebuild-from-tag
  to **promote the exact alpha-tested artifact** (Play track-to-track promotion via
  fastlane `supply --track-promote` or the edits API), so "what was tested is what
  ships." Needs Play set up (G3) to design/verify. Medium.
- **TASK-mobile-r8** (ADR 0034 G7) — enable R8 minify + resource-shrink for the release
  variant with **vetted keep-rules** (redux-kotlin, Firebase, Compose, kotlinx-
  serialization, ktor). Currently `isMinifyEnabled=false`. Verify a signed AAB still runs
  on-device + the fake-backend/debug paths are unaffected. Medium.
- **TASK-mobile-sdk-firstrun** (ADR 0034 G9) — validate the GitHub-runner Android-SDK
  setup on the first real CI run (the `platforms;android-37` vs `android-37.0` package
  name + build-tools), tighten the install step once observed. Small; do at first run.
- **TASK-ios-pipeline** (ADR 0034 G8) — **BLOCKED on building the Xcode/Swift host app**
  first (only the KMP framework compiles today). Then: TestFlight-internal as the iOS
  "alpha" (no merge-time auto-publish — Apple processing/review), fastlane `match`
  (signing) + `pilot`/`deliver` via an **App Store Connect API key** on a **macOS runner**
  (~10× minute cost), driven by the same `android-*`-parallel tags. Needs the operator's
  Mac + an Apple Developer account ($99/yr — **spend**). Large; sequenced after the iOS
  host shell (contends with the iOS-shell task in TASK-KMP).

## TASK-CLIENT-MODULARIZE — `:client`/`:ui` module split (ADR 0047) ✅ DONE 2026-07-02

**Status:** Slice 1 (`:ui` Compose extraction) COMPLETE. Branch `client-modularize`; commits `ef813d5` (P2.2b) + `ccb7aab` (P2.3 measure/docs) + fix commit (P2.3 isolation provenance + snapshot path + iOS-framework doc refs).

**Shipped (Phases 0–2):**
- **P0:** Measured the monolith — KT-62686 fires on every edit, ~15,570 lines recompiled, ~4.2s per incremental build. Kotlin-reports baseline captured; build-cache reverted (regression); CC kept.
- **P1:** Gradle caching/parallel/CC levers measured — none escape KT-62686 for inner loop. CC kept (~0.15s win; neutral), build-cache reverted (+~10s/loop regression).
- **P2.1:** Scaffolded empty `:ui` KMP module (`api`-depends `:client`).
- **P2.2a:** Moved all Compose files (~40 files: composables, theme, cards Compose, resources, entry points, expect/actual seams) from `:client` → `:ui`; rewired `:androidApp`; promoted cross-module internals; iOS framework target moved to `:ui`.
- **P2.2b:** Stripped Compose deps/plugin/framework from `:client` — now fully Compose-free (grep-verified).
- **P2.3:** Measured split — `:client` logic edit: 7,348 lines / ~2.4s (vs 15,570 / ~4.2s P0 = **−53% lines, ~−43% time**). KT-62686 still fires (size win, not KT-62686 escape). DoD verified: client 440/440 + ui 311/311 tests green; iOS framework links; `:client` Compose-free ✓. Docs updated.

**Deferred / still open:**
- `:androidApp:assembleDebug` / `assembleRelease` — **BLOCKED** by missing `google-services.json` (no secret in worktree). Required follow-up in CI/secret-bearing env before declaring full-build DoD.
- Android CC compatibility (`:androidApp:assembleDebug` + `com.google.gms.google-services`) — untested, same blocker.
- Android + full-graph incremental compile measurements — deferred for same reason.
- **`:model`/`:data` further split** (second slice, ADR 0047 §Remaining) — would shrink each module further; not in this slice.
- KT-62686 escape investigation — not Compose-specific (fires on all KMP modules); may need Kotlin 2.4.x or `enableUnsafeIncrementalCompilationForMultiplatform=true` (risky); deferred.

**ADR 0047 shape delta:** iOS framework now in `:ui` (not `:client`). Header: `apps/ui/build/bin/iosSimulatorArm64/debugFramework/client.framework/Headers/client.h`. Immutable ADR unchanged; delta recorded in `specs/client-modularize-measurements.md` P2 section.

---

## CODE DEDUP FINDINGS (2026-07-01 audit; re-swept + partly applied 2026-07-05)

Not urgent (CI is green, nothing broken) — surfaced by repo-wide simplify passes.
The 2026-07-05 pass applied the small, mechanically-safe items below **by careful
inspection** (no local Gradle/npm registry access in that sandbox either — same
constraint as 2026-07-01; verification relies on the real CI run on push, not a
local build). Re-verify green on `main` before trusting this list as current.

**Applied 2026-07-05 (verify CI landed green, then delete this sub-list):**
- `apps/api` — `bearer()` de-duplicated: sole definition now `src/auth/middleware.ts`
  (exported), imported by `app.ts`.
- `apps/api` — visibility/audience PUT validation extracted to
  `parseVisibilityAudience(raw)` in `app.ts`, used by both the card and hub PUT
  handlers.
- `apps/api` — the post-`authorizeTenant()` caller shape extracted to
  `callerFrom(a)` in `app.ts`, replacing all 11 inline rebuilds.
- `apps/api` — dead `repo.syncCards` (superseded by `syncContent`, zero callers)
  deleted from `repo.ts`.
- `apps/cli` — the refresh-token flow extracted to `refreshAccessToken(store,
  keychain): String` in `Main.kt`, replacing the three inlined copies in
  `authedGet`/`authedDelete`/`push`.
- CLI/skill doc drift closed: `timeline` added to `references/cli.md`'s type
  list (was in code + `templates/README.md`/`content-model.md` but missing
  there); `upgrade`/`-v` aliases documented in `USAGE` + `cli.md`; checklist
  item id-stamping (ADR 0038) documented in `USAGE` + `cli.md` (was real
  undocumented behavior — an agent hand-authoring checklist ids on every push
  would silently break per-member toggle continuity); `importance` +
  `relatedKicker` added to `content-model.md`'s BriefingCard field list.

**Still open — not applied, still needs a build-capable toolchain to verify:**
- **`apps/api`** — auth-route boilerplate (`bearer(c)` + lazy `verifyAccess` +
  the revoked-credential check) is repeated ~9× across `/auth/signout`,
  `/auth/whoami`, `GET`/`PATCH /auth/me`, `/auth/me/export`, `/auth/me/credentials`
  (GET+DELETE), `DELETE /auth/me`, `POST /families`, `GET /device/pending`
  (2026-07-05 audit: `app.ts` ~lines 158/177/189/207/228/244/266/290/720). Extract
  a `requireSession(c): {sub,cid} | Err` helper mirroring `authorizeTenant`. Left
  unapplied this pass — 9 call sites is more surface than the mechanical
  extractions above; do it with a real build to catch a subtle miss.
- **`apps/api`** — "fetch hub, check visible, else 404" repeated verbatim 3×
  (`GET /hubs/:id`, `/hubs/:id/tree`, `/hubs/:id/audience`) + once more (with an
  extra author-gate check) inside the hub PUT. A `hubs.getVisibleHub(fid, id,
  caller)` helper in `content/hubs.ts` would cover the 3 GET routes.
- **`apps/api`** — credential-minting (`INSERT INTO credentials` + `grantScopes`
  with the same 3 default scopes) is near-duplicated across `/auth/dev-token`,
  `auth/identity.ts:mintCredentialFor`, `auth/device.ts:redeem`. Lower priority —
  the `kind`/columns differ slightly per path.
- **`apps/api`/`packages/linkrules`** — `media-validation.ts` / `MediaValidation.kt`
  two-copy duplication is **intentional** (ADR 0036 Phase 2 plans codegen-from-one-
  source) — leave as-is; if picked up before Phase 2, the lower-risk interim step
  is a CI parity guard, not a shared implementation.
- **`apps/api`** — `src/generated/content.timeline.test.ts` is hand-written inside
  the codegen-output `generated/` dir. Move next to (or merge with)
  `src/content-validation.timeline.test.ts` before someone deletes it as stale
  generated output. Also: the ~46 other API tests all live under `apps/api/test/`
  — these two are the only ones beside their source; normalize the convention.
- **`apps/api`** — `app.ts` is ~1000 lines holding all ~45 routes. Splitting into
  per-resource route modules is still the biggest win but the biggest risk;
  needs a real build to land safely.
- **CLI/skill docs** — moderate (3-4x) duplication of the same explanations
  across `SKILL.md` / `references/cli.md` / `references/content-model.md` /
  `templates/README.md` / `USAGE`: hub timeline, block payload field table,
  visual-enrichment/`media`, auto-linkify, and "local validation is a pre-check
  only" are each explained in 2-4 places. Not inconsistent (the copies agree),
  just redundant — consolidate to `cli.md` = command mechanics,
  `content-model.md` = field shapes, `guardrails.md` = policy only (one-line
  pointer instead of restating pre-check mechanics), `templates/README.md` =
  short pointer + its worked walkthrough. Deferred: touches 5 files' prose,
  worth doing as its own pass rather than folded into a code-dedup sweep.
