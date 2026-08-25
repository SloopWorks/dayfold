# CLI Release & Homebrew Distribution (ADR 0031)

How `dayfold` ships to users via `brew install` and how to cut a release.

```
brew install sloopworks/tap/dayfold      # one line; brew installs Java for the user
brew upgrade dayfold                       # update
```

## One-time operator setup (the ADR 0031 gates)

**Status 2026-08-25: gates 1–3 are closed and `brew install
sloopworks/tap/dayfold` works — verified end to end by the tap's CI on a real
macOS runner (checksum matched, `openjdk@17` auto-installed, `dayfold` linked onto
`PATH`, smoke test passed). Only gate 4 hardening remains, and it is repo
settings.**

1. ~~**License / distribution decision.**~~ **Done.** `apps/cli` is **Apache-2.0**
   (root `LICENSE`, per-path map in `LICENSING.md`), so the tap is public and the
   formula says `license "Apache-2.0"`. The server (`apps/api`) is separately
   unlicensed and nothing in the tarball comes from it.
2. ~~**Create the tap repo.**~~ **Done.** [`SloopWorks/homebrew-tap`](https://github.com/SloopWorks/homebrew-tap)
   is public, with `Formula/dayfold.rb` mirrored from `apps/cli/homebrew/dayfold.rb`
   and a `tests` workflow that runs `brew style` + `brew readall` on every push, then
   `brew audit --strict --online` + `brew install` + `brew test` once the formula's
   sha256 is no longer the 64-zero placeholder. (It is gated that way because before
   the first release the download *must* fail, and a permanently red check is a check
   nobody reads.) The install gate is what catches the `rk` empty-`bin/` bug.
3. ~~**Add the `HOMEBREW_TAP_TOKEN` secret.**~~ **Done 2026-08-25.** A fine-grained
   PAT with `Contents: Read and write` on **only** `SloopWorks/homebrew-tap` (least
   privilege — it must not grant write to the main repo), stored as
   `HOMEBREW_TAP_TOKEN` under *Settings › Secrets and variables › Actions*. It is
   used once per release, by the bump step. **It expires** — fine-grained PATs cap
   at ~1 year — and when it does the failure is quiet-ish: the `Is the tap
   configured?` step still sees a non-empty secret, so the bump runs and 403s
   instead of skipping. If a release ever publishes but `brew install` still serves
   the previous version, check the token first.
4. **Harden the release trigger** (from the security review — a `cli-v*` tag push runs
   with `contents: write` + the tap token):
   - ~~Restrict who can push `cli-v*` tags.~~ **Done 2026-08-25** — see
     [Tag rulesets](#tag-rulesets) below for the exact configuration and the
     `cli-edge` trap it has to avoid.
   - ~~CODEOWNERS-protect `.github/workflows/release-cli.yml`~~ — `.github/CODEOWNERS`
     now covers the release workflows, `apps/cli/homebrew/`, and the licence files.
     **Half-done on purpose:** CODEOWNERS only *requests* a review until branch
     protection on `main` enables "Require review from Code Owners", which is a
     repo-settings change and operator-only.
   - Optionally gate the release job behind a GitHub Environment with a required
     reviewer (a tag alone otherwise publishes).
   - (Already done in the workflow: `actions/checkout` + `actions/setup-java` are
     SHA-pinned; the release upload uses the pre-installed `gh` CLI — no third-party
     action; the tap token flows via scoped `GIT_CONFIG_*` env, never a URL/argv/
     `.git/config`; the untrusted tag is strict-semver-validated before any use.)

## Tag rulesets

**Applied 2026-08-25.** Recorded here because the obvious version of this rule
breaks the edge channel, and the failure is not obvious when it happens.

### The trap

`release-cli-edge.yml` force-pushes a tag on every `main` push that touches the CLI:

```sh
git tag -f cli-edge
git push -f origin cli-edge
```

`cli-edge` is a *deliberately mutable pointer*, moved by the Actions bot. A ruleset
that targets **all tags** with "Restrict updates"/"Restrict deletions" and no bypass
will break that push — and it fails inside a release job, several commits after
someone ticked the box, which is the worst place to discover it. So the two tag
patterns get opposite treatment.

### Ruleset A — lock `cli-v*`

*Settings › Rules › Rulesets › New ruleset › New tag ruleset.* (Rulesets superseded
the older *Settings › Tags* protection page.)

| Field | Value |
|---|---|
| Name | `Release tags (cli-v*)` |
| Enforcement status | Active |
| Bypass list | Repository admin |
| Target tags | Include by pattern → `cli-v*` |
| Rules | Restrict **creations**, **updates**, **deletions** |

*Creations* is the one that carries the weight: a `cli-v*` tag publishes a public
GitHub Release and pushes to the tap with an org-write token, so cutting a release
is now an admin action rather than anyone-with-write. *Updates* and *deletions* keep
shipped release history immutable.

Leave "Require signed commits" off unless tags are actually signed — it blocks the
operator's own release tags otherwise.

### Ruleset B — `cli-edge`, optional

Stops a human hand-moving the edge pointer while leaving CI free. Same path, then:
bypass list **Repository admin + GitHub Actions** (*Add bypass › Apps*), target
`cli-edge`, rules **Restrict updates + deletions only** — creations stay open.

Skipping B entirely is reasonable; `cli-edge` is disposable by design. If the Actions
bypass is misconfigured the symptom is the edge job failing at
`git push -f origin cli-edge`.

### What this does and does not buy

With a single writer this is mostly protection against *accident* and against future
collaborators or a leaked token, not against a present-day attacker. Worth knowing
what already holds without it: the formula pins the tarball's `sha256`, not the tag,
so a re-pointed tag cannot silently change what `brew install` fetches — the download
fails the checksum instead. The ruleset closes the *publishing* hole; the *install*
hole was already shut.

Also already true, and not something rulesets provide: agent sessions cannot push tag
refs at all (they can push branch refs — verified 2026-08-25 by probe). Release tags
are cut by a human for that reason.

### Adjacent trap: don't branch-protect the tap naively

The release bump pushes **directly** to `homebrew-tap`'s `main` using
`HOMEBREW_TAP_TOKEN`. Adding branch protection there without putting that token's
identity on the bypass list makes every future release bump 403 — the release still
publishes, and `brew install` silently keeps serving the previous version.

## Cutting a release

1. Decide the version (semver). Tag from `main`:
   ```
   git tag cli-v0.1.0 && git push origin cli-v0.1.0
   ```
2. `.github/workflows/release-cli.yml` then (on the `cli-v*` tag):
   - validates the tag is `cli-v<semver>`,
   - builds the dist — `apps/cli` is a **standalone Gradle build**:
     `cd apps/cli && ./gradlew distTar -PcliVersion=<version>` → `dayfold-<version>.tar`
     (a `dayfold-<version>/bin/dayfold` launcher + `lib/*.jar`),
   - publishes a **GitHub Release** with that tarball,
   - **bumps the tap formula** (`url` + `sha256`) and pushes to `homebrew-tap`
     (skipped if `HOMEBREW_TAP_TOKEN` is unset).
3. Users get it via `brew install sloopworks/tap/dayfold` / `brew upgrade dayfold`.

## Maintaining the tap mirror

`apps/cli/homebrew/dayfold.rb` is the source of truth;
`SloopWorks/homebrew-tap:Formula/dayfold.rb` is a mirror. The release bump rewrites
**exactly two lines** of the mirror (`url`, `sha256`), so:

- Change the formula **here**, then copy the file across in a separate commit to the
  tap. An edit made only in the tap is lost the next time anyone re-mirrors.
- Never hand-edit `url`/`sha256` in either copy — the bump owns them.
- The tap's `tests` workflow is the safety net: `brew style` + `brew readall` on every
  push, and after the first release also `brew audit --strict --online`, `brew install`
  and the formula's own `test do` block (which asserts `bin/dayfold` is on PATH and
  runs — the `rk` empty-`bin/` bug).

## Continuous "edge" channel (ADR 0037)

Stable releases stay tag-driven (above). For everyday dogfooding, **every push to
`main`** that touches `apps/cli/**` or `packages/schema/**` auto-publishes an edge
build — no manual tag:

- **`cli-edge` is a force-pushed, CI-owned tag** — any tag ruleset must carve it out
  or bypass GitHub Actions, or the edge job dies at `git push -f origin cli-edge`.
  See [Tag rulesets](#tag-rulesets).
- `.github/workflows/release-cli-edge.yml` builds the dist as `0.0.0-edge.<shortsha>`
  and refreshes a single **`cli-edge` GitHub pre-release** with a **stable asset
  name** `dayfold-edge.tar` (stable download URL across commits).
- It uses only the built-in `github.token` (no operator secret), so it runs **today**.
  PRs never publish (only `main` pushes). `cli-edge` is a *pre-release*, so the
  `releases/latest` API (stable) — and therefore `dayfold update`'s version check —
  ignores it. The Homebrew tap formula is **never** touched by edge (stable only).
- Grab an edge build directly:
  `curl -L https://github.com/SloopWorks/dayfold/releases/download/cli-edge/dayfold-edge.tar`.

## Updating an install (ADR 0037)

- `brew upgrade dayfold` — the canonical path (once the tap is live).
- **`dayfold update`** — reports the latest stable vs the running version, then runs
  `brew upgrade dayfold` if the CLI is brew-managed (else prints the install/upgrade
  instructions + the releases URL).
- A throttled (once/24h), TTY-only, fail-silent **nudge** prints after an interactive
  `push`/`pull` when a newer stable exists. Silence it with `DAYFOLD_NO_UPDATE_CHECK=1`
  (also auto-skipped under `CI`, when piped, and on dev/edge builds).

## How it works (ADR 0031, Option A)

- **Packaging:** the Gradle `application` plugin's `distTar` ships a runnable tree
  (launcher + jars). The formula `depends_on "openjdk"` so brew installs Java; because
  brew's openjdk is keg-only, `write_env_script` pins `JAVA_HOME` into `bin/dayfold` →
  **zero user configuration**. One platform-independent artifact (no per-arch matrix).
- **Why not jpackage/.app:** the `reduxkotlin/homebrew-tap` (`rk`) tool nested its
  launcher in a macOS `.app` → empty keg `bin/` → it never linked onto PATH (INB-19).
  We ship a plain top-level `bin/dayfold` and the formula's `test do` block exercises
  it, so that class of bug fails the tap CI rather than the user.

## Versioning notes

- The git tag is the source of truth; CI passes it to Gradle as `-PcliVersion`. Local
  builds default to `0.0.0-dev`.
- Homebrew derives the version from the `dayfold-<version>.tar` URL, so the bump only
  rewrites `url` + `sha256`.

## Future (recorded in ADR 0031, not adopted)

- **jlink** self-contained image (no openjdk dependency) if the ~300 MB JDK pull or
  JVM startup becomes a complaint — at the cost of a per-platform release matrix.
- A **native (Go/Rust) rewrite** would simplify packaging further (single static
  binary; GoReleaser/cargo-dist bump the formula for free) but must regenerate the
  shared `packages/schema/kotlin-gen` types into the new language.
