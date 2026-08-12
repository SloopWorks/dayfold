# Claude Cloud ↔ Dayfold CLI — Setup Runbook

Generic, reusable recipe for giving a Claude Code on the web session
(claude.ai/code) read/write access to **your own** Dayfold account through
the `dayfold` CLI. Works for any Dayfold user; nothing in the setup is
personal — identity enters only at the per-session phone approval.

Governed by **ADR 0066** (Proposed — do not use until Accepted). Attended
sessions only: this recipe must never be bent into unattended access (no
tokens in environment variables — see the ADR's hard boundary and ADR 0061).

Design rationale + full evidence:
`docs/superpowers/specs/2026-08-12-claude-cloud-cli-access-design.md`.

---

## 1. One-time: create the cloud environment (~5 min, any user)

At **claude.ai/code → environment settings**:

1. **Network access → Custom**, allowlisting:
   - your Dayfold API host — default `family-ai-dashboard.vercel.app`
   - `github.com` and `objects.githubusercontent.com` (CLI edge tarball)
   - (`Full` also works; the default `Trusted` list does **not** include
     the Dayfold API, so bare Trusted will fail.)
2. **Setup script:** paste the contents of
   [`scripts/claude-cloud/environment-setup.sh`](../scripts/claude-cloud/environment-setup.sh).
   It installs the CLI from the stable edge URL
   (`…/releases/download/cli-edge/dayfold-edge.tar`, ADR 0037) into
   `/opt/dayfold` and links `/usr/local/bin/dayfold`. It never fails the
   environment build; the session hook self-heals a missed install.
3. **Environment variables** (non-secrets only):
   - `DAYFOLD_NO_UPDATE_CHECK=1` (optional — the hook sets it too)
   - `DAYFOLD_API=https://…` only if you run your own API deployment
   - **Never** put a Dayfold token, refresh token, or `HOUSEHOLD_SECRET`
     here. The store is plain-text and visible to anyone using the
     environment. The whole design exists so you never need to.

If your session's repo is this repo, the committed `.claude/settings.json`
hooks are picked up automatically. For **any other repo**, copy
`.claude/settings.json` (or merge its `hooks` block) plus the
`scripts/claude-cloud/` directory — they contain nothing dayfold-repo-
specific.

## 2. Every session: sign in when Dayfold work starts

The SessionStart hook prints the current auth state into the session
context. When there is no credential (every fresh VM), the agent should:

1. Run `dayfold login --allow-env-key` **in the background** — it prints
   the code immediately, then polls for up to 10 minutes:

   ```
   To sign in, visit https://<api-host>/device and enter code: XXXX-XXXX
   ```

   (`--allow-env-key` is required: the VM has no OS keychain, so the
   refresh token is stored in the ephemeral VM's `0600` credentials file.)
2. Relay the code + URL to the user in chat and wait.
3. **User:** open the link (or the app's device-approval screen) on your
   phone and approve. You must be a **family owner** signed into the
   mobile app. Choose scope at approval:
   - **All content** — full curator workflows (`pull`, `push`, `delete`,
     `responses`); the default and usually what you want.
   - **Specific hubs** — least privilege, but note: bare `dayfold pull`
     and `dayfold responses` need family-wide `content:read`, so a per-hub
     session is limited to `pull --hub <id>` + writes into those hubs, and
     `content:delete` (block deletes) is only minted by an All-content
     approval.
4. Agent verifies with `dayfold whoami` (shows family, api, and resolved
   `scope=`) and proceeds — normal curator rules apply, including
   propose-confirm before every `push`/`delete`.

Denied or timed out (600 s)? Just re-run `dayfold login`.

## 3. End of session: revocation (layered)

1. The SessionEnd hook runs `dayfold logout` (best-effort server-side
   revocation + local file delete).
2. Belt-and-braces: the credential is visible in the app under
   **Settings → Devices** (labelled `dayfold-cli <user-agent>`, one per
   cloud session) — revoke there any time, especially if a session ended
   abruptly (a reclaimed VM can skip the hook).
3. Backstop: access tokens live 5 minutes; the refresh lineage expires
   absolutely at 45 days (ADR 0011).

## 4. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `dayfold: command not found` | Setup-script cache miss/failure — the SessionStart hook retries the install; else run `bash scripts/claude-cloud/session-start.sh` or install manually with the same curl+tar |
| `login` exits 2 complaining about keychain | Missing `--allow-env-key` |
| `curl` of the tarball fails | Network allowlist is missing `github.com` / `objects.githubusercontent.com` |
| API calls fail / login 500s | Allowlist missing the API host; or self-hosted API missing `AUTH_*` env |
| `whoami` shows `(legacy)` | `DAYFOLD_API`+`FAMILY_ID`+`HOUSEHOLD_SECRET` env vars are set — remove them; ADR 0066 forbids that path in cloud |
| 403 on `push --section/--block` with correct scope | Per-hub **role** (ADR 0053) — a hub owner must raise you to Contributor/Co-owner in-app; re-login won't help |
| `pull` 403s on a per-hub session | Expected (see §2 scope note) — use `pull --hub <id>` or re-approve with All content |
