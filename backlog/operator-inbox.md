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

- **INB-40 · 2026-08-20 · med · open — does the V0.1 schema freeze happen before
  or after ADR 0071 is accepted?** The packet contradicts itself. The gate table
  in `specs/smart-briefings-v0.1/CLAUDE-HANDOFF.md:66` bundles schema-writing
  with implementation in one row — "Implement schemas/server/client with
  synthetic data | no | ADR 0071 + constants/hosting accepted" — which gates the
  V2 schema freeze on ADR 0071 acceptance. The plan's own sequencing diagram,
  `docs/superpowers/plans/2026-08-20-smart-briefings-v0.1-claude-bridge.md:639-640`,
  places "WP1 final hi-fi + schemas" *before* "STOP for ADR 0008 + ADR 0071
  decisions", and only puts "WP2-6 server/bridge/publication implementation"
  after it. Both citations verified 2026-08-20. This changes when a Work Package
  may start, which `CLAUDE.md` reserves to the operator as an ADR-class
  scope/sequencing decision; an agent may propose the fix, not decide it.
  **Proposed default:** split "schemas" out of the gate table's bundled row and
  give the V2 schema freeze the same gating as the adjacent "Generate final
  hi-fi" row (`CLAUDE-HANDOFF.md:65`) — *after spike report / provider facts
  reconciled* — leaving server/bridge/client implementation gated on ADR 0071
  acceptance as it is today. That matches the plan's sequencing diagram, lets
  WP1 freeze the schema from recorded provider facts without pre-committing the
  credential model, and keeps every code-writing step behind the ADR. The
  alternative — moving WP1 §5.3's freeze to after the ADR decision — also
  resolves the contradiction and is the more conservative reading of the gate
  table; it costs a serialization of WP1 behind an operator decision. Whichever
  is chosen, the losing document must be edited so the packet stops
  contradicting itself. Context: `research/2026-08-20-smart-briefings-v0.1-packet-reconciliation.md`
  finding **F5** (row at line 138, and "Items that are operator decisions" item
  2). Distinct from INB-39, which ratifies the pilot boundary and the retention
  values, not the work-package sequencing.

- **INB-39 · 2026-08-20 · high · open — ratify the Claude Bridge operator-pilot
  boundary?** The operator selected bring-your-own Claude as the proof path. The
  repository's older Proposed ADR 0061 assumes a K1/K3 gateway and the existing
  hi-fi includes multiple providers and scheduling; neither is the minimum manual
  V0.1. Proposed ADR 0071 instead uses an isolated Dayfold Vercel OAuth/MCP bridge
  for plaintext M0: Claude owns model usage and Google OAuth; one Owner approves
  one Hub; Claude may read bounded context and submit at most one owner-private
  card proposal; only a human Dayfold app token may insert it for an exact
  restricted audience. No K3/E2EE claim, schedule, attachment, source write,
  direct Gmail OAuth, ChatGPT, BYOK, or auto-publish. **Proposed default:** approve
  this as a synthetic-first operator pilot, not the paid hosted release. Require a
  real-Claude OAuth/MCP/Gmail-write compatibility spike. Private data requires an
  eligible commercial no-training posture or explicit constitutional amendment;
  a consumer model-improvement toggle is insufficient. Keep non-operator accounts
  blocked until counsel/privacy, Terms, export, and deletion are solved. Also
  ratify or change the proposed retention values:
  60-minute enrollment, 10-minute code, 5-minute access, 45-day rotating refresh,
  14-day pending proposal, rejected/expired body purge within 24 hours, and 90-day
  content-free run history. Operator must separately sign off the resulting live
  Claude-only hi-fi under ADR 0008. Context:
  `specs/smart-briefings-v0.1/CLAUDE-HANDOFF.md`, Proposed ADR 0071, and
  `specs/smart-briefings-v0.1/system-design.md`.

- **INB-38 · 2026-08-10 · med · open — authorize the component-picker design
  pass?** `SloopWorks/debugdrawer` PR #1
  (WI-256) is merged + CI-green and declares the standalone repo the source of
  truth. Its first manual/operator-only Maven `publish` attempt failed: the
  repository supplied an empty `SLOOPWORKS_PACKAGES_TOKEN`, excluded the SWIP
  adapter, and GitHub Packages rejected core/noop uploads with 401.
  Per the operator's repository-sharing fallback, Dayfold PR #379 now pins the
  exact shared commit as a private submodule and substitutes its Gradle projects
  behind the stable 0.1.0 coordinates. It removes the embedded copy without
  waiting for package publication. The review also found
  that SWIP accepts UI-tree JSON and Point can snap to its
  bounds, but Point discards node identity/source and there is no preseeded
  frozen-capture handoff. **Direction recorded 2026-08-10:** integrating apps
  control UI-tree tooling per build (`Disabled` / `BoundsOnly` /
  `SourceOnDemand`); shared drawer/reporting plugins show availability and concise
  enablement help when unavailable. **Remaining proposed default:** merge the
  verified Dayfold migration, then run/sign off
  `designs/DESIGN-BRIEF-debugdrawer-component-reporting.md` before the new
  Components panel/live picker is built (ADR 0008). Approve a capture-feasibility
  spike and the typed SWIP handoff as explicit prerequisites. Keep it
  debug/internal-only; do not preserve Compose source strings through R8. Context:
  `docs/superpowers/specs/2026-08-10-debugdrawer-component-reporting-review.md`.

- **INB-36 · 2026-08-08 · high · open — accept the client-owned Calendar Check
  boundary after design/review?** The operator approved the feature direction and
  requested an ADR + design prompts. Proposed ADR 0063 keeps raw calendar access,
  identifiers, match state, and ignore decisions on the member's device; uses the
  native event editor for user-confirmed Dayfold→Calendar creation; makes
  Calendar→Dayfold a normalized, audience-reviewed proposal; and defaults matched
  generic start-time alerts to Calendar while retaining distinct Dayfold action
  nudges. **Proposed default: run the three design prompts, complete two
  fresh-context ADR reviews, then accept this boundary if the mockups preserve the
  privacy/audience/notification rules.** Acceptance must also ratify the initial
  bounded comparison horizon, eligible candidate types, and Calendar-owned
  start-alert default. This does not authorize a server-side Google Calendar OAuth
  connector, automatic bidirectional sync, attendees/invitations, recurring-series
  sync, or build before ADR 0008 sign-off. Context:
  `adr/0063-client-owned-calendar-reconciliation.md` and
  `designs/DESIGN-BRIEF-calendar-reconciliation.md`. **Gate 4 artifact landed
  2026-08-09** — `specs/calendar-import-contract-design.md` specifies the
  Calendar→Dayfold proposal/mutation contract and reconciles it with ADRs
  0030/0039/0053; it carries **seven open decisions with working defaults**
  (OD-1 description opt-in default-off · OD-2 provenance = constant
  `"calendar"`, display name stays device-local · OD-3 a new `event` template
  key · OD-4 additive `MilestonePayload.end`/`.tz` · OD-5 client-side vs
  server-side destination-Hub precondition · OD-6 section reuse · OD-7 =
  gate 3, still yours) that ratifying this item should sweep.

- **INB-35 · 2026-08-07 · high · open — approve the routine recovery and
  observability boundary?** The end-to-end UX review found that current SWIP
  postures do not cover a plaintext-processing routine gateway: client collection is
  debug/dogfood-only, release logging is unscrubbed, and ADR 0059 keeps API exception
  messages only because that API is content-blind. **Proposed default: accept
  Proposed ADR 0062 after its two reviews** — durable Dayfold records own recovery;
  closed phase/reason/action enums drive UI; expected auth/quota/conflict/policy
  outcomes stay out of Sentry; a gateway-specific generated SWIP source strips
  messages/requests and passes leak canaries; anonymous closed analytics remains
  dogfood-only until a separate release consent/disclosure ADR. This does not enable
  collection for another family. Context:
  `docs/superpowers/specs/2026-08-07-routine-integration-design.md`.

- **INB-34 · 2026-08-07 · high · open — where may scheduled routines decrypt
  family content?** Operator asked to plan Claude/OpenAI cloud routines that read
  Dayfold + connected email/docs/calendar and author smart updates. Current accepted
  ADRs keep W3 reasoning in K1/K3 and reserve hosted K4; M0 is plaintext, while E2EE
  ADRs 0015/0017 remain Proposed. The design found that putting `FCK` in a provider
  secret store makes that provider a durable family key-holder; encryption at rest
  does not change the workload trust boundary. **Proposed default: accept the
  K3-gateway-first direction in Proposed ADR 0061** — start K1/K3 shadow/staged,
  expose narrow audited tools and minimum selected plaintext to the provider, keep
  `FCK`/long-lived Dayfold credentials out of the cloud job, and leave direct K4
  disabled. Separately choose the first provider only after a connector-quality
  spike. This answer authorizes architecture direction only, not external
  provisioning, customer-data processing, spend, or auto-write. Context:
  `docs/superpowers/specs/2026-08-07-routine-integration-design.md`.

- **INB-33 · 2026-07-19 · med · open — planning loop hasn't iterated since
  bootstrap; P0 viability review is now overdue.** Found during the 13th
  repo-maintenance pass. `processes/loop-journal.md` has exactly one entry
  (Iteration 0, 2026-06-18, the bootstrap run) — zero planning-loop
  iterations since. `planning/workstreams.md`'s P0 Viability & feasibility
  review standing track states it was due 2026-07-18 "or +10 iterations,"
  and that "overdue review blocks all other loop work"; today is 2026-07-19,
  so by the board's own policy it's overdue and should be the very next
  loop selection. This isn't a red flag on the work itself — the 12 (now
  13) repo-maintenance passes plus operator-directed build-loop iterations
  have kept the codebase and docs in good shape — but neither of those is a
  *planning*-loop iteration (strategy/GTM/risk/spec deepening over the
  waterfall board), so the board hasn't moved and the confidence-bar/
  kill-switch register hasn't been re-scored since validation round 1
  (2026-06-18). **Proposed default: next session run "run a loop iteration"**
  (per `processes/planning-loop.md`) to execute the overdue P0 review — no
  content decision implied, this just asks whether resuming the loop is the
  right next priority vs. continuing build-first/maintenance work, which is
  a direction call for the operator, not something a docs-audit pass should
  decide unilaterally. Context: `backlog/now.md` time-sensitive section
  (updated this pass to reflect overdue status).

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
