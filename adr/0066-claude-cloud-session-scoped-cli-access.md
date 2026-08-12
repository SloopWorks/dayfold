# ADR 0066: Claude Cloud Sessions Use Session-Scoped Device-Grant CLI Credentials

## Status

**Proposed** 2026-08-12 (agent-drafted, operator-requested design). Operator-gated:
this touches credential placement and the automation-autonomy boundary. It does not
take effect by merging.

Design and evidence:
`docs/superpowers/specs/2026-08-12-claude-cloud-cli-access-design.md`.
Runbook: `processes/claude-cloud-setup.md`.

Extends ADR 0011 (hardened auth), ADR 0029 (resource-scoped grants), ADR 0037
(edge-channel CLI distribution). Composes with — and deliberately does **not**
pre-empt — Proposed ADR 0061: that ADR governs *unattended scheduled routines*;
this one governs *attended interactive cloud sessions* only.

## Context

The operator wants Claude Code on the web (claude.ai/code) sessions to read and
write their Dayfold account through the `dayfold` CLI, via a generic setup any
Dayfold user could replicate. Cloud sessions run in fresh, ephemeral,
Anthropic-managed VMs; environment variables there are plain-text with no secrets
store (the provider's docs warn against putting credentials in them); nothing in
`$HOME` persists between sessions.

Every headless credential shape the CLI supports today is unsuitable, and ADR 0061
already rejected the two obvious ones for cloud jobs: a copied refresh token goes
stale under rotation and can trip lineage-reuse revocation of the whole credential;
the legacy `HOUSEHOLD_SECRET` triple is a family-wide, non-expiring shared secret.

What ADR 0061 did not cover: an interactive session has a human present, and the
existing RFC 8628 device-grant flow (S3/S6-D, shipped) is built for exactly this —
the CLI prints a code, a family owner approves on the phone with full or per-hub
scope (ADR 0029), and the API mints a per-device credential.

## Decision

1. **Authority is minted per session via the existing device grant — never stored
   in the environment.** A cloud session that needs Dayfold access runs
   `dayfold login --allow-env-key`; the agent relays the user code + verification
   URL in chat; the user (family owner, `app` credential) approves on the phone,
   choosing scope at approval time. No Dayfold token, refresh token, credential
   file, or `HOUSEHOLD_SECRET` may ever be placed in cloud environment variables,
   setup scripts, or any provider-side store. The credential lives only in the
   session VM's `0600` credentials file (`--allow-env-key` is required — the VM
   has no OS keychain) and dies with the VM.

2. **The environment recipe is anonymous and reusable.** The one-time cloud
   environment config carries only non-secrets: a network allowlist (Dayfold API
   host + GitHub release host), a setup script that installs the CLI from the
   stable ADR 0037 `cli-edge` tarball URL, and optionally `DAYFOLD_API` for a
   self-hosted deployment. Identity enters only at per-session approval, so one
   published recipe serves every user/family.

3. **Repo-bundled hooks own install-verify and teardown.** `.claude/settings.json`
   (committed) runs `scripts/claude-cloud/session-start.sh` on every cloud session
   (gated on `CLAUDE_CODE_REMOTE=true`): self-heal the CLI install, set
   `DAYFOLD_NO_UPDATE_CHECK=1`, surface `dayfold whoami` state and the login
   procedure as session context. `session-end.sh` runs a best-effort
   `dayfold logout` (server-side revocation). Local sessions are untouched.

4. **Credential end-of-life is layered:** SessionEnd `logout` (best-effort) →
   user-visible revocation in the app's device list → 45-day absolute refresh
   expiry (ADR 0011) → reuse-detection stays armed since the credential never
   leaves the VM.

5. **Scope guidance, not new enforcement:** "All content" approval for full
   curator workflows; per-hub approval for narrower sessions, accepting the
   documented limits (bare `pull`/`responses` need family-wide `content:read`;
   `content:delete` is only minted by blanket approvals).

6. **Hard boundary — attended only.** This ADR authorizes credentials minted by a
   present human for the session they are using. It does not authorize scheduled/
   unattended cloud execution, parking a minted credential anywhere durable to
   skip a future approval, or any auto-write posture. Those remain governed by
   Proposed ADR 0061 (routine principals, K1/K3 gateway default) and INB-34.

## Consequences

### Positive

- Ships today: zero API or schema changes; reuses only Accepted mechanisms
  (ADR 0011 tokens, ADR 0029 grants, the shipped approval screen, ADR 0037 edge
  distribution).
- Zero standing secrets: nothing to leak from the plain-text env store, nothing
  to rotate, nothing that outlives the VM by more than the revocation backstops.
- Least-privilege capable per session, decided by the human at approval time.
- Generic: the same recipe works for any user, any family, any repo (the hooks
  and scripts contain nothing operator-specific).
- The curator skill's propose-confirm loop keeps a human in it — cloud sessions
  change where the CLI runs, not who authorizes writes.

### Negative

- One phone approval per session (600 s window) — deliberate friction; a
  fresh-VM session cannot silently inherit yesterday's authority.
- SessionEnd revocation is best-effort (a reclaimed VM skips it); until a grant
  TTL exists, the fallback is manual device-list revocation or the 45-day expiry.
- The session VM holds the refresh token in a plaintext `0600` file (no keychain)
  — accepted for an isolated ephemeral VM; same posture the CLI already documents
  for keychain-less hosts.
- Family plaintext is processed in the provider's VM — the same disclosure
  posture as the existing local-Claude authoring path (same provider), but it
  should be named in any future non-operator-family onboarding (counsel-gated per
  ADR 0061 before second-family use).

### Explicitly rejected

- **Any Dayfold secret in cloud environment variables or setup scripts** — the
  store is plain-text and shared with anyone who can use the environment.
- **Copying `credentials.json`/refresh tokens between machines or sessions** —
  rotation staleness + lineage-reuse revocation (per ADR 0061).
- **The legacy `HOUSEHOLD_SECRET` env path for cloud use** (per ADR 0061).
- **A new static PAT-like credential kind** — would fork the credential model to
  put a durable secret in a plain-text store; if unattended access is ever
  wanted, ADR 0061 §4's asymmetric routine principal is the designed answer.

## Acceptance gates

1. Operator accepts this ADR (INB-39).
2. Operator performs the one-time environment setup (external console action —
   operator-only per values file) following `processes/claude-cloud-setup.md`.
3. First dogfood session verified end-to-end: login relay → phone approval →
   `whoami` → one `pull` and one propose-confirmed `push` → logout/revocation
   observed in the device list.

## Revisit triggers

- ADR 0061 is accepted in a form that supersedes attended-session posture, or
  provider secrets stores / grant TTLs ship and change the trade-offs.
- Dogfood shows per-session approval friction pushes users toward credential
  parking (the exact anti-pattern this ADR forbids) — then design the TTL'd
  grant or routine principal instead of loosening this.
- A non-operator family wants the recipe — triggers the disclosure/counsel gates.
- The CLI gains `--label` / `login --json` / hub-scoped reads (§5 gaps), which
  would simplify the runbook.
