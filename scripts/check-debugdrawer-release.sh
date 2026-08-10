#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
apps_dir="$repo_root/apps"
bundle="$apps_dir/androidApp/build/outputs/bundle/release/dayfold-android-release.aab"
dependency_report="$(mktemp)"
trap 'rm -f "$dependency_report"' EXIT

(
  cd "$apps_dir"
  # Gradle 9.4.1 can close included-build project services while storing the
  # configuration for this report, then fail creating an unrelated iOS link task.
  # This diagnostic is cheap and deterministic without the configuration cache.
  ./gradlew --no-daemon --no-configuration-cache --console=plain \
    :androidApp:dependencies --configuration releaseRuntimeClasspath "$@"
) >"$dependency_report"

if ! grep -Fq 'com.sloopworks.debugdrawer:debugdrawer-noop:' "$dependency_report"; then
  echo "release classpath is missing the shared debugdrawer-noop dependency" >&2
  exit 1
fi

if banned_dependencies="$(
  grep -E 'com\.sloopworks\.debugdrawer:debugdrawer(-redux|-swip)?:' "$dependency_report" || true
)" && [[ -n "$banned_dependencies" ]]; then
  echo "release classpath contains a real shared debug drawer dependency:" >&2
  echo "$banned_dependencies" >&2
  exit 1
fi

if [[ ! -f "$bundle" ]]; then
  echo "release bundle not found: $bundle (run :androidApp:bundleRelease first)" >&2
  exit 1
fi

# The noop facade intentionally retains a small API-compatible
# com.sloopworks.debugdrawer surface. These packages/classes exist only in the
# real drawer and its optional adapters, so none may reach the release DEX.
banned_dex_pattern='com/sloopworks/debugdrawer/(host|internal|panels|redux|swip)|works/sloop/swip/debug'
if leaked_symbol="$(unzip -p "$bundle" 'base/dex/*.dex' | strings | grep -E -m 1 "$banned_dex_pattern" || true)" &&
  [[ -n "$leaked_symbol" ]]; then
  echo "release bundle contains a debug-only drawer/SWIP symbol: $leaked_symbol" >&2
  exit 1
fi

echo "Shared debug drawer release check passed: noop only; no real drawer/adapters in DEX."
