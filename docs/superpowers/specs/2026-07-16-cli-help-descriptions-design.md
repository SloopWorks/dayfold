# dayfold CLI — complete, per-command help

**Date:** 2026-07-16
**Surface:** `apps/cli` (`Main.kt` + new `Help.kt`)
**Goal:** Every command, alias, positional arg, and option carries a description;
`--help` lets a user (or agent) learn the whole tool. Add per-command help and a
machine-readable `--json` mode.

## Problem

Help today is a single monolithic `USAGE` string printed by top-level
`help`/`-h`/`--help`. There is **no per-command help** (`dayfold push --help` does
nothing useful), options are described only inside dense prose, `logout`/`template`
kinds/`push` targets aren't individually described, and there are no per-command
examples. The primary consumer is the **dayfold-curator agent skill**, which would
benefit most from focused, machine-scannable per-command contracts.

## Design

### Single source of truth — a command registry (`Help.kt`)

Serializable data classes describe the CLI; the renderers and the dispatcher all
read from this one model so nothing can drift:

```kotlin
@Serializable data class HelpArg(val name: String, val description: String, val required: Boolean = true)
@Serializable data class HelpOption(val flag: String, val arg: String? = null, val description: String)
@Serializable data class HelpCommand(
  val name: String,
  val aliases: List<String> = emptyList(),
  val synopsis: String,          // "push <id> <file.json> [flags]"
  val summary: String,           // one line, shown in the index + as the command header
  val details: List<String> = emptyList(),   // paragraphs of deeper behavior
  val args: List<HelpArg> = emptyList(),
  val options: List<HelpOption> = emptyList(),
  val examples: List<String> = emptyList(),
)
@Serializable data class HelpEnv(val name: String, val description: String)
@Serializable data class HelpExit(val code: Int, val meaning: String)
@Serializable data class HelpModel(
  val name: String = "dayfold",
  val version: String,
  val summary: String,
  val commands: List<HelpCommand>,
  val env: List<HelpEnv>,
  val exitCodes: List<HelpExit>,
)
```

`COMMANDS: List<HelpCommand>` (version-independent) is the registry; `helpModel()`
wraps it with `cliVersion()` for JSON. Every existing detail in the current `USAGE`
(keychain/`--allow-env-key`, scope grants, linkify + `--no-linkify`, checklist-id
stamping ADR 0038, enrichment `media` schema ADR 0036, visibility/audience ADR
0030/0038, the per-hub role-gate 403 ADR 0053, exit codes, legacy env auth) is
relocated into the relevant command's `details`/`options` — nothing is lost.

### Three renderers (pure functions)

- `renderIndex(m): String` — `usage: dayfold <command> [args] [flags]`, then one
  padded line per command (`name` + aliases → `summary`), then a compact footer:
  "Run `dayfold <command> --help` for details, or `dayfold help --json` for
  machine-readable help", the legacy-env auth line, and the exit-code table.
- `renderCommand(c): String` — synopsis + summary; `ARGUMENTS:` (each arg + desc);
  `OPTIONS:` (each option + desc); the `details` paragraphs; `EXAMPLES:`.
- `renderJson(m)` / `renderJsonCommand(c)` — `Json { prettyPrint = true }` over the
  model (whole model for the index, one command for a scoped `--help --json`).

`internal val USAGE get() = renderIndex(helpModel())` replaces the constant, so
`usage()` (misuse → stderr, exit 2) and existing references keep working — misuse
now prints the concise index, not the wall of text.

### Centralized help routing (`Main.kt`)

A pure `helpInvocation(args): HelpRequest?` decides help before the dispatch `when`:

- `args[0] ∈ {help, -h, --help}` → top-level help. If a following token resolves to
  a command → that command; else the index. `--json` anywhere → JSON.
- else if `args[0]` resolves to a known command **and** args contains `-h`/`--help`
  → that command's help (`--json` → scoped JSON).
- else → `null` (not a help invocation; fall through to the normal dispatch).

`main()` prints the resolved help to **stdout** and exits **0** (help is not an
error). Command/alias resolution (`rm`→delete, `upgrade`→update, `-v`→version) is a
single `commandByToken` lookup over the registry.

## Behavior

- `dayfold help` → concise index. `dayfold help push` / `dayfold push --help` →
  focused push help. `dayfold help --json` → full model JSON. `dayfold push --help
  --json` → push-only JSON. `dayfold rm --help` → delete help (alias).
- Unknown/misuse still → index on stderr, exit 2.

## Testing

- **Model completeness (enforces the ask):** every command has a non-empty
  `summary`; every `option` and every `arg` has a non-empty `description`; every
  command lists ≥1 example. A new command can't land under-documented.
- **Index:** starts with `usage: dayfold`, lists every command name + alias.
- **Routing:** `helpInvocation` resolves `push --help`, `help push`, `rm --help`
  (alias), `help --json`, `push --help --json`, and returns null for `push 01J…
  card.json` (real invocation, no help flag).
- **JSON:** parses as valid JSON (`Json.parseToJsonElement`) and contains every
  command name; scoped command JSON contains that command's options.
- **Migrated assertions:** the existing `UsageTest` intent — help must list every
  command, and must explain the content-modifying auto-link + `--no-linkify` — is
  preserved: the index lists commands; the auto-link/`--no-linkify` explanation is
  asserted against the **push** command help.

## Out of scope

No CLI framework (clikt/picocli) adoption — the hand-rolled parser stays; this adds
only a help model + renderers + routing. No behavior change to any command's actual
effect.
