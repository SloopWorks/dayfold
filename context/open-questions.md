# Open Questions

Unresolved items that block or shape ADRs, specs, or the roadmap. Move
resolved items to the bottom with their resolution + date. Seeded at
bootstrap from validation round 1 (`research/validation-round1-2026-06.md`).

## Blocking (gate validation → build)

- **OQ-license:** How should Dayfold (apps + CLI) be licensed & published —
  can it be open source *safely* (security + business), which license, and can
  it still be monetized? Ideal = OSS (showcase/resume/free tooling) + a paid
  path. → feeds a licensing ADR + closes ADR 0031's deferred license gate.
  Scope/method: `research/licensing-open-source-strategy-brief-2026-06.md`
  (`TASK-license-strategy`). **Legal/business → operator + `[pending-counsel]`.**
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
- **OQ-markdown-render** *(largely resolved 2026-06-18)*: renderer =
  `mikepenz/multiplatform-markdown-renderer` (+`-coil3`), lazy render,
  XSS-safe-by-structure; images **off at MVP**; app must allowlist link
  schemes + gate image hosts. Residual: confirm task-list/autolink rendering
  in-app + decide when to enable images. → `specs/event-hubs-design.md`
  §Markdown.
- **OQ-hub-collab:** At MVP, is Hub authoring push/Claude-only, or can family
  members edit Hubs in-app? (Lean: push-only at MVP, in-app edit post-MVP.)
  → feeds `specs/event-hubs-design.md`, C1b.
- **OQ-hub-archival:** Retention + export policy for archived Hubs. **Narrowed
  2026-06-23:** delete-on-request (Guardrail 4) is now covered by the MVP **manual
  hard-purge tool** (operator-chosen, schema review); what remains here is the
  *auto*-retention/export policy (auto-TTL stays OUT at MVP). → C4.
- **OQ-now-emission** *(NEW 2026-06-23, schema review)*: Does **Hub → "Now"**
  card emission stay **manual** (the Claude skill pulls hubs + authors imminent-
  item cards — the MVP choice) or gain a **server-side cron / hybrid deriver**
  post-MVP? MVP = manual, no server logic. → `specs/domain-model/scope-and-access-
  model.md` §7.
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
- Universal Links / App Links + `assetlinks.json` / `apple-app-site-
  association` (zero-redirect, iOS CDN ~24h). [N1/N2]
- Home-screen widget (Glance + WidgetKit — native-only, 2× UI). [N5]
- Document upload/storage + privacy tier (MVP = links + small refs). [OQ-doc-storage]

New prototype-level open items:
- **OQ-proto-auth:** exact single-household token mechanism (issue/rotate/scope)
  for the prototype. Minimal, not a login system. → A3.
- **OQ-ios-deploy:** Apple account for on-device iOS — free 7-day re-sign vs
  $99/yr stable. Decide before A3 device testing.

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
