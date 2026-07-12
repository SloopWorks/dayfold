#!/usr/bin/env bash
# no-direct-console gate (parity with @sloopworks/swip-logging's eslint rule).
# Client-side Kotlin must log via com.sloopworks.dayfold.client.Log, not raw
# println / android.util.Log. The ONE sanctioned console site is Log.kt (fallback).
set -euo pipefail
cd "$(dirname "$0")/.."

ALLOW="apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Log.kt"
# search production Kotlin only (skip any test sourceset dir)
FILES=$(find apps/client/src apps/ui/src apps/androidApp/src -name '*.kt' \
  | grep -viE '/(desktopTest|commonTest|androidUnitTest|androidInstrumentedTest|iosTest|test)/' \
  | grep -vxF "$ALLOW" || true)

HITS=$(echo "$FILES" | tr ' ' '\n' | grep -v '^$' | xargs -r grep -nE 'println\(|android\.util\.Log' 2>/dev/null || true)

if [ -n "$HITS" ]; then
  echo "no-direct-console: use com.sloopworks.dayfold.client.Log instead of raw println/android.util.Log:" >&2
  echo "$HITS" >&2
  exit 1
fi
echo "no-direct-console: clean"
