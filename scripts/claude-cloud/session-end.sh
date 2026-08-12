#!/bin/bash
# SessionEnd hook (ADR 0066): cloud sessions only. Best-effort revocation
# of the session's device-grant credential (server-side signout + local
# file delete). A reclaimed VM may never run this — the app's device list
# and the 45-day refresh expiry are the backstops.

set -u
[ "${CLAUDE_CODE_REMOTE:-}" != "true" ] && exit 0
command -v dayfold >/dev/null 2>&1 || exit 0
dayfold logout >/dev/null 2>&1 || true
exit 0
