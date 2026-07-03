#!/usr/bin/env bash
# CI guard: every commonMain `expect` declaration must have an `actual` in EACH platform
# source set (android/desktop/ios). Catches a missing per-target actual (e.g. a forgotten
# iosMain actual) that would otherwise only surface when that target is compiled — and CI
# does not compile iOS, so such breaks merge undetected (see #225, the rememberReduceMotion
# iOS-build break). Cheap: pure grep, runs on the ubuntu CI, no Kotlin/Native / macOS
# runner needed.
# ADR 0047 split the Compose UI (incl. the QrScanner / PlatformActions / rememberReduceMotion
# expect/actual seams) into :ui — scan BOTH modules, each against its own platform sets.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOTS=("apps/client/src" "apps/ui/src")
PLATFORMS=(androidMain desktopMain iosMain)
fail=0
total=0

for ROOT in "${ROOTS[@]}"; do
  COMMON="$ROOT/commonMain"
  [ -d "$COMMON" ] || continue   # a module may have no commonMain (skip cleanly)

  # Top-level `expect` declarations (names only). Tolerant of leading modifiers like
  # `@Composable expect fun` — the matched span starts at `expect`.
  names=$(grep -rhoE 'expect (fun|class|object|val|var|interface) [A-Za-z_][A-Za-z0-9_]*' "$COMMON" 2>/dev/null \
    | awk '{print $3}' | sort -u)
  [ -z "$names" ] && continue

  for name in $names; do
    total=$((total+1))
    for p in "${PLATFORMS[@]}"; do
      dir="$ROOT/$p"
      if [ ! -d "$dir" ]; then echo "MISSING SOURCE SET: $dir"; fail=1; continue; fi
      if ! grep -rqE "actual (fun|class|object|val|var|interface) ${name}\b" "$dir" 2>/dev/null; then
        echo "MISSING: 'expect … $name' ($ROOT) has no actual in $p"
        fail=1
      fi
    done
  done
done

if [ "$fail" -ne 0 ]; then
  echo ""
  echo "expect/actual parity FAILED — add the missing actual(s) above."
  echo "(every commonMain expect needs an actual in android/desktop/ios source sets.)"
  exit 1
fi
if [ "$total" -eq 0 ]; then echo "expect/actual check: no expects found (nothing to verify)"; exit 0; fi
echo "expect/actual parity OK — $total expect(s) covered across ${PLATFORMS[*]} in ${ROOTS[*]}"
