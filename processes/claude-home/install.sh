#!/usr/bin/env sh
# Symlink the user-level agent definitions into ~/.claude/agents/ so they apply
# in every repo on this machine. Refuses to overwrite an existing entry.
set -eu
SRC="$(cd "$(dirname "$0")/agents" && pwd)"
DEST_DIR="${HOME}/.claude/agents"
[ -d "${HOME}/.claude" ] || { printf '~/.claude not found — is Claude Code installed?\n' >&2; exit 1; }
mkdir -p "$DEST_DIR"
linked=0
for f in "$SRC"/*.md; do
  name="$(basename "$f")"
  dest="$DEST_DIR/$name"
  if [ -e "$dest" ] || [ -L "$dest" ]; then
    printf 'skip %s (exists — remove it first if you want the symlink)\n' "$dest" >&2
    continue
  fi
  ln -s "$f" "$dest"
  printf 'linked %s -> %s\n' "$dest" "$f"
  linked=$((linked + 1))
done
printf '%s agent(s) linked. Run /agents in Claude Code to confirm.\n' "$linked"
