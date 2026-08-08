# Smart Briefings — subscription routines (ADR 0008 design gate)

**Status: sign-off-ready mockups. Visuals only** — no app code, provider API calls, or
real secrets. This surface implements the "Smart Briefings via the user's AI
subscription" brief: the user's own Claude or ChatGPT subscription performs the work;
Dayfold supplies an OAuth-authenticated connector, hub-scoped context/update tools,
review controls, run receipts, and revocation. Where the older routine-architecture
draft / Proposed ADR 0061 implied Dayfold-funded model calls or Dayfold-owned
scheduling, this brief's framing wins for the UX; backend architecture is deliberately
not resolved here.

Open `Index.dc.html` for every Dayfold-owned state (light + dark), `Journey.dc.html`
for the annotated two-surface storyboard. `Smart-Briefings-Phone.dc.html` is the
parameterized phone (`mode` = light/dark, `view`, `provider` = claude/chatgpt).

## Files

| File | Contents |
|---|---|
| `Smart-Briefings-Phone.dc.html` | Parameterized phone — all 46 views below |
| `Index.dc.html` | Gallery: sections A–E, every state in light + dark, captions |
| `Journey.dc.html` | Cross-surface journey: Dayfold beats as phones, provider beats as annotated external cards, observe-vs-infer legend, per-provider connector lanes |
| `support.js` | Local DC runtime copy (unchanged) |

## Key decisions

- **The subscription is the compute plane.** No Dayfold-funded model calls anywhere in
  copy. "Runs count against your {provider} plan" appears at entry, privacy review, and
  the advanced test-run confirm. Provider options are **Claude** and **ChatGPT** —
  Codex appears only as a one-line technical footnote on provider choice.
- **Activation = first idempotent `dayfold_run_finish` receipt.** Until then the state
  is *Waiting for {provider}* with separate connector / per-source / schedule /
  first-safe-read steps and a visible pairing TTL. There is no `complete_enrollment`;
  a replayed finish resolves to the original receipt. "Dayfold only" completes on a
  successful context read + validation; a zero-result probe is a success.
- **Requested vs observed** is a per-source pill on Active and a per-source closed
  result at verification. No green check for merely returning from the provider.
- **Two-grant honesty.** provider↔Google (owned in provider, invisible to Dayfold) vs
  provider→Dayfold (approved on a Dayfold-owned screen, one-tap revocable). The
  no-external-writes promise is attributed to the routine's instructions, never to
  Google's OAuth grant. No Google account picker or consent UI in a Dayfold frame.
- **Plaintext disclosure is load-bearing** and verbatim on privacy review:
  "Selected Dayfold content is shared with {provider} … processes it as plaintext and
  its retention terms apply." No zero-knowledge / "AI never sees your data" claims.
- **Schedule is provider-owned.** Everywhere: "Requested schedule · managed in
  {provider}", plus last *observed* run. Never an authoritative next-run time.
- **Owner-only authority** (ADR 0029/0030): adult gets `entry-off-adult` and
  `adult-active-readonly` (no grant/schedule/revoke controls). The restricted hub
  (Health & appointments) is off by default with a lock and never auto-enrolls.
- **Review-first default**: Read + submit drafts; read-only offered; automatic
  publishing visible but locked "Available after you review initial runs".
- **Active-state failures are banners over last good content**, tone-mapped: error
  container only for real failures (connector denied, run-reported-failed, apply
  failed); tertiary container for needs-attention; neutral for unknown
  (`no-recent-checkin` asserts nothing about the provider task).
- **Stop = three responsibilities**, only the first performable by Dayfold: revoke
  grant (server-confirmed; Pending until then), remove provider task (external,
  explicit), optionally disconnect Google in the provider. No guilt copy.
- **Advanced "Run from Dayfold"** is a disclosure on Active (Claude only): one secure
  paste ("Paste Claude connection"), parse-preview of safe metadata only, token never
  re-shown, Save ≠ run, test-run confirm names real plan usage, connected state warns
  provider retries aren't duplicate-proof. ChatGPT variant states no external trigger
  exists (workspace-agent footnote only).
- **Provider marks** are restrained monogram tiles (C / G) — no co-branded hero art,
  no recreated provider UI. Provider screens exist only as dashed "outside Dayfold"
  annotation cards on the journey board.

## View inventory (46)

A: entry-off-owner · entry-off-adult · provider-choice · source-intent ·
source-permission-explainer · briefing-preset · hub-scope · privacy-review (×provider) ·
technical-details
B: handoff (×provider) · oauth-approval (×provider) · waiting (×provider) ·
source-setup-help (×provider) · source-syncing (chatgpt) · pairing-expired ·
returned-incomplete · provider-unavailable (×provider) · handoff-preparation-failed ·
authorization-denied · authorization-context-mismatch · return-recovery ·
source-verification-partial
C: active (×provider) · first-draft · first-run-no-changes · partial-source-result ·
source-not-observed · source-needs-reauth · no-recent-checkin ·
connector-needs-attention · run-reported-failed · draft-stale-or-conflicted ·
apply-retrying · apply-failed · routine-auto-paused · support-details · offline
D: stop-confirm · revoke-pending · revoke-failed · revoked-provider-task-remains ·
adult-active-readonly
E: advanced-run-claude · advanced-test-claude · advanced-claude-connected ·
advanced-run-chatgpt-unavailable

## Recovery matrix — closed phase/reason → action

Every displayed failure derives from `{phase, reason, retryability, action, support
code}`; raw provider error text and source content never enter copy. Support codes are
derived from the enrollment-attempt/run trace ID and are content-free. Analytics hooks
are content-free events; expected user/provider conditions are **not** Sentry defects.

| State (view) | Phase | Reason (closed) | Retryability | Primary CTA | Durable receipt shown | Analytics hook |
|---|---|---|---|---|---|---|
| handoff-preparation-failed | enrollment | gateway_unreachable | auto (bounded) → manual | Retry (idempotent) | none — nothing started externally | sb_enroll_prep_failed |
| pairing-expired | provider_handoff | enrollment_expired | final (restart) | Start again | none; no stale grant | sb_pairing_expired |
| returned-incomplete | provider_handoff | routine_not_confirmed | user action | Resume in provider | saved setup state | sb_return_incomplete |
| return-recovery | provider_handoff | app_return_lost | user action | Continue setup | live enrollment state | sb_return_recovered |
| authorization-denied | provider_handoff | oauth_denied | user action — never auto | Resume in provider / Cancel | none granted | sb_oauth_denied |
| authorization-context-mismatch | provider_handoff | context_mismatch \| late_callback | final (restart) | Start again with intended context | old grant untouched | sb_ctx_mismatch |
| provider-unavailable | provider_handoff | plan_unsupported \| admin_disabled | user action | Check in provider | none | sb_provider_unavailable |
| source-syncing | source_probe | drive_syncing | auto (later runs) | Continue anyway / Wait | probe results so far | sb_source_syncing |
| source-verification-partial | source_probe | source_auth_failed (subset) | user action | Repair in provider / Continue with available (explicit) | per-source closed results | sb_verify_partial |
| source-not-observed | source_probe | never_observed | user action | Open provider | last run receipt | sb_source_unobserved |
| source-needs-reauth | source_probe | reauth_required (routine-reported) | user action | Open provider connector settings | reporting run receipt | sb_source_reauth |
| no-recent-checkin | (none — absence) | checkin_overdue (policy threshold) | unknown ≠ failed | Open provider | last observed run + content | sb_checkin_overdue |
| connector-needs-attention | context_read | grant_revoked \| call_denied | user action | Reconnect (same scope, never wider) | last good content kept | sb_connector_attention |
| run-reported-failed | finish | invalid_output \| policy_rejection | never auto | View support details | structured failure receipt + last good | sb_run_failed |
| first-run-no-changes | finish | none (success) | — | none — calm receipt | success receipt | sb_run_noop |
| partial-source-result | analysis | partial_source_set | user action | Repair / accept reduced set (explicit) | draft + named source subset | sb_result_partial |
| draft-stale-or-conflicted | stage | oc_conflict | never auto-merge | Refresh draft (old kept read-only) | stale draft preserved | sb_draft_conflict |
| apply-retrying | apply | transient_network | auto (bounded backoff) | none — visible retrying | draft safe | sb_apply_retrying |
| apply-failed | apply | retries_exhausted | manual, idempotent | Retry | draft preserved | sb_apply_failed |
| routine-auto-paused | (policy) | repeated_conflicts \| unexpected_source_set \| volume_anomaly \| policy_review_expired | owner review required | Review & resume | pause reason + receipts | sb_auto_paused |
| revoke-pending | revoke | confirm_pending | auto + manual when online | Retry now | routine still shown active | sb_revoke_pending |
| revoke-failed | revoke | gateway_timeout | manual, idempotent | Retry revoke | last confirmed authority state | sb_revoke_failed |
| revoked-provider-task-remains | revoke | confirmed; provider_task_unknown | external step | Open provider to remove task | revoke confirmation | sb_revoked |
| offline | (transport) | offline | on reconnect | none — mutations disabled | cached status + receipts | sb_viewed_offline |
| advanced test failures | (trigger) | invalid \| paused \| quota_limited | manual | shown as quiet result rows | trigger receipt (no token text) | sb_trigger_result |

Idempotency: handoff, finish, stage, apply, and revoke callbacks/retries all resolve
to the original receipt or current authority state — never a duplicate routine, draft,
or write. OAuth denial, scope mismatch, conflicts and policy rejections never
auto-retry; only transient network/gateway failures do, with bounded backoff and a
visible "Retrying" state before a single manual Retry is offered.

## Unresolved provider-owned UI dependencies

- Claude connector UI, org-owner enablement flow, routine-creation connector picker
  and the API-trigger add/copy surface: provider-owned and may change; the journey
  cards annotate intent, not pixels. (Refs: Claude Google Workspace connectors +
  routines#connectors docs, 2026-08-07.)
- ChatGPT Settings → Apps flow, Drive sync semantics ("authorized ≠ indexed"),
  plan/region/workspace availability, and any future external trigger for Scheduled
  Tasks. (Refs: ChatGPT apps + Drive synced-connector docs, 2026-08-07.)
- Neither provider offers OAuth discovery or task-management APIs for consumer
  accounts — if that changes, `waiting`, `no-recent-checkin`, and `stop-confirm`
  copy could strengthen from "inferred" to "observed".
- Exact TTLs (setup instruction 60 min, pairing 43-min countdown shown) and the
  auto-pause policy thresholds are placeholders pending policy decisions.

## Sign-off ask (ADR 0008)

Operator review requested on: (1) the five-step consent ladder, (2) waiting-state
checklist honesty, (3) needs-attention tone mapping (tertiary vs error), (4) the
three-responsibility stop sheet, (5) advanced one-paste trigger framing. Sign-off
gates deeper planning and implementation.
