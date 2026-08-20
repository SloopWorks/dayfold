# Open Questions

Unresolved items that block or shape ADRs, specs, or the roadmap. Move
resolved items to the bottom with their resolution + date. Seeded at
bootstrap from validation round 1 (`research/validation-round1-2026-06.md`).

## Build / infra

- **OQ-claude-v0.1-eligibility** *(NEW 2026-08-20; constitution + provider data
  handling)*: can a user-owned consumer Claude account uphold Dayfold's absolute
  guarantee that family data is never used to train shared models when Gmail-
  derived details may appear in Claude responses/tool calls? Proposed default =
  synthetic testing only; private operator data needs an eligible commercial no-
  training contract/posture or separately approved constitutional amendment.
  Disabling consumer model improvement is useful risk reduction but not sufficient
  authority. → Proposed ADR 0071 / INB-39.

- **OQ-claude-remote-mcp-compatibility** *(NEW 2026-08-20; provider capability)*:
  does the exact Claude surface support the native Gmail connector and a custom
  OAuth remote MCP together with RFC 9728 discovery, resource-bound Authorization
  Code + S256 PKCE, refresh rotation, clean revocation, and provider-level Gmail
  mutation confirmation? Documentation is not sufficient; a synthetic operator-
  run spike is Work Package 0 and gates final hi-fi and implementation. →
  `docs/superpowers/plans/2026-08-20-smart-briefings-v0.1-claude-bridge.md`.

- **OQ-routine-observability-boundary** *(NEW 2026-08-07; customer-data + vendor
  telemetry)*: may existing API/client SWIP postures be reused for scheduled
  routines, or does the plaintext-processing K3 gateway require a separate source
  and stricter contract? Proposed default = durable Dayfold run records as truth,
  closed recovery enums, expected outcomes out of Sentry, gateway
  `stripMessage=true` with no request/extra fields, leak canaries, and dogfood-only
  anonymous analytics until a separate release consent/disclosure decision. →
  Proposed ADR 0062 / INB-35 /
  `docs/superpowers/specs/2026-08-07-routine-integration-design.md`.

- **OQ-cloud-routine-boundary** *(NEW 2026-08-07; customer-data + vendor + E2EE
  posture)*: should Claude/OpenAI scheduled routines call a K1/K3
  Dayfold-controlled gateway that holds credentials/`FCK`, or may a provider cloud
  job hold a routine unwrap key directly (K4)? Gateway-first preserves the durable
  key boundary but the provider still processes selected plaintext; K4 is simpler
  but makes the provider a durable key-holder and conflicts with current W3
  placement until separately superseded. Proposed default = K3 gateway, shadow then
  staged/bounded upserts; K4 off. → Proposed ADR 0061 / INB-34 /
  `docs/superpowers/specs/2026-08-07-routine-integration-design.md`.

- **OQ-migration-verify:** `verifyCommonMainContentDbMigration` (SQLDelight `verifyMigrations`)
  fails on `main` — the committed `1.db`/`2.db` schema snapshots are stale relative to the
  `card` table's later ALTERs (7.sqm importance, 11.sqm triggers) — and the task is **not in the
  CI gate** (`ci.yml` runs `build` + `:client:desktopTest :ui:desktopTest` + `:androidApp:assembleDebug`,
  none of which trigger it). Consequence: on-device **upgrade** migrations (every `N.sqm`, incl.
  ADR 0052's `12.sqm`) are unverified — only the fresh-install `Schema.create` path is tested.
  Surfaced 2026-07-09 during the ADR 0052 build. Fix = refresh the `.db` snapshots (or regen from
  migrations) + add `verifySqlDelightMigration` to the CI gate. Low urgency (additive migrations
  are low-risk) but it's a latent correctness gap for the whole app, not just 0052.

## Blocking (gate validation → build)

- **OQ-license:** How should Dayfold be licensed & published? **Researched
  2026-06-25 → Proposed ADR 0032 (awaiting operator + `[pending-counsel]`).**
  Findings: open-source-for-showcase = GO (near-zero opportunity cost); Apache-2.0
  client/CLI/schema + AGPL-3.0 server (not BSL) + G1 brains closed-by-not-publishing;
  monetize via hosted SaaS only; DCO contributions; blocking pre-flight secret scan.
  Report: `research/2026-06-25-licensing-open-source-strategy.md`. Closes ADR 0031's
  license gate on acceptance.
- **OQ-wtp:** Will any non-operator family *pay* for this over free
  incumbents (Gemini Daily Brief / Cozi / Maple)? Only field-provable.
  → feeds A4, A7, Gate G1b, KS-5. **The single product-defining unknown.**
- **OQ-niche:** Is there a defensible high-pain niche (co-parenting/split
  households, special-needs/IEP logistics, eldercare) that horizontal
  incumbents won't serve? → feeds A1, ADR 0004 flip-condition #2, Gate G1b.
- **OQ-gemini-family:** Does Google ship a *free, family-shared* variant of
  Gemini Daily Brief within ~12 months? If yes, the business case is likely
  terminal. → feeds KS-6, ADR 0004 flip-condition #1. Re-check quarterly.
- **OQ-ondevice-casa:** Does on-device-only Gmail processing (no server ever
  sees restricted data) exempt the CASA assessment while still needing
  restricted-scope verification? Architecture-defining if Gmail ingestion is
  ever pursued. → feeds A2, A3, post-MVP Gmail ADR.

## Important, not blocking
- **OQ-response-subjectref-stability** — *opened 2026-08-08 by ADR 0064 (Accepted).* The
  suppression key is now load-bearing in a way it wasn't as a dedup key. If extraction mints a
  **different** `subjectRef` for the same source across runs, Mark done silently stops working:
  the completed card comes back, and nothing errors. Today the only thing holding the key stable
  is a convention in the authoring skill — not a constraint the code can enforce. Worth deciding
  whether the routine contract should require a declared stable key per source (and reject an op
  without one) before the ADR 0061 gateway starts authoring at volume.
- **OQ-response-corrections** — *opened 2026-08-08 by ADR 0064 (Accepted), deferred there.* The
  "fix it" verbs — wrong hub, outdated/not accurate — are Tier 2 in the design notes and were
  explicitly left out of ADR 0064. They need a persistence contract of their own (structured
  feedback into the next run's input, outcome on the run receipt), which depends on ADR 0062's
  durable run records. Blocked on that; gets its own ADR.
- **OQ-response-minor-rights** — *opened 2026-08-08 by ADR 0064 (Accepted).* "Any adult sets, any
  adult removes" a family-wide mute is currently enforced by **nothing**, because ADR 0004's
  adults-only MVP makes every account holder an adult. Accepting ADR 0005 (14+ minor accounts)
  turns that into a real gate that does not exist — `app.ts`'s response routes are the code that
  would have to change. Personal responses are assumed available to any member.
- **OQ-response-partial-writes** — *opened 2026-08-08 by ADR 0064 (Accepted).* A personal mute
  makes one write partially succeed: the card is written for four members and stripped for the
  fifth, and the 200 tells the author nothing about the strip. Fine for the CLI (it can diff), but
  a routine reporting "added 3" when one of them reached fewer people is arguably under-reporting.
  Consider returning the stripped count if author-side visibility turns out to matter.
- **OQ-notbefore-gating** — **RESOLVED 2026-06-30 by ADR 0043 (Accepted)**: the on-device
  priority/ordering engine gates `not_before` (a derived/authored item surfaces only once its
  window opens). Original question retained: Should the **client feed gate `not_before`**, or is
  sort-only intended for M0? The spec says `not_before` **gates surfacing** ("don't surface before
  this time", `specs/prototype/12-briefing-card-spec.md:56`; "gates surfacing",
  `specs/domain-model/scope-and-access-model.md:161`; "Now feed filters `not_before`/`expires_at`",
  `specs/prototype/03-api.md:66`). But `feedCards` (`Selectors.kt`) filters only `expires_at` and
  uses `not_before` **purely for ordering** — and `SelectorsTest` *deliberately* asserts that two
  cards whose `not_before` is in the **future** (relative to `now`) are still **shown**
  (`not_before still orders nulls-last then by value`). The client `activeCards` query
  (`Content.sq`) and the API list (`repo.ts` `ORDER BY not_before`) likewise sort, not gate, so a
  future-scheduled card reaches the client via /sync and surfaces early. **Not a silent fix** —
  the sort-only behavior is intentional-as-tested, so changing it (add `not_before <= now` to the
  feed gate, update the test) is a **scope call**: is advance-scheduled authoring in M0 (→ gate
  now) or deferred (→ keep sort-only + soften the spec wording)? Likely moot today if the
  curator/CLI only authors `not_before ≈ now`, but the spec currently over-claims a filter that
  isn't implemented client-side. **Confidence: MEDIUM (product/scope) → operator.**
  - **Decisive evidence the deviation is DELIBERATE, not an oversight** (found 2026-06-29 re-read):
    `Selectors.kt:14` carries an explicit comment — *"not_before stays ordering-only (documented as
    a sort key, not a visibility gate)."* So a contributor consciously chose ordering-only against
    the spec. This makes it a true **spec↔code conflict to RESOLVE**, not a bug to fix: either (a)
    implement the gate + update that comment, the test, and align the spec, or (b) ratify the
    deliberate choice and **soften the three spec lines** (they currently assert a filter that does
    not exist). An agent should NOT pick a side here — both the spec (source-of-truth) and the code
    (deliberate, documented) have standing. **→ operator.**
- **OQ-web-target** *(NEW 2026-06-28, operator interest)*: Add a **Compose-for-Web
  (`wasmJs`) client**? CLAUDE.md lists Android/iOS/**Web**, but only `androidTarget()`,
  `jvm("desktop")`, and iOS (arm64 + sim) are wired in `apps/client/build.gradle.kts` — no
  `wasmJs` target, web source set, web module, or `index.html` entry exists. **Feasibility
  (investigated + dependency-verified against Maven Central 2026-06-28):**
  1. **No dependency blocker — every wasmJs artifact is PUBLISHED.** Verified each module's
     Gradle `.module` metadata declares `platform.type: "wasm"` (wasmJs*Elements):
     redux-kotlin `1.0.0-alpha03` (all four: `-threadsafe`/`-compose`/`-granular`/
     `-devtools-core`) ✓; `io.ktor:ktor-client-core:3.5.0` ✓; `io.coil-kt.coil3:coil-compose`
     + `coil-network-ktor3:3.2.0` ✓; `app.cash.sqldelight:runtime` + `coroutines-extensions`
     + **`web-worker-driver`** `2.3.2` ✓; kotlin 2.3.20 + Compose 1.11.1 native wasmJs.
     (Correction of an earlier guess: redux-kotlin wasmJs *is* published — only the **iosX64**
     native publication is genuinely missing, a separate gap.)
  2. **6 `expect`/`actual` seams need wasmJs actuals.** The easy five compile straight away:
     `PlatformActions` (`window.open` / `tel:` / `mailto:`) and `QrScanner` /
     `rememberCameraPermissionRequester` / `qrScanSupported` (stub unsupported on web). Plus the
     ktor wasmJs/js engine wiring (Coil), a web entry (`index.html` + `main()` with
     `ComposeViewport`), and a build/deploy path. **`DriverFactory` is the real gate (not a
     stub):** the only web SQLDelight driver is `web-worker-driver`, which is **async**, so it
     requires `generateAsync = true`. But the current DB layer is **synchronous** — no
     `generateAsync` in the `sqldelight {}` block; `ContentStore` uses `.executeAsList()` /
     `.executeAsOneOrNull()`; `DriverFactory.createDriver(): SqlDriver` is sync; and the driver
     is created **eagerly at startup** (desktop `Main.kt`, android `MainActivity`). So a working
     web DB means a **sync→async migration** of `ContentStore` + every caller (SyncEngine /
     HubEngine / the shells' startup) to suspend — which touches **all** shipping platforms
     (Android/desktop/iOS), so it's a deliberate architectural change + its own risk, NOT a
     drop-in actual. (A web build that merely *renders* could stub the driver to throw, but the
     shells construct `ContentStore` at startup → it'd crash before first paint unless that's
     also deferred.) **This async migration is the true effort gate for a functional web client.**
  3. **Design:** the **Expanded** adaptive breakpoint (`designs/content/adaptive/
     Breakpoints.dc.html` + the two-pane content/detail comps + `Settings-Adaptive`) is the
     layout reference. Web-chrome-specific affordances are deliberately unbuilt
     (`DESIGN-BRIEF-content-adaptive.md`: "web is not a build target yet — design desktop as
     the 'expanded' reference; don't design web-chrome-specific affordances"). A *minimal*
     browser build renders the existing Compose UI as-is; anything web-platform-specific
     (browser chrome, URL/deep-link routing, web auth-redirect flow) needs fresh design +
     sign-off (ADR 0008).
  **Decision (operator):** dependencies are all available (no Maven blocker), but a *functional*
  web client is gated on the **SQLDelight sync→async DB migration** (the true effort, touches all
  platforms) — that's the architectural call to make first. Sequence: (1) decide + do the async
  DB migration (its own ADR-ish change), then (2) the wasmJs target + the 5 easy actuals + the
  async `DriverFactory` + web entry (agent-doable), then (3) web-platform-specific UX (design-
  gated, ADR 0008). The non-DB scaffolding (target + 5 actuals + entry, DB deferred) could land
  first to de-risk, but a runnable web app needs step 1.
- **OQ-geocode-claim-wording** *(ADR 0028)*: the exact, legally-defensible
  marketing/UX wording for the location-privacy tiers — especially any
  first-party opt-in geocoding service (T5) "not sold / not shared / not linked
  to your identity / not retained" language. FTC + state privacy laws police
  "we don't sell" against actual data flows. **Counsel-gated before any
  external launch.** Structural tiers (T1–T4) need no such promise. → ADR 0028
  §Honest-claim, `[pending-counsel]`.
- **OQ-geocode-cpra-reading** *(ADR 0028)*: confirm the reading that an
  *authored venue coordinate* falls **outside** CPRA "precise geolocation" SPI
  (the statutory def is data "derived from a device…to locate a consumer", not
  a referenced place) — and the Apple/Play app-store-label conclusions for
  author-time/transient geocoding. Reasoning, not settled law → **privacy
  attorney to confirm before external launch.** → ADR 0028.

- **OQ-card-actions:** External card deep-links (the value-prop "[list]"→
  Instacart / "[reply]"→mail) have no structured home — `target` is
  internal-nav-only, `actions[]` is ADR 0016 2-way-reserved. M0 ships them as
  allowlisted **markdown links in `body_md`**; decide whether a structured
  external action layer (`links[]`, distinct from 2-way `actions[]`) is worth
  it before the action surface matters for WTP. → `specs/prototype/12-briefing-
  card-spec.md`.

- **OQ-twoway-member-scope** *(ADR 0038 / INB-25 #2)*: do member app credentials
  get **global `content:write`** (visibility filter = the human boundary;
  recommended) or **per-hub member scoping** to allow a **read-only member** role
  (e.g. an eldercare hub a member may see but not edit)? Values/scope; gates ADR
  0038. → `specs/two-way-collaborative-content-design.md` §6.5/§11.
- **OQ-twoway-clock** *(ADR 0038)*: M0 ships **wall-clock + per-install `actorId`
  LWW** for `done` (converges deterministically; **not** causally correct under
  large offline clock skew → a rare stale state, one-tap-recoverable). **HLC is the
  reserved drop-in** (forward-compatible field shape). Revisit trigger: dogfooding
  surfaces a real skew anomaly. → design §5.2.
- **OQ-twoway-transport** *(ADR 0038)*: M0 transport = **whole-block PUT** per
  toggle (re-sends the full payload). An append-only **op-log / granular
  `{item,field,value,stamp}`** transport is **reserved** (merge function identical;
  no re-model). Revisit trigger: whole-block re-send bandwidth on long lists, or
  concurrent-edit contention the client LWW-on-retry can't resolve calmly. → §6.1.
- **OQ-twoway-generalize** *(ADR 0038)*: build the generalized **addressable-
  togglable-item** primitive (checklist `done` + budget `paid` + RSVP) now, or
  **todo-only first** and mirror the id/stamp fields onto `BudgetPayload` when
  budget actually goes two-way (a cheap additive copy, no re-model)? Lean: todo-
  only first (avoid premature abstraction before the 2nd real caller). → §11.
- **OQ-twoway-engine-fwdcompat** *(P0; W1–W5 panel)*: forward-compat is **already
  broken** — every generated schema is `.strict()` / Kotlin `ignoreUnknownKeys=false`,
  so *any* added field breaks unupgraded clients. Fix = **split strict-authoring from
  lenient-decode** + unknown-`type` graceful-skip + migrate `block_type`/`card_kind`
  PG-ENUM→`text+CHECK` + mandatory `x-e2e` classification per new field + a CI Kotlin
  codegen-drift guard. Must land before *any* W1–W5 (or even ADR 0038) field ships. →
  `specs/two-way-engine-and-content-management-design.md` §5.
- **OQ-w3-freetext-constitution** *(blocks free-form W3)*: open free-form "add
  context" is the **free-text-prompt surface ADR 0016 §4 reserved behind a
  constitution amendment**. Ship W3 structured/template-bounded until amended? →
  engine design §4-W3 / INB-26 #1. Constitution-gated.
- **OQ-w3-loop-placement** *(E2EE posture)*: the async-AI loop must be a **key-holder**
  to decrypt W3 context (operator machine → controlled host). A **hosted** Claude
  routine breaks E2EE + triggers guardrail-#3 disclosure — reserved/ADR-gated, never
  default. → §4-W3 / INB-26 #2.
- **OQ-ondevice-k2** *(NEW 2026-07-13, operator question — E2EE posture / architecture)*:
  the key-holder tiers are named **K1** (operator machine) → **K3** (controlled host) →
  **K4** (hosted, reserved) — **there is no K2**, yet *the member's own phone is already a
  key-holder* (at M1 it holds the FCK + decrypted content, ADR 0015 §5). An **on-device
  LLM** is exactly that missing tier: zero recurring spend, zero E2EE compromise, and it
  makes ADR 0042's honesty chip ("Processed by Claude on your device") literally true.
  **Assessed 2026-07-13 → NO-GO today, slot reserved.** Gemini Nano / ML Kit GenAI is
  **capability-blocked, not policy-blocked**: <4k input / ≤256 output tokens, **no JSON
  schema, no tool calling, no multi-turn, foreground-only** (kills the ADR 0044 background
  surface), EN+KO only, ~4–5% of Android devices, and **"output varies by device"** — which
  breaks the **shared multi-member family briefing**, our one defensible wedge (two parents
  would read different text from identical content). Small-model long-context reasoning —
  precisely the briefing-authoring job — measures at **19–25% MRCR@128k**. The incumbent
  (`NowDerive.kt`/`NowRank.kt`: pure, deterministic, 131 golden snapshots) wins on every
  axis. **Revisit trigger:** Nano 4 ships structured output + tool calling (Google-promised
  "during the preview period"). Even then K2's honest scope is **bounded, single-record,
  schema-constrained, no-network** work only — never briefing authoring, and never W3-with-
  research (ADR 0042 §4 forbids device-side outbound fetch). **Hard trap recorded:**
  **Firebase AI Logic hybrid inference silently falls back to cloud Gemini** — if on-device
  inference is ever built it must call **ML Kit directly, never Firebase** (one wrong enum
  routes family content to a hosted LLM → ADR 0015 + guardrail #3 violation). Composes
  `OQ-w3-loop-placement` (above — this is its unnamed fourth option). →
  `research/2026-07-13-on-device-llm-assessment.md`.
- **OQ-ondevice-embeddings** *(NEW 2026-07-13, spun out of the on-device-LLM assessment)*:
  **ADR 0015 §3 explicitly sacrifices server-side FTS** (`tsvector`/GIN over `body_md`) to
  E2EE and accepts **"client-side search only"** as the cost. An on-device **embedding**
  model (NOT an LLM) fills that hole and beats an LLM on every axis that failed above: no
  input/output token cap, no hallucination, no schema-compliance problem, tens of MB not
  2.5 GB, deterministic-enough, we ship + pin the model ourselves (so no device-variance),
  and it runs well on exactly the runtimes that are *bad* at LLMs (ONNX Runtime, LiteRT).
  **This is the best on-device-AI opportunity in Dayfold — and it isn't an agent.**
  **Investigated for local search 2026-08-19:** technically feasible, but not the
  MVP. Ship/dogfood a derived lexical corpus plus bounded fuzzy fallback first;
  then evaluate a pinned small cross-platform TFLite embedder as a conservative
  reranker/fallback on 30–50 synthetic queries. Exact hits remain inviolable;
  semantic-only rows get no fabricated highlight and never drive actions or
  completion state. The current small USE artifact (~6.1 MB) is proportionate;
  the current 300M EmbeddingGemma task (~184 MB) is not. Runtime/model adoption
  remains an ADR-class decision. →
  `research/2026-07-13-on-device-llm-assessment.md` §5,
  `research/2026-08-19-local-search-exploration.md`.
- **OQ-objectstore-vendor** *(vendor + spend)*: member media = the first binary
  Dayfold stores → object store needed. **Cloudflare R2 (zero egress)** vs all-Vercel
  (Blob, egress-billed). → §4-W1 / INB-26 #3. ADR 0036 Phase-2.
- **OQ-hide-grain** *(privacy)*: per-member hide is self-scoped UI state (never the
  shared payload, never the ADR 0030 ACL). Local-only first; on promotion,
  **resource-grain = cleartext per-member table** (= `resource_visibility` leak class),
  **item-grain = in-ciphertext**. Confirm. → §4-W5 / §11.
- **OQ-freshness-spectrum** *(ADR 0020 — freshness owner; gates "support all client
  cadences")*: the system must serve **daily-poll → foreground-poll → push → realtime**
  clients simultaneously (a family may mix them). The keyset cursor already makes
  cadence a stateless client-side policy, but two things must move from open-question
  to contract: **(G1, load-bearing)** tombstone retention vs slow clients — track a
  per-credential sync watermark, GC only tombstones older than the *oldest active*
  watermark, and **force a full resync when a client cursor predates the GC horizon**
  (else daily/long-offline clients show ghost-deleted content); **(G2–G4)** push is a
  **contentless E2EE-safe signal** (not a transport) → needs a write-time change-signal
  + per-family **debounce** + **jitter**; held-connection sub-second realtime doesn't
  fit Vercel serverless → a managed pub/sub vendor later (the cursor sync underneath is
  unchanged). → `specs/two-way-engine-and-content-management-design.md` §10.5.
  **Contracted in Proposed `adr/0040-freshness-spectrum-and-tombstone-retention.md`**
  (extends ADR 0020 R2/R3; M0 = poll+background + the stale-cursor-full-resync +
  retention-floor rule; watermark-GC/push/realtime deferred). Residual operator-gated
  picks: the retention-floor constant, push credentials (FCM/APNs — ADR 0034/0023), the
  sub-second realtime vendor (spend).
- **OQ-decrypted-blob-cache** *(privacy)*: caching decrypted image thumbnails to disk
  for fast render weakens E2EE-at-rest; memory-only hurts scroll perf. Decide the
  cache policy. → engine design §7/§9.3.
- **OQ-e2e-encryption:** Adopt end-to-end encryption (CLI encrypts → server
  stores blind → device decrypts)? Feasible because the server never processes
  content. Crux = family-content-key distribution across the multi-member +
  invite + device-grant flows; cost = loses server-side FTS + adds key-loss
  recovery. → `backlog/next.md` TASK-E2E + `research/e2e-encryption-
  investigation.md`. ADR-class.
- **OQ-auth-recovery-floor:** passwordless last-resort recovery (all linked
  methods lost) + its abuse surface. → ADR 0011 / C4 security model.
- **OQ-family-switcher:** family-switcher UX when the multi-family UI ships
  (M:N model is built; UI is single-family at MVP). → ADR 0011.
- **OQ-invite-roles:** can a non-owner adult invite members? Default
  owner-only at MVP. → `specs/auth-and-family-design.md`.
- **OQ-markdown-render** *(M0 shipped 2026-06-26; full-fidelity deferred)*: the
  2026-06-18 plan was `mikepenz/multiplatform-markdown-renderer` (+`-coil3`).
  **What actually shipped (M0) is a hand-rolled `renderBlockMarkdown`**
  (`CardRender.kt`, no dependency) → `AnnotatedString` covering **bold/italic,
  bullet + checkbox + ordered lists, tables, ATX headings (`#`/`##`), and the
  scheme-allowlisted `[label](url)` links** — used by both hub blocks (PR #114/
  #115/#120) and feed cards (#119). XSS-safe via the shared `ALLOWED_SCHEMES`
  allowlist (same seam as cards). Surfaced + driven by the first real CLI-authored
  hub. **Images still off** (need async, host-gated loading). Adopt `mikepenz` only
  if/when full CommonMark fidelity (nested lists, autolink, raw HTML, images) is
  worth the dep. → `specs/event-hubs-design.md` §Markdown.
- **OQ-hub-collab:** At MVP, is Hub authoring push/Claude-only, or can family
  members edit Hubs in-app? (Lean: push-only at MVP, in-app edit post-MVP.)
  → feeds `specs/event-hubs-design.md`, C1b.
- **OQ-hub-archival:** Retention + export policy for archived Hubs. **Narrowed
  2026-06-23:** delete-on-request (Guardrail 4) is now covered by the MVP **manual
  hard-purge tool** (operator-chosen, schema review); what remains here is the
  *auto*-retention/export policy (auto-TTL stays OUT at MVP). → C4.
- **OQ-now-emission** — **RESOLVED 2026-06-30 by ADR 0043 (Accepted).** Hub→Now
  *structural* surfacing becomes **on-device derived** (a third answer the OQ
  never listed — and the only privacy-correct option for geo); the irreducible /
  external / hubless remainder stays **manual skill-authored**. Server-cron
  rejected (cannot do geo without leaking live location). *(Original: stay
  manual vs server-side cron/hybrid deriver post-MVP; schema review 2026-06-23.)*
  → ADR 0043; `specs/now-content-model-design.md`.
- **OQ-hub-created-by** *(NEW 2026-06-23 → decided same day by ADR 0030 round-1
  review)*: Hubs (and cards) get a resolved-`user_id` `created_by` column —
  resolving authorship through child-block `provenance` fails closed on credential
  deletion / zero-block hubs. **Decided: add it.** Kept here only as a build-task
  pointer. → content-slice build.
- **OQ-doc-storage:** When do we add real document upload + its privacy tier
  (vs links + small refs at MVP)? → ADR 0006 revisit, C4.
- **OQ-minor-age-gate:** Is a self-attested age gate sufficient for 14+
  minor accounts given no restricted/email data, and does the app trip the
  Maryland Kids Code DPIA trigger? → gates ADR 0005 acceptance. Counsel
  confirm (operator-gated).
- **OQ-casa-tier:** Exact CASA tier (2 vs 3) + current assessor fee for the
  Gmail restricted scope — only relevant once/if direct Gmail is pursued.
- **OQ-familylink-oauth:** Live Family Link supervised-account behavior —
  does Google block a restricted-scope OAuth grant? Build-time spike (A3)
  firms the one partially-confirmed compliance claim.
- **OQ-instacart-reopen:** Has Instacart's Developer Platform reopened to new
  applicants? Currently closed. Walmart add-to-cart URL is the fallback.
- **OQ-price-cadence:** Final price + cadence (recommend annual ~$39-59/yr).
  ADR-class, operator-gated. → feeds B6.
- **OQ-store-commission:** Does App Store/Play 15-30% commission apply to the
  billing model? Hits margin harder than LLM cost. → feeds A5.
- **OQ-cmp-web:** Is CMP Web (Wasm) Stable yet at build-decision time?
  Currently Beta — treat web as early-adopter.
- **OQ-action-revenue:** Can the deep-link action layer earn affiliate/
  referral revenue (ADR 0004 flip-condition #3)? Most consumer affiliate
  programs pay pennies and need volume.

## Deferred by design (prototype scope — ADR 0007)

Tracked, not gaps. Each re-enters via its own ADR/spec when the prototype
earns the next build (per ADR 0007 revisit trigger):

- Push notifications / FCM + APNs (needs Apple Dev $99/yr + Firebase). [review-gap G1]
- Multi-member login / household tenancy / roles. [review-gap G2]
- Data-source integrations: native EventKit/CalendarContract (no-OAuth path)
  or Google Calendar API (sensitive scope); Gmail/weather/commerce. [N4]
  Proposed ADR 0063 would reopen only the native, client-owned Calendar Check
  path; it leaves server-side Calendar OAuth and every other source deferred.
- Universal Links / App Links + `assetlinks.json` / `apple-app-site-
  association` (zero-redirect, iOS CDN ~24h). [N1/N2]
- Home-screen widget (Glance + WidgetKit — native-only, 2× UI). [N5]
- Document upload/storage + privacy tier (MVP = links + small refs). [OQ-doc-storage]

New prototype-level open items:
- **OQ-proto-auth:** exact single-household token mechanism (issue/rotate/scope)
  for the prototype. Minimal, not a login system. → A3.
- **OQ-ios-deploy:** Apple account for on-device iOS — free 7-day re-sign vs
  $99/yr stable. Decide before A3 device testing.
- **OQ-account-resurrection** *(2026-06-25, data-rights audit)*: After
  `DELETE /auth/me` soft-deletes a user (`users.deleted_at` set, memberships
  → `removed`, credentials revoked), a re-sign-in with the SAME identity mints a
  fresh, valid session — `findOrCreateUser` (`apps/api/src/auth/identity.ts:91-95`)
  matches the surviving `user_identities` row and returns the soft-deleted
  `user_id` with **no `deleted_at` check**, so `/auth/firebase` + `/auth/dev-token`
  issue tokens. But `/auth/me` (`app.ts:177`) and `/auth/me/export` (`:208`) filter
  `deleted_at IS NULL` and 401, so the session is a broken half-state (authed by
  token, invisible to whoami). No data leak today — it just can't reach `/auth/me`.
  **Decision needed** (guardrail #4 — honor delete, no dark-pattern retention): on
  re-sign-in to a soft-deleted account, (a) **resurrect** — clear `deleted_at`
  (easiest UX, but re-activates a deleted account — arguably anti-delete); (b)
  **reject** until the purge job runs (respects delete; the identity can't
  re-register in the window); or (c) **fresh account** — reassign the identity to a
  new `user_id` (must handle the `UNIQUE(provider, provider_uid)` constraint).
  ADR-class (customer-data handling) → resolve before any account-deletion UX ships.

## Resolved

- **OQ-owner-visibility-default** *(2026-06-23, INB-21)*: Does `role=owner`
  auto-read `restricted` hubs? **Resolved: A — owner NOT auto-permitted** (reads
  only as author or via allow-list; protects co-parent/eldercare privacy).
  Recorded in ADR 0030 (Accepted). Flip to owner-sees-all / audited break-glass =
  a superseding ADR.
- **OQ-card-vs-hub-posture** *(2026-06-23, INB-21)*: Is "a card can't out-expose
  its hub" server-enforced or author-trusted at MVP? **Resolved: A —
  author-trusted** (skill stamps card audience; no server intersection).
  Server enforcement re-enters when multi-author/in-app authoring lands (ADR 0030
  Revisit Trigger).
- **review-gap G3 — per-Hub / per-member visibility** *(2026-06-23, schema
  review)*: Moved from "Deferred by design" **into MVP**. Operator chose per-hub
  visibility at MVP; resolved by **ADR 0030** (`family`|`restricted` + allow-list,
  read-path filter, shares ADR 0029's resource model). Builds with the content-API
  slice. Residual values-shaped sub-question → OQ-owner-visibility-default (above).
- **OQ-now-scope** *(2026-06-23, schema review)*: Is "Now" a subscription query
  over subscribed Hubs, or self-contained? **Resolved: self-contained, own table
  (`briefing_cards`).** Not a live query; coupled to Hubs by one-way reference
  edges only (deep-link out; manual CLI-authored cards in). →
  `specs/domain-model/scope-and-access-model.md` §2/§7.
- **OQ-child-accounts** *(2026-06-18)*: Can children have their own logins
  reading their own Gmail? **No — structurally blocked** by Google's
  supervised-account architecture (under-13s can't self-grant restricted
  OAuth) on top of COPPA. Resolution: **adults-only accounts at MVP, parent
  as data controller** (ADR 0004). Re-opening requires a new ADR.
- **OQ-llm-cost** *(2026-06-18)*: Is LLM cost a viability threat? **No** —
  ~$0.02-0.54/family/mo; 78-91% contribution margin. Acquisition/retention
  is the real constraint, not cost.
- **OQ-block-payload-schema** *(2026-06-26, authoring-doc review)*: The hub-**block**
  `payload` shape **diverges** between the client render model
  (`apps/client/.../Model.kt` `BlockPayload`) and the generated content schema
  (`packages/schema/kotlin-gen/Content.kt`, from `content.schema.json`). The server
  stores `payload` as JSONB **verbatim** (passthrough — `content/hubs.ts`), so the
  *render* contract is the client model: `items[{text,done,due,assignee}]`,
  `url`/`label`/`domain`/`docRef`, `name`/`role`/`phone`/`email`, `address`/`lat`/`lng`,
  `date`, `total`/`spent`. The schema instead names `ref`/`source`/`kind`/`mapUrl`
  and a richer `Item` (`amount`/`label`/`paid`). **Impact:** a structured block authored
  per the *schema* (e.g. `document.ref`, `location.mapUrl`, `budget` via item `amount`)
  silently won't render; `body_md` markdown is unaffected. **Not yet hit** (live content
  is `body_md`-only). **Action:** reconcile the two to one source of truth (likely
  regenerate/realign `content.schema.json` ↔ client model) before structured-payload
  authoring is relied on; until then authors use the client-model field names (now in
  `apps/cli/templates/README.md`) or `body_md`. → **ADR 0035 (Accepted 2026-06-26)**
  → on implementation, adopted **Option C** (the schema is **frozen + well-designed**,
  there's no Kotlin codegen, and the renderer needs fields the schema lacks, so the
  original Option A was wrong). **Done (M0):** block-payload validation in the CLI
  `validateHubTree` (#144) + the server (#145, also fixing the `BlockSchema.payload =
  z.any()` codegen stub), renderer reads canonical schema names alongside client ones
  (#146), authoring doc marks the schema canonical. **Residual (M1):** collapse to ONE
  representation (rename client → schema, decide location `lat/lng` vs `mapUrl` + budget
  itemized vs summary, build Kotlin codegen) — until then both names are accepted.
- **OQ-feed-empty-state** *(2026-06-26, dogfood review)*: An **established** family with
  **no briefing cards** sees the feed render `FamilyNullState` ("Your family space is
  ready · Invite a member · connect a device") — the **onboarding** state — because
  `FeedScreen` shows it for *any* empty `state.cards` (not syncing/error). For a family
  that already has hubs/members and is simply caught up, this misframes "no briefing
  right now" as "nothing set up yet." Surfaced dogfooding the operator's own account
  (a hub authored, zero cards). **Design-gated (ADR 0008):** the fix is a calm
  "you're all caught up / nothing needs you right now" empty state (optionally linking
  to hubs), distinct from first-run onboarding — needs a hi-fi mockup + operator
  sign-off before build. Distinguisher is also non-trivial on the feed (hubs/members
  aren't loaded there yet), so the design should decide the signal too. Low urgency
  (cosmetic; no data risk). → **Design brief authored**:
  `designs/DESIGN-BRIEF-feed-empty-states.md` (4 posture states + the
  new-vs-established signal). → **Hi-fi comp authored + rendered (light+dark)**:
  `designs/Family AI dashboard design brief/designs/States-Feed.dc.html` (caught-up,
  first-run, syncing, offline; recommends `established = hubs.isNotEmpty() ||
  members.size > 1`, with the `familyHasContent`-on-sync variant; includes the
  M3→FeedScreen mapping). → **Built 2026-06-27 (#209)**: operator signed off; FeedScreen
  now routes its empty branch into the four posture states keyed on the recommended signal
  `established = state.hubs.isNotEmpty() || state.members.size > 1` (both already on the Now
  surface — SyncEngine watches `activeHubsFlow`, roster loads at session). All four states
  snapshotted (light+dark) with the misframing branch-logic + the caught-up→Hubs forward-path
  nav tested (#210/#211). **Fully resolved.**
