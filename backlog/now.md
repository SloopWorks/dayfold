# Backlog — Now

## ⚠ Time-sensitive (hard dates — keep pinned at top)

- **Quarterly:** re-check whether Google ships a *free, family-shared*
  Gemini Daily Brief variant (KS-6 / OQ-gemini-family). First check ~2026-09.
- **Next P0 viability review due 2026-07-18** (or +10 iterations).

Stage: **M0 render prototype BUILT + cloud-live (2026-06-19).** server · Kotlin
CLI · KMP/Compose client · feed — on Vercel + Neon, rendering on the Pixel 10.
Validation verdict still stands: **CONDITIONAL — learning-lab GO, business
NO-GO** → **building to learn**; the business unknowns (OQ-wtp / niche / gemini)
are **untouched by design**. The "brains" (G1 authoring loop) is a deliberate
later milestone; interim authoring = operator + Claude Code via the CLI.

**Status update (2026-06-26): first real on-device sign-in is LIVE on prod.** Real
Google sign-in + foreground sync now work end-to-end on the Pixel against
`family-ai-dashboard.vercel.app`, after fixing a two-part prod-config gap (DB schema
behind the entire AUTH epic + missing token-signing env) and two in-app bugs
(sync token-refresh-on-401 #104, debug-drawer Logs bridge #106). See the DONE entry
under *Operator actions* for the full account + the `npm run preflight` recurrence
guard. Next real validation: operator drives sign-in → create-family → `dayfold
login` (device grant now works on prod) → CLI authoring → on-device render.

**Status update (2026-06-23):** TASK-KMP + TASK-SYNC done+merged. The **AUTH epic**
(ADR 0021; S1·S3·S4·S5·S6) and the **CL/Dayfold content epic** (CL-0…CL-9) all
merged to `main` (PRs #2–#21). **AUTH-S2 *real Google path* ✅ DONE + MERGED**
(PR #25, `main` 8c0ccec) — Firebase ID-token verify (JWKS, ADR 0027),
`/auth/firebase`, client `firebaseToken` seam, CI Firebase emulator job, S2 design
spec + operator runbook; brought the branch up to Gradle 9.4.1 + the debugdrawer
modules (PR #26). **Entire AUTH epic S1–S6 now shipped.** **Open inbox:** INB-3,
INB-13. **INB-19 PARTIAL (2026-06-22):**
rk ratified + pinned alpha02 (ADR-0019-realized); publishing `redux-kotlin-snapshot`
+ Homebrew-tap symlink fix still pending (operator-only) — **golden harness not yet
wired**, so hold CL-NAV/CL-10 build until it lands.
**ADR 0020 (TASK-SYNC) still *Proposed*** though built — operator may flip to
*Accepted*.

## Design-first gate (ADR 0008) — status

The **feed-only** M0 slice was built **build-first** (operator-directed) from the
initial Now mockups in `designs/`. ADR 0008 **still governs unbuilt surfaces**:
the **M1 trigger surface** needs its hi-fi mockups (trigger v2 = INB-13) **before**
it's built. **Event Hubs render: design gate CLEARED (INB-22, 2026-06-24)** — the
Hubs phone surface (INB-15/16) + content adaptive two-pane (INB-20) + the ADR-0030
visibility delta (`Hubs-Visibility.dc.html`, signed off) are all in; the content-
API enforcement is built (PRs #34/#35). Hub render is build-ready.

## Operator actions pending

- [x] **DONE 2026-06-26 — prod DB schema synced + auth env configured (agent, under
  ADR 0012 rails, operator-supplied the Neon string).** Diagnosing the first real
  on-device Google sign-in (`firebase HTTP 500`) revealed prod had only ever run the
  **legacy `HOUSEHOLD_SECRET` content path** — the prior "0006–0010 applied" note was
  inaccurate; the entire **AUTH epic (0002/0003/0008) + 0004/0005 + 0009 + the
  0010/0011 fanout** were never applied (incl. the subtree-revocation 0011 this item
  tracked). Applied all missing migrations → `npm run db:check` = in sync (17/17).
  Also: prod had **no `AUTH_SIGNING_KEY`/`AUTH_ISS`/`AUTH_AUD`** (token minting threw
  → `/auth/firebase` 500) — generated an Ed25519 key, set the three vars, redeployed;
  all auth endpoints non-500. **Sign-in + foreground sync now work on the Pixel.**
  In-app fixes shipped same day: sync token-refresh-on-401 (#104, the "Couldn't
  refresh" wedge) + debug-drawer Logs bridge (#106). Recurrence guards: `npm run
  preflight` (`env:check` #103 + `db:check` #93) + the migration drift-guard (#91).
  Durable fix for the manual-apply process = **ADR 0033 (Proposed)** — accept to
  build the tracked runner.
- [ ] **ADR 0031 (CLI Homebrew distribution) — review + accept/reject + setup.**
  Spike (`research/2026-06-25-spike-cli-homebrew-distribution.md`) + Proposed ADR
  recommend a one-line `brew install` via a first-party tap. Operator-gated steps:
  (1) **license / public-vs-private distribution decision** (repo is unlicensed; a
  public tap distributes the CLI publicly); (2) create `SloopWorks/homebrew-tap`;
  (3) add a `HOMEBREW_TAP_TOKEN` secret; (4) accept the ADR → then the inert
  `release-cli.yml` + formula land and `cli-v0.1.0` is cut. The packaging-ready
  build change already merged (#76).
- [ ] **INB-3** kill-checks (~2 hrs): Gemini Daily Brief + Maple+ hands-on;
  note the niche gap → feeds A1. *(Only matters if pursuing the business path.)*
- [ ] **INB-13** hand the trigger-design v2 fix-list (`designs/DESIGN-BRIEF-
  triggers.md §6b`) back to Claude Design before the M1 trigger surface.
- [ ] Counsel confirm for ADR 0005 (14+) — only if/when pursuing teen accounts.
- [ ] **INB-19 remainder** (operator-only): publish `redux-kotlin-snapshot` to
  Maven Central + fix `reduxkotlin/homebrew-tap` symlink (keg `bin/` empty →
  binary at `libexec/Contents/MacOS/rk`). Unblocks the `:client:snapshotUi`
  golden harness → prereq for CL-NAV/CL-10.

## State (2026-06-18 — post 6-agent review)

- **Spec-build loop SUSPENDED** (cron stopped) — process right-sized for the
  solo M0 build (kept: spec-gate + inbox + multi-agent reviews).
- **Decisions:** M0 = **plaintext** (live E2EE → M1, ADR 0017 gate); M0 surface
  = **briefing-feed only** (Hubs → next slice); redux **1.0.0-alpha01** (INB-11
  superseded 2026-06-19 — operator owns reduxkotlin; `f(store.state)→UI`).
- **Spec suite + impl plan + JSON-schema contract = done; schema freeze
  unblocked.** Ready to build the M0 spine.
- **INB-9 RESOLVED → TypeScript on Vercel (ADR 0018).**
- **✅ M0 PROTOTYPE BUILT (12 build-loop iterations; build loop now STOPPED).**
  server (TS/Hono/Postgres) · CLI (Kotlin) · client core (redux-kotlin 0.6.2) ·
  feed UI (Compose, headless-render-tested) — all green, CI-enforced on GitHub,
  every component multi-agent-reviewed + fixes applied. Full CLI→API→DB→/sync
  round-trip verified live; the feed renders. Deploy config ready (Vercel + Neon
  pooler). Tracker: `specs/prototype/00-build-spec-plan.md`.
- **Blocked only on operator gates for literal G1a:** **INB-12** (create Vercel +
  Neon → deploy, recipe in inbox) · **INB-14** (Android SDK/device; iOS = Mac).
- **✅ DONE + MERGED — `TASK-KMP` (apps/client → true KMP module).** Merged to
  `main` 2026-06-19 (merge `0d621e5`, pushed to origin; feature branch deleted).
  Single Gradle build at `apps/` (8.11.1 + AGP 8.7.2); `:client` = KMP —
  commonMain holds all shared logic+UI+SQLDelight+ktor sync; **android + desktop
  + iOS** targets all build from it. `:androidApp` = thin app dep on `:client`
  (srcDir borrow + ContentStore/Main excludes GONE → TASK-SYNC step 2 unblocked).
  SyncClient → ktor `suspend`; driver via expect/actual `DriverFactory`. 17
  desktop tests + snapshots green; Android APK assembles; **both iOS targets
  compile + debug framework links** (iosX64/intel-sim dropped — granular alpha01
  lacks that publication). **Operator-gated remainder (DoD "run gated on Mac"):**
  Xcode iosApp project (Swift @main + signing + sim run) + iOS sync-config
  plumbing → folds into **TASK-SYNC**.
- **✅ DONE + MERGED — `TASK-SYNC` (offline-first DB-as-SoT, ADR 0020).** Merged to
  `main` 2026-06-19 (merge `13db28b`, pushed; branch deleted). Delivered **R1**
  (instant offline cold-start), **R2** (foreground poll ~45s + sync-on-resume),
  **R4** (unidirectional `network→DB→store→UI`, crash-safe cursor in `sync_meta`).
  `SyncClient`→transport (`fetchPage`); new **`SyncEngine`** owns the mutex-guarded
  drain loop + DB→store bridge (`activeCardsFlow`→`CardsLoaded`) + poll lifecycle
  (`start`/`resume`/`pause`/`stop`, public `syncNow` = future push hook). Store is a
  pure DB projection (no network→store path); cursor removed from `AppState`. Desktop
  file DB + WAL; iOS native driver. **24 desktop tests green, Android APK assembles,
  iOS framework links.** Built subagent-driven (spec+plan in `docs/superpowers/`,
  3 review rounds + final whole-branch review). kotlinx-datetime bumped 0.6.1→0.7.1
  (`Clock`→`kotlin.time`). **OUT (deferred):** R3 background (WorkManager/BGTask),
  push (FCM/APNs/SSE), E2EE (ADR 0017), 2-way/outbox (ADR 0016), iOS sync-config,
  the `payload`/`$defs` richer card fields. **ADR 0020 still marked *Proposed* —
  operator may flip to *Accepted* now that it's built.**
- **Deferred by design (operator, 2026-06-19): G1 content-authoring loop ("the
  brains") = much-later milestone; interim authoring = operator + Claude Code via
  the CLI.**
- **Still-queued gaps: G2 usefulness signal, the `payload` $defs, the
  Claude-Design triggers v2 (INB-13) + M3-Expressive upgrades.**

## Done this period

- Bootstrap (2026-06-18): scaffold, ADRs 0001-0004, validation fleet, board.
- Event Hubs design + block-level deep-linking (ADR 0006).
- Prototype scope locked (ADR 0007); design-first gate added (ADR 0008).
- Inbox swept: INB-1/2/4/5/6/7/8 answered; INB-3 pending operator.
- Design system = M3 Expressive, adaptive (ADR 0009); design brief shipped;
  initial Now/Hubs/Auth mockups added; repo public on GitHub.
- Auth/family/invite architected (ADR 0010) → **5-agent review**
  (`research/design-review-auth-2026-06.md`) → **hardened (ADR 0011
  supersedes 0010)**: all-invites-owner-approved, email→push cut, device-
  grant anti-phishing, no-auto-link, per-request revocation, Firebase dedupe
  corrected, relational content tables. Spec + Hub schema hardened.

## Auth is now in ACTIVE BUILD (ADR 0021, supersedes the "later milestone" note)

Operator pulled auth forward 2026-06-19 (ADR 0021, extends 0011): build order
**S1→S3→S2→S4→S5/S6**. **AUTH-S1 (tenancy & token backbone) ✅ DONE + MERGED**
(`main` 5753b32): EdDSA tokens + refresh lineage + `authorizeTenant` (JWT + legacy
household path, default-deny, fail-closed, cross-tenant 404) + `/auth/{refresh,
signout}` + `POST /families` + JWKS + gated local-only dev-token. 51 tests + final
security review passed; legacy household token still works (cutover at S3). Details
+ carried debt (S3 refresh-grace, S4 per-family cred binding) in `backlog/next.md`.
**AUTH-S3 ✅ DONE + MERGED** (PR #2, `main` 1fc1918): RFC 8628 CLI device grant
(`/device/*` + owner approve + lazy-mint) + refresh ~20s grace + Kotlin CLI
`login`/`push`. Twice multi-agent-reviewed; CI green. Legacy household token still
works (removal gated). **UPDATE 2026-06-23:** S4 (owner-approved invites), S5
(sign-in/out + account flow), S6 (member roster, connected-devices, profile,
data-export, account soft-delete) all **DONE + MERGED** (PRs #4–#20); A8b auth
mockups imported (design gate cleared). **AUTH-S2 real Google path ✅ DONE +
MERGED** (PR #25). Full S1–S6 epic shipped. Prod deploy of the auth surface =
operator-gated (set `AUTH_*` env in Vercel).
