# Backlog — Next, history

Append-only archive of completed epic narratives moved out of `backlog/next.md`
(2026-07-12 repo-maintenance pass) to keep that file focused on the active/
queued build queue — mirrors the `now.md`/`now-history.md` split done
2026-07-03. **Not loaded by default** — read `backlog/next.md` first; come
here only when you need the detailed build narrative (task-by-task DoD,
review notes, spec links) behind a shipped epic. Nothing was reworded or
summarized; entries below are moved verbatim.

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


---

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

