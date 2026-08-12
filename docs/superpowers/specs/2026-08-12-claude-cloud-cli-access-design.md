# Claude Cloud Session ↔ Dayfold CLI Access — Design

**Date:** 2026-08-12 · **Status:** Design accepted into Proposed ADR 0066
(operator-gated) · **Author:** agent session, operator-directed request:
*"enable a Claude cloud environment to read and write with the dayfold CLI
to my personal dayfold account, as a generic reusable process anyone can
use."*

Companion artifacts: `adr/0066-claude-cloud-session-scoped-cli-access.md`
(the decision), `processes/claude-cloud-setup.md` (the reusable setup
runbook), `scripts/claude-cloud/*` + `.claude/settings.json` (the shipped,
inert-until-configured mechanics).

---

## 1. Problem

Claude Code on the web (claude.ai/code, "Claude cloud") runs sessions in
ephemeral, Anthropic-managed VMs. The operator wants those sessions to run
the `dayfold` CLI — `pull`, `push`, `delete`, `responses`, the curator
skill's whole authoring loop — against their own family account, and wants
the setup to be a **generic, repeatable recipe** any Dayfold user could
apply to their own account, not a one-off hack for the operator's machine.

Constraints that make this non-trivial:

| Fact | Source | Consequence |
|---|---|---|
| Cloud env vars are **plain-text**; docs explicitly say "don't add API keys or other credentials" — no secrets store exists | code.claude.com/docs/en/cloud-environments | No durable Dayfold secret may live in the environment config |
| Session VMs are **fresh + ephemeral**; `$HOME` never persists | same | `~/.config/dayfold/credentials.json` cannot carry over between sessions |
| CLI refresh tokens **rotate**, with lineage-reuse revocation | `apps/api/src/auth/refresh.ts`; ADR 0011 | A copied credential file goes stale and can revoke the whole lineage — ADR 0061 explicitly rejects this |
| `HOUSEHOLD_SECRET` legacy env works headlessly | `apps/cli` Main.kt legacy branch | Family-wide, non-expiring, no policy — ADR 0061 explicitly rejects this too |
| `dayfold login` is an interactive RFC 8628 device grant (code + owner approval on phone, 600 s window, per-hub scoping per ADR 0029) | `apps/api/src/auth/device.ts`, `apps/cli` | Interactive ≠ blocked: a cloud session **has a human present** who can approve on their phone |
| Repo-committed `.claude/settings.json` hooks and env **setup scripts** run in cloud sessions; setup scripts are cached ~7 days, SessionStart hooks run every session | cloud-environments docs | The repo can bundle install/verify/teardown mechanics |
| The CLI ships a continuously-rebuilt tarball at a **stable URL** (`releases/download/cli-edge/dayfold-edge.tar`, ADR 0037); Java is preinstalled on the cloud image | ADR 0037, release-cli-edge.yml | Install needs one `curl` + `tar`, no Homebrew tap (which doesn't exist yet — ADR 0031 pending) |

## 2. The distinction that unlocks the design

Proposed ADR 0061 (INB-34, open) analyzed "cloud jobs holding Dayfold
credentials" and rejected every static-secret shape — but its subject is
**unattended scheduled routines**: nobody present, credentials parked in a
provider secret store, prompt text as the only propose-confirm boundary.

An interactive Claude cloud session is a **different trust shape**:

- a human (the account owner or a member) starts it and is present in chat;
- authority can be minted **per session** by the existing owner-approval
  flow on the phone — scoped (full vs. per-hub, ADR 0029) at approval time;
- the credential lives only inside one isolated, ephemeral VM and dies with
  it;
- the curator skill's propose-confirm guardrail still has a human on the
  other end for every push/delete.

So nothing ADR 0061 rejects is needed. The design below is
**session-scoped device-grant login**: zero standing secrets anywhere,
reusing only Accepted mechanisms (ADR 0011 tokens, ADR 0029 grants, the
shipped approval UI). ADR 0066 records this as its own posture and draws a
hard line: it does **not** authorize unattended access — that remains ADR
0061's (still-open) question.

## 3. Options considered

| Option | Verdict | Why |
|---|---|---|
| **A. Per-session device-grant login** (chosen) | ✅ | No API changes; no secret in env config; owner-scoped per session; revocation = existing device list; complies with every Accepted ADR |
| B. Copy `credentials.json` / refresh token into env vars | ❌ | Rotation staleness + lineage-reuse revocation; plain-text store; rejected by ADR 0061 |
| C. `HOUSEHOLD_SECRET` legacy triple in env vars | ❌ | Family-wide non-expiring shared secret in a plain-text store; rejected by ADR 0061 |
| D. New PAT-style static "agent token" kind | ❌ for now | Real API/credential-model work; still parks a durable secret in a plain-text store; if unattended access is wanted, ADR 0061 §4's asymmetric routine principal is the better-designed version of this — decide there (INB-34), don't fork the credential model here |
| E. K1/K3 gateway (cloud session calls narrow tools, gateway holds creds) | ❌ for this use case | Right shape for *unattended* routines (ADR 0061 default), but for an attended dev/authoring session it adds an always-on host + new tool surface to avoid a credential that option A already makes ephemeral and scoped |

## 4. Chosen design

### 4.1 One-time environment setup (per user; no per-user secrets)

At claude.ai/code the user creates (or edits) a cloud environment:

1. **Network access:** `Custom` allowlist containing the Dayfold API host
   (default `family-ai-dashboard.vercel.app`), `github.com` +
   `objects.githubusercontent.com` (edge tarball), and the Ubuntu archive
   (only needed if the image's Java is ever missing). `Full` also works;
   `Trusted` alone does not include the Dayfold API.
2. **Setup script:** paste `scripts/claude-cloud/environment-setup.sh`
   (or `curl` it from the repo). It installs the CLI from the stable
   `cli-edge` URL into `/opt/dayfold` and symlinks
   `/usr/local/bin/dayfold`. Cached ~7 days; failures are non-fatal
   (the SessionStart hook re-checks and can self-heal).
3. **Environment variables:** only non-secrets. `DAYFOLD_NO_UPDATE_CHECK=1`
   (also set by the hook), and optionally `DAYFOLD_API=<your deployment>`
   for self-hosted API instances. **Never a token** — the whole point.

This step is identical for every user; nothing in it is personal. That is
what makes the recipe generic: the *environment* is anonymous, and identity
enters only at step 4.3.

### 4.2 Repo-bundled session mechanics (shipped in this change)

- `.claude/settings.json` — SessionStart + SessionEnd hooks, both gated on
  `CLAUDE_CODE_REMOTE == "true"` so local sessions are untouched.
- `scripts/claude-cloud/session-start.sh` — every cloud session: exports
  `DAYFOLD_NO_UPDATE_CHECK=1` via `$CLAUDE_ENV_FILE`; if `dayfold` is
  missing (setup-script cache miss/failure) attempts the same tarball
  install user-space; runs `dayfold whoami` and prints the auth state as
  session context, including the exact login procedure for the agent to
  follow. Exit 0 always — informational, never blocking.
- `scripts/claude-cloud/session-end.sh` — best-effort `dayfold logout`
  (revokes the credential server-side and deletes the local file).

Any other repo can copy `.claude/settings.json` + `scripts/claude-cloud/`
verbatim — nothing in them is dayfold-repo-specific beyond the CLI itself.

### 4.3 Per-session sign-in (the only personal step)

When Dayfold work is requested and `whoami` shows no credential:

1. Agent runs `dayfold login --allow-env-key` **in background** (the
   command prints the user code + verification URL immediately, then polls
   up to 600 s). `--allow-env-key` is required: the VM has no OS keychain,
   so the refresh token lands in the `0600` credentials file — acceptable
   because the VM is isolated and ephemeral (same class as the CLI's
   existing documented headless posture).
2. Agent relays the `user_code` and `verification_uri_complete` to the
   user in chat. QR is auto-skipped (no TTY) — the code + URL are the UX.
3. User (or their family owner — approval requires `role=owner` with an
   `app` credential, per S6-D) approves on the phone, choosing scope:
   **"All content"** for full curator workflows, or **per-hub** for
   narrower sessions (see §5 caveat).
4. Agent confirms with `dayfold whoami` (family id + resolved grants) and
   proceeds. All existing guardrails apply unchanged: curator
   propose-confirm before every push/delete, ADR 0064 response rules,
   per-hub roles (ADR 0053).

### 4.4 Credential end-of-life (defense in depth)

1. **SessionEnd hook** runs `dayfold logout` — server-side revocation.
   Best-effort: a reclaimed/killed VM may skip it.
2. **In-app device list** (`Settings → Devices`, `DELETE
   /auth/me/credentials/:id`) — the user can revoke any session credential
   any time; the CLI's UA-derived label identifies it.
3. **Absolute expiry** — refresh lineage dies at 45 days regardless
   (ADR 0011); access tokens are 5 minutes.
4. **Reuse detection stays armed** — the credential never leaves the VM,
   so lineage-reuse revocation (the thing that breaks *copied* creds)
   remains a tripwire, not a hazard.

## 5. Known gaps and follow-ups (recorded, not built here)

1. **Per-hub grants can't run `dayfold pull` / `responses` /
   `changeset diff`** — those endpoints require family-wide
   `content:read`, so least-privilege per-hub sessions conflict with the
   curator's read-before-author loop (`pull --hub <id>` works; bare `pull`
   doesn't). Follow-up API option: let a hub-scoped credential get a
   grants-filtered `pull`/`responses`. → `context/open-questions.md`
   (OQ-hub-scoped-read).
2. **`content:delete` needs a blanket approval** (per-hub approvals never
   mint it) — already documented in the curator skill; surfaced here
   because scoped cloud sessions will hit it.
3. **Login ergonomics for agents:** a `--label` flag (so the device list
   shows "claude-cloud 2026-08-12" instead of a UA string) and a
   `login --json` machine-readable output would polish the flow. Small CLI
   PRs, not gating.
4. **Grant TTL at approval** ("approve for 24 h") would close the
   SessionEnd-may-not-fire gap tighter than the 45-day backstop — an ADR
   0029 revisit-trigger item.
5. **Unattended/scheduled cloud sessions** (no human to approve) remain
   governed by Proposed ADR 0061 / INB-34 — this design deliberately does
   not touch them, and its artifacts must not be repurposed into one (no
   parking a minted credential in env config to skip tomorrow's approval).

## 6. Privacy / guardrail check

- **Model-provider disclosure:** family content already flows through
  Anthropic when the operator uses local Claude Code + the curator skill —
  the intended MVP authoring path (ADR 0007, `docs/architecture.md`). A
  cloud session is the same provider and the same content; what changes is
  credential placement (ephemeral Anthropic VM vs. operator laptop), which
  is exactly what ADR 0066 exists to ratify. M0 is plaintext; E2EE (ADRs
  0015/0017) remains Proposed and unaffected.
- **No restricted-scope data** (guardrail 3): the CLI touches only the
  Dayfold content API; no Gmail/Calendar OAuth is involved.
- **No children's accounts, no pricing, no external actions** implicated.
- **Second-family use:** the recipe is generic by design, but *offering*
  it to non-operator families inherits ADR 0061's disclosure/counsel gates
  — flagged in the ADR, operator-gated.
