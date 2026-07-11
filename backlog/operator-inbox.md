# Operator Inbox

Questions and ratifications awaiting the operator. Swept weekly (per
`context/values-and-direction.md`). Nothing auto-applies; items aging >2
sweeps escalate in the digest. Newest first.

**Open items only below — kept short on purpose (this file is mandatory
start-of-session reading for loop/planning work).** Full narrative for every
resolved/answered item (proposals, review findings, the reasoning behind each
default) → [`backlog/operator-inbox-history.md`](operator-inbox-history.md);
read it when you need the detailed context behind a past decision, not by
default. File split 2026-07-11 (was 508 lines; mirrors the `now.md` →
`now-history.md` split of 2026-07-03).

Format: `INB-N · date · urgency(high/med/low) · status(open/answered/stale)`
Each item: question, context link, **proposed default**, urgency.

---

## Open

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

- **INB-15 · 2026-06-19 · med · open — reduxkotlin 1.0 feedback (you maintain it).**
  *(Note: this is a distinct item from the other, since-resolved "INB-15" [ADR 0022
  content-library acceptance] in the history file — a pre-existing numbering collision in
  the original inbox, carried forward as-is rather than renumbered, to avoid rewriting
  history.)* Findings from wiring `1.0.0-alpha01` into the app →
  `research/reduxkotlin-1.0-feedback.md`. Headline **P0: `redux-kotlin-compose`
  doesn't pull `redux-kotlin-granular` transitively** (GMM variant misses it,
  though the POM declares it) → `FieldStateKt` (selectorState/fieldState) fails
  to load → bare "unresolved reference". Also: compose needs Kotlin ≥2.3.x while
  core/threadsafe read from 2.2.x; selectorState/fieldState are extensions
  (top-level call = "unresolved"); and `concurrentStore`/CLI aren't on Maven Central yet. **DevTools IS published
  (1.0.0-alpha01) — now wired + verified on-device (ADR 0019).** Doc has the
  full list + severities for 1.0.0; `DevTools.md` text predates the publish.
  *(Largely overtaken by the later INB-19 `rk` ratification — worth a status check next
  sweep rather than treating as still-live blocking feedback.)*

- **INB-3 · 2026-06-18 · med · open — Cheapest kill-checks (you, ~2 hrs).**
  Before/while building: (a) run Gemini Daily Brief's school-email→family-
  digest flow yourself; (b) use Maple+ a bit and name what it can't do for a
  niche. These most cheaply move the verdict (KS-6 / OQ-niche). **Operator
  action — cannot be agent-run.** Report findings into A1.

## Resolved / answered (full narrative in history)

- **INB-31** · ADR 0052 (DB-first cold-start route gate) — Accepted, built, shipped 2026-07-09.
- **INB-29** · ADR 0043/0044 (Now derived surfacing + Phase B background notifications) —
  both gates cleared, shipped to `main` 2026-07-01 (PR #260).
- **INB-28** · ADR 0043 + `designs/now-derived/` — Accepted + signed off 2026-06-30.
- **INB-26** · Two-way engine generalization (W1–W5, ADRs 0039–0042) — ratified 2026-06-29.
- **INB-25** · ADR 0038 (two-way collaborative content) — Accepted, global `content:write` 2026-06-29.
- **INB-24** · Hub/card visual enrichment (ADR 0036) — Accepted, Wikimedia-only 2026-06-26.
- **INB-23** · ADR 0034 (mobile release pipeline) — Accepted 2026-06-26.
- **INB-22** · ADR 0008 hub-visibility design delta — signed off 2026-06-24.
- **INB-21** · ADR 0030 (per-member visibility) — Accepted, owner NOT auto-permitted 2026-06-23.
- **INB-20** · Adaptive two-pane design — signed off + scope accepted 2026-06-22.
- **INB-19** · `rk` toolchain — ratified + pinned alpha02 2026-06-22; **publish
  `redux-kotlin-snapshot` + Homebrew-tap symlink fix are still operator-only pending actions**
  (tracked live in `backlog/now.md`'s "Operator actions pending", not re-duplicated here).
- **INB-18** · M0 ships all 6 content types — 2026-06-19.
- **INB-17** · Product name "Dayfold" confirmed — 2026-06-19.
- **INB-16** · Phone mockups signed off — 2026-06-19.
- **INB-15** (ADR 0022 content-library acceptance) · Accepted, extend-in-place — 2026-06-19.
- **INB-14** · Android SDK/device render — done 2026-06-19 (iOS still needs the operator's Mac).
- **INB-13** · Trigger designs v2 — closed 2026-07-01 (shipped in Phase-B, PR #260).
- **INB-12** · Vercel + Neon bootstrap — done 2026-06-19, M0 API deployed live.
- **INB-11** · redux-kotlin version — superseded 2026-06-19, use latest (`1.0.0-alpha01`).
- **INB-10** · E2EE posture — resolved 2026-06-18, M0 = plaintext (ADR 0015 recovery posture deferred).
- **INB-9** · API host — ratified 2026-06-18, TypeScript on Vercel (ADR 0018).
- **INB-8** · ADR 0007 + 0006 — Accepted 2026-06-18.
- **INB-7** · ADR 0006 (Event Hubs) — Accepted 2026-06-18.
- **INB-6** · ADR 0005 (14+ minor accounts) — direction ratified, ADR stays Proposed pending counsel.
- **INB-5** · Loop start — confirmed 2026-06-18.
- **INB-4** · Pricing direction — acknowledged, deferred to B6.
- **INB-2** · MVP scope guardrails — ratified 2026-06-18.
- **INB-1** · Validation verdict & direction — accepted 2026-06-18.
