# ADR 0062: Routine Recovery State and Privacy-Bounded Observability

## Status

**Proposed** 2026-08-07. Operator-gated: this adds behavioral analytics and
error/logging policy for a workflow that processes selected family plaintext.
Merging this draft does not enable collection for non-operator families.

Design and plan:

- `docs/superpowers/specs/2026-08-07-routine-integration-design.md`
- `docs/superpowers/plans/2026-08-07-routine-integration.md`
- `designs/DESIGN-BRIEF-smart-briefings-subscription-routines.md`

Composes with ADR 0054 (debug bug reports), ADR 0055 (debug analytics), ADR 0056
(on-device logging), ADR 0059 (content-blind API errors), ADR 0060 (debug client
errors), and Proposed ADR 0061 (routine execution/private-content boundary). It
does not widen any existing SWIP consent, release-build, or vendor scope.

## Context

A provider-owned routine crosses several systems Dayfold cannot fully inspect:
Dayfold enrollment, Claude/ChatGPT UI, provider-to-Google OAuth, provider-to-Dayfold
OAuth, a K1/K3 gateway, connected-source reads, model analysis, validation,
staging/apply, and provider-managed scheduling. A generic `failed` state cannot tell
the owner whether to retry, reconnect a source, refresh a conflicted draft, or wait.
It also cannot distinguish a provider task that reported failure from one that was
never observed.

The current observability posture cannot simply be reused:

- client SWIP analytics/errors and the bug reporter are debug/dogfood-only;
- release client logging is an unscrubbed, on-device WARN+ fallback;
- the API SWIP error source grants server-side consent and retains exception
  messages because ADR 0059 assumes a content-blind API;
- a routine gateway processes selected decrypted Dayfold content and source-derived
  material, invalidating that content-blind assumption.

The product needs durable recovery state and operational evidence without making
family content, source metadata, or stable family/resource identifiers telemetry.

## Proposed decision

### 1. Durable Dayfold records are the recovery source of truth

Persist enrollment attempts, run phase/outcome, per-source status, safe counts,
timestamps, idempotency identity, and closed recovery guidance in Dayfold-controlled
storage. The app renders those records and the last successful result.

PostHog, logs, Sentry, and provider histories are lossy diagnostics. They never own
the product state machine and their absence never prevents recovery.

### 2. Use one closed error/recovery envelope

Every incomplete or failed enrollment/run/revoke carries:

- closed `phase`, `reason`, `retryability`, and `recommendedAction` enums;
- an opaque per-attempt/run trace ID and timestamps;
- content-free counts and per-source status enums.

Provider exception text, source titles/bodies/URLs/IDs, prompts/model output,
changesets, OAuth codes/tokens, family/member IDs, hub/resource IDs, schedule text,
and support codes never enter this envelope. Clients map enums to owned localized
copy and show a generic recovery state for an unknown value.

Retries, callbacks, finish, apply, and revoke are idempotent. Transient failures may
retry with bounded backoff; denial, scope/context mismatch, policy rejection, and
conflict require explicit user action. Active failures preserve the last successful
briefing and receipt. A confirmed zero-result source query and a `no_changes` run are
success, not failure.

### 3. Keep expected outcomes out of Sentry

OAuth cancellation/denial, expired enrollment, offline, provider quota, connector
reauthorization, admin block, source syncing, gateway/rate-limit timeout,
validation/policy rejection, optimistic conflict, partial source set, and no-change
are expected domain outcomes. They are durable run states plus bounded analytics/
logging signals; they are not Sentry defects.

Sentry is reserved for unhandled exceptions and violated invariants.

### 4. Give the K3 gateway its own generated SWIP source

Do not copy ADR 0059's API configuration. The gateway source must:

- use `stripMessage=true`;
- attach no request URL/query/headers/body, response body, or `extra` fields;
- permit only closed tags such as component, phase, provider enum, release,
  environment, and reason=`internal`;
- use a separately verified Sentry project/source identity if Sentry is enabled;
- flush through a bounded, behavior-neutral path appropriate to the gateway
  lifecycle;
- prove through product-owned canaries that content, source metadata, auth
  material, stable family/resource identifiers, and model output never reach any
  diagnostic sink; full trace/support codes never reach SWIP, PostHog, Sentry,
  crash artifacts, or provider-visible diagnostics. Only a non-resolvable ephemeral
  suffix may appear in Dayfold-controlled local gateway logs.

If the gateway implementation language cannot use the existing generated SWIP
runtime safely, it emits the same closed internal contract to a Dayfold-controlled
adapter. It does not hand-wire a vendor SDK or loosen the schema.

### 5. Add a dogfood-only, closed analytics slice

The initial SWIP/PostHog events cover setup, provider handoff, Dayfold grant,
enrollment completion, first run, resume/cancel, recovery action, draft review,
pause, and confirmed revoke. Properties are approved closed enums, counts, and
duration buckets only. No stable IDs, source identities, support codes, free text,
or content.

This slice remains debug/dogfood-only under ADR 0055/0060. Collection from a
non-operator family requires a separately accepted release-scope ADR, privacy-policy
disclosure, and a real consent surface wired to SWIP `CollectionMode`. API analytics
remain denied.

### 6. Logging is structured and value-poor

Routine client/gateway logs use stable milestone names and closed phase/outcome,
safe counts, duration buckets, and at most an ephemeral local trace suffix. Callers
never interpolate provider responses, source metadata, prompts, changesets, or auth
material. Until a scrubbed release client sink is separately approved, release
routine log calls remain value-free and do not rely on the existing unscrubbed
fallback to remove PII.

### 7. Support uses a content-free code

The app may expose **Copy support code**, derived from an opaque enrollment-attempt
or run trace. Dayfold-controlled storage maps the code to the durable run record.
The copied value contains no family/resource identifier or content and is not sent
to analytics/error vendors.

The current SWIP bug reporter remains debug-only/on-device. Any upload or real-user
diagnostic sharing needs its own consent/disclosure decision.

## Consequences

### Positive

- Every sad path has an honest owner action and can recover without vendor telemetry.
- Product analytics measures funnel/recovery shape without source or family content.
- Expected provider/network conditions do not pollute Sentry defect triage.
- Gateway exceptions cannot reuse the API's unsafe-for-plaintext message posture.
- Cross-system support can correlate a report to a durable run without exposing a
  stable family/resource ID to the user or telemetry vendors.

### Negative

- Enrollment/run persistence, error enums, client copy mapping, generated SWIP
  schemas, gateway configuration, and leak-canary tests add implementation work.
- Count/enum-only analytics deliberately cannot answer content-level quality
  questions; dogfood review must use user-visible drafts/receipts instead.
- Real-user analytics/errors remain unavailable until consent/disclosure work is
  separately accepted and designed.
- Separate API/client/gateway Sentry sources add vendor configuration and smoke-test
  maintenance if gateway Sentry is enabled.

### Explicitly rejected

- **Use Sentry as the run history.** Delivery is lossy and the vendor is not product
  state.
- **Send raw provider errors for easier debugging.** They may contain source content,
  URLs, account details, or tokens and are not stable UX contracts.
- **Treat every failed run as an exception.** Auth, quota, conflict, partial source,
  and policy rejection are expected outcomes.
- **Reuse ADR 0059 unchanged.** Its content-blind and `stripMessage=false` argument
  does not hold in the gateway.
- **Identify analytics with family/member/routine IDs.** The dogfood slice stays
  anonymous/count-only and cannot become a shadow family activity log.
- **Turn on release collection because the feature exists.** Consent and disclosure
  remain explicit gates.

## Acceptance gates

Before this ADR can be Accepted:

1. Proposed ADR 0061's execution boundary is accepted or this ADR is reconciled to
   its replacement.
2. Two fresh-context reviews cover recovery completeness and privacy/telemetry
   leakage, then simplification/maintenance.
3. The generated event/error schemas and allowlisted fields are reviewed.
4. Canary and routing tests prove expected outcomes stay out of Sentry and private
   content stays out of every diagnostic sink.
5. Dogfood smoke confirms durable run recovery when SWIP/PostHog/Sentry are absent.
6. Any non-operator collection waits for a separate accepted release-scope consent/
   disclosure ADR and signed-off UI.

## Revisit triggers

- SWIP gains a generally available release consent/diagnostics surface.
- The gateway implementation/runtime or hosting model changes.
- Product support needs user-submitted diagnostic upload rather than a support code.
- A provider offers a reliable task/run-status API that changes what Dayfold can
  observe directly.
- Dogfood cannot diagnose routine failures within the operator's maintenance budget
  using the content-free record and telemetry contract.
