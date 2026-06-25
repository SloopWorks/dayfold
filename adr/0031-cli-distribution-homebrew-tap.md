# ADR 0031: CLI Distribution — First-Party Homebrew Tap (Zero-Config JVM)

## Status

**Proposed** 2026-06-25 (agent-drafted from the distribution spike; operator-gated —
adds a distribution channel + release automation + a public-distribution/licensing
implication). Immutable once Accepted — supersede, do not edit. Composes with
**ADR 0018** (TS/Vercel API; CLI stays Kotlin), **ADR 0026** (`com.sloopworks.*`
naming; CLI binary `dayfold`), and **ADR 0012** (agent-operated build — but the
operator steps here exceed those rails: secrets + an external repo + licensing).
Spike: `research/2026-06-25-spike-cli-homebrew-distribution.md`.

## Context

The CLI (`apps/cli`, `dayfold`) is the MVP wedge's authoring surface (content API +
CLI + Claude skill, per `CLAUDE.md`). Today it is installed only by building from
source (`apps/cli` is a **standalone Gradle build**; `./gradlew installDist`), which
is fine for the operator but not for any other user. The operator's requirement: a
**one-line install**, `dayfold` on PATH everywhere, easy versioning + updates,
**CI-driven**, with **zero user configuration** — specifically *no manual JDK
install and no `JAVA_HOME`/env setup*. The CLI is a Kotlin/JVM `application`, so it
needs a Java 17 runtime at run time; the design problem is providing that runtime
transparently.

A prior first-party homebrew tool (`reduxkotlin/homebrew-tap`'s `rk`) hit a packaging
trap: `jpackage --type app-image` nested the launcher inside a macOS `.app`
(`…/Contents/MacOS/rk`), leaving the keg `bin/` empty so the tool never linked onto
PATH until a manual symlink fix (backlog INB-19). That lesson constrains the choice.

## Decision

1. **Channel — a first-party Homebrew tap.** Distribute via
   **`SloopWorks/homebrew-tap`** (`Formula/dayfold.rb`), not Homebrew core (core has
   notability bars + review latency unsuited to a first-party dev tool). Install:
   `brew install sloopworks/tap/dayfold`; update: `brew upgrade dayfold`. The same
   formula serves Linuxbrew.

2. **Packaging — Option A: `application` dist + `depends_on "openjdk"` + an
   env-script that pins `JAVA_HOME`.** Homebrew installs `openjdk` as a dependency
   (the user never installs Java); because brew's openjdk is keg-only, the formula
   wraps the dist launcher so the runtime is hard-wired:
   `(bin/"dayfold").write_env_script libexec/"bin/dayfold", JAVA_HOME: Language::Java.java_home`.
   Result: **zero user configuration** — no JDK install, no `JAVA_HOME`, no PATH
   edits. One platform-independent artifact (no per-arch matrix). This also avoids
   the `rk` trap by shipping a real top-level `bin/dayfold` (verified in #76) and
   **rejects** `jpackage`/`.app`/`.pkg` (bundle nesting + notarization friction).

3. **Versioning + CI release.** Annotated git tags **`cli-v<semver>`** are the
   source of truth. A tag-triggered workflow (`/.github/workflows/release-cli.yml`,
   `working-directory: apps/cli`) builds `distTar -PcliVersion=<v>`, publishes a
   GitHub Release with the tarball, and **auto-bumps the tap formula** (`url` +
   `sha256`) — a release is one tag push, no manual formula editing. The tap repo
   runs `brew audit`/`test-bot` so a bad bump (the `rk` empty-`bin/` class) is caught
   before users. The enabling build change (`applicationName = "dayfold"` + version
   from `-PcliVersion`) already landed (#76).

4. **Upgrade path, recorded but not adopted now.** If the ~300 MB openjdk pull or
   JVM startup becomes a real complaint, switch packaging to **`jlink`** (a bundled
   minimal runtime, no openjdk dependency) at the cost of a per-platform release
   matrix — the tap + tag conventions are unchanged. A future non-JVM rewrite
   (Go/Rust) would *simplify* packaging further (single static binary;
   GoReleaser/`cargo-dist` bump the formula for free) but must regenerate the shared
   `packages/schema/kotlin-gen` types into the new language; deferred.

## Consequences

- **Maintenance:** a new repo (`SloopWorks/homebrew-tap`) + one workflow to keep
  green; formula bumps are automated. An `openjdk` dependency is pulled for users
  without Java (~300 MB) — acceptable for a dev CLI; revisit via jlink if it bites.
- **Distribution posture:** a public Homebrew formula implies **public distribution**
  of the CLI. This intersects the repo's (currently absent) license and the
  customer-relationship guardrail — see the operator gates below.
- **Reproducibility:** releases are tag-driven and CI-built (no local publish), so
  versions are auditable.

## Operator gates (must be decided/done before adoption — not agent-decidable)

1. **License + public-vs-private distribution.** The repo is currently unlicensed; a
   public tap distributes the CLI publicly. Choose a license and confirm public
   distribution is intended (or use a private tap). *(scope/legal — operator only.)*
2. **Create `SloopWorks/homebrew-tap`** with `Formula/dayfold.rb` (draft in the
   spike).
3. **Add the `HOMEBREW_TAP_TOKEN` repo secret** (a PAT with write to the tap).
4. **Accept this ADR** (and optionally confirm Option A vs the jlink alternative).
   On acceptance, the inert `release-cli.yml` + the formula are landed and
   `cli-v0.1.0` is cut.

## Alternatives considered

- **B — `jlink` self-contained image:** no runtime dependency, ~40–70 MB, but a
  per-platform release matrix. *Recorded as the upgrade path (Decision §4).*
- **C — `jpackage` app-image / `.pkg`:** what `rk` used → the empty-`bin/` symlink
  trap + notarization. **Rejected.**
- **Curl-pipe install script / GitHub Release download:** no dependency management,
  no clean update story, weaker UX than `brew`. Rejected for the primary channel.
- **npm/`npx` wrapper:** would shoehorn a JVM tool into the node ecosystem +
  re-introduce a runtime-bootstrap problem. Rejected.
- **Native rewrite (Go/Rust) now:** simplest packaging but loses the shared schema
  types and is premature. *Deferred (Decision §4).*
