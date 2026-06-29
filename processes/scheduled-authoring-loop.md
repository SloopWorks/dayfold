# Scheduled Authoring Loop — Option A (legacy household token)

> **Goal:** let a scheduled task in a cloud environment (Claude Code on the web /
> GitHub Actions / any cron) run the `dayfold` CLI to author + update content,
> unattended.
>
> **Option A = use what's already shipped.** No app code changes. It rides the
> existing **legacy household-token** auth path the CLI and API already support
> (`07-cli.md` calls this path the enabler for "scheduled-task/loop authoring").
> Verified end-to-end in this repo on 2026-06-29 (build-from-source + legacy
> `whoami` gate; the M0 deploy already proved `push → 200` on this token).
>
> **This is a stopgap.** The legacy branch is marked `TODO(S3-cutover)` in
> `apps/api/src/auth/middleware.ts` and is effectively single-tenant. The durable
> replacement is a first-class machine credential + (optionally) a hosted MCP
> surface — both ADR-gated, deferred. See **§5 Caveats / migration**.

## Why the device-login path can't be used here

`dayfold login` mints a **5-min access token + 45-day _rotating_ refresh token**.
The refresh token changes on every call and must be persisted to durable storage
between runs. Cloud scheduled environments are **ephemeral** (container reclaimed,
repo re-cloned), so a rotated token written this run is gone next run → the loop
dies. The legacy household token is **static and non-rotating**, so it survives a
fresh container with nothing but three env vars.

## The pieces

| Piece | Where | What it needs |
|---|---|---|
| `dayfold` CLI | the cloud task's container | JDK 17 to build (or a JRE if a release tarball exists) + the three env vars below |
| Content API | Vercel (`family-ai-dashboard`) | `HOUSEHOLD_SECRET` **and** `HOUSEHOLD_CREDENTIAL_ID` set, matching a non-revoked `credentials` row scoped to the family |
| The household token | a secret store | minted once by `apps/api/scripts/provision.mjs`; **already provisioned at M0** for `fam_b47fc75bfb5a` |

The CLI's legacy env path (`Main.kt`) sends `HOUSEHOLD_SECRET` straight as the
bearer token; the API's `authorizeTenant` legacy branch constant-time-compares it
to `process.env.HOUSEHOLD_SECRET` and loads `process.env.HOUSEHOLD_CREDENTIAL_ID`
for scope + family + revocation. **Both** server env vars must be present, or every
write 401s.

## Step 1 — One-time: the household token (operator)

A token was already minted at M0 deploy (`fam_b47fc75bfb5a`) and `HOUSEHOLD_SECRET`
is live in Vercel. **Reuse it** if you still have the secret stored.

If you need a fresh one (lost the secret, or a second household), mint it — this
touches the prod Neon DB and creates a long-lived `content:write` token, so it is
**operator-only**:

```bash
DATABASE_URL=<neon-pooled-url> node apps/api/scripts/provision.mjs "My Family"
# prints (once): FAMILY_ID, HOUSEHOLD_CREDENTIAL_ID, HOUSEHOLD_SECRET
```

Then set on the **API** (Vercel, production) so the server honors it:
`HOUSEHOLD_SECRET`, `HOUSEHOLD_CREDENTIAL_ID`. (Provisioning writes the credential
row; the env vars are what the legacy branch reads.)

## Step 2 — Configure the cloud scheduled-task environment (operator)

Set these as **secrets** in the environment that runs the scheduled task (Claude
Code on the web env vars, GitHub Actions secrets, etc.):

```
DAYFOLD_API=https://family-ai-dashboard.vercel.app
FAMILY_ID=fam_b47fc75bfb5a            # your family
HOUSEHOLD_SECRET=<the 256-bit token>  # secret — never in the repo
```

And run the CLI installer as the environment's **setup script** (it's idempotent;
builds from source since no `cli-v*` release is published yet):

```bash
bash scripts/install-dayfold-cli.sh   # → /usr/local/bin/dayfold
```

> Faster cold start later: cut a CLI release (`git tag cli-v0.1.0` → triggers
> `release-cli.yml`), then change the setup script to download the prebuilt
> `dayfold-X.Y.Z.tar` (needs only a JRE ≥17). See the NOTE in the install script.

## Step 3 — The scheduled task

Sanity check first — `dayfold whoami` must print the family with `(legacy)` (a
**non-empty** family is what passes the `dayfold-curator` prereq gate; empty =
stop):

```
family=fam_b47fc75bfb5a api=https://family-ai-dashboard.vercel.app (legacy)
scope=content:read,content:write
```

Then the task does its work. Two modes — **pick per the autonomy decision in §4:**

- **Propose-only (recommended first):** `dayfold pull` → analyze gaps → draft the
  hub/card JSON and surface it (a file artifact, a PR, a message) for operator
  review. **No `dayfold push`.** Honors the curator skill's propose-confirm rule
  with no human in the loop.
- **Auto-author:** the task runs the `dayfold-curator` skill and `dayfold push`es
  directly to the operator's own dashboard. Only appropriate for the operator's
  **own dogfood household** (not an external action), and only once the operator
  has explicitly accepted unattended pushes (see §4).

## Step 4 — Autonomy boundary (operator decision, not agent-decided)

The `dayfold-curator` skill rule is **"propose-confirm before EVERY push."** A
scheduled task has no operator present at push time. This is a real tension and an
**operator call**, because it sets an automation-autonomy boundary (a hard
guardrail / ADR-class area):

- Authoring to the operator's **own** dashboard is **not** an external action
  (no customer/prospect/vendor sees it), so the external-action guardrail doesn't
  forbid it.
- But unattended push still bypasses the skill's confirm step. Default to
  **propose-only** until the operator ratifies an unattended-push mode (ideally
  scoped: e.g. auto-push only `provenance.source="claude"` cards into a single
  hub, never deletes).

## Step 5 — Caveats & migration

- **Deprecated path.** `TODO(S3-cutover)` will remove the legacy branch once the
  CLI is fully on JWTs. Track it; this loop breaks when it lands.
- **Single-tenant.** The API honors one global `HOUSEHOLD_SECRET` /
  `HOUSEHOLD_CREDENTIAL_ID`. Fine for operator dogfood; does not scale to N
  families.
- **God-token.** The secret is a long-lived `content:write` (and read) credential
  with no expiry. Store it only in a secret store. **Revoke** by setting
  `revoked_at` on its `credentials` row (`HOUSEHOLD_CREDENTIAL_ID`) — the next
  request 401s immediately.
- **Durable replacement (deferred, ADR-gated):** a first-class machine/service
  credential (per-credential lookup, scoped, revocable, optional expiry) fronted
  by a hosted MCP server, so Claude scheduled tasks call typed `dayfold_*` tools
  with no JVM/binary/creds in the task env. Drafting that ADR is the natural next
  step when Option A has proven the loop.

## Verified (2026-06-29)

- `./gradlew installDist` builds the `dayfold` binary on JDK 17 (env had only JDK
  21 → installed 17; the build pins `jvmToolchain(17)` and won't substitute).
- `dayfold --version`, `dayfold template hub` (offline) work.
- Legacy env → `whoami` prints `family=… (legacy)` (passes the curator gate);
  no env → guides to login.
- M0 deploy previously verified the same token: `push → 200`, no-token → 401.
