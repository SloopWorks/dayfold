#!/bin/bash
# SessionStart hook (ADR 0066): cloud sessions only. Verifies the dayfold
# CLI install (self-healing a setup-script cache miss), silences the update
# nudge, and prints the current auth state + login procedure as session
# context. Informational only — always exits 0, never blocks the session.

set -u

# Local (non-cloud) sessions: do nothing.
[ "${CLAUDE_CODE_REMOTE:-}" != "true" ] && exit 0

# Persist env for subsequent Bash commands in this session.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "DAYFOLD_NO_UPDATE_CHECK=1" >> "$CLAUDE_ENV_FILE"
fi
export DAYFOLD_NO_UPDATE_CHECK=1

# Self-heal a missing install (setup script may have been skipped/failed).
if ! command -v dayfold >/dev/null 2>&1; then
  bash "${CLAUDE_PROJECT_DIR:-.}/scripts/claude-cloud/environment-setup.sh" >/dev/null 2>&1 || true
fi

if ! command -v dayfold >/dev/null 2>&1; then
  cat <<'EOF'
[dayfold] CLI is NOT installed and self-install failed (likely the network
allowlist is missing github.com / objects.githubusercontent.com, or no JVM).
Dayfold commands are unavailable this session. See
processes/claude-cloud-setup.md §4 (Troubleshooting).
EOF
  exit 0
fi

WHOAMI_OUT="$(dayfold whoami 2>&1)"
if [ $? -eq 0 ] && ! printf '%s' "$WHOAMI_OUT" | grep -q "(legacy)"; then
  cat <<EOF
[dayfold] CLI installed and signed in: ${WHOAMI_OUT}
Curator rules apply: propose-confirm before every push/delete.
EOF
else
  cat <<'EOF'
[dayfold] CLI installed, NOT signed in (fresh session VM — expected).
When Dayfold work is requested, sign in per ADR 0066 / processes/claude-cloud-setup.md §2:
  1. Run `dayfold login --allow-env-key` in the BACKGROUND (it prints a
     user code + verification URL immediately, then polls up to 600s).
  2. Relay the code and URL to the user in chat; a family owner approves
     on their phone (choosing All-content or per-hub scope).
  3. Confirm with `dayfold whoami`, then proceed (propose-confirm before
     every push/delete).
Never place Dayfold tokens in environment variables; never copy
credentials between machines. If `whoami` reported "(legacy)", legacy env
vars (DAYFOLD_API/FAMILY_ID/HOUSEHOLD_SECRET) are set and must be removed
from the environment config — that path is forbidden in cloud (ADR 0066).
EOF
fi
exit 0
