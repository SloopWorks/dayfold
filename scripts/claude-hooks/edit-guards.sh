#!/usr/bin/env bash
# Claude Code PostToolUse hook (Edit|Write|MultiEdit): deterministic reminders
# for the drift classes that have repeatedly shipped to `main` in this repo.
# Wired from .claude/settings.json. Exit 2 = feed stderr back to Claude as a
# follow-up instruction; exit 0 = nothing to say. Never blocks (the edit has
# already happened) and never touches files. Documented in processes/subagents.md.
set -uo pipefail

input=$(cat)
if command -v jq >/dev/null 2>&1; then
  path=$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty' 2>/dev/null)
else
  path=$(printf '%s' "$input" | sed -n 's/.*"file_path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
fi
[ -n "$path" ] || exit 0

root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
rel="${path#"$root"/}"

msgs=()
case "$rel" in
  *sqldelight*/*.sq)
    msgs+=(".sq edited ($rel): add a companion migrations/<N>.sqm (new columns at END of table) and run 'cd apps && ./gradlew :client:verifyCommonMainContentDbMigration' — see kmp-verifier / backlog/now.md 2026-08-21.") ;;
esac
case "$rel" in
  apps/ui/src/desktopTest/resources/snapshots/macos/*)
    msgs+=("macOS golden changed ($rel): CI compares snapshots/linux/ — re-record the Linux set too (processes/agent-dev-loop.md 'rk snapshot').") ;;
  apps/ui/src/desktopTest/resources/snapshots/linux/*)
    msgs+=("Linux golden changed ($rel): keep snapshots/macos/ in step.") ;;
esac
case "$rel" in
  packages/schema/*.schema.json|specs/domain-model/schemas/*.schema.json|packages/schema/codegen.mjs)
    msgs+=("Schema changed ($rel): 'npm run codegen' (commit TS + Content.kt outputs) then 'cd apps/api && npm run build:fn' (commit api/index.js) — api-verifier checks both.") ;;
esac
case "$rel" in
  apps/api/src/*.ts|apps/api/src/*/*.ts|apps/api/src/*/*/*.ts)
    case "$rel" in apps/api/src/generated/*) ;; *)
      msgs+=("API source changed ($rel): rebuild + commit the Vercel bundle — 'cd apps/api && npm run build:fn' (api/index.js).") ;;
    esac ;;
esac
case "$rel" in
  apps/api/migrations/*.sql)
    msgs+=("Migration touched ($rel): sequential numbering, ADR 0033 runner semantics; prod apply is operator-gated.") ;;
esac
case "$rel" in
  apps/cli/src/main/kotlin/*)
    msgs+=("CLI source changed ($rel): if a command/flag changed, update .agents/skills/dayfold-curator/references/cli.md + apps/cli/templates/README.md.") ;;
esac
case "$rel" in
  adr/0[0-9][0-9][0-9]-*.md)
    case "$rel" in adr/0000-adr-template.md) ;; *)
      msgs+=("ADR edited ($rel): Accepted ADRs are immutable (status flips are fine) — supersede, don't edit; sync adr/decisions-index.md.") ;;
    esac ;;
esac
case "$rel" in
  apps/client/src/commonMain/*|apps/ui/src/commonMain/*)
    if grep -qE '^\s*expect\s+(fun|val|class|object)' "$path" 2>/dev/null; then
      msgs+=("'expect' in $rel: confirm android, desktop AND ios actuals — CI skips iOS (scripts/check-expect-actual.sh).")
    fi ;;
esac
# Reachability: only when a NEW surface appears (untracked file, or an added
# Route/Screen/Host/Action declaration) — not on every edit under features/.
case "$rel" in
  apps/ui/src/*/features/*.kt|apps/client/src/commonMain/*.kt)
    if ! git -C "$root" ls-files --error-unmatch -- "$rel" >/dev/null 2>&1 \
       || git -C "$root" diff -U0 --no-color -- "$rel" 2>/dev/null | grep -qE '^\+.*((data )?object [A-Za-z]+ ?: ?Route|: Route\b|@Composable\s+fun [A-Za-z]+(Screen|Host)\(|sealed interface [A-Za-z]*Action\b)'; then
      msgs+=("New Route/Screen/Host/Action in $rel? Wire a production caller or add a dated ReachabilityGuardTest allow-list entry (WI-462).")
    fi ;;
esac
# Silent-test gotcha: a JUnit test written as `= runBlocking {` whose last
# expression is not Unit is never run. Demand runBlocking<Unit>.
case "$rel" in
  apps/*/src/*Test*/*.kt|apps/*/src/*/kotlin/*Test.kt)
    # Only ADDED lines (or a new file) — existing tests are not re-flagged on every edit.
    if git -C "$root" ls-files --error-unmatch -- "$rel" >/dev/null 2>&1; then
      hits=$(git -C "$root" diff -U0 --no-color -- "$rel" 2>/dev/null | grep -cE '^\+.*=\s*runBlocking\s*\{')
    else
      hits=$(grep -cE '=\s*runBlocking\s*\{' "$path" 2>/dev/null)
    fi
    if [ "${hits:-0}" -gt 0 ]; then
      msgs+=("$hits new '= runBlocking {' test(s) in $rel — use 'runBlocking<Unit> {' or JUnit may silently skip them; check the test COUNT moved.")
    fi ;;
esac
# Toolchain pins are restated in processes/agent-dev-loop.md and .shipyard.yaml.
case "$rel" in
  apps/gradle/wrapper/gradle-wrapper.properties|apps/cli/gradle/wrapper/gradle-wrapper.properties|apps/build.gradle.kts|apps/gradle.properties|apps/settings.gradle.kts|.github/actions/setup-jvm/action.yml|apps/api/package.json)
    msgs+=("Pin file changed ($rel): update processes/agent-dev-loop.md 'Toolchain' and .shipyard.yaml 'constraints' to match.") ;;
esac

[ "${#msgs[@]}" -gt 0 ] || exit 0
printf 'edit-guards (repo drift reminders):\n' >&2
for m in "${msgs[@]}"; do printf -- '- %s\n' "$m" >&2; done
exit 2
