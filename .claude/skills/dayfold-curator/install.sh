#!/usr/bin/env sh
# Install the dayfold-curator skill globally by symlinking it into
# ~/.claude/skills/ (available in every project on this machine).
#
# Per-project install instead: copy this directory into <repo>/.claude/skills/.
set -eu

SRC="$(cd "$(dirname "$0")" && pwd)"
DEST_DIR="${HOME}/.claude/skills"
DEST="${DEST_DIR}/dayfold-curator"

mkdir -p "$DEST_DIR"

if [ -e "$DEST" ] || [ -L "$DEST" ]; then
  printf 'refusing to overwrite existing %s\n' "$DEST" >&2
  printf 'remove it first: rm -rf %s\n' "$DEST" >&2
  exit 1
fi

ln -s "$SRC" "$DEST"
printf 'linked %s -> %s\n' "$DEST" "$SRC"
printf 'restart Claude Code to pick up the skill.\n'
