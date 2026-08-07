# ADR 0061: Cloud Routine Execution and Private-Content Boundary

## Status

**Proposed** 2026-08-07. Operator-gated: this changes the automation-autonomy
boundary, vendor placement, credential model, customer-data handling, E2EE threat
model, and disclosure posture. It does not take effect by merging this draft.

Design and evidence:
`docs/superpowers/specs/2026-08-07-routine-integration-design.md`.

Extends ADRs 0012 (agent-operated build), 0015/0017 (proposed E2EE and key
authenticity), 0029 (resource-scoped grants), 0030 (visibility), and 0039
(two-channel mutation spine). It does **not** supersede ADRs 0041/0042: member
intents remain key-holder-only, and direct K4 hosted processing remains forbidden
for them unless a later constitution/ADR change says otherwise.

## Context

Dayfold's intended "brains" loop is still the operator plus an agent using the
Kotlin CLI and `dayfold-curator`. Claude and OpenAI now offer remotely scheduled
agent sessions with connectors, skills/plugins, repository environments, and
background execution. Those surfaces can implement the desired daily flow:
trigger → read Dayfold + email/docs/calendar → analyze → author updates.

The existing CLI is not sufficient for an ephemeral unattended host:

- login is interactive;
- refresh tokens rotate, so a copied static secret becomes stale and can trigger
  lineage-reuse revocation;
- headless storage is a plaintext `0600` file;
- writes are sequential resource PUTs rather than a transactionally validated
  changeset;
- the curator's human propose-confirm boundary has no unattended equivalent.

The privacy question is load-bearing. A cloud model that reasons over plaintext
necessarily receives plaintext. Giving its VM the family content key goes further:
the provider becomes a durable key-holder, weakening the current K1/K3 placement
posture and expanding prompt-injection and credential blast radius.

## Proposed decision

### 1. Provider-neutral routine contract

Dayfold names the abstraction **routine**, independent of Claude/OpenAI product
names. A server-side owner-approved routine manifest defines runner type, provider
adapter, mode (`shadow|staged|bounded_auto`), expiration, source window, permitted
hubs/resources, and hard action limits.

Provider adapters are thin triggers/tool bindings. The durable contract is the
manifest + structured changeset + gateway tool surface.

### 2. K1/K3 gateway is the default placement

The family key, Dayfold long-lived credential, and decryption happen on:

- **K1:** the operator's trusted machine for dogfood; then
- **K3:** a patched, always-on Dayfold-controlled host when reliability is needed.

Claude/OpenAI scheduled sessions call narrow authenticated gateway tools. They may
receive the minimum plaintext required for the run, so the selected content is
still disclosed to that model provider. They do not receive `FCK`, its unwrap key,
the Dayfold refresh token, or gateway filesystem access.

This preserves zero-knowledge against the Dayfold server after E2EE and prevents a
single cloud-session compromise from acquiring the durable family key. It does not
claim privacy from the model provider.

### 3. Direct K4 remains an optional, inactive installation type

Direct K4 means a routine private key capable of unwrapping `FCK` lives in the
Claude/OpenAI provider's secret store and the cloud job uses the CLI directly.

K4 is not enabled by this Proposed ADR. Activating it requires all of:

- explicit operator acceptance of this or a successor ADR;
- explicit per-family opt-in and an honest provider/retention disclosure;
- a reviewed commercial/business account whose training and retention controls
  meet the chosen posture;
- a short-lived, least-privilege routine principal (never `HOUSEHOLD_SECRET` or a
  human credential);
- separate key fingerprint approval, expiry, rotation, and revocation;
- no member-intent/W3 processing until ADR 0041/0042 are separately superseded.

### 4. Add an asymmetric routine principal

A routine is not a human or generic CLI credential.

- Generate an Ed25519 keypair on the K1/K3 gateway.
- Owner approval binds the public-key fingerprint to one family, routine label,
  runner type, policy, resource grants, and expiry.
- Each run signs a short-lived, single-use client assertion (`routine_id`, `run_id`,
  `aud`, `iat`, `exp`, `jti`).
- The API verifies the registered key + live policy + replay state, then mints a
  five-minute access token. There is no refresh token.
- Access continues to resolve credential grants and routine policy server-side on
  every request; revocation is effective on the next request.
- Key rotation repeats owner fingerprint approval per ADR 0017.

### 5. Make unattended authority structural

The rollout modes are:

1. **shadow:** read + validate only; no Dayfold content write;
2. **staged:** create a changeset that a human approves per run;
3. **bounded_auto:** apply only server-enforced, pre-authorized upserts.

The first bounded-auto tier cannot delete, widen an audience, change visibility,
manage roles/invites, send messages, take external actions, or edit member-authored
blocks. Prompt text cannot widen this list.

A changeset carries stable operation IDs, base cursor/versions, source references,
and provenance. Validation enforces schema, scopes, visibility/audience
intersection, authorship, write caps, optimistic concurrency, and routine policy.
Apply is replay-safe and transactional. Audit records identifiers, counts, policy
results, and provider/run provenance — never raw email/document bodies.

### 6. Keep source OAuth with the selected provider for the pilot

The first adapter uses the user's existing provider-connected Gmail, Calendar,
Drive, and related tools. Dayfold does not receive or store those OAuth refresh
tokens and does not add a Dayfold-owned Gmail integration in this slice.

Selected source content still reaches the model provider and must be disclosed.
Whether the resulting arrangement creates additional Google policy obligations for
Dayfold is counsel-gated before a non-operator family uses it.

### 7. Use the CLI as the implementation seam

The gateway uses the Dayfold CLI/library for pull, decryption after E2EE, local
validation, and changeset staging/apply. Cloud agents receive narrow tools rather
than a shell with secrets. A local K1 routine can invoke the same operations as CLI
commands.

The direct provider adapters do not get separate business logic or content models.

## Consequences

### Positive

- One provider-neutral path supports Claude, OpenAI, a local scheduler, or a future
  runner.
- Existing CLI, grants, visibility, curator content model, and provenance are
  reused.
- Long-lived credentials and `FCK` stay outside third-party job VMs by default.
- The Dayfold server can remain zero-knowledge after E2EE.
- Unattended writes gain an enforceable policy/transaction/audit boundary.
- The first dogfood slice can ship as read-only shadow mode before E2EE or new UI.

### Negative

- K3 adds an always-on controlled component and some operator maintenance.
- The model provider still processes selected family plaintext; this is disclosure,
  retention, and vendor-trust work, not solved by the gateway.
- Routine-principal enrollment/revocation and run review eventually need designed,
  operator-approved UI (ADR 0008).
- Changeset apply, audience intersection, run audit, and key lifecycle are new API
  work.
- Multi-provider support remains integration work even with a shared contract.

### Explicitly rejected

- **Put `HOUSEHOLD_SECRET` in a cloud secret.** Family-wide, legacy, non-expiring
  enough to be the wrong blast radius and bypasses the routine identity/policy.
- **Copy the current CLI refresh token into each job.** Rotation makes ephemeral
  runs race/stale and reuse detection can revoke the lineage.
- **Claim the key is private because it is encrypted at rest.** The workload must
  decrypt/use it, so the provider/control plane remains in the trust boundary.
- **Let the prompt implement propose-confirm.** Authorization must be enforced by
  policy and API gates.
- **Build Dayfold Gmail OAuth for the pilot.** It crosses the restricted-scope/CASA
  wall before the routine value is proven.
- **Build Claude and OpenAI adapters simultaneously.** Prove the gateway/changeset
  contract with one adapter; portability comes from the contract.

## Acceptance gates

Before this ADR can be Accepted:

1. Two fresh-context adversarial review rounds: correctness/security, then
   simplification/maintenance.
2. Operator selects K3-gateway-first vs. direct K4 and the first provider spike.
3. Privacy/counsel review covers model-provider disclosure, retention, connected
   Google sources, and second-family use.
4. ADR 0017 key-authenticity details are reconciled with routine enrollment.
5. The first auto-write action matrix and policy constants are operator-ratified.
6. UI work remains gated on ADR 0008 mockups/sign-off.

## Revisit triggers

- A provider offers attested confidential compute that can prove the approved
  workload/key boundary.
- K3 steady-state maintenance exceeds the operator's two-hour/week target.
- Connected-source quality makes one provider materially non-portable.
- E2EE ADRs 0015/0017 are accepted and change the key distribution shape.
- W3 member intents are routed through a hosted routine (requires a separate
  constitution/ADR decision).
- Dogfood shows staged changesets are sufficient and bounded auto-write is not worth
  its risk/maintenance.
