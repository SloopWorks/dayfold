# Backlog — Next

Queued behind the validation gates (`context/goals-and-constraints.md`).
Populated at bootstrap and by loop close-outs.

> **Tracking convention:** build/work items = `TASK-<slug>` here (`next.md`),
> promoted to `now.md` when active, `later.md` when deferred. Operator decisions
> = `INB-N` in `operator-inbox.md`. High-level phases = `planning/workstreams.md`.
> No issue tracker yet (workstream D2 deferred).

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
  2026-06-20 (PR #5, `f399583`); operator merged = sign-off.
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
