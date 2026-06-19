# Backlog — Now

## ⚠ Time-sensitive (hard dates — keep pinned at top)

- **Quarterly:** re-check whether Google ships a *free, family-shared*
  Gemini Daily Brief variant (KS-6 / OQ-gemini-family). First check ~2026-09.
- **Next P0 viability review due 2026-07-18** (or +10 iterations).

Stage: **bootstrapped 2026-06-18; inbox swept.** Validation verdict:
**CONDITIONAL — learning-lab GO, business NO-GO.** ADRs 0006/0007/0008
Accepted; 0005 (14+) Proposed pending counsel.

## ⛔ Design-first gate (ADR 0008)

**No deep planning or build until hi-fi mockups exist + are approved.**
The immediate next work = **A8: hi-fi mockups of Now + Hubs** (Claude Code +
`frontend-design`, committed to `designs/`). A3 build is `blocked(A8)`.

## Operator actions pending

- [ ] **INB-3** kill-checks (~2 hrs): Gemini Daily Brief + Maple+ hands-on;
  note the niche gap → feeds A1.
- [ ] Counsel confirm for ADR 0005 (14+): age-gate sufficiency + Maryland
  DPIA — only if/when pursuing teen accounts.

## State (2026-06-18 — post 6-agent review)

- **Spec-build loop SUSPENDED** (cron stopped) — process right-sized for the
  solo M0 build (kept: spec-gate + inbox + multi-agent reviews).
- **Decisions:** M0 = **plaintext** (live E2EE → M1, ADR 0017 gate); M0 surface
  = **briefing-feed only** (Hubs → next slice); redux default **0.6.2**.
- **Spec suite + impl plan + JSON-schema contract = done; schema freeze
  unblocked.** Ready to build the M0 spine.
- **One open operator decision before P0 build: INB-9 (API host = TS/Vercel).**
- **Next product gaps to write (review): G1 content-authoring-loop runbook
  (`processes/`), G2 usefulness signal, the `payload` $defs, the Claude-Design
  triggers brief + the M3-Expressive signature upgrades.**

## Done this period

- Bootstrap (2026-06-18): scaffold, ADRs 0001-0004, validation fleet, board.
- Event Hubs design + block-level deep-linking (ADR 0006).
- Prototype scope locked (ADR 0007); design-first gate added (ADR 0008).
- Inbox swept: INB-1/2/4/5/6/7/8 answered; INB-3 pending operator.
- Design system = M3 Expressive, adaptive (ADR 0009); design brief shipped;
  initial Now/Hubs/Auth mockups added; repo public on GitHub.
- Auth/family/invite architected (ADR 0010) → **5-agent review**
  (`research/design-review-auth-2026-06.md`) → **hardened (ADR 0011
  supersedes 0010)**: all-invites-owner-approved, email→push cut, device-
  grant anti-phishing, no-auto-link, per-request revocation, Firebase dedupe
  corrected, relational content tables. Spec + Hub schema hardened.

## Auth is a separate later milestone

Per ADR 0011: the **prototype (A3) keeps the single household token** — no
RFC 8628, no Universal Links. The full auth/family/invite story builds after
the prototype. A8b (auth mockups, incl. the missing authorize-device screen)
can be designed now via Claude Design.
