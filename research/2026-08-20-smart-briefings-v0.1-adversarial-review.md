# Smart Briefings V0.1 — two-round adversarial review

**Date:** 2026-08-20

**Scope:** Proposed ADR 0071, system design, Claude Design prompt, implementation
plan, and Claude handoff.

**Review method:** Three fresh-context agents performed read-only platform/security,
privacy/provider/commercial, and UX/simplification reviews. The primary agent
checked findings against current repository code and reconciled the packet. No
reviewer edited files.

## Lead verdict

**NO-SHIP for private Gmail data or autonomous production implementation.** The
packet is safe to hand to Claude for repository review and the local synthetic
compatibility spike. External Claude/deployment work remains operator-only.

The corrected milestone is a **V0.1 operator pilot**, not the paid hosted release.
It is one installation, one owner, one Hub, one manual run, and zero or one private
card proposal. Final hi-fi follows the provider spike. Private data follows a
separate no-training authority and provider-level Gmail mutation gate.

## Evidence baseline

- Claude's Google Workspace connector is owned by the Claude account; current
  Gmail behavior includes message bodies and attachment metadata, not attachment
  contents. It also supports Gmail mutations: send/reply/forward ask by default,
  while Team/Enterprise owners may permit actions without asking each time. This
  is why exact-surface configuration is a no-go gate.
  [fact:https://support.claude.com/en/articles/10166901-use-google-workspace-connectors]
- Claude custom connectors call remote MCP resources and use provider-supported
  connector/OAuth behavior that must be tested on the exact client/plan.
  [fact:https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp]
- MCP OAuth requires resource binding and forbids token passthrough.
  [fact:https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization]
- A paid Claude chat subscription is separate from Claude API billing; this pilot
  does not convert a consumer subscription into an API key.
  [fact:https://support.claude.com/en/articles/9876003-i-have-a-paid-claude-subscription-pro-max-team-or-enterprise-plans-why-do-i-have-to-pay-separately-to-use-the-claude-api-and-console]
- Anthropic training and retention controls vary by product/settings and require
  separate treatment from raw connector data.
  [fact:https://privacy.claude.com/en/articles/10023580-is-my-data-used-for-model-training]
  [fact:https://privacy.claude.com/en/articles/10023548-how-long-do-you-store-my-data]

Provider documentation is not proof of exact custom-connector coexistence,
installation, OAuth return, Gmail tool inventory, or mutation confirmation. Those
remain spike questions.

## Round 1 — correctness, security, privacy, and provider constraints

| Finding | Severity | Resolution in packet |
|---|---:|---|
| Connector refresh token could enter current `/auth/refresh` and mint a human API token | P0 | Chose a separate bridge issuer/audience/key/verifier and physically separate connector refresh store. Cross-present access/refresh tokens in both protocols. |
| Accepted card could default to family visibility and generic upsert could overwrite | P0 | Acceptance always performs dedicated insert-only `visibility='restricted'` with source owner mandatory and exact additional adults, even in family Hubs. Direct GET and `/sync` audience tests required. |
| Gmail write prevention was prompt-only | P0 | Added hard synthetic spike gate: exact Claude surface must be read-only or require unavoidable per-mutation confirmation; injected email attempts send/reply/label/archive/delete. |
| Enrollment mixed short-lived OAuth attempt with durable routine identity | P0 | Split `routine_enrollment_attempts` from durable `routine_installations`; unique live installation per family. |
| Run identity/retry/finish was incomplete and accepted model authority-shaped fields | P0 | Context input is `{schemaVersion}` only; server creates/resumes one run, returns run/cursor/digest, expires after two hours, and permits one proposal. Finish supports no changes and replay. |
| Kill switch was a flag, not revocation | P0 | Added DB-backed `paused_security`, route-by-route gates, transactional credential/refresh revoke-all, and tests for already-issued tokens. |
| Consumer training toggle could not uphold constitutional “never” | P0 | Changed to synthetic-only. Private data needs an eligible commercial no-training posture or explicit constitutional amendment. Toggle alone is insufficient. |
| “No raw email” was not structurally enforceable | P0 | Added bounded/minimized proposal schema, forbidden artifact fields, and honest disclosure that semantic copying still cannot be proven; human review remains required. |
| Generic Hub grants/routes created a confused-deputy risk | P1 | Removed all `hub:*` connector grants. Bridge authorization binds installation/Hub in dedicated policy/repositories; generic middleware rejects connector tokens. |
| Other adults' personal response rules could leak through context | P1 | Context is limited to family-level rules plus source owner's personal rules; two-adult adversarial test required. |
| OAuth browser approval lacked poll-secret and browser hardening | P1 | Added browser-only 256-bit poll secret via Secure/HttpOnly/SameSite channel, distinct human user code, exact app-attempt pairing, no code in poll response, CAS transitions, rate controls, CSP/no-store/no-referrer/frame denial, and escaped client names. |
| Diagnostic choice was optional/incompatible with nested API middleware | P1 | Chose a separate MCP service/entrypoint and generated request/message-stripped source. |
| Query/window UI had no sensitive-data contract | P1 | Removed free-text query/labels; a requested 14-day/100-result preset is copied into instructions, explicitly unverifiable by Dayfold, and not stored as Gmail query text. |
| Original Gmail action was unsupported | P1 | Canonical pilot says `Original link unavailable`; no locator is accepted/stored. |
| Legal acceptance and retention were incomplete | P1 | Pilot explicitly excludes non-operator release; later release requires versioned acceptance. Retention now covers OAuth/run/proposal/control records and calls out unresolved card/backup deletion as a blocker. |

## Round 2 — simplification, UX, accessibility, and autonomous execution

| Finding | Resolution in packet |
|---|---|
| “V0.1” sounded like the paid hosted product | Renamed/positioned it as the V0.1 operator pilot and explicitly deferred commercial signup/pricing/billing/Terms. |
| Final hi-fi preceded provider evidence | Reordered to paper contract → synthetic provider spike → reconcile → final hi-fi → ADR 0008 sign-off → implementation. |
| OAuth approval, exchange, first call, and finish contradicted each other | Normative lifecycle separates `approved_waiting_exchange`; successful exchange creates `ready_first_run`, then `run_in_progress → ready_manual + draft/no_changes/failed`. App return alone never promotes and “Active” is removed. |
| Arbitrary source intent had no durable contract | Replaced it with one ratifiable requested source preset; Dayfold states it cannot enforce/verify Gmail search and stores no free-text intent. |
| Edit was promised without an API/validation contract | Removed Edit everywhere from the pilot. |
| Multi-operation changesets created premature partial/atomicity complexity | Cut to one run → zero/one proposal; removed operation table, arrays, batch decisions, and partial acceptance. |
| Browser approval was missing from hi-fi | Added responsive waiting, app-unavailable, approved/redirecting, denied, expired, and manual-fallback designs. |
| Draft discovery was unspecified | Claude says “Open Dayfold to review”; Account/Today gets a refresh-driven pending badge, no notification system. |
| Audience intent was requested twice | Setup selects only the Hub; exact recipients are selected once at acceptance. |
| Source-owner/role/Hub invalidation and sensitive draft states were incomplete | Added fail-closed auto-revoke plus loading/offline/expired/other-device/background-cleared states. |
| Immediate-deletion copy overpromised | Copy separates immediate removal from review and ratified server purge timing. |
| Design inventory was too broad | Reduced to ten happy-path phone views, four recovery families, browser ceremony, and targeted responsive/a11y QA. |
| Handoff would cause every executor to repeat planning | Marked recorded reviews complete, added a work/authority/evidence table, and made the first stop point explicit. |

## Focused final verification

The three reviewers re-read the reconciled packet. Their final findings were
incorporated:

- source limits are now a **requested** preset that Dayfold cannot enforce or
  verify in Gmail;
- owner approval enters `approved_waiting_exchange`; successful code exchange,
  not approval/return, creates `ready_first_run`;
- `partial` is removed; inconsistent hidden proposals are expired and purged in
  `no_changes`/`failed` finish;
- the browser-only polling secret is separate from the human user code/QR/app link;
- security pause advances a locked control epoch and invalidates pending
  authorizations, attempts, codes, installations, credentials, and refresh
  lineages across pause/issuance races and after resume.

After those corrections, reviewers reported no remaining known P0/P1
contradiction in the architecture packet. The verdict remains no-ship because the
operator/provider/legal gates below are intentionally unresolved, not because
Claude may infer authority to cross them.

## Remaining operator gates

1. Approve the operator-pilot boundary and accept/replace ADR 0071.
2. Authorize the synthetic external Claude test and any preview deployment,
   account, Terms, or spend it needs.
3. Ratify the requested Gmail preset, retention periods, value thresholds, separate
   Vercel bridge, and diagnostic source.
4. After the spike, sign off final hi-fi under ADR 0008.
5. Select a valid private-data authority; a consumer toggle is insufficient.
6. Before any customer use, complete counsel/privacy, versioned legal acceptance,
   export/erasure, card hard-purge/backup policy, store disclosure, pricing, billing,
   and commercial release decisions.

## Review completion rule

The two-round review gate is complete for this architecture packet. Do not repeat
it in a fresh execution session. Reopen security review only if a load-bearing
choice changes—especially provider surface, credential domain, hosting boundary,
Gmail mutation behavior, publication/audience semantics, data retention, or E2EE.
