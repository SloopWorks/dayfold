# Operating Lessons

Why this template is shaped the way it is — distilled from the KeepQR
project (idea → ADR-governed spec → autonomous milestone execution → live
staging) and the RevenueCatch project (idea → multi-agent validation →
planning loop). Read at bootstrap; consult when tempted to skip process.

## Governance

1. **ADR discipline.** Immutable-once-Accepted + a decisions index.
   Decisions never silently drift; superseding is explicit. Escalate any
   ADR-class decision discovered mid-task — work stops first.
2. **Constitution = scope firewall.** One page of what the venture is NOT,
   derived from real nearby failure modes, consulted before every feature
   and sales decision.
3. **Repo Markdown is the source of truth; everything else is working
   memory.** Definitions originate in Markdown; trackers mirror it; "a new
   requirement must never first appear in an issue." If memory conflicts
   with the repo, the repo wins.
4. **Operator-owned values file.** Agents propose via inbox, never edit.
   Thresholds (kill switches) change only by operator edit or ADR — a
   working doc that silently amends a kill switch is a process violation
   (caught live in RevenueCatch iteration 2).

## Review

5. **Two-round adversarial review is the cheapest defect-removal step
   anywhere in the system.** Round 1 correctness, round 2 simplification.
   It killed KeepQR's structurally-unprofitable pricing tier pre-launch and
   caught RevenueCatch's audit playbook overpromising *inside its own
   compliance table* plus a PII leak in its own method — both P0s authored
   hours earlier by the same agent that drafted everything else correctly.
6. **Reviewers get fresh context and a hostile mandate.** Builder
   self-review was explicitly rejected in KeepQR's security review.
7. **Viability is P0 and recurring.** Standing adversarial re-attack
   (strategist + red team minimum) every N iterations / 30 days / any gate.
   Overdue review blocks all other work. Verdicts: ship / conditional /
   pivot / kill.

## Research

8. **Cite or die.** Every claim verdicted (confirmed / partially /
   refuted / unverifiable) with a consulted URL; training-memory assertions
   are unverifiable by definition. Primary sources outrank roundups.
9. **Circular sourcing is the default failure.** RevenueCatch round 1
   inherited four competitor prices from one competitor's comparison page —
   all four were wrong by the time anyone checked vendor pages. A claim
   repeated across your own documents is still ONE source.
10. **Stats imported across contexts are partially-confirmed at best**
    (SaaS benchmarks applied to brick-and-mortar membership billing
    overstated recovery by ~25% relative).
11. **Ground-truth block in every fleet prompt** — state what has NOT
    happened yet (no customers contacted, nothing built, no entity). An
    agent once analyzed "exposure already incurred" from outreach that had
    never occurred.
12. **Archive raw fleet outputs next to the synthesis** — they carry the
    per-claim citations; temp files vanish.
13. **Desk research goes further than you think** — public API docs, help
    centers, statutes, court PDFs, and *shipped SDK source* (better than
    rotted vendor docs) answered "operator-only" questions repeatedly.
    Board items should split desk-researchable vs truly-operator parts.
14. **Dated research is immutable.** Corrections come as new rounds that
    link back; supersede, don't edit.

## Economics

15. **Model contribution margin per customer tier before accepting any
    pricing** — processor fees, support minutes at an explicit hourly
    value, taxes. KeepQR's $0.99 tier died this way ($0.66 after fees; one
    chargeback erased 24 sales). RevenueCatch's flat-fee conversion was
    upside-down for the bottom two-thirds of its list.
16. **The modeled customer must equal the targeted customer.** RevenueCatch
    modeled a client 2–5× larger than the prospects it actually listed.
17. **Hours are the real cost floor for a solo operator.** "85–92% margin"
    is true only if operator hours are valued at zero; one support edge
    case can wipe a small client's monthly contribution.
18. **Timeline arithmetic must use real hours/week.** "8 weeks to revenue"
    at 150–200 build hours and 10–15 hrs/wk is 13–20 weeks — the plan's own
    numbers contradicted its own headline and nobody noticed for two
    research rounds.

## Process mechanics

19. **Design-complete ≠ delivery-ready.** Schedule an explicit delivery
    review between spec acceptance and build (KeepQR found CI/test/security
    gaps after architecture was "done").
19a. **Split unknowns/spikes by credential need** — no-credential spikes
    run immediately; credential-gated ones wait on provisioning, so
    discovery never blocks on signups (KeepQR M1a/M1b).
19b. **Write runbooks at the moment of pain** — provisioning/deploy
    procedures captured when reality disagreed (deploy-before-secret-put
    ordering, key-format gotchas) are cheap then, expensive to rediscover.
19c. **Audit config/secret inventory as a step, not on failure** — KeepQR
    found gaps one 5xx at a time until a systematic audit was demanded;
    shared-package env vars must be provided by every consumer.
20. **Reconcile tracker vs reality at every close-out**, not quarterly.
21. **Verify vendor behavior against primary docs/SDKs, not
    pattern-matching** (a webhook-signature scheme that superficially
    resembled a standard cost a day of debugging).
22. **Self-improvement needs a forcing function**: journal every iteration
    with one improvement candidate; apply small evidenced tweaks
    immediately; structural changes via ADR; meta-review every ~15
    iterations. Adopt a rule only on second occurrence (avoid premature
    process).
23. **Time-sensitive items live pinned at the top of `backlog/now.md`**
    with hard dates.
24. **Resolve naming early** — renames get costlier with every artifact.

## The ordering insight

Validate the riskiest dimension first. KeepQR built first because tech risk
dominated; RevenueCatch validated demand/compliance first because the build
was known-feasible. At bootstrap, ask: what kills this venture — and aim
the first fleet there.
