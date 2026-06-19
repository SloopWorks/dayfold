# Operator Inbox

Questions and ratifications awaiting the operator. Swept weekly (per
`context/values-and-direction.md`). Nothing auto-applies; items aging >2
sweeps escalate in the digest. Newest first.

Format: `INB-N · date · urgency(high/med/low) · status(open/answered/stale)`
Each item: question, context link, **proposed default**, urgency.

---

- **INB-9 · ANSWERED 2026-06-18 → TypeScript on Vercel** (ADR 0018). CLI/client stay Kotlin; types codegen from schema; Postgres via pooler. **Last P0 gate cleared.**

- **INB-9 (orig) · Ratify API host = TypeScript/Vercel.**
  Spec-build loop (architecture review) recommends the backend API in
  **TypeScript on Vercel** (preserves the ADR 0012 preview→promote→rollback
  deploy-autonomy rail; a JVM API needs a container host + standing cost vs
  the <$50/mo cap). **CLI stays Kotlin**; types codegen'd from the JSON
  schema. Platform choice = ADR-class. **Proposed default:** ratify at C3 as
  an ADR. Confirm or pick Kotlin/JVM + Cloud Run instead.

- **INB-11 · ANSWERED 2026-06-18 → default `0.6.2` stable** (alpha01 opt-in). Indexed on ADR 0013.
- **INB-10 · ANSWERED 2026-06-18 → M0 = PLAINTEXT** (live E2EE = M1 option, gated by ADR 0017). Schema freeze unblocked.
- *(review decisions 2026-06-18: M0 = briefing-feed-only [Hubs→next slice]; planning-loop ritual suspended for the solo M0 build — keep spec-gate + inbox + multi-agent reviews.)*

- **INB-11 (orig) · redux-kotlin: alpha01 or 0.6.2 stable?**
  You chose `1.0.0-alpha1` (ADR 0013). Verified: it exists (exact
  `1.0.0-alpha01`, all modules, KMP), but it's ~1-day-old (pub 2026-06-17) with
  two alpha01-only modules. **Proposed default:** build P3 on **`0.6.2` stable**
  (hand-written root reducer + `selectorState`/`select{}`) and adopt alpha01
  behind a feature flag once it matures — no hard block either way, and code
  calls `fieldState` (not `fieldStateOf`). Confirm stable-default / insist on
  alpha01.

- **INB-10 · 2026-06-18 · high · open — Adopt E2E encryption (ADR 0015)?**
  Investigation (`research/e2e-encryption-investigation.md`) verdict:
  **CONDITIONAL GO** — the dumb-store architecture makes E2EE nearly free;
  encrypt at M0, distribute keys at M1 (per-member X25519 wrap mapping onto
  owner-approve). **Why high/now:** it changes the **M0 schema** (content
  columns become ciphertext, drop the server FTS index) — cheap to lock before
  real data, expensive to retrofit. Trade-off: loses server-side search;
  **lost keys = lost data** (recovery = OS keychain + owner-mediated re-grant +
  owner recovery phrase, no server escrow — this is **values-shaped, your
  call**). **Proposed default:** accept ADR 0015 → next loop iterations adjust
  02/05/06/08 specs for the encrypted column split. Accept / amend recovery
  posture / defer.

- **INB-13 · 2026-06-18 · med · open — Trigger designs need a v2 pass (Claude Design).**
  The new trigger/place/notification mockups are complete (14.5/15) + calm, but
  the 3-agent review found a **P0 honesty bug**: the Places/affordance copy says
  saved place coords "never leave the device" — false per ADR 0014 (they're
  encrypted server-side family content; only *live position* stays local). Fix
  list is in `designs/DESIGN-BRIEF-triggers.md §6b` (privacy copy + the four
  M3-Expressive signatures + offline screen + geo=M1 labeling). **Hand §6b back
  to Claude Design before the trigger surface (M1) gates (ADR 0008).** Schema
  side (`Place.kind`) already fixed.

- **INB-12 · 2026-06-18 · high · open — P-1 operator bootstrap (human-only; blocks cloud P0/P1).**
  The build loop is running; these account/auth steps only you can do (ADR
  0012 — agents can provision *within* an authed account, but not create the
  account/billing/domain):
  1. **Vercel** — create/auth the project + org (for the MCP deploy rail).
  2. **Postgres** — create a **Neon** (or Supabase) project + billing; put the
     pooled connection string into Vercel's secret store. (Neon serverless
     driver, per ADR 0018.)
  3. **Domain** `api.<host>` DNS (operator-owned).
  4. **Apple** Developer account + a **Mac** (for the iOS client, P3 — not
     needed for P0/P1/P2).
  The loop proceeds on **unblocked** work meanwhile (schema codegen, the TS API
  + Kotlin CLI against a **local Postgres** + seed fixture). Reply when the
  Vercel + Neon items are done and the loop will wire the cloud pipeline.

- **INB-3 · 2026-06-18 · med · open — Cheapest kill-checks (you, ~2 hrs).**
  Before/while building: (a) run Gemini Daily Brief's school-email→family-
  digest flow yourself; (b) use Maple+ a bit and name what it can't do for a
  niche. These most cheaply move the verdict (KS-6 / OQ-niche). **Operator
  action — cannot be agent-run.** Report findings into A1.

## Answered

- **INB-8 · answered 2026-06-18** — Ratify ADR 0007 + 0006. **→ Accepted
  both** (sweep). Prototype scope locked.
- **INB-7 · answered 2026-06-18** — ADR 0006 (Event Hubs). **→ Accepted.**
  Design cleared to become binding spec at C1b.
- **INB-6 · answered 2026-06-18** — ADR 0005 (14+ minor accounts).
  **→ Direction ratified, ADR stays Proposed pending counsel** on the
  age-gate mechanism + Maryland Kids Code DPIA trigger (legal = never
  agent-decided, guardrail #1). Adults-only remains in force until counsel
  confirms.
- **INB-5 · answered 2026-06-18** — Loop start. **→ Confirmed**, but the
  immediate next item is now **A8 (hi-fi mockups, ADR 0008)**, which gates
  A3. Loop iteration 1 = A8.
- **INB-4 · answered 2026-06-18** — Pricing direction. **→ Acknowledged;
  deferred to B6** (lean annual ~$39-59/yr when set).
- **INB-2 · answered 2026-06-18** — MVP scope guardrails. **→ Ratified**
  (adults-only / no-Gmail-OAuth / plain deep-links; ADR 0004 + 0007).
- **INB-1 · answered 2026-06-18** — Validation verdict & direction.
  **→ Accepted:** proceed building the dogfood prototype as a learning
  artifact; business path stays NO-GO until a flip-condition/niche is
  evidenced.
