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

## Where to look next

- **`dayfold help`** (or `dayfold <command> --help`, or `--json` for machine-
  readable output) is the canonical, always-current command reference — every
  flag/arg/example is enforced by `HelpTest.kt`. Prefer it over any doc below if
  they ever disagree.
- **`templates/README.md`** — the content-authoring reference: the 6 card types,
  the hub-tree model, block-payload field tables.
- **`examples/README.md`** — worked example pushes (a full hub tree, a feed of
  cards) you can copy from or push as-is against a test family.
- **`../../.claude/skills/dayfold-curator/`** — the Claude Skill that wraps this
  CLI for agent-authored content (onboarding, hub authoring, guardrails).
- **`../../processes/cli-release.md`** — cutting a release (tag, Homebrew tap).

## Layout

```
src/main/kotlin/   Main.kt (entry point + HTTP), Help.kt (command registry),
                    Credentials.kt / SecretStore.kt (auth storage), Validate.kt
                    (local pre-push checks), ChecklistStamp.kt, Linkify.kt,
                    Update.kt, Qr.kt
src/main/resources/templates/  starter JSON bodies for `dayfold template <type>`
src/test/kotlin/    mirrors src/main/kotlin/, plus example-fixture regression tests
examples/           worked hub-tree + feed pushes
homebrew/            the `dayfold.rb` formula (source of truth; release-cli.yml
                     copies it to the SloopWorks/homebrew-tap repo on tag push)
```
