# ADR 0065: Shared DebugDrawer — Pinned Repository Composite Build

## Status

**Accepted** 2026-08-10 (operator-directed: use GitHub repository sharing when
artifact publication adds overhead). This supersedes only ADR 0057's ownership
of `apps/debugdrawer-swip`; the inspector behavior, privacy posture, and release
boundary in ADR 0057 remain unchanged.

## Context

Dayfold extracted its reusable drawer, no-op facade, Redux adapter, and SWIP
adapter into the private `SloopWorks/debugdrawer` repository. Keeping the old
`apps/debugdrawer*` modules would leave two sources of truth.

The initial GitHub Packages publication of version 0.1.0 failed because the
producer repository had no `SLOOPWORKS_PACKAGES_TOKEN`: the SWIP adapter was
excluded and uploads received HTTP 401. The producer deliberately keeps
publication operator-only, so making Dayfold depend on that publication adds an
unnecessary deployment gate to an internal shared-code migration.

## Decision

1. Dayfold pins exact DebugDrawer commit `92fec3b` as the private git submodule
   `third_party/debugdrawer`.
2. `apps/settings.gradle.kts` includes that repository as a Gradle composite
   build and substitutes its four projects for the stable
   `com.sloopworks.debugdrawer:*:0.1.0` dependency coordinates. App build files
   therefore retain the same dependency/variant contract as future binary
   publication.
3. Debug uses the real drawer plus Redux and SWIP adapters. Release uses only
   `debugdrawer-noop`; CI verifies the resolved release graph and final DEX.
4. CI and Android release jobs initialize the private submodule with the dedicated,
   repository-scoped `DEBUGDRAWER_REPO_TOKEN` read credential. Local developers
   initialize the submodule and keep the existing GitHub Packages credential for
   SWIP.
5. GitHub Packages publication remains an optional distribution path for other
   consumers. It is not a prerequisite for Dayfold builds or upgrades.
6. DebugDrawer upgrades are explicit Dayfold PRs that advance the gitlink, run
   producer tests plus Dayfold debug/release checks, and preserve the release
   boundary.

## Consequences

Positive:

- one shared source tree, pinned reproducibly to a reviewed commit;
- no operator package release is required to validate or merge Dayfold changes;
- stable coordinates keep the consumer build compatible with a future switch to
  published artifacts.

Costs:

- a fresh clone must initialize the private submodule;
- external contributors without SloopWorks access cannot compile the app lanes,
  matching the existing limitation imposed by private SWIP packages;
- composite configuration couples Dayfold's app build to the shared repository's
  Gradle toolchain, so upgrades must be tested in both repositories.

## Rejected alternatives

- **Keep the embedded copy:** creates two sources of truth and guarantees drift.
- **Block on GitHub Packages:** preserves an operator-only publication gate for an
  internal consumer without improving runtime behavior.
- **Copy source during CI:** produces an unpinned, CI-only build shape that local
  development cannot reproduce.
- **Git subtree/vendor snapshot:** hides provenance and recreates manual source
  synchronization.
