# Spike — Publishing the `dayfold` CLI to Homebrew

**Date:** 2026-06-25 · **Status:** Spike (investigation + recommendation; adoption is ADR-gated)
**Author:** agent · **Scope:** one-line install, on PATH everywhere, easy version/update, CI-driven, **zero user configuration** (no manual Java install/env).

---

## 1. Goal

```
brew install sloopworks/tap/dayfold      # one line, then `dayfold` works anywhere
brew upgrade dayfold                      # update
```

Hard requirements (from the operator):
- **R1** One-line install; `dayfold` on PATH system-wide.
- **R2** **Zero config** — the user must NOT install a JDK or set `JAVA_HOME`/env. Install "just works".
- **R3** Easy versioning + updates, **driven by CI** (tag → release → formula bump).
- **R4** Apply the lessons from the `reduxkotlin/homebrew-tap` (`rk`) tool.
- **R5** Note how publishing changes if the CLI is later rewritten in a non-JVM language.

## 2. Current state (verified this spike)

- `apps/cli` is a **standalone Gradle build** — deliberately *not* in `apps/settings.gradle.kts`
  ("both are sibling dirs, intentionally NOT included"). Build it with `./gradlew` **from
  `apps/cli`** (or `-p apps/cli`); `:cli:<task>` from `apps/` mis-abbreviates to `:client`. CI's
  `cli` job already uses `working-directory: apps/cli` + `./gradlew --no-daemon build`.
- It's a **Kotlin/JVM `application`** (`mainClass = com.sloopworks.dayfold.cli.MainKt`). The
  `application` plugin's `installDist`/`distTar`/`distZip` ship a runnable tree:
  `bin/<launcher>` (a POSIX shell script) + `lib/*.jar`. It needs a **Java 17 runtime**.
- It imports `packages/schema/kotlin-gen` as a source dir → the **generated schema types are the
  single source of truth** shared with the server contract (relevant to §8).
- **This spike's enabling change (merged with this doc):** set `applicationName = "dayfold"` +
  `version` (from `-PcliVersion`). The dist now produces `dayfold-<v>/bin/dayfold` + a versioned
  archive `dayfold-<v>.tar` — confirmed by `distTar -PcliVersion=0.1.0`. A clean top-level
  `bin/dayfold` is important — see the `rk` lesson in §4.

## 3. Lessons from `reduxkotlin/homebrew-tap` (`rk`) — R4

The `rk` tool (operator's prior homebrew CLI) hit a documented gotcha (backlog INB-19):

> keg `bin/` empty → binary at `libexec/rk.app/Contents/MacOS/rk`

**Diagnosis:** `rk` was packaged with **`jpackage --type app-image`**, which emits a macOS **`.app`
bundle** with the launcher nested at `…/Contents/MacOS/rk`. Homebrew's auto-symlink step links
`<keg>/bin/*` into the prefix — but there was nothing in `bin/`, so `rk` never landed on PATH
until the formula manually symlinked the nested binary.

**Takeaways for `dayfold`:**
- **Avoid `jpackage --type app-image`** (and `.app`/`.pkg`) for a *CLI*. They add macOS bundle
  nesting (the symlink trap) and `.pkg`/`.app` invite **notarization/gatekeeper** friction we
  don't want for a dev tool.
- **Prefer a layout with a real top-level `bin/<exe>`** that Homebrew can link directly — which is
  exactly what the plain `application` dist (or a `jlink` image) gives us. Our §2 change ensures
  `bin/dayfold` exists.

## 4. Packaging options (the R2 "zero-config Java" decision)

A JVM CLI needs a Java runtime. Three ways to satisfy R2 without the user touching Java:

| Option | How Java is provided | Artifacts | Pros | Cons |
|---|---|---|---|---|
| **A. `application` dist + `depends_on "openjdk"` + env-script** (RECOMMENDED) | Homebrew installs `openjdk` as a dependency; the formula wraps the launcher to pin `JAVA_HOME` | **one** platform-independent tarball | Simplest; one artifact for all platforms/arches; brew manages Java; self-configuring | Pulls a full JDK (~300 MB) if not present; ~150–250 ms JVM startup |
| **B. `jlink` self-contained image** (badass-runtime / `jlink` task) | A **minimal JRE is bundled** into the artifact (no Java dependency) | **per-OS/arch** tarballs (macOS-arm64, macOS-x64, linux-x64, …) | No runtime dep; smaller than full JDK (~40–70 MB); fast; fully self-contained | **Release build matrix** (one per platform); formula must select by `OS::CPU` |
| **C. `jpackage` app-image** | Bundled runtime inside a `.app` | per-OS bundles | self-contained | the **`rk` symlink trap**; notarization friction; **rejected** (§3) |

**Why A satisfies "zero config" (R2):** Homebrew's `openjdk` is *keg-only* (not on PATH), so a
naive launcher wouldn't find `java`. The standard Homebrew JVM-CLI pattern fixes this in the
formula — wrap the dist launcher with an env-script that pins `JAVA_HOME` to brew's openjdk:

```ruby
# in the formula's install:
libexec.install Dir["*"]                      # the unpacked dayfold-<v>/ tree
(bin/"dayfold").write_env_script libexec/"bin/dayfold",
  JAVA_HOME: Language::Java.java_home("17")    # pin the runtime → user never sets env
```

The user runs `brew install …`; brew pulls `openjdk` automatically; the env-script hard-wires the
runtime. **No manual JDK install, no `JAVA_HOME`, no PATH edits.** That is R2 met.

**Recommendation:** **Start with Option A** — one artifact, simplest CI, fully self-configuring,
and it sidesteps the `rk` nesting problem (plain `bin/dayfold`). **Graduate to Option B (`jlink`)
only if** the ~300 MB openjdk pull or JVM startup becomes a real complaint; B removes the runtime
dependency at the cost of a per-platform release matrix. (B is also the natural stepping-stone to
a fully static future — §8.)

## 5. Distribution channel — the tap

- Create a **tap repo `SloopWorks/homebrew-tap`** (reuse the existing org tap convention from
  `reduxkotlin/homebrew-tap`). A tap is just a GitHub repo named `homebrew-<name>` holding
  `Formula/dayfold.rb`.
- Install becomes `brew install sloopworks/tap/dayfold` (R1). No core-Homebrew submission needed
  (core has notability bars + review latency; a tap is the right call for a first-party tool).
- Linux: the same formula works under **Linuxbrew** for Option A (platform-independent tarball).
  Option B needs a linux artifact in the matrix.

## 6. Versioning + the CI release flow (R3)

**Versioning:** annotated git tags `cli-v<semver>` (e.g. `cli-v0.1.0`). The tag is the single
source of truth; CI passes it to Gradle as `-PcliVersion=0.1.0` (wired in §2).

**Release pipeline** — a new workflow `/.github/workflows/release-cli.yml`, triggered on `cli-v*`
tags, separate from `ci.yml`:

```yaml
name: Release CLI
on:
  push:
    tags: ["cli-v*"]
permissions:
  contents: write           # create the GitHub Release + upload assets
jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "17" }
      - name: Derive version
        id: v
        run: echo "version=${GITHUB_REF_NAME#cli-v}" >> "$GITHUB_OUTPUT"
      - name: Build dist
        working-directory: apps/cli
        run: ./gradlew --no-daemon distTar -PcliVersion=${{ steps.v.outputs.version }}
      - name: Publish GitHub Release + asset
        uses: softprops/action-gh-release@v2
        with:
          files: apps/cli/build/distributions/dayfold-${{ steps.v.outputs.version }}.tar
      - name: Bump the tap formula
        uses: dawidd6/action-homebrew-bump-formula@v3
        with:
          token: ${{ secrets.HOMEBREW_TAP_TOKEN }}   # PAT with write to SloopWorks/homebrew-tap
          tap: SloopWorks/homebrew-tap
          formula: dayfold
          tag: ${{ github.ref_name }}
```

`action-homebrew-bump-formula` recomputes the `url` + `sha256` and opens/commits the formula bump
on the tap — so a release is **one `git tag` push**, no manual formula editing. (Alternative: a
hand-rolled step that checks out the tap, `sed`s `url`/`sha256`/`version`, and pushes — more code,
fewer moving parts.)

**Update UX:** `brew update && brew upgrade dayfold`.

## 7. The formula (Option A, ready to lift to `SloopWorks/homebrew-tap:Formula/dayfold.rb`)

```ruby
class Dayfold < Formula
  desc "Dayfold content-authoring CLI (cards + hub tree)"
  homepage "https://github.com/SloopWorks/dayfold"
  url "https://github.com/SloopWorks/dayfold/releases/download/cli-v0.1.0/dayfold-0.1.0.tar"
  sha256 "<filled by the bump action>"
  license "UNLICENSED"            # set the real license before first release (see §9 risks)

  depends_on "openjdk"            # brew installs Java automatically → R2

  def install
    libexec.install Dir["*"]                       # the dayfold-<v>/ tree (bin/, lib/)
    (bin/"dayfold").write_env_script libexec/"bin/dayfold",
      JAVA_HOME: Language::Java.java_home          # pin the runtime; no user env needed
  end

  test do
    assert_match "usage: dayfold", shell_output("#{bin}/dayfold 2>&1", 2)
  end
end
```

Note: the dist's top-level dir is `dayfold-<v>/`; depending on how the tarball is rolled, either
`libexec.install Dir["*"]` after a strip, or point at the nested dir. The `test do` block exercises
the real launcher (catches the `rk` "empty bin/" class of bug in CI-for-the-tap).

## 8. If we move off the JVM (R5)

A future rewrite in **Go or Rust** changes packaging substantially — mostly *simpler*:

| Aspect | JVM (today) | Native (Go/Rust) |
|---|---|---|
| Runtime dep | Needs a JRE (Option A pulls openjdk; B bundles one) | **None** — one static binary |
| Artifact | tarball of jars + launcher (+ maybe JRE) | **single self-contained binary** per OS/arch |
| Startup | ~150–250 ms (JVM) | ~instant |
| Formula | `depends_on openjdk` + env-script, or jlink matrix | trivial — install one binary into `bin` |
| Release automation | custom workflow + bump action | **GoReleaser** (Go) / `cargo-dist` (Rust) generate the release **and** the tap formula end-to-end |
| **Cost of the switch** | — | **loses the shared `packages/schema/kotlin-gen` types** — the CLI currently consumes the generated Kotlin schema as the single source of truth; a native CLI must regenerate the schema into Go/Rust types (the codegen already targets multiple langs from one JSON schema, so this is feasible but adds a target) |

**Guidance:** the JVM path is right *now* (one language, shared schema types, fastest to ship).
Revisit native **only if** startup latency or the openjdk download become real pain, or the CLI
grows into a heavily-used standalone tool. The JSON-schema-driven codegen means a native port
wouldn't have to hand-maintain types — it'd add a Go/Rust codegen target. Packaging would get
*easier* (GoReleaser/cargo-dist do formula bumps for free), so this spike's tap + tag conventions
carry over.

## 9. Adoption plan, operator gates, and risks

**Already done (this PR):** `applicationName = "dayfold"` + `-PcliVersion` → the dist is
packaging-ready (`bin/dayfold`, versioned archive), verified by `distTar`.

**Operator-gated / external (cannot be agent-done — secrets + external repos + ADR):**
1. **ADR** — adding a distribution channel + release automation touches *maintenance burden* and a
   *platform/vendor choice* → ADR-class per `CLAUDE.md`. Write a Proposed ADR ("Distribute the CLI
   via a first-party Homebrew tap, Option A") and accept before wiring the live workflow.
2. Create the **`SloopWorks/homebrew-tap`** repo with `Formula/dayfold.rb`.
3. Add a **`HOMEBREW_TAP_TOKEN`** repo secret (a PAT with write to the tap).
4. Land `/.github/workflows/release-cli.yml` (§6) — inert until a `cli-v*` tag is pushed.
5. Cut `cli-v0.1.0` (tag push) → the pipeline builds, releases, and bumps the formula.

**Risks / open items:**
- **License.** The repo is currently unlicensed; a public Homebrew formula implies public
  distribution. Set an explicit license (and decide public-vs-private distribution) **before** the
  first release. *(Decision for the operator — not agent-decidable.)*
- **openjdk size.** Option A pulls a full JDK if absent (~300 MB). If that's a complaint, switch to
  Option B (jlink, ~40–70 MB bundled) — at the cost of a per-platform release matrix.
- **Apple Silicon / Intel / Linux.** Option A is platform-independent (one artifact). Option B and
  any native future need a build matrix + per-platform `url`/`sha256` in the formula.
- **Tap formula CI.** Add a `brew test-bot` / `brew audit --strict dayfold` check on the tap repo so
  a bad bump (the `rk` empty-`bin/` class of bug) is caught before users hit it.
- **Standalone-build quirk.** The CLI's separate Gradle build is easy to forget — the release
  workflow must `working-directory: apps/cli` (mirrored from `ci.yml`).

## 10. Recommendation (one line)

Adopt **Option A** — `application` dist + a first-party `SloopWorks/homebrew-tap`, formula uses
`depends_on "openjdk"` + an env-script to pin `JAVA_HOME` (zero user config), released by a
tag-triggered workflow that auto-bumps the formula. It's the simplest path that meets every
requirement, avoids the `rk` nesting trap, and the tag/tap conventions survive a future jlink or
native-rewrite upgrade. Gate adoption behind a short Proposed ADR + the operator setting up the
tap repo and token.
