# Dayfold CLI

Standalone Gradle/JVM module (`dayfold-cli`) — the content-authoring client that
external AI loops, scheduled tasks, and the operator use to `push`/`pull`/`delete`
Dayfold cards and hubs through the content API. Kotlin, no other language/runtime
dependency; ships as a Homebrew-installable `dayfold` binary (see `homebrew/`).

## Build / test

```
cd apps/cli
./gradlew build     # compile + test
./gradlew test       # test only
```

JDK 17+ toolchain (`build.gradle.kts`); Gradle auto-provisions it if missing.

## Local routine shadow loop

`changeset` is an output-only, create-card proposal tool. It has no push/apply
branch and prints counts or stable `path code` diagnostics rather than content:

```sh
dayfold changeset validate routine-manifest.json routine-changeset.json
DAYFOLD_NO_UPDATE_CHECK=1 dayfold changeset diff \
  routine-manifest.json routine-changeset.json --current pull.json
```

`--current` keeps diff entirely offline. Without it, diff uses the existing
credential for exactly one GET of the family card collection; a hub-only
credential fails closed because the current API requires family-wide
`content:read` for cards. This bounded path never widens access, calls PUT or
DELETE, refreshes the session, or rotates local credentials. A 401 fails closed
with explicit `dayfold login` guidance.

The safe unattended recipe is: sanitized operator-owned source records plus
`dayfold pull` → dayfold-curator no-push changeset output → `changeset validate`
→ `changeset diff`. No step in that recipe invokes `push` or applies a result.

## Where to look next

- **`dayfold help`** (or `dayfold <command> --help`, or `--json` for machine-
  readable output) is the canonical, always-current command reference — every
  flag/arg/example is enforced by `HelpTest.kt`. Prefer it over any doc below if
  they ever disagree.
- **`templates/README.md`** — the content-authoring reference: the 6 card types,
  the hub-tree model, block-payload field tables.
- **`examples/README.md`** — worked example pushes (a full hub tree, a feed of
  cards) you can copy from or push as-is against a test family.
- **`../../.agents/skills/dayfold-curator/`** — the agent skill that wraps this
  CLI for agent-authored content (onboarding, hub authoring, guardrails).
  Harness-neutral: Claude Code reads it through the
  `.claude/skills/dayfold-curator` symlink, Codex reads `.agents/skills`
  directly.
- **`../../processes/cli-release.md`** — cutting a release (tag, Homebrew tap).

## Layout

```
src/main/kotlin/   Main.kt (entry point + HTTP), Help.kt (command registry),
                    RoutineContract.kt / RoutineDiff.kt (strict local shadow),
                    Credentials.kt / SecretStore.kt (auth storage), Validate.kt
                    (local pre-push checks), ChecklistStamp.kt, Linkify.kt,
                    Update.kt, Qr.kt
src/main/resources/templates/  starter JSON bodies for `dayfold template <type>`
src/test/kotlin/    mirrors src/main/kotlin/, plus example-fixture regression tests
examples/           worked hub-tree + feed pushes
homebrew/            the `dayfold.rb` formula (source of truth; release-cli.yml
                     copies it to the SloopWorks/homebrew-tap repo on tag push)
```
