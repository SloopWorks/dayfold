# MVP Feature Boundary — In / Authorable-Only / Out

> Status: **draft (authored 2026-06-23, schema/scope review; both adversarial
> rounds applied)** — binding on ADR 0030 acceptance. Reconciles the asymmetries the review found
> between ADR 0007 (prototype scope), ADR 0022 (typed content), ADR 0029 (credential
> scopes), and **ADR 0030 (per-member visibility)**. One crisp table for "what
> renders, what's authorable-only, what's deferred." Where this disagrees with an
> older doc, this doc + the ADRs win; flag the drift in review.

This boundary describes the **MVP** = the dogfood product (M0 prototype already
built/live + the M1 auth/content slice in active build), **not** a standalone
business. Business path stays NO-GO until a flip-condition/niche is evidenced
(`CLAUDE.md`, INB-1).

---

## Legend

- **IN** — renders + works end-to-end at MVP.
- **AUTHORABLE-ONLY** — the data model + content API support it and the operator/CLI
  can author it, but there is **no render surface** yet (stored, not shown).
- **OUT** — explicitly deferred; re-enters via its own ADR/spec when earned.

---

## 1. Surfaces

| Capability | Status | Notes / gate |
|---|---|---|
| **"Now" briefing feed** (`briefing_cards`) | **IN** (live M0) | time-windowed, keyset sync, deep-links |
| 6 typed content cards (file/link/invite/contact/geo/email) | **IN** (building, ADR 0022; INB-18) | one renderer → Now/Hub/Detail |
| Detail view + fold gesture | **IN** (building) | container-transform (ADR 0022) |
| Adaptive two-pane (tablet/expanded) | **IN** (queued, CL-NAV) | gated on snapshot harness (INB-19) + dep spike (INB-20) |
| **Hub render** (hubs/sections/blocks shown) | **OUT** (authorable-only) | schema + API exist; no render surface. Re-enters with content-API + CLI-verbs slice. |
| Hub deep-link from a card (tap-through) | **IN** (internal nav) | client-side resolve, nearest-ancestor fallback |
| Web (CMP/Wasm) | **OUT** | Android/iOS only at MVP; web is early-adopter (OQ-cmp-web) |
| Home-screen widget | **OUT** | N5 |
| Push notifications (FCM/APNs) | **OUT** | G1; pull-to-view only |

## 2. Identity, family, access

| Capability | Status | Notes / gate |
|---|---|---|
| Firebase sign-in (Google + Apple) | **IN** (building, ADR 0023/0027) | phone-OTP deferred |
| Multi-member family tenancy (M:N) | **IN** (built, ADR 0011) | UI single-family at MVP (OQ-family-switcher) |
| Owner-approved invites (QR/link, no auto-join) | **IN** (built) | owner-only mint at MVP (OQ-invite-roles) |
| CLI device-grant (RFC 8628) | **IN** (built, S6-D) | interim global `content:read+write` |
| Per-device revocation (Connected Devices) | **IN** (built) | |
| **Per-hub credential scope** (`hub:<id>:read|write`) | **OUT→IN** (designed ADR 0029, builds with content slice) | interim = global scope |
| **Per-member hub/card visibility** (`family`\|`restricted`) | **IN at MVP** (ADR 0030, **operator-chosen 2026-06-23**) | builds with content slice; resolves G3 |
| Roles beyond owner/adult (teen 14+) | **OUT** | ADR 0005 pending counsel; adults-only |
| Child accounts | **OUT (hard guardrail)** | COPPA; ADR-gated re-open |
| In-app content authoring by members | **OUT** | OQ-hub-collab; push-only at MVP |
| In-app visibility management UI | **OUT** | ADR 0030; visibility authored via content API at MVP |

## 3. Content authoring & data flow

| Capability | Status | Notes / gate |
|---|---|---|
| Content API (idempotent upsert: cards/hubs/sections/blocks) | **IN** | the MVP wedge |
| CLI (Kotlin) push | **IN** | `dayfold` binary |
| Claude skill authors cards from family signals | **IN** (the loop) | external intelligence; not in-app chatbot |
| **Hub → Now derivation** | **IN, manual** (operator-chosen) | Claude skill pulls hubs, authors imminent-item cards; **no server cron / client synth** at MVP (OQ-now-emission) |
| Hub visibility (`family`\|`restricted` + allow-list) | **IN** (ADR 0030) | the wedge primitive; upsert carries `visibility` + `audience[]` |
| Card visibility (flat author-stamped `audience`) | **IN** (ADR 0030, round-2 R2-1) | no inheritance/materialization/fan-out; skill stamps audience |
| **Productized hard-purge tool** (delete-on-request) | **OUT** (deferred, round-2 R2-7) | single-operator dogfood → operator SQL covers Guardrail 4; productize when a non-operator family exists |
| Auto-TTL retention sweep | **OUT** | soft-delete-authoritative; auto-retention is later (OQ-hub-archival) |

## 4. Data sources / integrations

| Capability | Status | Notes |
|---|---|---|
| Calendar/email/lists/weather as **authored** content (via CLI/Claude over operator's OWN data) | **IN** | the content-API path; no server-side OAuth |
| Native EventKit/CalendarContract ingestion | **OUT** | N4 |
| Google Calendar API (sensitive scope) | **OUT** | N4 |
| Server-side Gmail (restricted scope) | **OUT (hard guardrail)** | CASA; Guardrail 3 |
| Weather/commerce APIs (Instacart/Walmart) | **OUT** | N4; OQ-instacart-reopen |
| Universal/App Links + association files | **OUT** | N1/N2; internal nav only |
| Document upload/storage | **OUT** | links + small refs only; OQ-doc-storage |

## 5. Cross-cutting posture

| Capability | Status | Notes |
|---|---|---|
| Plaintext at rest (server) | **IN** | M0 = plaintext (INB-10) |
| E2E encryption | **OUT** | M1 option, ADR 0015/0017 |
| On-device trigger matching (geo/time) | **IN** (metadata) | live position never leaves device (ADR 0014) |
| Author-side geocoding only (T1/T2) | **IN** | server zero-knowledge (ADR 0028) |
| Two-way interactive actions (`actions[]`) | **OUT (reserved)** | ADR 0016 |
| External deep-link actions (`links[]`) | **OUT (markdown links at MVP)** | OQ-card-actions |

---

## 6. Asymmetries reconciled

The review surfaced four mismatches between docs; here is the resolved position:

1. **ADR 0029 builds per-hub *credential* scoping while G3 deferred per-member
   *visibility*.** → **Resolved by ADR 0030:** per-member visibility is now IN at
   MVP and shares ADR 0029's resource model. The two stop being divergent: one
   `hub:<id>` resource identity, two subjects (member-read, credential-write).
   Both build together with the content slice.

2. **"Now" subscription ambiguity.** → **Resolved:** "Now" is self-contained
   (`briefing_cards`), never a live query over hubs. Hub→Now is manual
   CLI-authored card emission. (`specs/domain-model/scope-and-access-model.md` §7.)

3. **E2EE column-split timing.** → Already resolved (INB-10): M0 plaintext, no
   schema gate; E2EE machinery is M1. ADR 0030 allow-list is the map the M1
   per-member key-wrap will follow.

4. **Retention vs delete-on-request.** → **Resolved:** soft-delete-authoritative;
   Guardrail 4 covered at the single-operator dogfood by operator SQL access, a
   productized purge tool deferred until a non-operator family exists (round-2
   R2-7); auto-TTL stays OUT.

---

## 7. Build sequencing implication

The content-API + CLI-verbs slice is now the pivotal next build: it unblocks (a)
hub render, (b) ADR 0029 credential scopes, (c) ADR 0030 hub visibility +
allow-list + the touch-trigger, and (d) visibility/audience authoring — all share
the hub-as-API-resource model and should land as one coherent slice, not
piecemeal. Visibility-aware `/sync` (+ the two revocation paths from round-1) rides
with it. Card-level machinery (inheritance, materialization, fan-out) was cut in
round-2 — cards carry a flat author-stamped audience.

Cross-ref: `specs/domain-model/scope-and-access-model.md`, ADR 0029, ADR 0030,
`planning/content-detail-epic.md`.
