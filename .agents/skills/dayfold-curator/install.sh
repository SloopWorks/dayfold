#!/usr/bin/env sh
# Install the dayfold-curator skill for every project on this machine, by
# symlinking it into each supported agent harness's personal skills root:
#
#   Claude Code  ~/.claude/skills/
#   Codex        ${CODEX_HOME:-~/.codex}/skills/
#
# A root whose parent directory doesn't exist is skipped (that harness isn't
# installed here) — not an error unless none is found.
#
# In THIS repo no install is needed: the skill lives at
# `.agents/skills/dayfold-curator/` (Codex's repo skills root) and
# `.claude/skills/dayfold-curator` is a symlink to it. To vendor it into
# another repo, copy this directory into that repo's `.agents/skills/`.
set -eu

SRC="$(cd "$(dirname "$0")" && pwd)"
linked=0

for DEST_DIR in "${HOME}/.claude/skills" "${CODEX_HOME:-${HOME}/.codex}/skills"; do
  # Parent missing => that harness isn't installed on this machine.
  [ -d "$(dirname "$DEST_DIR")" ] || continue

  DEST="${DEST_DIR}/dayfold-curator"
  mkdir -p "$DEST_DIR"

  if [ -e "$DEST" ] || [ -L "$DEST" ]; then
    printf 'refusing to overwrite existing %s\n' "$DEST" >&2
    printf 'remove it first: rm -rf %s\n' "$DEST" >&2
    exit 1
  fi

  ln -s "$SRC" "$DEST"
  printf 'linked %s -> %s\n' "$DEST" "$SRC"
  linked=$((linked + 1))
done

if [ "$linked" -eq 0 ]; then
  printf 'no supported agent harness found (looked for ~/.claude and ~/.codex)\n' >&2
  exit 1
fi

printf 'restart your agent to pick up the skill.\n'
