# Open Questions

Unresolved items that block or shape ADRs, specs, or the roadmap. Move
resolved items to the bottom with their resolution + date. Seeded at
bootstrap from validation round 1 (`research/validation-round1-2026-06.md`).

## Blocking (gate validation → build)

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

- **OQ-auth-recovery-floor:** passwordless last-resort recovery (all linked
  methods lost) + its abuse surface. → ADR 0010 / C4 security model.
- **OQ-family-switcher:** family-switcher UX when the multi-family UI ships
  (M:N model is built; UI is single-family at MVP). → ADR 0010.
- **OQ-invite-roles:** can a non-owner adult invite members? Default
  owner-only at MVP. → `specs/auth-and-family-design.md`.
- **OQ-hub-collab:** At MVP, is Hub authoring push/Claude-only, or can family
  members edit Hubs in-app? (Lean: push-only at MVP, in-app edit post-MVP.)
  → feeds `specs/event-hubs-design.md`, C1b.
- **OQ-hub-archival:** Retention + export policy for archived Hubs. → C4.
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
- Per-Hub / per-member visibility + permissions (esp. sensitive Hubs). [review-gap G3]
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

- **OQ-child-accounts** *(2026-06-18)*: Can children have their own logins
  reading their own Gmail? **No — structurally blocked** by Google's
  supervised-account architecture (under-13s can't self-grant restricted
  OAuth) on top of COPPA. Resolution: **adults-only accounts at MVP, parent
  as data controller** (ADR 0004). Re-opening requires a new ADR.
- **OQ-llm-cost** *(2026-06-18)*: Is LLM cost a viability threat? **No** —
  ~$0.02-0.54/family/mo; 78-91% contribution margin. Acquisition/retention
  is the real constraint, not cost.
