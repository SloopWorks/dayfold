# Cloud routine integration — design draft

**Date:** 2026-08-07
**Status:** Draft for operator review; does not authorize hosted processing or auto-write
**Decision record:** Proposed ADR 0061
**Implementation plan:** `docs/superpowers/plans/2026-08-07-routine-integration.md`

## Verdict

**Build the routine contract around the existing Dayfold CLI, but keep decryption
and long-lived Dayfold credentials in a Dayfold-controlled K1/K3 gateway.** Claude
and OpenAI scheduling surfaces can trigger a run, read connected sources, and call
narrow Dayfold tools. They must not receive the family content key (`FCK`) by
default.

A direct cloud job that stores `FCK` (or a private unwrap key) in the provider's
secret store is feasible, but it is **K4 hosted processing**: the provider becomes a
family key-holder. Encryption at rest does not change that. It is an explicit,
disclosure-bearing, operator-gated option, not the baseline.

The first useful slice is deliberately smaller:

1. run locally/on K3 on a schedule;
2. pull current Dayfold state through the CLI;
3. read the operator's connected sources;
4. use `dayfold-curator` to produce a machine-readable changeset;
5. validate and show the diff, but do not write;
6. graduate to bounded upserts only after the shadow output is trustworthy.

## Product terminology (current, and still moving)

- **Claude Code:** Anthropic documents `/schedule` as **Cloud Jobs**; unlike local
  `/loop`, those jobs continue when the laptop is closed. Claude Code cloud sessions
  run in isolated remote environments with setup commands and configurable network
  access. [fact:[Claude Code power-user guide](https://support.claude.com/en/articles/14554000-claude-code-power-user-tips)]
- **Claude Cowork:** the broader non-code surface calls these **Scheduled tasks**.
  Remote tasks can use connected tools, skills, and installed plugins; tasks that
  need a local folder/app run locally instead. [fact:[Claude Cowork scheduled tasks](https://support.claude.com/en/articles/13854387-schedule-recurring-tasks-in-claude-cowork)]
- **OpenAI:** current help distinguishes **ChatGPT Scheduled tasks** from **Codex
  Automations**. OpenAI's Codex documentation URL is still titled "Scheduled
  tasks" and describes web tasks that can use connected tools, skills, and plugins,
  while project-folder tasks require the desktop host to remain available.
  [fact:[OpenAI scheduled tasks/Codex automation docs](https://developers.openai.com/codex/app/automations)]
- **Codex cloud environment:** this is the isolated repo execution environment, not
  the product-neutral name of the scheduler. It checks out the repository, runs a
  setup script, and then runs the agent. Configured secrets are decrypted for task
  execution but are intentionally available only to setup scripts, not the agent
  phase. [fact:[Codex cloud environments](https://learn.chatgpt.com/docs/environments/cloud-environment)]

Dayfold should use the provider-neutral term **routine** internally and treat each
product as an adapter. The product names and entitlement boundaries have changed
enough that they must not leak into Dayfold's persisted data model.

## Goal and non-goals

### Goal

Support the flow:

```text
schedule/event
  -> ephemeral agent session
  -> current Dayfold state + source deltas
  -> bounded analysis with Dayfold skill/policy
  -> validated changeset
  -> staged or policy-authorized Dayfold update
  -> auditable run result
```

The routine should make a family briefing smarter without making Dayfold the
source of record for email, calendar, or documents.

### Non-goals

- No conversational assistant or real-time reply surface.
- No Dayfold-owned Gmail OAuth in this slice.
- No delete, audience widening, role change, invite, or outbound message from an
  unattended routine.
- No claim that the routine is end-to-end private from the model provider.
- No provider-specific workflow embedded in the Dayfold API.
- No guaranteed cadence or real-time SLA.

## Current Dayfold baseline

[fact:repo]

- The Kotlin/JVM CLI already provides `login`, `whoami`, `pull`, `push`, `delete`,
  templates, local validation, JSON help, and idempotent resource PUTs.
- Device authorization mints revocable credentials with global or per-hub grants.
  Access tokens last five minutes; each refresh token rotates and is issued with a
  45-day expiry.
- An interactive host stores the refresh token in macOS Keychain or Linux
  Secret Service. A headless host can use `--allow-env-key`, but that puts the
  refresh token in a plaintext `0600` file.
- `dayfold-curator` already knows how to pull current state, read connected sources,
  author hubs/cards, preserve checklist IDs, and stamp provenance.
- The skill's binding rule is human propose-confirm before every push or delete.
- M0 content is plaintext. ADRs 0015 and 0017 reserve E2EE and key-authenticity for
  M1; neither is accepted/built.
- ADRs 0041/0042 permit bounded member commands only in a key-holding loop. They
  explicitly reserve K4 hosted processing for a later disclosure-bearing ADR.
- The `/mutations` batch and `intents` channel are designed but not implemented.

### What does not work unchanged in an ephemeral cloud job

1. **Interactive login cannot be repeated per run.** A scheduled job cannot wait
   for the owner to approve RFC 8628 every morning.
2. **A static copied refresh token is unsafe.** The first refresh rotates it; a
   later job presenting an old consumed token can trigger reuse detection and revoke
   the credential lineage.
3. **The headless fallback is persistence, not secret management.** Mode `0600`
   protects against other Unix users, not the cloud workload or provider.
4. **Codex cloud removes secrets before the agent phase.** The setup script can
   bootstrap data or mint a narrow token, but raw CLI auth is awkward for later agent
   writes without deliberately re-exposing a credential.
5. **Sequential PUTs are not a changeset.** A failed run can partially update a hub
   tree, and a stale routine can overwrite a newer human/loop edit.
6. **Propose-confirm has no unattended equivalent.** "The prompt said to be
   careful" is not an authorization boundary.

## Trust and encryption model

### The unavoidable truth

If a model must analyze plaintext email, documents, and Dayfold content, the model
provider processes plaintext. E2EE can keep the Dayfold API/database blind; it
cannot hide the exact material submitted to the model that reasons over it.

Putting `FCK` in a provider secret store protects the key at rest and from casual
logging. It does **not** protect the key from the provider's workload control plane,
a compromised job, or an agent with enough local authority. Confidential compute
would need provider-supported attestation tied to the exact workload; neither
Claude Code Cloud Jobs nor Codex cloud environments currently document that
contract. [inference from the published execution/secret models]

### Recommended boundary: K1/K3 gateway

```text
                         provider account boundary
  Gmail / Drive app  ->  scheduled agent session
                               | selected plaintext
                               | authenticated narrow tools
                               v
                    +--------------------------+
                    | Dayfold Routine Gateway  |  K1/K3
                    | - routine policy         |
                    | - Dayfold CLI            |
                    | - FCK / unwrap key        |
                    | - changeset validation   |
                    | - audit + idempotency     |
                    +-------------+------------+
                                  | ciphertext + routing metadata
                                  v
                         Dayfold API / database
```

The gateway can be the operator Mac for dogfood (K1) and a small, patched,
always-on controlled host for reliability (K3). It exposes a narrow MCP/HTTPS tool
surface to the scheduled agent. The cloud session never receives the Dayfold access
token, refresh token, `FCK`, or gateway filesystem.

The gateway still returns selected plaintext to the agent when needed. Therefore:

- the Dayfold server remains zero-knowledge after E2EE;
- the agent provider is disclosed as a third party processing the selected
  plaintext;
- the provider is **not** a holder of the durable family key;
- compromise of one session exposes that session's working set, not every stored
  family object decryptable by `FCK`.

### Direct K4 mode (reserved)

In direct K4, owner approval wraps `FCK` to a routine public key whose private key
lives in the Claude/OpenAI cloud secret store. The cloud CLI decrypts and writes
directly.

This is operationally simpler but changes the promise:

- the provider is a durable family key-holder;
- secret rotation/revocation cannot erase plaintext from prior run histories,
  logs, caches, or provider retention systems;
- prompt injection has a larger blast radius because the workload can decrypt all
  content allowed by the routine principal;
- it conflicts with the current ADR 0041/0042 placement boundary for member
  intents until a superseding ADR is accepted.

Direct K4 must remain off until the operator accepts ADR 0061 (or a successor),
chooses eligible commercial provider plans, approves disclosures/retention, and
accepts the weakened threat model.

### Threat matrix

| Threat | K1/K3 gateway | Direct K4 |
|---|---|---|
| Dayfold DB/API breach reads content | protected after E2EE | protected after E2EE |
| Cloud job steals durable `FCK` | no `FCK` present | possible; key is present/unwrappable |
| Provider sees selected prompt/tool plaintext | yes | yes |
| Prompt injection can mass-read all permitted Dayfold content | gateway can cap each call/run | routine principal's full grant is locally available |
| Revocation stops future Dayfold access | yes | yes |
| Revocation erases prior provider copies | no | no |
| Long-lived OAuth/Dayfold tokens exposed to model | no | likely unless provider mediates them |

## Source integrations and compliance posture

Use the scheduling provider's user-connected Gmail/Google Drive/Calendar tools for
the pilot. Dayfold should receive source URLs, IDs, excerpts, and derived content —
not the Google OAuth refresh tokens.

This preserves the existing MVP choice that Dayfold itself does not run a
server-side Gmail OAuth integration. It does **not** make the processing invisible:
the selected source content enters the agent session and must be disclosed as
third-party LLM processing.

If Dayfold later obtains restricted Gmail scopes itself, Google says an app that
stores or transmits restricted-scope data through a server needs restricted-scope
verification and an annual third-party security assessment. [fact:[Google restricted
scope verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification)]
Google also requires accurate disclosure, minimum necessary scopes, and new consent
before a materially new use of Google user data. [fact:[Google API Services User
Data Policy](https://developers.google.com/terms/api-services-user-data-policy)]

Whether use of a user's provider-connected app makes Dayfold a separate Google API
client/processor is a counsel question, not settled in this draft. The technical
default remains: **do not add Dayfold-owned Gmail OAuth.**

For any cloud pilot, require a commercial/business workspace whose contract does
not train on inputs/outputs by default and whose retention/admin settings are
reviewed. OpenAI states Business, Enterprise, and API inputs/outputs are not used to
train by default. [fact:[OpenAI data-use policy](https://help.openai.com/en/articles/5722486-chatgpt-privacy-policies)]
Anthropic distinguishes consumer Claude/Claude Code sessions from commercial
products; commercial API data defaults to deletion within 30 days, while saved
Claude for Work chats persist until the customer deletes them or applies enterprise
retention controls. [fact:[Anthropic training policy](https://privacy.claude.com/en/articles/10023580-is-my-data-used-for-model-training),
[Anthropic commercial retention](https://privacy.claude.com/en/articles/7996866-how-long-do-you-store-my-organization-s-data)]

Consumer Pro/Max/Plus accounts are not the production default for family content,
even when model-improvement toggles are off. Product retention and account-level
controls must be part of the approval, not inferred from the absence of training.

## Provider-neutral routine contract

### Routine manifest

Each installed routine has a server-side, owner-approved manifest:

```json
{
  "routineId": "rt_daily-family-brief",
  "familyId": "fam_...",
  "runner": "k3-gateway",
  "providerAdapter": "claude-cloud-job",
  "mode": "shadow",
  "allowedHubs": ["school-2026", "summer-trip"],
  "mayCreateCards": true,
  "mayCreateBlocks": true,
  "mayUpdateLoopAuthored": false,
  "mayDelete": false,
  "mayWidenAudience": false,
  "maxWritesPerRun": 20,
  "sourceWindowHours": 36,
  "retention": "ephemeral-working-set"
}
```

The exact constants are operator policy, not accepted by this draft. The important
property is that authority is structured and enforced outside the prompt.

### Routine principal

Do not reuse a human app credential, the legacy `HOUSEHOLD_SECRET`, or the rotating
CLI refresh token.

[proposal]

- Enrollment generates an Ed25519 keypair on the gateway.
- The owner approves the routine label, key fingerprint, provider adapter, hub
  list, read/write mode, and expiration in Dayfold.
- Dayfold stores the public key and grants. The private key stays in the gateway
  keychain/TPM/KMS.
- Per run, the gateway signs a short-lived client assertion containing
  `family_id`, `routine_id`, `run_id`, `aud`, `iat`, `exp`, and unique `jti`.
- The API verifies the registered key, single-use `jti`, live routine policy, and
  grants, then mints a five-minute access token. There is no refresh token and no
  cross-run rotating state.
- Revocation deletes future authority immediately. Key rotation repeats the
  fingerprint approval ceremony (ADR 0017).

For direct K4 only, the same private key would live in the provider secret store.
That is a separate installation type visible in audit/UI.

### Tool surface

The scheduled agent gets tools, not a shell with secrets:

```text
dayfold_context_get(window, allowed_hubs)
dayfold_changeset_validate(changeset)
dayfold_changeset_stage(changeset)
dayfold_run_finish(finish_receipt)
```

Auto-apply is absent initially. When enabled, it is one constrained tool:

```text
dayfold_changeset_apply(staged_id, expected_versions)
```

The gateway implements these using the CLI/library. A local agent can use the same
contract directly as CLI commands.

`dayfold_run_finish` is the single idempotent terminal operation for success,
no-change, partial, rejection, and failure. It also completes a pending enrollment;
there is no separate `complete_enrollment` tool. Its content-free payload includes:

```json
{
  "schemaVersion": 1,
  "familyId": "family_...",
  "routineId": "routine_...",
  "runId": "run_...",
  "enrollmentAttemptId": "enr_...",
  "outcome": "success",
  "phase": "finish",
  "reason": null,
  "sourceOutcomes": [
    { "source": "gmail", "status": "observed" },
    { "source": "drive", "status": "syncing" }
  ],
  "operationCounts": { "proposed": 2, "staged": 2, "applied": 0 }
}
```

The API/gateway returns the original receipt for a repeated
`enrollmentAttemptId + runId`; late or duplicate callbacks cannot create a second
routine or result. A source is `observed` when a low-risk connector query succeeds,
including a successful zero-result query. A Dayfold-only enrollment completes after
a successful context read and dry-run validation, with an explicit `dayfold`
`observed` or `zero_results` source outcome. Every requested source must have
exactly one terminal outcome; an unexpected or partial source set does not silently
satisfy enrollment or bounded-auto policy.

### Changeset

A run produces data, not free-form instructions:

```json
{
  "runId": "run_...",
  "baseCursor": "...",
  "operations": [
    {
      "opId": "...",
      "action": "upsert",
      "kind": "card",
      "id": "school-rsvp-2026-08-07",
      "expectedVersion": null,
      "targetHubId": "school-2026",
      "sourceRefs": ["gmail:thread:opaque-id"],
      "body": {}
    }
  ]
}
```

Required enforcement:

- schema/content validation before any network write;
- stable IDs + `opId` replay protection;
- optimistic concurrency on updates (`expectedVersion`);
- audience must be a subset of the source/target hub audience;
- routine policy and credential grants both pass;
- no edit of member-authored blocks;
- no delete, ACL, role, invite, or outbound action in the first auto-write tier;
- all-or-nothing transaction for an approved changeset;
- provenance includes provider, routine, run, and source references without raw
  source content in the audit log;
- a complete run result (`success`, `no_changes`, `partial`, `rejected`, `failed`)
  plus mode (`shadow`, `staged`, `bounded_auto`) and apply state where relevant.

This is the unattended equivalent of curator propose-confirm: the operator approves
the **policy envelope**, and the server enforces every run against it. Shadow and
staged modes retain per-run review.

## User-visible state and recovery contract

The UI must not infer behavior from free-text errors. The API persists a durable,
content-free state transition for every enrollment attempt and run:

```text
off
  -> preparing
  -> awaiting_provider
  -> awaiting_dayfold_approval
  -> verifying
  -> active_review
  -> active_bounded_auto
  -> paused | revoked
```

Provider task health is not authoritative because Dayfold cannot inspect the user's
task list. It remains an orthogonal observation (`never_seen`, `last_seen`,
`reported_failed`, `stale`) rather than pretending the provider task is enabled or
disabled. Requested schedule is similarly separate from observed runs.

Each requested source has its own state:

```text
requested | probing | observed | syncing | reauth_required |
admin_blocked | unavailable | removed
```

Each incomplete/failed transition carries only:

- `phase`: `enrollment`, `provider_handoff`, `source_probe`, `context_read`,
  `analysis`, `validation`, `stage`, `apply`, `finish`, or `revoke`;
- a closed `reason` code such as `expired`, `denied`, `context_mismatch`,
  `provider_quota`, `source_reauth`, `admin_blocked`, `gateway_unreachable`,
  `invalid_output`, `policy_rejected`, `write_cap`, `conflict`, `rate_limited`,
  `timeout`, or `internal`;
- `retryability`: `automatic`, `user_action`, or `final`;
- `recommendedAction`: `retry`, `resume_provider`, `manage_source`,
  `restart_enrollment`, `continue_review_only`, `refresh_draft`,
  `review_policy`, or `contact_support`;
- timestamps, safe counts, and an opaque per-attempt/run trace ID.

No provider exception text, source title/body, URL, OAuth code/token, family/member
ID, hub/resource ID, prompt, or model output is part of this envelope. The client maps
the enums to owned localized copy. Unknown codes render a generic recoverable state
and support code, not the raw value.

Recovery semantics:

- bounded automatic retry is limited to transient network/gateway/rate-limit
  outcomes and reuses the same idempotency identity;
- OAuth denial, context/scope mismatch, policy rejection, and optimistic conflict
  require explicit user action and never auto-retry;
- active surfaces retain the last successful briefing/receipt while showing a calm
  needs-attention state;
- `no_changes` is a successful run;
- a partial source run records exactly which sources contributed. It may produce a
  review-only draft after explicit owner acceptance of the reduced set; bounded-auto
  pauses instead;
- a stale/conflicted draft remains readable but cannot apply. Refresh/regeneration
  uses current versions rather than silently rebasing model output;
- revoke is `pending` until the API confirms the principal is revoked. A timeout or
  5xx never produces a false success state. Provider task/source cleanup remains
  separately owned by the provider;
- after the policy-set repeated-failure/conflict/volume threshold, the routine
  auto-pauses and requires owner review. Exact thresholds remain operator policy.

The owner can copy a support code derived from the opaque attempt/run trace ID. The
Dayfold database maps it back to the durable run record; the copied value contains no
stable family/resource identifier or content.

## Observability and diagnostics boundary

The durable `routine_runs`/enrollment records are the source of truth for product UI,
recovery, and support. PostHog, logs, and Sentry are lossy operational signals, never
the state machine and never the only copy of a failure.

### SWIP analytics / PostHog

Add generated, closed-schema events for the dogfood funnel:

```text
routine_setup_started
routine_provider_handoff_opened
routine_dayfold_grant_approved
routine_enrollment_completed
routine_first_run_completed
routine_setup_resumed
routine_setup_cancelled
routine_recovery_action_selected
routine_draft_reviewed
routine_paused
routine_revoke_confirmed
```

Properties are closed enums/counts only: phase, outcome, reason, recovery action,
provider enum where approved, source count, and duration bucket. Never emit source
names/refs, hub/resource IDs, schedule text, provider error text, support code, or any
content. The current client SWIP integration is debug/dogfood-only; measuring any
non-operator family requires a separately accepted consent/disclosure ADR and a real
`CollectionMode` surface. The API's SWIP handle continues to deny the analytics scope.

### SloopLogging

Client and gateway logs use structured milestone names plus closed phase/outcome,
duration buckets, safe counts, and an ephemeral trace suffix only. Never interpolate
provider responses, request bodies, source/document metadata, prompts, changesets, or
auth material. The current release client logger is an unscrubbed WARN+ fallback, so
routine release call sites must remain value-free until a scrubbed release sink is
separately approved.

### SWIP errors / Sentry

Expected domain outcomes—OAuth denial, expiry, offline, provider quota, connector
reauth, admin block, validation/policy rejection, conflict, rate limit, and no-change—
are run states and analytics/log signals, not Sentry defects. Sentry receives only
unexpected exceptions/invariant failures.

ADR 0059's API configuration cannot be copied to the K3 gateway: it assumes the API
is content-blind and keeps exception messages. A gateway-specific generated SWIP
source must use `stripMessage=true`, attach no request/body/headers/query/extra, and
allow only closed tags such as component, phase, provider enum, release, environment,
and reason=`internal`. It must have product-owned canary tests proving raw content,
tokens, source metadata, stable family/resource IDs, and support codes never reach
SWIP/PostHog/Sentry, local logs, crash artifacts, or provider-visible output.

This boundary is recorded separately in Proposed ADR 0062 because widening client
analytics/error collection to real families and instrumenting a plaintext-processing
gateway are customer-data and vendor decisions, not implementation details.

## End-to-end flow

1. **Trigger.** Start with a daily time trigger. Webhook/event triggers are a later
   provider capability; ChatGPT scheduled tasks currently do not support webhooks.
   [fact:[OpenAI Scheduled tasks FAQ](https://help.openai.com/en/articles/10291617-scheduled-tasks-in-chatgpt)]
2. **Boot.** The provider creates an ephemeral session. A repository checkout is
   optional; durable Dayfold instructions live in the installed skill/plugin.
3. **Begin run.** The agent calls the gateway with the provider run identity. The
   gateway registers `run_id`, resolves manifest/policy, and obtains a short-lived
   Dayfold access token using its routine principal.
4. **Pull Dayfold context.** Gateway/CLI pulls current cards + permitted hub trees,
   decrypts locally after E2EE, minimizes fields, and returns only the permitted
   working set.
5. **Read source deltas.** The agent uses the user's provider-connected Gmail,
   Calendar, Drive, and other tools within the routine's source window. Treat every
   retrieved body/document as untrusted data for prompt-injection purposes.
6. **Analyze.** Run the Dayfold curator rules plus the routine manifest: deduplicate,
   preserve source links and provenance, prefer updates only where ownership permits,
   and generate a structured changeset.
7. **Validate.** The gateway validates schema, IDs, privacy chips, audience,
   entitlements, versions, write caps, and forbidden actions. Invalid output never
   reaches the write API.
8. **Stage/apply.** Shadow mode stores only the redacted diff/run summary. Staged
   mode waits for operator approval. Bounded-auto mode applies the whole changeset
   transactionally with replay protection.
9. **Close.** Erase the ephemeral plaintext working directory and call idempotent
   `dayfold_run_finish` with the outcome, phase/reason when needed, per-source status,
   and safe counts. The API stores the durable content-free run receipt and, for a
   valid pending attempt, completes enrollment. Do not retain raw source bodies in
   logs, Git, task notes, telemetry, crash artifacts, or prompt feedback.

## Claude and OpenAI adapter notes

### Claude

- Prefer **Claude Cowork Scheduled tasks** when the value comes primarily from
  Gmail/Drive/Calendar connectors and a Dayfold remote tool/plugin.
- Prefer **Claude Code `/schedule` Cloud Jobs** when the workflow needs the
  repository, CLI setup, or the checked-in skill.
- In both cases, treat any credential injected into the VM as accessible to the
  workload. "Protected credential handling" is not a promise that an agent with
  shell access cannot cause the credential to be used.
- The first Claude adapter should call the K3 gateway; do not put `FCK` in the
  cloud environment.

### OpenAI

- Prefer **ChatGPT Scheduled tasks** when connected Gmail/Drive tools plus a
  Dayfold plugin are the primary inputs. Web tasks can use connected tools,
  skills, and plugins without a local checkout.
- Prefer **Codex Automations** for a local/worktree or focused Codex workflow.
- Use a **Codex cloud environment** only for a repo/CLI experiment. Its setup-only
  secret model means a raw long-lived CLI session is a poor fit; minting a
  single-run, least-privilege capability or calling the K3 gateway fits better.
- Restrict agent egress to the gateway/Dayfold API and required connector/provider
  domains. OpenAI explicitly warns that agent internet access increases prompt-
  injection and secret-exfiltration risk. [fact:[Codex agent internet access](https://learn.chatgpt.com/docs/cloud/internet-access)]

## Rollout recommendation

1. **K1 shadow:** manual trigger on the operator Mac, current CLI + skill, no pushes.
2. **K1 scheduled shadow:** local Claude/Codex automation, same output, no pushes.
3. **K3 shadow:** always-on controlled gateway, remote read/validate/stage tools.
4. **K3 staged writes:** operator reviews each changeset, apply is transactional.
5. **K3 bounded auto-upserts:** no deletes/ACL/actions; alert on every policy reject.
6. **One cloud adapter:** Claude or OpenAI based on the better connected-source
   experience; keep the Dayfold contract unchanged.
7. **Dogfood observability:** durable run states + content-free SWIP schemas and
   gateway error configuration; prove recovery and vendor leak canaries before any
   non-operator rollout.
8. **K4 experiment only if explicitly accepted:** direct cloud-held key, commercial
   plan/retention review, new disclosure, separate dogfood flag, no W3 member intents.

Do not build both providers first. The portability boundary is the manifest,
changeset, and gateway tool contract; the scheduler prompt is thin.

## Decisions still required

1. Accept/reject Proposed ADR 0061's K3-gateway-first boundary.
2. Decide whether any direct K4 dogfood experiment is worth the weaker privacy
   posture.
3. Choose the first provider adapter after a connector-quality spike.
4. Define the routine policy constants (hubs, write cap, source window, expiration).
5. Decide the user disclosure and eligible account tiers/retention settings with
   privacy counsel before processing a second family's data.
6. Accept ADRs 0015/0017 before implementing E2EE or routine key wrapping.
7. Accept/reject Proposed ADR 0062's routine observability boundary; any release
   client analytics/error collection also needs the consent/disclosure surface that
   existing SWIP ADRs reserve.

## Review status

This draft has an author correctness/simplification pass folded in. It has **not**
received the repository-required two fresh-context adversarial reviews and must not
be treated as accepted architecture until those reviews and the operator decision
are recorded.
