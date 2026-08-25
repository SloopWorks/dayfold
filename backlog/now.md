# Backlog — Now

Current state only — kept short on purpose (start-of-session reading cost).
**Full chronological build history (every dated status update, feature by
feature) → [`backlog/now-history.md`](now-history.md).** This file was split
2026-07-03 (was 570 lines, one append-only log); read the history file when
you need the detailed narrative behind something below, not by default.
**Repo-maintenance passes older than the most recent one live in the history
file too** (moved there 2026-07-10, same reason) — this file keeps only the
latest pass's findings so it doesn't re-grow past its own stated purpose.

## ⚠ Time-sensitive (hard dates — keep pinned at top)

- **✅ SQLDelight migration gap — FOUND AND FIXED 2026-08-21/22 (20th pass).**
  `membership`, `calendar_import` and `card.target_{hub,section,block}_id` reached
  `Content.sq` (WI-450 `a50876e`, `0e47b04`) with no companion `.sqm`.
  **Correction to the first report of this:** the mechanism was worse than
  "migrate() runs a chain that misses them" — because no `.sqm` was added,
  `Schema.version` never moved off **16**, so on an existing device `migrate()`
  **never ran at all**. A device installed before those commits sat on a DB with no
  `membership` table, no `calendar_import` table and no `card.target_*` columns, with
  **no path to ever acquiring them**. Fresh installs were correct, which is why it
  went unseen. Fixed by `16.sqm` (three `ALTER`s + both `CREATE TABLE`s), which bumps
  the version to 17 and gives stranded devices the upgrade they never got.
  Deliberately **no `CLIENT_SCHEMA_VERSION` bump**: unlike `11.sqm`'s `triggers`,
  which needed a forced resync so the server could backfill a since-added field,
  both tables refill on their own (AuthEngine re-saves memberships after every auth
  resolve; `calendar_import` is transient local proposal state).
- **The same root cause had a second, wider instance — also fixed.** Turning the
  guard on revealed **ordinal-position drift** in `card`, `hub` and
  `content_response`: `card.media` (3.sqm), `hub.media` (3.sqm), `hub.timeline`
  (9.sqm) and `content_response.created_at` (15.sqm) were all `ALTER`-appended but
  written **mid-table** in `Content.sq`. SQLite's `ALTER TABLE` can only append, so
  the fresh and migrated schemas disagreed on column ORDER. All four moved to the end,
  matching the convention `importance` and `triggers` already documented in that file.
  **This instance was latent, not live** — order would only corrupt reads under
  positional mapping, and `Content.sq` has **zero `SELECT *`** and no column-list-free
  `INSERT`, so every query resolves by name. It mattered because it held the guard red,
  and is one `SELECT *` away from becoming real. The missing objects above are what
  actually broke upgraded devices.
- **The guard now runs.** `verifyCommonMainContentDbMigration` was enabled in
  `apps/client/build.gradle.kts` all along but wired to `:client:build`, which CI
  never invokes — so it had never once executed. It is now in the Compose job's
  gradle line (~9s). This is what makes the class of bug non-recurring.
- **`main` was RED 2026-08-20 → 2026-08-21; fixed by PR #385 (19th pass), merged
  as `6283a7d`, CI green on all six jobs on the PR head before merge.** Last
  green CI run on `main` before the fix was `37d12ace` (2026-08-10). The failing
  lane was **Client core + feed UI (Compose, headless)**: four golden snapshots —
  `hubCanonical()`, `hubEnriched()`, `hubCanonicalDark()`, `hubEnrichedDark()`.
  Cause was as this file predicted: the 2026-08-19 mobile review re-recorded the
  **macOS** Hub goldens after the ADR 0064 respond-⋮ affordance reflowed those
  screens, but the Linux counterparts were never recorded, and CI runs Linux.
  Measured drift: **21.9%** (canonical, both themes) and **12.4%** (enriched,
  both themes) against a 4% tolerance. The stale Linux PNGs showed the
  pre-ADR-0064 layout — contact actions inline on the title row rather than
  wrapped below, and an untruncated `…/immunization.pdf` URL.
  **Now recorded on Linux and committed.** Three separate defects had to be
  cleared first, all found this pass:
  1. **The golden dashboard artifact never uploaded — ever.**
     `apps/ui/.rk-snapshots` is a dot-directory and `actions/upload-artifact@v4`
     excludes hidden paths by default, so every red run logged `No files were
     found with the provided path` and produced a zero-artifact run. This is why
     this file's own prescribed fix ("grab the PNGs from a fresh run's
     artifact") had never been executable. Fixed with `include-hidden-files:
     true`.
  2. **The ~18-minute post-failure hang below destroyed the evidence**, because
     a job that hits its 25-minute cap *cancels* its remaining steps — including
     the two `if: failure()` dashboard/upload steps. Fixed by capping the Gradle
     step at 18 minutes, under the job's 25, so a hang fails the step and still
     leaves ~7 minutes to render (~70s) and upload the artifact.
  3. **Release Android had been failing since 2026-08-20 too** — a second red
     lane this file never recorded. Not the goldens: `gem install --no-document
     fastlane` in the Play publish step dies with `Gem::FilePermissionError:
     You don't have write permissions for the /var/lib/gems/3.2.0 directory`
     (root-owned system Ruby on the hosted image). The AAB builds fine; only the
     upload dies. Fixed by installing into a runner-writable `GEM_HOME` under
     `$RUNNER_TEMP`. **Operator note:** that step only runs because both
     `ANDROID_KEYSTORE_BASE64` and `PLAY_SERVICE_ACCOUNT_JSON` are set, so
     repairing it means merges to `main` resume uploading to the Play
     **internal** track (≤100 testers, no review) exactly as ADR 0034 intends —
     nothing about the trigger, track, or gating changed.
- **CI now names the failing test on the run page.** The `if: failure()`
  diagnostics above covered golden-image mismatches only; for any other test
  failure a red run showed just `Execution failed for task ':client:desktopTest'`,
  and finding *which* of ~1000 cases failed meant downloading the raw log (~160 KB
  for #390). `scripts/ci-test-failures.sh` reads the JUnit XML Gradle already
  writes and posts the failing class, test name, kind and message to
  `$GITHUB_STEP_SUMMARY`; the XML + HTML reports upload alongside for full stack
  traces. Wired into both the Compose job and the CLI job. It is diagnostic only
  — it always exits 0, so it can never mask or become the failure — and it
  distinguishes three cases that otherwise look alike: real failures, a build
  that died before any test ran (no XML), and a suite killed mid-write by the
  step timeout or OOM (truncated XML, which would otherwise read as "0 failed").
  Verified end to end by breaking a CLI test on purpose: Gradle printed only
  "There were failing tests", the script named
  `AuthRetryTest > get refreshes once on 401 and retries with the new token`.
- **A `check_suite.completed / success` webhook on this repo is USUALLY not CI —
  don't merge on it.** The repo runs gitleaks as its own workflow, so every push
  produces two check suites. The gitleaks one finishes in **5–9 seconds** and fires a
  `conclusion: success` event while the real CI suite still has five jobs running;
  three times in the 2026-08-22 pass that event arrived seconds after a push and, taken
  at face value, would have merged an unverified PR. The suite id differs each time, so
  it can't be filtered by id either. Always resolve the PR's state per job (check runs),
  never from the suite conclusion — the event's own note says as much, and it is right.

- **The ~18-minute Gradle hang is NOT "fails to tear down after a failed task" —
  that hypothesis is disproved.** `ci.yml`'s own comment (and the entry below) read
  the hang as Gradle continuing to run *after* `:ui:desktopTest FAILED`, blaming
  `--no-daemon` teardown. Run **32552296539** (PR #391) refutes it: the step hit the
  18-minute cap with **nothing failed** — `:client:desktopTest` and `:ui:desktopTest`
  were both still executing, 668 tests reported, zero failures. So it hangs during a
  perfectly clean run and a failed task is not the trigger. The comment in `ci.yml`
  is corrected. Still undiagnosed, and still non-reproducing locally (the same suites
  finish in ~7 minutes on the sandbox), which keeps pointing at something
  runner-specific rather than something in the build.
  Two things this run did confirm working: the 18-minute **step** cap did its job —
  the job survived with ~7 minutes to spare and both `if: failure()` artifacts were
  produced — and the snapshot dashboard uploaded properly (356 files, 27 MB).
  Note the dashboard's "13 mismatched" on such a run is NOT golden drift: the
  dashboard re-renders all 204 scenes, a wider set than the gate, so mismatches there
  are expected output and are not what failed the job.
  **It also hangs on a DOCS-ONLY commit, which rules out any code cause.** `712c252`
  changed exactly one thing — a bullet in this file — and its Compose job on `main`
  (run 32554377580, attempt 1) ran the gradle step 05:27:19→05:45:31 = **18m12s**,
  hit the cap, with no test failure. A markdown-only diff cannot hang a Kotlin test
  run for a reason in the code, so whatever this is lives in the runner or the
  toolchain, not in the build. The re-run (attempt 2) of the identical commit passed
  in ~6 minutes.
  Observed rate on 2026-08-22 — **2 of 6** Compose runs, both pure timeouts with zero
  test failures:

  | run | commit | outcome |
  |---|---|---|
  | CI #908 (main) | `563e294` | passed ~10 min |
  | PR #391 | `9d494a5` | **timed out at 18 min** |
  | PR #391 | `3e2a17a` | passed ~6 min |
  | CI #912 (main) | `4b62503` | passed |
  | PR #392 | `12a8719` | passed ~6.4 min |
  | CI #914 (main) | `712c252` (docs-only) | **timed out at 18 min**, re-run passed ~6 min |

  A passing run is ~6–10 minutes, so a hung one is not "slow" — it is stuck, at
  roughly triple the normal time, and the 18-minute cap is what converts it from a
  cancelled job with no diagnostics into a failed step that still uploads them.
  Leads for whoever picks this up: it is always the `:client:desktopTest`/
  `:ui:desktopTest` gradle step; the runner reports orphaned `java` processes at
  cleanup on both hung and healthy runs; and it has never reproduced on the sandbox
  (~7 min there). Note a re-run CLEARS the previous attempt's logs, so capture
  anything needed from a hung attempt BEFORE re-running it.

- **The API lane is one token away from being locally verifiable — the recorded
  "no npm registry access at all" no longer holds.** The entry below (2026-08-21)
  records that org policy 403s *both* `registry.npmjs.org` and `npm.pkg.github.com`,
  so `vitest`/`tsc` could not run and CI was the only oracle. Re-tested 2026-08-22:
  `npm ci` resolves the public registry fine and fails at exactly one point —
  `401 Unauthorized … @sloopworks/swip-sentry` from `npm.pkg.github.com`. That is a
  **missing credential, not a policy block**: `.npmrc` expands `NODE_AUTH_TOKEN`,
  which is unset here. Postgres is not a blocker either — Postgres 16 is installed,
  and a cluster for `fad_test` starts fine (`initdb`/`pg_ctl` as the `postgres` user,
  data dir directly under `/tmp` so the daemon can traverse it).
  So a session with a `read:packages` token can run the API tests, `tsc`, AND
  `npm run build:fn` locally — which is precisely what the parked archive-route
  guard in `backlog/next.md` is waiting on. Worth confirming before the next pass
  assumes the API lane is verify-by-CI only.

- **A Linux golden re-record IS possible from a cloud sandbox — the "7 GB
  container" wall was environmental, not fundamental.** Prior sessions recorded
  this as blocked ("docker OOMs at 7.65GB compiling `:ui`", "twice lost its
  Gradle daemon"). On a 15 GB / 4-CPU Linux sandbox the full `:ui:desktopTest`
  compiles and runs in **~7 minutes**. The setup that works, for the next
  session that needs it:
  - `apt-get update && apt-get install -y openjdk-17-jdk-headless` — the image
    ships JDK 21 only, and every module pins `jvmToolchain(17)`. Run
    `apt-get update` first; a stale index 404s on the current point release.
  - Android SDK is required even for a desktop-only task, because `:client` and
    `:ui` both apply `com.android.library`. Install `commandlinetools-linux`,
    then `sdkmanager "platforms;android-37.0" "build-tools;37.0.0"` — note
    **`android-37.0`, not `android-37`**, which does not exist as a package —
    and write `sdk.dir` to `apps/local.properties` (gitignored).
  - `third_party/debugdrawer` is a private submodule; attach `SloopWorks/debugdrawer`
    to the session, clone it, and check out the pinned commit.
  - The private SWIP GitHub Packages token is **not** needed for
    `:ui:desktopTest` — only `:swip-wiring` and `:androidApp` debug consume
    those artifacts. `:swip-wiring:desktopTest` therefore **cannot** run in a
    sandbox without that token (it 401s at dependency resolution); leave that
    module to CI.
  - **Set `LANG=C.UTF-8 LC_ALL=C.UTF-8`.** The image defaults to `LC_CTYPE=POSIX`,
    which makes `sun.jnu.encoding` ASCII — and `:client:compileTestKotlinDesktop`
    then dies with `InvalidPathException: Malformed input or input contains
    unmappable characters` because `AuthClientTest` has a backticked test name
    containing `→` (U+2192) that becomes a `.class` filename. Nothing to do with
    the code; it will look like an internal compiler error if you don't know.
  - **`apps/cli` also builds here** — its own wrapper, Gradle 9.5.1, and
    `./gradlew test` is **153 tests green in ~70 s**. Prior passes recorded "no
    working path to a Kotlin compile check" for the CLI and fell back to
    verify-by-PR-CI; that constraint is lifted on a sandbox set up as above,
    which unblocks the queued `apps/cli` `main()` extraction + auth-layer tests.
  - **Font risk is real but was measured and is nil.** This sandbox has 59 fonts
    vs. the CI runner's much larger set, so a locally-recorded golden could in
    principle drift from CI's. Two checks settled it. Weak check: the **other
    133 Linux goldens passed unchanged** in the same run. Strong check: once the
    artifact fix landed, CI's own renders of these four scenes were downloaded
    and diffed against the locally-recorded PNGs — **0.0000% differing pixels,
    identical 822×1782 dimensions, all four**. Bit-for-bit agreement between
    this sandbox's FreeType and the runner's. Redo the strong check before
    trusting any future local record; it costs one artifact download.
- **Unexplained, watch it:** on the CI runs since `46a652db`, the Compose job
  **hung for ~18 minutes after** `:ui:desktopTest FAILED` and was killed by the
  25-minute cap, where earlier runs failed fast in ~6m35s. Same test failures
  either way — only the post-failure behaviour differs. Still no mechanism
  identified, and **it did not reproduce locally** (`BUILD FAILED in 6m 59s`,
  clean exit, same 4 failures, same `--no-daemon` invocation) — which argues for
  something runner-specific rather than the Gradle invocation itself. The
  step-level timeout above bounds the damage but does **not** diagnose it. If
  you pick this up, that local-vs-CI asymmetry is the lead.
- **Latent test-order hazard observed while verifying (intermittent; not a
  `main` break, not fixed).** On ONE full `:ui:desktopTest` run in this sandbox,
  a test outside the golden set failed: `AuthFlowUiTest.owner_opensInviteAndMintsQr`
  with `IllegalStateException: The singleton image loader has already been
  created … 'setSafe' is being called after the first 'get' call`. A clean
  `--rerun-tasks` run of the same suite immediately after was **649 tests, 0
  failures** — so it is **intermittent, not deterministic**, and it passes on CI
  (CI's own run is 649 tests / 4 failed, the 4 being exactly the goldens).
  Expected-output PNGs cannot influence it. Mechanism: `setupImageLoader()` (`apps/ui/.../CoilSetup.kt`) is
  invoked from a `remember { }` inside `FeedApp`, but `SingletonImageLoader.setSafe`
  throws if any Coil `get()` already happened in that JVM. So whichever test first
  triggers an image load *without* going through `FeedApp` poisons every later
  `FeedApp`-composing test in the same worker. Today that ordering happens to be
  benign on the runner. It is one test-class rename away from going red on CI for
  reasons that will look unrelated to whatever change triggers it.
- **Quarterly:** re-check whether Google ships a *free, family-shared*
  Gemini Daily Brief variant (KS-6 / OQ-gemini-family). First check ~2026-09.
- **Quarterly:** re-check whether **Gemini Nano 4 has shipped structured output
  + tool calling** (OQ-ondevice-k2). That is the revisit trigger for an
  on-device "K2" key-holder agent — assessed 2026-07-13 as **NO-GO today**
  (capability-blocked: 4k-in/256-out, no JSON schema, no tools, foreground-only,
  per-device output variance breaks the shared-briefing wedge). First check
  ~2026-10. → `research/2026-07-13-on-device-llm-assessment.md`.
- **P0 viability review is OVERDUE** (was due 2026-07-18; the planning loop
  has logged only its bootstrap iteration, `processes/loop-journal.md`
  Iteration 0) — full narrative + proposed default in
  `backlog/operator-inbox.md` **INB-33**, open, awaiting the operator.

## Current state (as of 2026-08-24)

### Shipped — Timeline jump to now (2026-08-24)

Hub timelines now expose a compact trailing **Now** affordance once the live
time/date marker is fully outside the visible list. Tapping it returns to the
marker with an animated scroll (or an immediate jump under reduced motion), and
the control disappears as soon as the marker is visible again. The implementation
is shared across day and roadmap timelines, includes bottom-content clearance and
accessibility semantics, and is covered by visibility-boundary tests plus light and
dark interaction snapshots. Design rationale and review record:
`docs/superpowers/specs/2026-08-24-timeline-jump-to-now-design.md`.

### Active — Smart Briefings V0.1 Claude Bridge, Phases A–C (2026-08-21)

Work Package 0's spike is built under `spikes/claude-mcp-v0.1/` — a synthetic
OAuth authorization server, a stateless Streamable HTTP MCP surface with two
spike tools, content-blind diagnostics, and a leak-canary suite. **171 tests
pass** (`npm test` there). Zero production Dayfold code changed. Merged as PRs
#381–#384 and #386.

**Phase B ran on 2026-08-21** — the earlier "never been run against Claude"
note is superseded. An operator-driven session against a live Claude **Max**
account (claude.ai web / Chrome / personal), reached through an ephemeral
Cloudflare tunnel the operator created, ran, and terminated. **3 of 10 matrix
questions are answered** (1, 3, 8); the rest are `UNKNOWN` on coverage. Evidence:
`research/2026-08-20-smart-briefings-v0.1-compatibility-spike.md`.

**Still synthetic.** No mailbox was ever connected to a Dayfold surface, **no
Gmail tool ever executed**, and no private data was processed. Three narrow
read-only uses of the operator's own Gmail connector are recorded there (a
tool-name listing, a filter-tool existence probe, and a confirmation-mechanism
probe whose every dialog was **denied** — nothing sent).

What the run produced, beyond coverage:

- **A production-blocking CSP defect, found and fixed.** `form-action 'self'`
  on the approval page silently stops the authorization code reaching the
  client in Chrome/Safari — server logs read `oauth.approve / ok` then nothing,
  and `curl` cannot detect it because it does not enforce CSP. WP4 inherits the
  carve-out requirement (`system-design.md` §10).
- **Two facts that simplify WP4:** Claude sends the RFC 8707 `resource`
  indicator (exact binding is enforceable; the spike's deliberate leniency
  closes), and **DCR is not required** — the client is metadata-driven, so the
  registration route need not exist.
- **§9 condition 1 is false by default, not unsatisfiable** — corrected
  2026-08-21 (PR #386, `F-PERMS`). It was first recorded as dead; that measured
  the connector's *default* configuration, not its capabilities. `Settings ›
  Connectors` exposes per-tool **allow/ask/block** with the 22 write/delete
  tools grouped and defaulted to "Needs approval". Whether **block** removes a
  tool from the run's surface or refuses at call time is **unmeasured**, and
  §9's wording ("tools *exposed to the run*") is sensitive to the difference.

Phase C step 1 (reconcile recorded provider facts into ADR 0071 / system design
/ plan) is complete, along with the security review plan §5.1 makes mandatory
when the Gmail-write gate changes. **No gate is passed**: ADR 0071 stays
**Proposed**, the design stays no-ship, and §9's normative conditions are
unchanged.

**Repeated lesson, now three revisions deep:** four provider surfaces have been
consulted about the Gmail tool inventory — directory catalog, runtime tool
manifest, settings UI, and documentation — and **three of the four disagreed
with at least one other**. Anthropic's own Workspace-connector doc under-states
the runtime (it describes per-tool control as Team/Enterprise-only), and that
error runs in the **unsafe** direction: a reader trusting it designs around a
constraint that does not exist. The durable rule recorded in `F-INVENTORY`:
**when surfaces disagree, probe — do not adjudicate.**

### Active — mobile app review / Hub completion parity (2026-08-19)

The cross-platform review is implemented in an isolated branch: Hub blocks expose
**Mark done**, completion has a durable byline/date/note presentation, the root response
overlay and Back/dismiss behavior are consistent, compact/IME/accessibility layouts are
corrected, and rich Hub content/date/contact fallbacks are portable across Android/iOS.
The review also hardened response tenancy/ACL/idempotency/convergence, notification
suppression and cleanup, collision-proof Android tap/alarm identities, serialized geofence
replacement, retained/serialized iOS region passes, topology-safe completion, causal response
Undo ordering, and exact-alarm denial fallback/guidance. The full flow was exercised through
durable **Done by You** on Android and iOS simulators; macOS Hub goldens were recorded and
reviewed. API, client, UI, SWIP, Android build/connected platform tests, iOS device/simulator
frameworks, and the iOS host build are green. Linux Hub goldens remain pending because the
prescribed 7 GB container twice lost its Gradle daemon during compilation.

### Calendar Check epic (CAL, ADR 0063 — Proposed, not yet Accepted) — 2026-08-09

Client-owned Calendar↔Dayfold reconciliation (WI-446 through WI-451) is built
and merged to `main`: device-local reconcile + gap review, Android/iOS native
add-event handoff, a reviewed Calendar→Dayfold import wizard, and
Calendar-owned start-alert suppression. WI-451 (CAL-11) closed the epic's
privacy proof — permanent guard tests across `:client`/`:swip-wiring`
desktopTest asserting raw calendar identifiers/fingerprints/observations
never reach sync/outbox payloads, logs, analytics, or the SWIP debug
inspector (ADR 0063 acceptance gate 6). **Full epic detail, remaining
operator gates (mockup sign-off record, horizon-constant ratification,
permission-copy device review, store data-safety disclosures, ADR
acceptance reviews), and the on-device smoke checklist live in the shipyard
epic — this is a pointer, not the record.**

### Active — TASK-CLIENT-RUNTIME-HARDENING (started 2026-07-14)

PR 1 plus the runtime/session, engine-hardening, immutable-command,
platform-lifecycle, stable-Compose-boundary, and route-level render-isolation
work through the bounded portion of Task 14 are implemented and locally
verified, except for the plan's explicitly unchecked PR 2
collector-extraction/race-test items. Production Redux notifications use serial
UI-thread contexts; `ContentStore` owns process-safe writer/snapshot
serialization; auth/family epochs fence stale commits; sync requests conflate;
Hub work is generation-correlated; Auth uses narrow request gates; and Now uses
one ordered actor plus consistent multi-table snapshots. Production hosts retain
one runtime graph and expose stable selection/dispatch, method-only command, and
platform-handoff capabilities to Compose. The duplicate command wrapper has
since been replaced by the compiler-stable `DayfoldCommandPort`; `SelectorStore`
is limited in use to selection and dispatch. The root whole-state subscription
and callback wall are gone; active
routes subscribe to immutable feature projections, while per-entity row
subscriptions remain explicitly open. Cold mobile notification targets now wait
for family restoration and are dropped at identity/tenant boundaries. Adversarial
verification also found and fixed production 401 self-join and ContentBridge
lock-order deadlocks. The serialized gate is green across 666 client, 513 UI,
and 16 SWIP desktop tests; 7 client and 3 UI iOS simulator tests; Android
debug/release; iOS device/simulator framework linking; and 12 Android API-35
connected tests. ADR 0058 is **Accepted** (operator accepted in-session
2026-07-14). Task 14 per-row isolation, Task 15 state-keyed route effects, PR 5
state/reducer slicing, and PR 6 notification/performance/platform closure remain
staged in
`docs/superpowers/plans/2026-07-14-dayfold-runtime-concurrency-render-isolation.md`.

**Stage: M0 render prototype BUILT + cloud-live** — server (TS/Hono/Postgres
on Vercel+Neon) · Kotlin CLI · KMP client (`apps/client` core + `apps/ui`
Compose, ADR 0047) · Android (dogfood, real device) + iOS (sim-verified) —
full CLI→API→DB→sync→render loop works end-to-end in prod. Validation
verdict still stands: **CONDITIONAL — learning-lab GO, business NO-GO**
(commoditized by Gemini Daily Brief/Alexa+; the defensible surface is a
**multi-member family-tenant briefing**) → **building to learn**; the
business unknowns (OQ-wtp / niche / gemini) stay untouched by design.

**Shipped and live on `main`:** full AUTH epic (device-grant login, Google
sign-in, roster/devices/account) · owner invite-mint UI — QR + share-link,
outstanding/revoke, cross-platform QR via qrose · Hub & card visual enrichment (ADR 0036) ·
Now-derived surfacing Phase A+B — priority-ranked feed + Android background
geofence/exact-alarm local notifications (ADR 0043/0044) · iOS notification
parity (sim-verified) · two-way member writes — checklist toggle, delete,
local hide (ADR 0038–0042) · Hub Timeline, authored + on-device-derived
fallback (ADR 0045/0046) · CL-SNAP headless golden-snapshot CI gate (131
goldens) · `:client`/`:ui` module split (ADR 0047, faster agent inner loop) ·
card↔detail container-transform morph fix (plain `AnimatedContent`,
predictive-back commit-animated, ADR 0050, #307) ·
navigation transition system — every nav edge animates by a central,
future-proof taxonomy (tab/push/modal/wizard/gate/hero), reduced-motion aware
(ADR 0051, #308) ·
Now-feed + Hubs-list scroll preservation across tab switches and card/hub
detail (#309/#312/#313) · card→detail shared-element morph — accent tile,
kicker, title, and primary button travel into the detail, content-equality-
gated so it's correct across all card types (#310) ·
**DB-first cold-start route gate — reopening Dayfold after the OS reclaims it
now paints from the on-device cache instead of the logo+spinner while
waiting on a network `whoami`; session reconciles in the background (ADR
0052, #314, 2026-07-09)** · **timeline detail no longer draws under the
status bar (#315, 2026-07-09)** · **SWIP product analytics wired — debug-only,
PostHog EU, count-only 8-event slice, geoip-off, never-identify (ADR 0055,
consuming swip-core/schema-dayfold 0.1.2, 2026-07-11)**.
Deferred by design: G1 content-authoring "brains" loop (interim authoring =
operator + Claude Code via the CLI/curator skill); E2EE (ADR 0017); web
target (`wasmJs`, needs a client DB async migration first).

**Shipped since (2026-07-10 → 2026-07-12, not yet folded into the "Shipped
and live" paragraph above):** account avatars + hub **People** management +
per-hub **Viewer/Contributor/Co-owner** roles (ADR 0053, #ae38c3f/#ccd38d6) ·
editable display name · scoped CLI/device tokens — per-hub grants on the
approval screen (ADR 0029 extension, 2026-07-11) · leveled/scrubbed on-device
logging (`Log` front-door, SWIP `swip-logging` bound debug-only, ADR 0056) ·
a SWIP debug-drawer **inspector panel** — live analytics timeline,
mask-by-default with `FLAG_SECURE` reveal isolation, debug-only, zero release
footprint (ADR 0057) · a 3-bug analytics-delivery fix (missing PostHog key +
missing consent grant + two SWIP SDK bugs — dogfood analytics from the
2026-07-11 slice were silently never arriving; now confirmed reaching
PostHog) · analytics events now flush on backgrounding and persist to an
on-device durable queue (SQLite/WAL) instead of being lost on a process kill.
**Mandatory on-device smoke test for the inspector panel (`FLAG_SECURE`
screenshot blanking, chrome insets) is still pending** (operator, physical
device) — the only item from this window not yet operator-verified.

**2026-07-28 repo-maintenance pass (18th)** — scheduled, same six-point scope,
but deeper than usual: three dedicated Explore agents (one per app/dimension)
did a from-scratch audit of `apps/api`, `apps/cli`, and docs/skills instead of
the usual quick pass, since it had been 4 days (17th pass, 2026-07-24) with no
new commits to re-check. This surfaced real findings 11-17 prior passes'
lighter touch had missed:

1. **Dedup/simplification — real findings in both apps this time** (unusual;
   the 17th pass found `apps/api`/`apps/cli` "clean, 11 prior passes already
   worked that queue"):
   - **`apps/api` test-suite migration drift (highest-impact finding of this
     pass).** 32 of ~35 DB-backed Vitest suites each hardcoded their own
     subset of `migrations/*.sql` filenames in `beforeAll` — inconsistent
     across files, most skipping newer migrations (0010/0011/0019 etc.).
     `test/migrations.test.ts`'s own header comment already named this as
     the exact failure class behind a real prod outage (`briefing_cards`
     missing columns no suite's hardcoded list ever applied — fixed
     reactively by `0014_card_columns_repair.sql`). Added
     `apps/api/test/_migrations.ts` (`applyAllMigrations(q)` — applies every
     file in `migrations/`, sorted, matching what `migrations.test.ts`
     already proved safe) and converted all 32 suites to call it instead of
     hand-listing files; preserved each suite's own post-migration seed SQL
     untouched. Hand-verified the full migration chain applies cleanly via a
     local Postgres 16 cluster (`pg_ctlcluster`) + `psql` before rolling
     this out — this sandbox has psql/docker but **no npm registry access at
     all** (org policy 403s both `registry.npmjs.org` and
     `npm.pkg.github.com`, confirmed via the proxy's own status endpoint),
     so `vitest`/`tsc` could not run; left to CI as the verification oracle
     (same posture prior passes used for Kotlin).
   - **`apps/api/src/app.ts`: 35 of 45 dynamic `await import(...)` sites had
     no reason to be lazy.** The file's own comment said auth imports were
     dynamic so `api.test.ts` (no `AUTH_*` env) could load `app.ts` without
     tripping a module-scope throw — but only `tokens.ts` actually throws at
     module scope; `audit.ts`/`ratelimit.ts`/`refresh.ts`/`identity.ts`/
     `invites.ts`/`device.ts`/`origin.ts`/`sweep.ts`'s `sweep` export do not.
     Hoisted all of those to static top-of-file imports (aliasing the two
     colliding `redeem` exports from `device.ts`/`invites.ts` as
     `redeemDevice`/`redeemInvite`); left only `tokens.ts` dynamic, with an
     accurate comment. Net -27 lines in `app.ts`.
   - **Three call sites re-implemented `requireCred()`'s bearer→JWT→live-
     credential prologue inline** instead of calling the already-extracted
     helper — `/auth/signout` and `POST /families` now use `requireCred(c)`
     (closes a real gap: `POST /families` previously never checked whether
     the backing credential was revoked, unlike every other mutating route);
     `/auth/whoami` left alone as the report recommended (it needs an extra
     column from the same query, restructuring would add a second round-
     trip for no benefit). Also extracted `isDeployedEnv()` for 3
     independently-written copies of the same prod/preview gate check
     (debug routes, dev-token, Firebase-emulator bypass).
   - **Two stray unit tests lived in `apps/api/src/` instead of `test/`**
     (`content.timeline.test.ts`, `content-validation.timeline.test.ts`) —
     moved to `test/hub-timeline-schema.test.ts` /
     `test/hub-timeline-validation.test.ts`, import paths updated to match
     every other file in `test/`.
   - **`apps/cli/Main.kt`: the same `Credentials()` + `resolveKeychain()` +
     `loadCreds()` prologue was copy-pasted 4x** (whoami/pull/delete/push) —
     extracted a `Session`/`loadSession()` pair; all four call sites now use
     it. `push`'s two branches (device-creds vs. legacy-env) also had
     identical duplicated `println("push ...")` + error-exit blocks —
     collapsed to one shared block after an `if/else` that only computes
     `(code, body)`. Four CLI test files had a stray-location/missing-
     package inconsistency (`LinkifyTest.kt`/`LinkifyPayloadTest.kt` had no
     `package` declaration at all; those two plus `QrTest.kt`/
     `UpdateVersionTest.kt` lived directly under `src/test/kotlin/` instead
     of the `com/sloopworks/dayfold/cli/` package dir every other CLI test
     uses) — moved all four, added the missing package line to the two that
     lacked one. **This sandbox still has no working path to a Kotlin
     compile check** (JDK 21 only, and this time even the Gradle wrapper's
     own distribution download 403s through the proxy) — same
     verify-by-PR-CI posture as the 14th/17th passes' Kotlin work, applied
     here after careful line-by-line inspection of every call site.
   - **The untested auth-orchestration layer is now covered (19th pass).** The
     401-retry / legacy-vs-device branch selection is no longer inlined in
     `main()` — it lives in `authedGet`/`authedDelete`/`authedPut`, and the only
     thing keeping it untestable was that those were `private` and called the
     transport directly. They now take an injected transport + refresh
     (defaulting to the real ones), and `AuthRetryTest` covers the rule: refresh
     once and retry with the NEW token on 401; never refresh on the legacy
     `HOUSEHOLD_SECRET` path (no refresh token exists there); never refresh on
     403/404/409/500; retry at most once so a persistently-401ing server can't
     loop on `/auth/refresh`; and replay the identical body on a PUT retry. Both
     the guard removal and a dropped-body retry were confirmed to fail the suite
     (mutation-checked). 153 → 164 CLI tests. This did NOT need the `main()`
     extraction the 17th/18th passes assumed was a prerequisite.
   - `apps/cli`'s `main()`-as-one-220-line-`when`-block (still worth extracting
     into named `cmd*()` functions for readability, but no longer blocking any
     test), and `apps/api`'s dead `places` table (created by 0001, referenced by
     the generated sync schema, never read/written by any route or test) are
     real but larger findings **left for a future pass** — noted here so they
     aren't rediscovered from scratch. `places` needs an explicit
     build-it-or-drop-it call, not a silent removal.
2. **Agentic-docs accuracy** — two small findings, fixed: `docs/architecture.md`'s
   hardcoded "as of (2026-07-18)" header had gone stale again (the Deploy
   section was edited 2026-07-23 by the 16th pass without bumping the date —
   the exact drift class a prior pass already hit once) — this time removed
   the hardcoded date entirely rather than just re-bumping it, so it can't
   drift the same way a third time; pointed readers at `git log` instead.
   `specs/prototype/07-cli.md` (an early M1 design spec) still described a
   materially different, unbuilt CLI — wrong env var name (`FAMILYAI_TOKEN`
   vs. the real `DAYFOLD_API`/`FAMILY_ID`/`HOUSEHOLD_SECRET`), an X25519/E2EE
   device-login key bootstrap that was never built (E2EE is deferred, ADR
   0017), and a declarative git-backed directory-authoring model with no
   shipped counterpart — added a superseded-status banner pointing to the
   real sources of truth (`dayfold help`, the new `apps/cli/README.md`,
   `templates/README.md`, the curator skill) instead of rewriting/deleting a
   historical design doc. `AGENTS.md`, `CLAUDE.md`, and every `processes/*.md`
   file re-audited clean (no stale facts found) — `processes/deploy-m0.md`
   is archived-but-still-referenced-live (an open operator action item in
   this file's own "pending" section links its §2 for Vercel env setup), so
   it was deliberately NOT relocated despite being noise for a cold agent
   scanning `processes/` — moving it would break that live link for a
   one-pass tidiness win.
3. **CLI --help / skill-doc completeness** — verified clean, line-by-line
   against the live `Help.kt` command registry, the generated content
   schema, and the server-side icon allowlist: every command, flag, enum
   value, and scope-gating rule in `.claude/skills/dayfold-curator/` matches
   the shipped CLI exactly. Added `apps/cli/README.md` (the module had no
   entry point doc — `templates/README.md` existed one level down but
   nothing linked a cold reader to `dayfold help`/examples/the skill from
   `apps/cli/` itself).
4. **README/CHANGELOG/architecture** — all current, no edits needed beyond
   the architecture.md date fix above. Screenshots exist and are correctly
   referenced (`apps/ui/.../snapshots/linux/*.png`, CI-golden-verified); no
   CHANGELOG gap (last entry 2026-07-16 is accurate — every commit since is
   either an internal client-runtime refactor or a maintenance pass, neither
   of which the established convention gives an entry).
5. **CI** — no live break found. Best-evidence check only (no `gh`/GitHub-API
   access from this sandbox): `backlog/now.md`'s own time-sensitive section
   already recorded `main` green as of `715a486` (17th pass), no newer
   red-CI note exists, no unresolved CI-fix commits in `git log`, and all 7
   `.github/workflows/*.yml` files' referenced paths/scripts still resolve
   to real files — cross-checked, none stale.
6. **Values/privacy spot-check** — clean. Diff-scanned for secret-shaped
   strings (API-key/PEM-key patterns) — none found. No PII added, no new
   data collection, no dark patterns; the one behavior change with security
   surface (`POST /families` now checks credential revocation) tightens
   toward the existing pattern every other mutating route already follows,
   doesn't loosen anything.

No CHANGELOG entry — every change this pass is internal (test-suite
correctness hardening, dedup, doc accuracy); no product/API surface, request/
response shape, or feature changed. `backlog/now.md` self-pruned per its own
policy: moved the 17th-pass paragraph to `now-history.md`.

## Design-first gate (ADR 0008) — status

The **feed-only** M0 slice was built **build-first** (operator-directed) from the
initial Now mockups in `designs/`. ADR 0008 **still governs unbuilt surfaces**,
but the trigger surface's design gate is now cleared too: the v2 hi-fi mockups
(trigger v2 = INB-13, the §6b honesty rework) were signed off and **shipped** in
the Phase-B surfaces on `main` (PR #260, 2026-07-01 — see INB-13 CLOSED in
`backlog/operator-inbox-history.md`); this line was stale (still described the
M1 trigger surface as needing its mockups) until this pass corrected it.
**Event Hubs render: design gate CLEARED (INB-22, 2026-06-24)** — the
Hubs phone surface (INB-15/16) + content adaptive two-pane (INB-20) + the ADR-0030
visibility delta (`Hubs-Visibility.dc.html`, signed off) are all in; the content-
API enforcement is built (PRs #34/#35). Hub render is build-ready.
**Responses to smart content: BUILT (2026-08-08, ADR 0064 Accepted)** — designs imported
from `designs/content-feedback/`, ADR 0064 accepted in-session (INB-37), and the build
landed end to end: `subject_ref` persisted as the suppression key; `content_responses`
rows (mute + family Done) with server-side suppression by ID only; `/sync` delivery on
the existing cursor with per-member visibility and a matching tombstone sweep; the client
rule engine, outbox lane, and `ResponseEngine`; the response sheet, scope step, swipe
escalation, and Settings › Smart content; on-device enforcement on the derived lane **and**
the notification path; the CLI pre-flight filter. Verified green: API 461, `:client` 790+,
`:ui` 600+, CLI, iOS simulator + framework link, Android `assembleDebug`.
Deliberately NOT built, and not silently dropped: the **fix-it/corrections** channel
(Tier 2 — deferred to its own ADR, blocked on ADR 0062's run receipt) and the design's
**"saw N marked done"** run-receipt row (omitted rather than shown with a fabricated
count). Golden gates for the new scenes are macOS-only — docker OOMs at 7.65GB compiling
`:ui` here, the same wall the hub-people and authorize-* scenes hit; re-add the gates once
a linux golden is recorded in CI. Open follow-ups are in `context/open-questions.md`
(`OQ-response-subjectref-stability` is the one that can silently break Done).

**Mobile follow-up (2026-08-19): Hub Done reachability + cross-platform audit.**
The missing Hub entry point is now closed: a block's overflow opens the direct
Done/note step, exact subject refs suppress only that live block, and the Hub
retains an attributed, timestamped completion card (including honest pending
copy offline). Response overlays now live at the app root, compact/IME/large-
text behavior and selection semantics are explicit, and Android Back follows
platform modal convention while the explicit sheet Back control follows the
visible step hierarchy. The same audit repaired two iOS-host regressions:
`:ui` now re-exports the client bridge Swift calls, and simulator Dev fake
sign-in uses the in-process busy-family backend. The API now visibility-gates
concrete response subjects with uniform 404s; response reads/sync obey Hub
audience changes, response ids cannot be stolen, and one atomic Done record is
allowed per live subject. Rejected optimistic writes roll back instead of
leaving a ghost completion. Done and competing content writes are serialized on
one pinned transaction with a transaction-scoped lock (also validated with the
serverless one-connection pool); replay keys remain bound to their authorized
response, stale section moves cannot redirect a block write, a losing device
atomically adopts the canonical Done row without cursor dependence (and remains
an anonymous, timestamp-free, deduplicated, retry-capped suppressor against an older API);
schema/stale-cursor cache healing preserves pending response rows beside their queued writes; rejected
response removal restores its rollback snapshot after cursor advance, while a successful DELETE removes
any row rehydrated by a racing full sync; Done deletion remains durable,
and Hub topology changes emit the right response tombstones. Done responses also
suppress stale cached content in background/exact notification planning and
cancel delivered or pre-scheduled reminders on both platforms; authored-card
changes re-plan schedules, iOS removes stale/disabled requests without applying today's
exhausted cap to tomorrow, and identity/family teardown clears platform notifications,
exact alarms, and geofences (including Android group summaries and an outstanding iOS nearest-region
lookup). Android boot/package replacement now restores both geofences and exact alarms, and iOS debug
notification callbacks share the foreground fake cache. The visual audit also fixed
compact contact actions, raw due/milestone timestamps, blocked-image labels, and
Markdown preview leakage. Verification: API 480 passed (3 skipped),
full `:client:desktopTest` + `:ui:desktopTest` passed, Android debug built and
the Hub completion path was exercised on the API-37 foldable emulator through
the durable **Done by You** state. iOS device/simulator client compilation and
the simulator host build/install/launch are green; the full overflow → note →
completion → durable Done path was exercised in the iOS simulator as well. macOS Hub goldens were
re-recorded and reviewed; the prescribed 7 GB Linux container again lost its
Gradle daemon during compilation, so those counterparts remain pending.

## Operator actions pending

- [x] **ADR 0032 §5 full-history secret scan — RUN 2026-08-25, CLEAN. No secrets, nothing to rotate.**
  Both scanners the gate names, over the complete history (the clone was
  `--unshallow`ed first; 1353 commits reachable, 280 remote branches, 1 tag):
  - **gitleaks 8.30.1** (the version CI pins), `--log-opts="--all"`, all refs:
    **1 finding, false positive.** `specs/smart-briefings-v0.1/system-design.md:428`
    — the `generic-api-key` rule fires on a comma-separated token run inside a
    markdown table documenting the `oauth_clients` schema (ADR 0071). The "secret"
    is the next English word in the sentence. Entropy 3.62, consistent with text.
    Now suppressed by fingerprint in `.gitleaksignore`, with the reasoning recorded
    there. *(This bullet deliberately does not quote the matching phrase: the first
    draft did, and the PR-range `secret-scan.yml` check then flagged **this file** —
    documenting the false positive reproduced it. If you re-add the literal, add a
    second ignore entry or expect a red check.)*
  - **trufflehog 3.90.8**, full git history, **`--no-verification`** (deliberately: live
    verification makes outbound calls to third-party services to test candidate
    credentials, which is an external action and was not authorised for this run):
    **0 verified, 4 unverified — all one value**, `AIzaSyStub…Stub00`, the deliberate
    self-describing stub committed into `ci.yml` + `release-android.yml` so the Google
    Services Gradle plugin is satisfied on a compile-only smoke build. It spells "Stub"
    eight times. Both files document it as a stub inline.
  **Coverage:** gitleaks scanned 1212 commits. That reconciles as 1353 reachable − 131
  merges (skipped by design; their content appears in the parents) − 4 empty = 1218
  with content, so 1212/1218 ≈ 99.5%. The ~6-commit remainder is most plausibly
  binary-only (PNG snapshot) commits, which carry no scannable text — unconfirmed.
  **Gate status.** The "scan full history → rotate anything exposed" half is done:
  nothing is exposed, so there is nothing to rotate. **Secret scanning enabled —
  operator-confirmed 2026-08-25.** One sub-item is worth a 10-second check rather
  than an assumption: §5 names secret scanning **and push protection** as two
  toggles, and only the first was confirmed by name. If push protection is also on,
  §5 is fully closed. (`Settings → Code security`.)
  Note `secret-scan.yml` only scans a PR's `base..head` range by design, so it never
  covers history — this run was the one-time backstop, and re-running it is cheap if
  history is ever rewritten.
  **Context that makes this urgent rather than routine:** `SloopWorks/dayfold` is
  **already public** (verified 2026-08-25: `"visibility": "public"`,
  `allow_forking: true`) and has **no `LICENSE` file** — so it is published under
  all-rights-reserved. ADR 0032 §5 calls this scan BLOCKING *"before any repo goes
  public"*, and the repo went public without it, so this run is a backstop after the
  fact, not a gate cleared in advance. It came back clean, so no harm resulted.
  Relatedly, `secret-scan.yml`'s own header still describes the repo as private
  ("useful now (private repo hygiene) and a prerequisite for going public") — stale.

- **Outsider-buildability is DEFERRED (operator call, 2026-08-25) — and it does not
  block the CLI.** The already-public repo cannot be built by someone outside the org:
  `apps/api` needs the private `@sloopworks/swip-*` npm packages (GitHub Packages),
  `:client`/`:ui`/`:androidApp` need the private Maven repos `SloopWorks/swip` +
  `sloopworks-ui` plus the `SloopWorks/debugdrawer` submodule. That caps the
  showcase value ADR 0032 is built on — a visitor who clones gets a build failure.
  Deprioritised for now; revisit if/when the showcase goal is actually being cashed in.
  **Important carve-out, verified 2026-08-25: `apps/cli` is already buildable by
  anyone.** It references no SloopWorks artifact at all, the CI `cli` job passes it no
  token and does not check out submodules (plain `actions/checkout@v4`), and it builds
  clean from a bare sandbox with no credentials — done repeatedly this session. It is a
  standalone Gradle build by design.
  So deferring this costs **nothing** for the CLI lane: an initial publication scoped
  to the CLI (the 4c shape) needs no buildability work, and ADR 0031's tap is
  unaffected because the release builds in CI regardless. What is deferred is only the
  broader "clone the monorepo and build it" claim.

- [ ] **Claude Bridge: Phase B partly run, everything remaining is operator-gated
  (updated 2026-08-21).** The external-spike authorization, the connector
  install, the tunnel, and the Gmail-mutation no-go approval were all given and
  used on 2026-08-21; 3 of 10 matrix questions are answered. What is left, none
  of it agent-decidable:
  1. **Run the block-then-enumerate test** — set Gmail's write/delete group to
     blocked in `Settings › Connectors`, then ask Claude to enumerate its Gmail
     tools. If the 22 disappear, blocking removes them from the run's surface
     and §9 condition 1 is restorable; if they remain and refuse, it is
     call-time enforcement and condition 1's literal wording may not be met.
     **No mailbox needed**, but it changes an account setting, so it is the
     operator's to run. This should come *before* the decisions below.
  2. **INB-41** — now two questions in order: ratify "block the write/delete
     group" as the pilot's required Gmail posture, and (only if that fails the
     test above) the original "no remembered approval" interpretation. Its own
     revised default is *run the test first and ratify nothing yet*.
  3. **INB-39** — ratify the operator-pilot boundary and retention constants;
     accept or replace Proposed ADR 0071. Its acceptance gate 1 needs the Gmail
     write behaviour settled, so it waits on (1)/(2).
  4. **INB-40** — resolve whether the V2 schema freeze is ADR-0071-gated; the
     packet contradicts itself (`CLAUDE-HANDOFF.md:66` vs plan `:639-640`).
  5. **ADR 0008 sign-off** on the spike-informed hi-fi (Phase C step 2, not yet
     generated — it now has real provider facts to draw on, including a
     client-ID paste step and an entirely untested mobile ceremony).
  6. **A throwaway Google account** for matrix questions 2 and 9. Possibly no
     longer the critical path if (1) restores condition 1 — worth deciding after
     the test, not before. Question 9 against a personal mailbox was **declined**
     and stays declined: it plants an email instructing Claude to send, reply,
     label, archive and delete, and the whole point is that compliance is unknown.
  7. **Before any private data**, unchanged: an eligible commercial no-training
     posture or an explicit constitutional amendment. A consumer
     model-improvement toggle alone remains insufficient.
- [ ] **WI-462 reachability guard (`:ui:desktopTest` → `ReachabilityGuardTest`)
  found 10 already-orphaned actions + 3 additional dark Calendar Check
  composables on `main` on its first run (2026-08-10)** — allow-listed with
  reasons (see `ReachabilityGuardTest.kt`'s `WIRING_ALLOW_LIST`) rather than
  fixed, since deciding dead-code-vs-real-gap per item is a product/eng call
  outside that WI's scope. Needs a follow-up triage pass (mirrors how WI-461
  was spawned from the Calendar Check wiring gap): `HubsFailed`/`HubNotFound`
  (hub-list error/404 paths — may be superseded by DB-driven sync, or a real
  gap), `CalendarSettingsLoaded` (reducer arm with no dispatcher),
  `ResponseStepBack` (no back affordance wired in `ResponseSheet`), and 6
  `RoutinePreviewAction` members the Smart Briefings preview's
  `smartBriefingsPreviewActions()` doesn't yet map (low risk — closed local
  fixture, no real provider ever invoked). `CalendarAlertOverrideHost`,
  `CalendarMatchedSummaryScreen`, `CalendarReturnScreen` are the additional
  Calendar Check (ADR 0063, still Proposed) surfaces beyond the three WI-461
  is already wiring — same staged-epic posture, revisit alongside it.
- [x] **CONFIRMED 2026-08-23: Claude Code's skill loader DOES follow a symlinked
  skill directory** (2026-07-30, harness-neutral skill move). The skill's real
  files live in `.agents/skills/dayfold-curator/` — Codex's repo skills root, so
  one copy serves both harnesses — and `.claude/skills/dayfold-curator` is a
  committed symlink to it (git mode `120000`, object `8469f68`). Codex discovery
  was already verified (`codex exec` lists it); the part needing a fresh session
  is now verified too — a Claude Code session started on 2026-08-23 listed
  `dayfold-curator` among its available skills.
  The evidence is not just "it appeared": `readlink -f` resolves the symlink to
  the `.agents/` path, and `find` confirms there is exactly **one** `SKILL.md` in
  the tree, under `.agents/` — so no stray `.claude/` copy could account for the
  discovery. It was loaded through the symlink.
  **The fallback is therefore NOT needed** (a generated `.claude/` copy plus a CI
  drift check — `docs/superpowers/specs/2026-07-30-harness-neutral-skill-source-design.md`).
  One honest caveat: this was the **remote/web** Claude Code harness. The loader is
  the same codebase, so the local CLI is very unlikely to differ, but that
  specific surface was not separately observed.
  **Edit only the `.agents/` copy from now on.**
- [ ] **API error reporting (ADR 0059) — PR #336 merged (`c65c0d4`, 2026-07-15);
  set Vercel env before the next prod deploy.**
  `apps/api` is wired to the SWIP error pillar (PostHog + Sentry,
  joined on `swip.fingerprint`; flush awaited in a Hono `finally` because Vercel freezes
  the container at response time). Verified live against both real vendors. The SWIP npm
  packages are published; the merged code pins `swip-js 0.5.1` / `swip-sentry 0.2.3` /
  `swip-schema-dayfold 1.0.3` (the republished set with the `scrubField` fix, SWIP #76).
  Before the next prod deploy: add `SENTRY_NODE_EU_DSN` (the API's project — *not* the mobile
  app's), `SENTRY_RELEASE`, `POSTHOG_PROJECT_KEY`, `POSTHOG_HOST` to Vercel prod
  (`processes/deploy-m0.md` §2), and the `SLOOPWORKS_PACKAGES_TOKEN` repo secret must have
  `read:packages` (it already exists for the Gradle lanes). Also still Proposed, not
  Accepted (same status-accuracy gap as ADR 0054-0057 — see INB-32).
- [ ] **Accept ADR 0060** (client crash/error reporting — debug-only Android,
  SWIP error pillar → Sentry KMP project + PostHog). Agent-drafted 2026-07-15,
  merged as PR #339 (`311c290`); Tasks 1–4 wired (error runtime, Sentry crash
  reporter, `Application` hoist, debug trigger). Same status-accuracy gap as
  ADR 0059 (merged but still Proposed — INB-32).
- [ ] **Run the on-device smoke for ADR 0060 (Task 5, Pixel dogfood
  device)** — the evidence step no unit test substitutes for: trigger the
  debug `wtf()`/`record()` and confirm the Sentry↔PostHog fingerprint join;
  force a real crash, relaunch, and confirm the mirrored `handled:false`
  PostHog event correlates by type/message/time (not by id, per the ADR).
- [ ] **ADR 0060's release-scope follow-up is blocked**, not yet actionable:
  needs the SWIP `consented`-gate gap closed (drafted issue at
  `.superpowers/sdd/swip-consent-gap-issue.md`, not yet filed) plus a
  consent surface wired to `CollectionMode`/`ConsentScope.ERRORS` and a
  privacy-policy disclosure.
- [ ] **Enable branch protection on `main` requiring the CI check before
  merge.** The 2026-07-05 CI outage (PR #289/`cf2898a`) landed without
  waiting on its own CI result; branch protection would prevent a repeat.
  Repo-settings change, operator-only (agents can't self-grant this).
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
- [ ] Counsel confirm for ADR 0005 (14+) — only if/when pursuing teen accounts.
- [ ] **INB-19 remainder** (operator-only): publish `redux-kotlin-snapshot` to
  Maven Central + fix `reduxkotlin/homebrew-tap` symlink (keg `bin/` empty →
  binary at `libexec/Contents/MacOS/rk`). Unblocks the `:client:snapshotUi`
  golden harness → prereq for CL-NAV/CL-10.

Full narrative for all of the above (build order, TDD slices, on-device
verification notes, superseded plans, and older repo-maintenance passes) is in
[`backlog/now-history.md`](now-history.md).
