# Backlog — Next (History)

Completed/superseded narrative pruned out of `backlog/next.md` (2026-07-14,
repo-maintenance pass) so that file stays a short list of what's actually
still queued — mirrors the `now.md`/`now-history.md` split (2026-07-03).
Read this when you need the detailed build narrative behind a shipped
feature; `next.md` keeps only a one-line pointer into here where relevant.

**Verification note:** three entries below (TASK-AUTH-S6-D, TASK-AUTH-CONTENT,
TASK-KMP) were archived on strong-but-not-build-verified evidence (git log,
CHANGELOG.md, file existence — no compile/test tooling available in this
sandbox). `next.md` keeps a short flagged stub for each pending one real
verification pass; don't treat their presence here as a substitute for that.

---

## CONTENT LIBRARY + DETAIL + FOLD GESTURE (ADR 0022 — Accepted 2026-06-19)

> **STATUS 2026-06-21 — M0 build order EXHAUSTED + MERGED TO MAIN** (PR #7
> `cl-integrate`: CL-0…CL-8 + CL-PLAT + CL-3, with auth S1–S5).

From the Claude Design import (`designs/content/*`, `designs/Brand.dc.html`).
**Full breakdown + DoD + file touchpoints: `planning/content-detail-epic.md`.**
**Gates CLEARED (INB-15/16/17/18):** ADR 0022 accepted · **D2 = extend
`briefing_cards` in place** (unify→M1) · phone mockups signed off (ADR 0008) ·
name **Dayfold** confirmed · **M0 ships all 6 content types**.

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
  tests + full api suite (73/1-skip) green.
- **TASK-CL-2** — Server: typed storage + nested validation + keyset sync. ✅
  **DONE** 2026-06-20. Migration `0005_typed_content.sql` extends `briefing_cards`
  IN PLACE (D2): nullable `type`/`payload`(jsonb)/`privacy`(jsonb)/`hub_ref` + a
  `type`-enum CHECK. `repo.upsertCard` carries all 4; `SELECT *` serves them on
  GET/`/sync`. New `content-validation.ts :: crossValidateCard` enforces
  typed-iff-payload + payload-key === `type`; legacy kind-only cards still valid.
  Full api suite 80 pass / 1 pre-existing skip. Spec:
  `docs/superpowers/specs/2026-06-20-cl-2-server-typed-storage-design.md`.
- **TASK-CL-3** — CLI typed authoring (content-API wedge). ✅ **DONE** 2026-06-20.
  CLI consumes the generated `com.sloopworks.dayfold.schema.*` types. `dayfold push
  <id> <file> --type <t>` runs local structural validation and fails fast with
  field errors before the server; `dayfold template <type>` emits a valid starter.
  CLI test green (ValidateTest 8/0, CredentialsTest 2/0). Spec:
  `docs/superpowers/specs/2026-06-20-cl-3-cli-typed-authoring-design.md`.
- **TASK-CL-4** — Client data: typed model + SQLDelight + store. ✅ **DONE**
  2026-06-20. `Card` gains `type`/`payload`/`privacy`/`hubRef`; new wrapper
  `Payload` + 6 variant data classes. `Content.sq` `card` table +
  `upsertCard`/`activeCards` carry the new fields. **36 desktop tests green**;
  Android + iOS-sim compile. Spec:
  `docs/superpowers/specs/2026-06-20-cl-4-client-typed-data-design.md`.
- **TASK-CL-5** — Client UI: 6 typed Now cards. ✅ **DONE** 2026-06-20. `cards/`
  package: `CardAction` (closed union, read-only ADR 0020), pure `TypedCardLogic`,
  `TypedCards` (6 composables + shared chrome + dispatcher). **46 desktop tests
  green** (5 logic + 8 snapshots incl. 6-type light+dark + 3 RSVP states);
  Android + iOS-sim compile. Spec:
  `docs/superpowers/specs/2026-06-20-cl-5-typed-now-cards-design.md`.
- **TASK-CL-6** — Client UI: DetailScreen + redux nav. ✅ **DONE** 2026-06-20.
  Nav as app state (ADR 0013): `AppState.detailStack` + `NavToDetail`/`NavBack`.
  DetailScreen: colored hero header + per-type hero media + safe actions row +
  DETAILS meta list + provenance/privacy chips. **69 desktop tests green**;
  Android + iOS-sim compile. Spec:
  `docs/superpowers/specs/2026-06-20-cl-6-detail-screen-design.md`.
- **TASK-CL-7** — Fold gesture (M0 = base transition, per INB-18). ✅ **DONE**
  2026-06-20. Added `ui-backhandler` dep; hardware/gesture back → `NavBack`; base
  feed↔detail `AnimatedContent` transition. **72 desktop tests green.** Spec:
  `docs/superpowers/specs/2026-06-20-cl-7-base-transition-design.md`.
  **→ CL-7b v1 ✅ DONE** 2026-06-20 — `SharedTransitionLayout` container
  transform (feed card ↔ detail share bounds); debug card-seed for on-device
  exercise without an API. **Verified LIVE on the emulator** (feed→detail→back +
  hardware-back + seeded feed + RELATED nav). **76 desktop tests.**
  **CL-7b-remaining (spec-sanctioned, on-device iteration, cut to M0):**
  corner-morph/scrim/content-fade tuning; predictive-back scrub; live
  mid-transition frame capture. Later folded/superseded by ADR 0050/0051.
- **TASK-CL-8** — Related-edges (cross-links / attachment↔email). ✅ **DONE**
  2026-06-20. `BriefingCard` gains `relatedKicker` + `related[]` edges. Server
  migration `0006_related.sql`; client `Card.related`; `DetailScreen` RELATED
  section → detail-to-detail chaining (dangling targetId = no-op). **76 client
  tests + 82 api (1-skip).** Spec:
  `docs/superpowers/specs/2026-06-20-cl-8-related-edges-design.md`.
- **TASK-CL-9** — Map-render strategy spike (ADR 0014 privacy posture). ✅
  **SPIKE DONE + DECISION RECORDED** 2026-06-21
  (`docs/superpowers/specs/2026-06-21-cl-9-map-render-spike.md`). **Decision:
  M0 = keep the stylized `MapStrip()` placeholder + Navigate handoff** — no key,
  no cost, no third-party coord leak. **Key finding:** a static-map call
  transmits the authored place coordinate to a third party — ADR-class,
  operator-gated. **Follow `CL-9b` (deferred, M1, still queued — see
  `next.md`):** author-time-stamped static map image, behind a new ADR for
  third-party map-provider disclosure.
- **TASK-CL-PLAT** — Platform action effect layer (CL-6 prerequisite). ✅
  **DONE** 2026-06-20. `expect class PlatformActions { perform(CardAction) }` +
  3 actuals (android/desktop/iOS). Pure `cardActionUri` vets at one seam —
  shared allowlist with `CardRender.ALLOWED_SCHEMES`. **54 desktop tests
  green**; androidApp + iOS-sim compile. Spec:
  `docs/superpowers/specs/2026-06-20-cl-platform-actions-design.md`.

(**TASK-CL-10** — Adaptive two-pane detail — still **BLOCKED** on a
Claude-Design expanded-detail pass; kept in `next.md`, not archived here.)

## AUTH (ADR 0021 — S1→S3→S2→S4→S5/S6)

All of S1/S3/S4/S5/S6 (identity, tenancy, CLI device grant, invites, sign-in
UI, account/family/device management) are **shipped and live** — `now.md`
lists "full AUTH epic (device-grant login, Google sign-in, roster/devices/
account)" under "Shipped and live on `main`." Full build narrative:

- **AUTH-S1 (Tenancy & token backbone)** — ✅ DONE + MERGED 2026-06-19
  (branch `auth-s1`). EdDSA token service + refresh lineage + `authorizeTenant`
  middleware (JWT + legacy household path, default-deny, fail-closed) +
  `/auth/{refresh,signout}` + `POST /families` + JWKS + gated local-only
  dev-token. 51 tests + 1 skipped; final whole-branch security review passed.
  Spec/plan: `docs/superpowers/{specs,plans}/2026-06-19-auth-s1*`.
- **AUTH-S3 (CLI device grant, RFC 8628)** — ✅ DONE + MERGED 2026-06-19
  (PR #2). `/device/{authorize,token}` + approve/deny + `/auth/whoami` + the
  refresh ~20s reuse-grace + Kotlin CLI `login`/`logout`/`whoami` + device-
  granted `push`. Owner+`kind='app'` approve gate, PATH-resolved tenancy,
  lazy-mint at redeem, DB-backed rate-limit + lockout, audit log. 67 API
  tests + CLI CredentialsTest + live round-trip green.
- **AUTH-S4 (owner-approved invites + family-agnostic cred fix)** — ✅ DONE,
  MERGED 2026-06-20 (PR #4). `invites` table; app creds family-agnostic
  (clears the S1 two-family limit); mint/redeem/approve/decline/revoke/remove
  (≥1-owner row-lock); owner+`kind='app'` gate; uniform-404 + per-account
  lockout. 96 API tests / 0 skips.
- **AUTH-S2 vendor gate** — ADR 0023 (operator-directed 2026-06-20): Firebase
  Google + Apple only, Phone-OTP deferred. Real Google/Apple sign-in shipped
  behind the buttons once the Firebase project/console step landed.
- **A8b auth/family/invite mockups** — delivered + operator signed off
  2026-06-20/21 (ADR 0008); `Auth-Phone.dc.html` 37 frames incl. failure/
  destructive screens (invite-declined/locked/error, delete-confirm,
  transfer-owner, device-denied/expired).
- **AUTH-S5 slice-1 (authenticated session + onboarding gate)** — ✅ DONE
  2026-06-20 (branch `auth-s5`). App's first navigation (pure `when(route)`
  gate) + session/token layer: `AuthClient` (ktor), `TokenStore`, `AuthEngine`
  (mutex orchestrator + 401 refresh-and-retry), sign-in/create-family/
  family-null screens. 74 desktopTest green; live round-trip pass.
- **AUTH-S5/S6 full build** (post slice-1, 2026-06-21) — client
  auth/account/family surface comprehensive + e2e-tested on a real emulator
  (4 instrumented `AuthFlowE2ETest` cases). Shipped: AccountScreen + sign-out,
  invitee-join (transport/UI/e2e), owner approvals (queue + approve/decline),
  member roster (GET /members + render + remove), connected-devices client
  (`DevicesScreen` + revoke), profile endpoints (display name), retention
  sweep. **Account-delete stayed gated** on an operator policy call
  (ON-DELETE cascade / sole-owner transfer / soft-vs-hard delete) —
  check `context/open-questions.md` / `backlog/operator-inbox.md` if still
  unresolved.

**TASK-AUTH-S6-D (CLI device-approval UI + scan/deep-link) — believed
shipped, archived here pending one verification pass** (flagged, not
silently asserted — see `next.md`'s stub). Evidence: `apps/ui/.../
DeviceApprovalScreens.kt` exists and was touched as recently as the
2026-07-11 scoped-tokens commit (`581fbdb`); `now.md`'s "Shipped and live"
paragraph lists the full AUTH epic including device-grant login. Original
scope was **Phase 1** (4 approval screens, CLI terminal QR, CLI refresh-token
→ OS keychain — all platform-agnostic) + **Phase 2** (in-app QR scanner +
App/Universal Links, gated on `designs/DESIGN-BRIEF-device-scan.md` sign-off).
Phase 1's code exists; Phase 2's scanner/deep-link status was **not**
independently confirmed this pass. Spec:
`docs/superpowers/specs/2026-06-23-auth-s6d-device-approval-design.md`.

**TASK-AUTH-CONTENT (content-API + CLI content verbs + per-hub scoping) —
believed mostly shipped, archived here pending one verification pass.**
Evidence: API hub endpoints (`GET/PUT/DELETE/archive /hubs/:id`,
participants, visibility) all exist in `apps/api/src/app.ts:528-707`;
CHANGELOG.md's 2026-07-11 entry confirms scoped CLI/device tokens (per-hub
grants, ADR 0029 extension) shipped; ADR 0030 (per-member hub visibility) is
Accepted and its DB/read-path pieces are live. **Not independently
confirmed:** the CLI's exact verb parity (`status`, `push --dry-run/--diff`,
`hub get|archive|rm`) against the original slice's scope — `Main.kt`
confirms `pull`/`push`/`whoami`/`template`/`delete`/`login`/`logout`/`update`
but a byte-for-byte diff of every originally-scoped verb wasn't done in this
pass. Specs: `specs/domain-model/scope-and-access-model.md`, ADR 0029, ADR
0030.

## TASK-KMP — Restructure `apps/client` into a true KMP module

**Believed done, archived here pending one verification pass.** Evidence:
`apps/client/src` already has `commonMain`/`androidMain`/`desktopMain`/
`iosMain` source sets, ktor-client is cross-platform (darwin target wired,
superseding the original `java.net.HttpURLConnection` limitation this task
was scoped to fix), and `apps/iosApp` exists as its own module. The task's
own stated DoD (one `:client` KMP module, no `srcDir` borrow, iOS target
compiles) reads as met by the current tree — not independently re-verified
against a live build in this pass (no Gradle registry egress in this
sandbox). Original scope, gotchas, and DoD are preserved below for
reference if the verification pass finds a gap:

Prerequisite for offline Android DB + the iOS shell — originally: today
`apps/androidApp` borrowed `apps/client` source via `srcDir`, which can't
carry SQLDelight's per-variant generated code, and there was no iOS target.
Scope: convert `apps/client` to `kotlin("multiplatform")` +
`com.android.library` + `org.jetbrains.compose`; SQLDelight in commonMain;
swap `SyncClient`'s HTTP to ktor-client (cio/okhttp desktop+android, darwin
iOS); iOS app target. **Gotchas already solved** (still relevant to anyone
touching this module): redux-kotlin alpha01 on Kotlin 2.3.20;
`store.selectorState{}` is an extension; SQLDelight 2.3.2 + sqlite-3-38
dialect (UPSERT); devtools `debugImplementation`/`releaseImplementation`
split; JDK 17; compose-MP 1.9.3 (watch the AGP↔Kotlin↔compose-MP matrix).

## TASK-CLIENT-MODULARIZE — `:client`/`:ui` module split (ADR 0047) ✅ DONE 2026-07-02

**Status:** Slice 1 (`:ui` Compose extraction) COMPLETE. Branch
`client-modularize`; commits `ef813d5` (P2.2b) + `ccb7aab` (P2.3
measure/docs) + a fix commit.

**Shipped (Phases 0–2):**
- **P0:** Measured the monolith — KT-62686 fires on every edit, ~15,570
  lines recompiled, ~4.2s per incremental build.
- **P1:** Gradle caching/parallel/CC levers measured — none escape
  KT-62686 for inner loop. CC kept (~0.15s win; neutral), build-cache
  reverted (+~10s/loop regression).
- **P2.1:** Scaffolded empty `:ui` KMP module (`api`-depends `:client`).
- **P2.2a:** Moved all Compose files (~40 files) from `:client` → `:ui`;
  rewired `:androidApp`; iOS framework target moved to `:ui`.
- **P2.2b:** Stripped Compose deps/plugin/framework from `:client` — now
  fully Compose-free (grep-verified).
- **P2.3:** Measured split — `:client` logic edit: 7,348 lines / ~2.4s (vs
  15,570 / ~4.2s P0 = **−53% lines, ~−43% time**). KT-62686 still fires
  (size win, not KT-62686 escape). DoD verified: client 440/440 + ui
  311/311 tests green; iOS framework links; `:client` Compose-free.

**Deferred / still open (superseded detail — check `next.md` for what's
still actually queued):**
- `:androidApp:assembleDebug`/`assembleRelease` full-build verification —
  blocked at the time by a missing `google-services.json` in the worktree.
- Android + full-graph incremental compile measurements — same blocker.
- KT-62686 escape investigation (Kotlin 2.4.x or the unsafe-incremental
  flag) — deferred, not Compose-specific.

**ADR 0047 shape delta:** iOS framework now in `:ui` (not `:client`). Delta
recorded in `specs/client-modularize-measurements.md` P2 section. (The
original 3-way `:model`/`:ui`/`:data` proposal this superseded — full
measured rationale for *why* the split — is no longer needed context now
that the decision is made and shipped; the **residual `:model`/`:data`
split is still queued**, see `next.md`.)

## TASK-SYNC — Persistence & Sync (offline-first client) · ADR 0020 — core DONE

**Status:** ✅ DONE + MERGED to `main` 2026-06-19 (merge `13db28b`). Steps
1–4 + foreground poll shipped: SQLDelight DB-as-SoT, `SyncClient`→transport,
`SyncEngine` (mutex drain + `activeCardsFlow`→`CardsLoaded` bridge +
start/resume/pause/poll), instant offline cold-start, unidirectional
`network→DB→store→UI`, crash-safe cursor. 24 desktop tests green, Android
APK assembles, iOS framework links. Spec+plan:
`docs/superpowers/{specs,plans}/2026-06-19-task-sync*`. **Why it mattered:**
the M0 client was in-memory (network round-trip every open, no
offline/cursor) — now fixed. (**REMAINING items — background sync, push,
iOS sync-config — are genuinely still queued, see `next.md`.**)

## TASK-license-strategy — Licensing & open-source strategy (research) · ADR-class — research DONE

**✅ RESEARCH DONE 2026-06-25 → Proposed ADR 0032** (awaiting operator +
`[pending-counsel]`). Report: `research/2026-06-25-licensing-open-source-strategy.md`.
Verdict: open-source-for-showcase GO; Apache client/CLI/schema + AGPL server
+ closed G1; hosted-SaaS monetization. Method: deep research (cited) + the
`solo-business-strategist` agent + a security open/closed-split analysis +
two adversarial-review rounds. Original brief:
`research/licensing-open-source-strategy-brief-2026-06.md`. **Final license
is legal/business → operator-gated + `[pending-counsel]`; the research
informs, the operator decides** (still open — see `next.md`'s short stub).

## TASK-E2E — Investigate end-to-end encryption (privacy differentiator) — investigation DONE

**Why:** the server is a dumb store that never processes content (ADR
0004/0007), so E2E is structurally feasible: CLI encrypts → server stores
blind ciphertext → device decrypts. Investigation:
`research/e2e-encryption-investigation.md`. Scope covered: what can be E2E
vs what must stay cleartext for routing; key management/distribution across
multi-member family + invite + device-grant flows; features sacrificed
(server-side FTS, server validation); recovery/key-loss UX; perf
(decrypt-each-time vs cached-decrypted); KMP crypto libraries
(libsodium/lazysodium, Tink, age); threat model. Recommendation: likely M0
E2E is easy (single-household, operator-only key); multi-member key
distribution is the hard M1 part — split. This is ADR-class (privacy
posture + architecture) — feeds ADR 0015/0017's M1 E2EE gate. **The actual
M1 build is still queued** — see `next.md`'s short stub.

## MOBILE RELEASE PIPELINE (ADR 0034 — Proposed 2026-06-25) — pipeline DONE

**✅ PIPELINE BUILT + locally-verified** (signed `bundleRelease`,
versionCode/Name from env, unsigned-without-secrets all confirmed on a
local SDK). `release-android.yml` (merge→`internal`, `android-beta-v*`→
`beta`, `android-v*`→`production` draft) + `:androidApp` signing/versioning
+ a PR `assembleDebug` smoke job in `ci.yml`. Inert until the operator gates
(**INB-23** / ADR 0034 G1–G5). **4 follow-on tasks (G6/G7/G9/G8) are still
queued** — see `next.md`.

## CODE DEDUP FINDINGS — Applied 2026-07-05 (verified green on `main`)

From the 2026-07-01 audit, re-swept 2026-07-05. Applied by careful
inspection (no local Gradle/npm registry access in that sandbox either):
- `apps/api` — `bearer()` de-duplicated: sole definition now
  `src/auth/middleware.ts` (exported), imported by `app.ts`.
- `apps/api` — visibility/audience PUT validation extracted to
  `parseVisibilityAudience(raw)` in `app.ts`, used by both the card and hub
  PUT handlers.
- `apps/api` — the post-`authorizeTenant()` caller shape extracted to
  `callerFrom(a)` in `app.ts`, replacing all 11 inline rebuilds.
- `apps/api` — dead `repo.syncCards` (superseded by `syncContent`, zero
  callers) deleted from `repo.ts`.
- `apps/cli` — the refresh-token flow extracted to `refreshAccessToken(store,
  keychain): String` in `Main.kt`, replacing the three inlined copies in
  `authedGet`/`authedDelete`/`push`.
- CLI/skill doc drift closed: `timeline` added to `references/cli.md`'s type
  list; `upgrade`/`-v` aliases documented; checklist item id-stamping (ADR
  0038) documented; `importance` + `relatedKicker` added to
  `content-model.md`'s BriefingCard field list.

## ~~SWIP analytics — event delivery is best-effort and non-durable~~ — RESOLVED 2026-07-12

**Fixed** (dayfold `feat/swip-background-flush-persistence` + swip-core
0.1.7): flush-on-background wired via `ProcessLifecycleOwner`
(persist-then-send inside Android's post-`onStop` window),
`SqlDelightPersistentQueue` wired as `persistence` (main-process-guarded),
and swip-core now calls `recover()` on init — it never did, which made the
persistent queue write-only. Verified on device: airplane-mode send failure
left 2 batches `PENDING` on disk; after relaunch they recovered and sent,
zero drops. **WorkManager deliberately NOT used** — INVARIANT 14 forbids
scheduled wakeups, and once the queue is durable it buys little.

Original report (found 2026-07-12): surfaced while root-causing "no events
in PostHog." No flush on background (`swip-lifecycle` emitted
`app_backgrounded` but never called `flush()`); no persistent queue
(`swipInit` passed no `persistence`, so the pipeline queue was memory-only);
net effect — the only delivery triggers were a 30s ticker + `flushAtEvents
= 30`, silently dropping a large fraction of events on a frequently
backgrounded/killed phone.
