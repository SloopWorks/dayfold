# Operator Inbox

Questions and ratifications awaiting the operator. Swept weekly (per
`context/values-and-direction.md`). Nothing auto-applies; items aging >2
sweeps escalate in the digest. Newest first.

**Answered/resolved/superseded/closed entries live in
[`backlog/operator-inbox-history.md`](operator-inbox-history.md)** (moved
there 2026-07-16, same reason `now.md`/`next.md` split their own history —
this file only keeps genuinely open items so the required start-of-session
read stays cheap). Read the history file when you need the resolution
narrative behind a past decision, not by default.

Format: `INB-N · date · urgency(high/med/low) · status(open/answered/stale)`
Each item: question, context link, **proposed default**, urgency.

---

- **INB-32 · 2026-07-13 · low · open — flip ADR 0054/0055/0056/0057 status text from "Proposed" to "Accepted"?**
  Found during a repo-maintenance pass (code-dedup/docs/CI/values audit): ADR 0054 (SWIP bug
  reporter), 0055 (SWIP analytics), 0056 (SloopLogging), and 0057 (SWIP debug inspector) are all
  still headed **"Proposed ... (accept on merge)"** in their own files and in
  `adr/decisions-index.md`, but all four PRs (#328/#327/#329 etc.) are already merged to `main`
  and running live in debug builds. Every other Accepted ADR in the index records an explicit
  operator act ("operator-directed in-session," "operator ratified," "operator accepted as
  written") — these four instead carry a **self-referential "accept on merge" clause the drafting
  agent wrote into its own Proposed header**, which this pass is treating as *not* the same thing
  as an explicit operator ratification (ADR-class decisions are never agent-decided per
  `CLAUDE.md`). Nothing in these four ADRs is a red flag on its own (the 07-13 values/privacy
  spot-check passed all five checks — debug-only, count-only, zero release footprint, no secrets,
  no PII) — this is a **process/status-accuracy** question, not a content one. **Proposed default:
  confirm merging was intended to be the acceptance act for these four (matching their own "accept
  on merge" text) and flip all four to Accepted** — or say no and they stay Proposed until you
  explicitly ratify each. Either way, once you answer, the four ADR files' status headers need a
  one-line edit (agent-executable once you pick a direction). Separately (not gated, just
  noted): ADR 0053's own body still says per-hub Contributor/Co-owner is "not built" under its
  Milestone-posture section, but `apps/api/src/content/write-guard.ts` + migration `0018` show it
  IS built and live — the ADR text is stale but, per governance, an Accepted ADR isn't edited
  after acceptance; flagging for awareness, not action. Context: this pass's findings below;
  `adr/decisions-index.md` now cross-references this item at the 0054-0057 rows.
  **2026-07-16 update (same pattern, two more instances):** ADR 0059 (API SWIP error pillar, PR
  #336) and ADR 0060 (client crash/error reporting, PR #339) are both merged to `main` and live,
  but both ADR files still read "Proposed ... (agent-drafted; accept on merge)" — identical
  status-accuracy gap to the four above. Folding them into this same question rather than opening
  a new INB: whatever you decide for 0054-0057's acceptance mechanics should apply to 0059/0060
  too. ADR 0059 also had one stale sentence fixed this pass (see `adr/0059-api-swip-error-pillar.md`
  — "blocked on publication" corrected to reflect the merged/live state; a wording fix only, not a
  status flip, since flipping Proposed→Accepted is exactly the operator act this item asks about).

- **INB-30 · 2026-07-07 · low · open — invite-approval context: include joiner location/IP?**
  Question: in the owner's invite-approval row (`TASK-INVITE-APPROVAL-IDENTITY`), beyond
  **name/email/verified-provider/join-time/mint-provenance** (spec `05-invite.md` §69–73,
  shipping first), should we also show the **joiner's IP / approximate location**? This is
  a customer-data-handling call (guardrail #3/#4) — a *person's* location is more sensitive
  than the device-grant flow's `origin_ip`/`origin_kind` for a *device*. **Proposed default:
  do NOT show location in v1** — ship the identity context, and if location is wanted, decide
  separately (what's shown, coarseness city-vs-IP, and disclosure to the joiner that their
  location is visible to the inviter). Context: `backlog/next.md#TASK-INVITE-APPROVAL-IDENTITY`.

- **INB-27 · 2026-06-29 · low · open — [pending-ratify] content-tombstone retention-floor
  constant.** Slice 6 (ADR 0040 §3, freshness) shipped the stale-cursor full-resync directive
  + a content-tombstone GC arm on `/cron/sweep`. Both halves are gated by ONE constant —
  `CONTENT_TOMBSTONE_RETENTION_DAYS` (`apps/api/src/auth/sweep.ts`): a soft-deleted content
  row is hard-purged only once older than the floor, and a client whose cursor is older than
  the floor takes the full-resync path (so it never silently misses a delete). ADR 0040 §3
  lists the **exact value** as operator-gated (values/cost → OQ-freshness-spectrum). **Proposed
  default: 90 days** (the conservative end of the ADR's 60–90d recommendation — longer = safer
  for slow/long-offline clients, slightly more tombstone storage; env-overridable via
  `CONTENT_TOMBSTONE_RETENTION_DAYS`). Shipped at 90 as `[pending-ratify]`; ratify or adjust.
  Urgency low (only matters once a client is >floor-days stale or tombstone volume grows).

- **INB-23 · ANSWERED 2026-06-26 → ADR 0034 ACCEPTED.** Operator "inb 23 approved" →
  ADR 0034 flipped Proposed→Accepted; **G5 posture ratified** (all tracks→prod Vercel
  API, real sign-in AUTH-S3, never bake `HOUSEHOLD_SECRET`/`DEV_AUTH_SECRET`). The
  remaining **G1–G4 are one-time operator setup actions** to switch the (merged, inert)
  pipeline live — recommended order G1+G3 first (keystore + Play account) so merges
  auto-ship to `internal`, then G2 (real Firebase) before relying on Google sign-in, G4
  before a real beta. Runbook: `processes/mobile-release.md`. Original below.
  **Kept live (not archived) because G1–G4 are one-time operator setup actions with no
  repo-visible evidence of completion — can't confirm resolved from the repo alone.**

  **Mobile release pipeline: one-time store gates
  (ADR 0034).** The 3-track Android pipeline is built + merged
  (`release-android.yml` + signing/versioning + a CI compile smoke) and **inert until**
  these operator-only gates are done (secrets / accounts / spend / store listing).
  Runbook: `processes/mobile-release.md`. **Proposed default: do G1+G3 first** (keystore +
  Play account) so merges auto-ship to the `internal` track; defer G4 until closer to a
  real beta.
  - **G1** generate the upload keystore (+ opt into Play App Signing) → 4 secrets.
  - **G2** real Firebase `google-services.json` → `GOOGLE_SERVICES_JSON_BASE64` (else
    Google sign-in is dead in store builds).
  - **G3** Play Console + service account ($25 one-time — **spend**); first AAB uploaded
    by hand → `PLAY_SERVICE_ACCOUNT_JSON`.
  - **G4** store listing + **data-safety form** (intersects children's-data / restricted-
    scope guardrails — review carefully).
  - **G5** confirm: all tracks → prod Vercel API (no staging), real sign-in (AUTH-S3),
    **never bake `HOUSEHOLD_SECRET`/`DEV_AUTH_SECRET`** into a store build.
  - Also **accept/flip ADR 0034** (Proposed → Accepted) — platform/vendor + external
    publishing + spend, so it's operator-gated.

- **INB-19 · ANSWERED 2026-06-22 → PARTIAL: (1) rk RATIFIED + (2) PINNED
  alpha02 (operator). (3) publish `redux-kotlin-snapshot` + Homebrew-tap
  symlink fix STILL PENDING — both operator-only (external action on the
  operator's own packages; agents draft-not-send).** Recorded as
  **ADR-0019-realized** (no new ADR; tooling/maintenance class). Urgency
  reframed low→**med**: the "before CL-5/6/7 commit" gate is overtaken —
  those merged *without* the golden harness (current = hand-rolled
  `FeedSnapshotTest`, no diff). Real next consumer = **CL-NAV/CL-10
  adaptive** (resize/hinge/pane reflow = visual-regression-sensitive) →
  **hold CL-NAV/CL-10 build until the harness lands.** Agent-buildable once
  (3) ships: `:client:snapshotUi` scene registry + CI golden job (stub
  prepared). Original below.
  **Ratify `rk` as the client dev+CI snapshot/
  devtools toolchain + pin.** The redux-kotlin CLI is now published (Homebrew
  `reduxkotlin/tap/rk` **1.0.0-alpha02**, unified devtools+snapshot). Incorporated
  into `processes/agent-dev-loop.md` + epic task **CL-SNAP** (rk snapshot golden-
  diff CI + rk devtools bridge) — this realizes ADR 0019's deferred golden-diff +
  CLI items. **Two caveats:** (a) it's **alpha** → pin like the redux-kotlin alpha
  bet; (b) `redux-kotlin-snapshot` (the app-side scene dep) is **not yet on Maven
  Central** per the docs — you own reduxkotlin, so confirm/publish the coordinate
  before CL-5/6/7 commit to it. **Also: the Homebrew formula symlink is broken**
  (keg `bin/` empty; binary at `…/libexec/Contents/MacOS/rk`; formula points at
  `libexec/rk.app/…`) — worth a fix in `reduxkotlin/homebrew-tap`. **Proposed
  default:** ratify rk as the toolchain, pin alpha02, publish `redux-kotlin-
  snapshot`. Tooling/maintenance = mild ADR-class; note as ADR-0019-realized.

- **INB-15 · 2026-06-19 · med · open — reduxkotlin 1.0 feedback (you maintain it).**
  Findings from wiring `1.0.0-alpha01` into the app →
  `research/reduxkotlin-1.0-feedback.md`. Headline **P0: `redux-kotlin-compose`
  doesn't pull `redux-kotlin-granular` transitively** (GMM variant misses it,
  though the POM declares it) → `FieldStateKt` (selectorState/fieldState) fails
  to load → bare "unresolved reference". Also: compose needs Kotlin ≥2.3.x while
  core/threadsafe read from 2.2.x; selectorState/fieldState are extensions
  (top-level call = "unresolved"); and `concurrentStore`/CLI aren't on Maven Central yet. **DevTools IS published
  (1.0.0-alpha01) — now wired + verified on-device (ADR 0019).** Doc has the
  full list + severities for 1.0.0; `DevTools.md` text predates the publish.

- **INB-3 · 2026-06-18 · med · open — Cheapest kill-checks (you, ~2 hrs).**
  Before/while building: (a) run Gemini Daily Brief's school-email→family-
  digest flow yourself; (b) use Maple+ a bit and name what it can't do for a
  niche. These most cheaply move the verdict (KS-6 / OQ-niche). **Operator
  action — cannot be agent-run.** Report findings into A1.
