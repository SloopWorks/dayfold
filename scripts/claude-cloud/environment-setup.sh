#!/bin/bash
# Dayfold CLI install for a Claude Code cloud environment (ADR 0066).
#
# Paste this into the environment's "Setup script" at claude.ai/code (it
# runs as root on Ubuntu 24.04, cached ~7 days), or run it standalone.
# It must never fail the environment build: every step degrades to exit 0
# and the SessionStart hook (scripts/claude-cloud/session-start.sh)
# re-checks and self-heals a missed install at session time.
#
# Installs from the stable ADR 0037 edge-channel URL — no Homebrew tap
# needed. Contains no secrets and nothing user-specific by design:
# authentication happens per session via `dayfold login` (ADR 0066).

set -u

DAYFOLD_EDGE_URL="${DAYFOLD_EDGE_URL:-https://github.com/SloopWorks/dayfold/releases/download/cli-edge/dayfold-edge.tar}"
DAYFOLD_PREFIX="${DAYFOLD_PREFIX:-/opt/dayfold}"

main() {
  if command -v dayfold >/dev/null 2>&1; then
    echo "dayfold already installed: $(dayfold version 2>/dev/null || true)"
    return 0
  fi

  # The dist launcher needs a JVM (17+). The cloud image ships Java; only
  # apt-install if it's genuinely absent.
  if ! command -v java >/dev/null 2>&1; then
    apt-get update -qq && apt-get install -y -qq openjdk-17-jre-headless || {
      echo "WARN: no java and apt install failed — dayfold will not run" >&2
      return 0
    }
  fi

  local tmp
  tmp="$(mktemp -d)"
  if ! curl -fsSL --retry 3 "$DAYFOLD_EDGE_URL" -o "$tmp/dayfold-edge.tar"; then
    echo "WARN: could not download dayfold edge tarball (network allowlist?)" >&2
    return 0
  fi

  mkdir -p "$DAYFOLD_PREFIX"
  # Tarball tree: dayfold-<version>/{bin,lib}/...
  if ! tar -xf "$tmp/dayfold-edge.tar" -C "$DAYFOLD_PREFIX" --strip-components=1; then
    echo "WARN: could not extract dayfold tarball" >&2
    return 0
  fi
  chmod +x "$DAYFOLD_PREFIX/bin/dayfold"
  ln -sf "$DAYFOLD_PREFIX/bin/dayfold" /usr/local/bin/dayfold 2>/dev/null \
    || echo "WARN: could not link into /usr/local/bin — add $DAYFOLD_PREFIX/bin to PATH" >&2
  rm -rf "$tmp"

  echo "dayfold installed: $(dayfold version 2>/dev/null || echo '(installed, version check failed)')"
}

main "$@" || true
exit 0
