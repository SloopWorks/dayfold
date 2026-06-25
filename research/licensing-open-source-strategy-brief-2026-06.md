# Research Task Brief — Licensing & Open-Source Strategy for Dayfold

**Status:** TASK (scope, not yet executed) · **Created** 2026-06-25 · **Class:** business
strategy + legal + security → **ADR-class** (feeds a licensing ADR; final choice is
operator + `[pending-counsel]`). Tracked: `TASK-license-strategy` (`backlog/next.md`),
`OQ-license` (`context/open-questions.md`). Trigger: ADR 0031 deferred the license
decision ("repo currently unlicensed; a public tap distributes the CLI publicly").

## The question (operator's words)

How do we license & publish the Dayfold **apps + CLI**? Specifically:
1. **Can it be open source safely** — both **security** and **business-strategy** wise?
2. **If so, which license?**
3. **Can Dayfold still be monetized if open source?**
4. What are the **business-strategy tradeoffs**?
5. **Ideal outcome:** open source **and** monetizable — OSS gives showcase / resume /
   portfolio value + free tooling, while a paid path still funds durable side income.

This aligns with the stated direction: the project is a **learning lab (GO)**, durable
side income a **co-goal**, validation verdict **standalone-business NO-GO** — so
"open source for showcase + a thin monetization path" is squarely on-strategy, not a
distraction. The research must confirm or refute that fit.

## What to investigate (deliverables)

### A. Security — what is safe to open vs must stay closed
- Map every component to an open/closed recommendation with rationale:
  **client** (KMP/Compose), **CLI**, **schema/contracts** (`packages/schema`),
  **server/API** (`apps/api`), **deploy config**, **secrets**, and the future **G1
  authoring "brains"** (AI prompts/loop).
- The security model is **not** obscurity: ADR 0011 (auth), 0030 (visibility), 0029
  (scopes) are designed to be safe when public; secrets are never in the repo
  (`HOUSEHOLD_SECRET`, tokens, Neon/Vercel config). Verify that holds — grep for any
  embedded secret/endpoint/assumption that *relies* on the code being private.
- Threat-model the deltas of opening the **server**: does it expose a not-yet-hardened
  surface, or make trivial self-hosting that undercuts revenue? (Note: public auth/
  privacy code is also a **trust asset** for a family-data product — transparency.)
- Reconcile with the hard guardrails (children's data, restricted-scope/LLM handling,
  customer-relationship line) — OSS does not change data handling, but confirm.

### B. License options — compare against the goals (showcase + monetize + protect)
Cover at least:
- **Permissive** (MIT / **Apache-2.0** — Apache adds a patent grant): max
  showcase/adoption; anyone (incl. a competitor) may fork + commercialize.
- **Copyleft** (GPL-3.0 / **AGPL-3.0**): AGPL closes the SaaS loophole (network use ⇒
  share source) → protects a hosted business from a closed competitor fork.
- **Source-available / BSL** (Business Source License — Sentry/HashiCorp style): source
  visible, free for non-prod/self-host, commercial-use restricted, converts to OSS
  after N years. Showcase + monetization protection, but **not OSI "open source."**
- **Open-core** and **dual-license** (e.g. AGPL + commercial) as structural options,
  not just licenses.
- For each: showcase value, monetization protection, contribution friction, brand/
  optics, and per-component fit (a different license per component is allowed +
  common).

### C. Monetization while open source — is it viable, and how
- **Hosted SaaS** (managed service): families pay for convenience, not to self-host a
  daily driver — the hosted service + brand + ops is the moat even under AGPL.
- **Open-core:** OSS client/CLI + closed premium features and/or the G1 "brains."
- **Support/services**, sponsorship, "host-it-yourself vs we-host-it."
- Map each model to the recommended license set; identify the **minimum closed
  surface** that protects revenue without hurting the showcase goal.

### D. Business-strategy tradeoffs (the explicit ask)
- **Pros of OSS here:** showcase/resume/portfolio (the primary goal), trust/
  transparency for family data, free marketing, community PRs, no lock-in fear.
- **Cons:** competitor forks, self-hosting cannibalization (assess how real for this
  audience), support burden, the obligation to keep the hosted edge.
- Weigh against the **NO-GO business verdict**: if monetization is a long shot anyway,
  OSS-for-showcase has high upside and low opportunity cost — quantify that framing.

## Method

- **Deep research** on current OSS-business patterns (AGPL+SaaS, BSL adopters and the
  2023–24 relicensing wave, open-core economics), with citations labeled
  `[fact:source]` / `[estimate]` / `[assumption]`.
- Use the **`solo-business-strategist`** agent for the monetization/unit-economics +
  tradeoff modeling (pass: the learning-lab-primary goal, the NO-GO business verdict,
  the hosted-SaaS hypothesis, low infra budget).
- A focused **security analysis** of the open-vs-closed split (above).
- **Adversarial review** (two rounds — correctness, then simplification) per the
  planning loop before acceptance.

## Hypothesis to test (state it, then try to refute it)

**Open-source the showcase surface permissively, protect the hosted business, keep the
"brains" closed:** **Apache-2.0** for client + CLI + schema (max showcase, patent
grant), **AGPL-3.0 (or BSL)** for the server (network-copyleft protects a hosted SaaS
from closed forks), the future **G1 authoring loop/prompts kept closed** (open-core
differentiator), secrets/deploy never in the repo. Monetize via a **hosted SaaS** (+
optional premium). This would satisfy all five asks at once — verify the security split
is clean, the license combo is compatible (Apache + AGPL across components is fine), and
the monetization survives self-host availability.

## Output + gates

- A **research report** (`research/...`) answering A–D with a recommendation, and a
  **Proposed ADR** (component-by-component license + closed surface + monetization model
  + security split) that **extends/closes ADR 0031's license gate**.
- **Operator-gated + `[pending-counsel]`:** the final license is a legal/business
  decision — the research *informs*; the operator (with counsel for the legal call)
  *decides*. Resolves `OQ-license`.
