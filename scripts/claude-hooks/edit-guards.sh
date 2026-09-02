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
    msgs+=("SQLDelight schema edited ($rel): every table/column change needs a companion migrations/<N>.sqm so Schema.version moves — otherwise existing devices never migrate (see backlog/now.md 2026-08-21). SQLite ALTER TABLE only appends: put new columns at the END of the table in Content.sq. Run the guard TASK explicitly — 'cd apps && ./gradlew :client:verifyCommonMainContentDbMigration' — it is not part of desktopTest; or ask kmp-verifier.") ;;
esac
case "$rel" in
  apps/ui/src/desktopTest/resources/snapshots/macos/*)
    msgs+=("macOS golden changed ($rel): CI compares against snapshots/linux/. Re-record the Linux set too (docker recipe in processes/agent-dev-loop.md 'rk snapshot'), or CI goes red on GoldenSnapshotTest.") ;;
  apps/ui/src/desktopTest/resources/snapshots/linux/*)
    msgs+=("Linux golden changed ($rel): keep the macOS set in step (local agent loop uses snapshots/macos/).") ;;
esac
case "$rel" in
  packages/schema/*.schema.json|specs/domain-model/schemas/*.schema.json|packages/schema/codegen.mjs)
    msgs+=("Schema changed ($rel): run 'npm run codegen' and commit BOTH outputs (apps/api/src/generated/** and packages/schema/kotlin-gen/Content.kt), then 'cd apps/api && npm run build:fn' and commit api/index.js. CI rejects either drift. api-verifier checks this lane.") ;;
esac
case "$rel" in
  apps/api/src/*.ts|apps/api/src/*/*.ts|apps/api/src/*/*/*.ts)
    case "$rel" in apps/api/src/generated/*) ;; *)
      msgs+=("API source changed ($rel): the committed esbuild bundle apps/api/api/index.js IS the Vercel function. Run 'cd apps/api && npm run build:fn' and commit the bundle, or CI fails 'api bundle is up to date'.") ;;
    esac ;;
esac
case "$rel" in
  apps/api/migrations/*.sql)
    msgs+=("Migration added/edited ($rel): numbering must be sequential with no collision (ls apps/api/migrations | sort); runner semantics per ADR 0033 (idempotent, re-run-safe). Prod apply is operator-gated — log it in backlog/now.md.") ;;
esac
case "$rel" in
  apps/cli/src/main/kotlin/*)
    msgs+=("CLI source changed ($rel): if a command or flag changed, update .agents/skills/dayfold-curator/references/cli.md and apps/cli/templates/README.md (the curator skill only knows the commands documented there). doc-drift-auditor checks this.") ;;
esac
case "$rel" in
  adr/0[0-9][0-9][0-9]-*.md)
    case "$rel" in adr/0000-adr-template.md) ;; *)
      msgs+=("ADR edited ($rel): Accepted ADRs are immutable — supersede, don't edit (a status flip Proposed→Accepted is fine). Keep adr/decisions-index.md's row in sync.") ;;
    esac ;;
esac
case "$rel" in
  apps/client/src/commonMain/*|apps/ui/src/commonMain/*)
    if grep -qE '^\s*expect\s+(fun|val|class|object)' "$path" 2>/dev/null; then
      msgs+=("commonMain file declares 'expect' ($rel): confirm an 'actual' exists for android, desktop AND ios — CI does not compile iOS, so a missing iosMain actual merges undetected (scripts/check-expect-actual.sh).")
    fi ;;
esac
# Reachability: only when a NEW surface appears (untracked file, or an added
# Route/Screen/Host/Action declaration) — not on every edit under features/.
case "$rel" in
  apps/ui/src/*/features/*.kt|apps/client/src/commonMain/*.kt)
    if ! git -C "$root" ls-files --error-unmatch -- "$rel" >/dev/null 2>&1 \
       || git -C "$root" diff -U0 --no-color -- "$rel" 2>/dev/null | grep -qE '^\+.*((data )?object [A-Za-z]+ ?: ?Route|: Route\b|@Composable\s+fun [A-Za-z]+(Screen|Host)\(|sealed interface [A-Za-z]*Action\b)'; then
      msgs+=("New Route/Screen/Host/Action in $rel? It needs a production dispatcher or call site, or a dated ReachabilityGuardTest allow-list entry — 'built, tested, unreachable' has shipped three times (WI-462).")
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
      msgs+=("$hits new test(s) written as '= runBlocking {' in $rel — JUnit silently skips a test whose last expression is not Unit; use 'runBlocking<Unit> {' and verify the test COUNT moved.")
    fi ;;
esac
# Toolchain pins are restated in processes/agent-dev-loop.md and .shipyard.yaml.
case "$rel" in
  apps/gradle/wrapper/gradle-wrapper.properties|apps/cli/gradle/wrapper/gradle-wrapper.properties|apps/build.gradle.kts|apps/gradle.properties|apps/settings.gradle.kts|.github/actions/setup-jvm/action.yml|apps/api/package.json)
    msgs+=("Toolchain/version pin file changed ($rel): update the pins in processes/agent-dev-loop.md 'Toolchain' and .shipyard.yaml 'constraints' (doc-drift-auditor checks these four copies).") ;;
esac

[ "${#msgs[@]}" -gt 0 ] || exit 0
printf 'edit-guards (repo drift reminders):\n' >&2
for m in "${msgs[@]}"; do printf -- '- %s\n' "$m" >&2; done
exit 2
