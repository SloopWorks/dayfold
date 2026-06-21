# Backlog — Next

Queued behind the validation gates (`context/goals-and-constraints.md`).
Populated at bootstrap and by loop close-outs.

> **Tracking convention:** build/work items = `TASK-<slug>` here (`next.md`),
> promoted to `now.md` when active, `later.md` when deferred. Operator decisions
> = `INB-N` in `operator-inbox.md`. High-level phases = `planning/workstreams.md`.
> No issue tracker yet (workstream D2 deferred).

## CONTENT LIBRARY + DETAIL + FOLD GESTURE (ADR 0022 — Accepted 2026-06-19)

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
- **TASK-CL-2** — Server: typed storage + nested validation + keyset sync.
- **TASK-CL-3** — CLI + Claude-skill typed authoring (the content-API wedge).
- **TASK-CL-4** — Client data: typed model + SQLDelight + store.
- **TASK-CL-5** — Client UI: 6 typed Now cards (light+dark, inline actions).
- **TASK-CL-6** — Client UI: DetailScreen (per-type hero + provenance/privacy).
- **TASK-CL-7** — Fold gesture: container transform (SharedTransitionLayout;
  predictive-back needs Compose-MP ≥1.10 — sub-task/risk).
- **TASK-CL-8** — Related-edges (cross-links / attachment↔email).
- **TASK-CL-9** — Map-render strategy spike (ADR 0014 privacy posture).
- **TASK-CL-10** — Adaptive two-pane detail — **BLOCKED** on a Claude-Design
  expanded-detail pass (design gap; phone-only designed).

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
