# ADR 0026: Package & App-ID Naming — `com.sloopworks.*` Umbrella

## Status

Accepted 2026-06-21 (operator-directed; branding/vendor-convention class).
Proposed + revised same day after a 4-agent adversarial review
(completeness, sequencing, security, correctness) — corrections folded in;
operator answered the four open naming decisions (recorded in §Decision)
and authorized execution. Gate 1 cleared: `auth-account-delete-soft`
merged to main (PR #20); pre-S2 window confirmed.

## Context

The M0 prototype currently ships under `com.familyai.client`
(Android `applicationId` + Kotlin `namespace`), `com.familyai.cli`
(CLI Kotlin package), and `com.familyai.schema` (codegen output). Two
problems:

- `familyai` is not a domain the operator controls, and `.client` is a
  code-layer name, not a product — a weak reverse-DNS app identity.
- Sloopworks LLC is the umbrella entity; future apps need a stable,
  collision-safe, multi-app naming convention.

An Android/iOS `applicationId`/bundle id is **permanent once published**
to the stores — changing it ships a *new* app and forfeits installs,
ratings, and reviews. The product is pre-publish (M0, on-device + Vercel
only), so the rename is free **now** and expensive later. The product
name is settled as **Dayfold** (already used internally: `DayfoldTheme`,
ADR 0022 M3 theme). The operator owns / will register `sloopworks.com`.

**Auth coupling — corrected by review (load-bearing).** The rename must
land **before S2** (Firebase + Google + Apple identity provisioning, ADR
0023). As of today there is **no Firebase, no Google OAuth Android client,
no SHA-1 registration, no deep-link/redirect URIs** in the repo — auth is
a custom Hono device-grant + dev-token stub (`identity.ts` =
StubVerifier/DevVerifier; "Firebase verifier arrives at S2"). So the
rename is currently a pure mechanical sweep with **zero** OAuth/Firebase/
Apple/keystore migration cost. The moment S2 registers identity keyed on
package + SHA-1, the same rename becomes a multi-console migration (Google
Cloud OAuth + Firebase + Apple Developer) with a Google-Sign-In-`DEVELOPER_ERROR`
window. Hence the timing gate below.

A decision is needed now, before S2 and before more auth/content branches
fork and re-cement the old package.

## Decision

Adopt `com.sloopworks.*` as the umbrella naming convention for all
Sloopworks LLC apps. For Dayfold specifically, rename these surfaces:

| Surface | From | To |
|---|---|---|
| androidApp `applicationId` | `com.familyai.client` | `com.sloopworks.dayfold` |
| androidApp `namespace` | `com.familyai.client.android` | `com.sloopworks.dayfold.android` |
| client `namespace` | `com.familyai.client` | `com.sloopworks.dayfold.client` |
| sqldelight `packageName` | `com.familyai.client.db` | `com.sloopworks.dayfold.client.db` |
| sqldelight `.sq` dir | `commonMain/sqldelight/com/familyai/` | `.../com/sloopworks/dayfold/` |
| cli Kotlin package | `com.familyai.cli` | `com.sloopworks.dayfold.cli` |
| **schema codegen** `--package` | `com.familyai.schema` | `com.sloopworks.dayfold.schema` |
| compose.desktop `mainClass` | `com.familyai.client.MainKt` | `com.sloopworks.dayfold.client.MainKt` |
| CLI binary / command | `familyai` | `dayfold` |
| device-grant client-id | `familyai-cli` | `dayfold-cli` |
| env var | `FAMILYAI_API` | `DAYFOLD_API` |
| npm scope | `@family-ai/api`, `@family-ai/schema` | `@sloopworks/api`, `@sloopworks/schema` |
| npm root pkg | `family-ai-dashboard` | `dayfold` |
| CLI creds path | `~/.config/familyai/credentials.json` | `~/.config/dayfold/credentials.json` |
| desktop session/DB dir | `~/.family-ai-dashboard/` | `~/.dayfold/` |
| Gradle root names | `familyai-apps`, `familyai-cli` | `dayfold-apps`, `dayfold-cli` |
| `archivesName` (APK) | `familyai-android` | `dayfold-android` |
| AndroidManifest `android:label` | `family-ai-dashboard` | `Dayfold` |

**Operator decisions (resolved 2026-06-21):**
1. **Runtime data paths: rename, no migration.** New paths; existing local
   creds/DB are orphaned → one re-login on the dogfood device. Acceptable
   at single-user M0.
2. **Env vars + npm: rename both.** Brand-bearing names move to Dayfold/
   Sloopworks. Domain-semantic vars `FAMILY_ID` and `HOUSEHOLD_SECRET`
   are **not** brand strings (they name the tenant concept) → left as-is;
   only `FAMILYAI_API` carries the brand.
3. **androidApp namespace = `com.sloopworks.dayfold.android`** (drop the
   dead `.client.` segment).
4. **CLI: rename package + binary.** `com.sloopworks.dayfold.cli` and the
   invoked command becomes `dayfold` (update docs/specs referencing
   `familyai login`).

Rules going forward:

1. `applicationId` / bundle id = **org + product leaf only**
   (`com.sloopworks.<product>`). No code-layer suffix.
2. Kotlin `namespace` / package roots keep the meaningful leaf
   (`.client` / `.cli` / `.schema`) to disambiguate co-resident trees.
   `applicationId` and `namespace` intentionally differ — OAuth/Firebase
   registration uses the **`applicationId`** (`com.sloopworks.dayfold`),
   not the namespace.
3. Every future Sloopworks app uses `com.sloopworks.<product>` as its
   store id; new products get a new leaf.

### Not in scope today (forward rules, no current referent)

- **iOS bundle id.** No Xcode project / `Info.plist` / `.pbxproj` exists
  yet — iOS is only the KMP framework. The KMP framework `baseName`
  **stays `client`** (a code symbol, not store-facing). When an iOS app
  project is created, its bundle id = `com.sloopworks.dayfold`.
- **Web target.** No js/wasm target exists. When added, it inherits the
  `com.sloopworks.dayfold.client` namespace; no separate store id.
- **Google OAuth / Firebase / Apple identity.** None wired (pre-S2). When
  S2 provisions them, register under `com.sloopworks.dayfold` + signing
  SHA-1 from the start. This is the reason for the timing gate, not a
  current rename cost.

## Rationale

- Org-scoped reverse-DNS (`com.sloopworks`) keyed on a controlled domain
  is the standard multi-app convention (cf. `com.google.*`) — clean
  trademark + collision hygiene.
- Pre-publish + pre-S2, the cost is only a mechanical sweep; post-launch
  the store id is irreversible and post-S2 it becomes a multi-console
  identity migration.
- Dropping `.client` from the store id keeps the user-facing identifier
  short while Kotlin packages keep the leaves they need to coexist.
- The CLI rename is a **consistency choice, not a store-identity one** —
  the CLI is a dev tool, not a store app, and isn't even in
  `settings.gradle`. Included because schema + CLI share generated types,
  so a half-rename (schema renamed, CLI not) is uglier than doing both.

Alternatives rejected:
- **Keep `com.familyai.client`** — weak, undomained identity; only gets
  more expensive to change.
- **`.client` in the app id** — needless length in the permanent id; the
  disambiguation it provides is only relevant in code.
- **`...dayfold.client.android` for the app shell** — dead `.client.`
  segment; the shell wraps `:client`, it is not a client *of* client.

## Consequences

Positive:
- Permanent store identity locked correctly before launch + before S2
  identity provisioning; zero forfeited installs/ratings and zero OAuth
  re-registration if the timing gate holds.
- Reusable umbrella convention for all Sloopworks apps.

Negative / costs:
- One-time mechanical sweep across **all Kotlin trees** (`:client`,
  `:cli`, `:schema` codegen) — package decls + import moves + source-dir
  renames. Note: CLI `src/main` sources are flat (package-only decl, no
  dir move); CLI **tests** live under `com/familyai/cli/` (dir move).
  androidApp `BuildConfig`/`R` are generated under `namespace` →
  `BuildConfig` imports (e.g. `MainActivity` reading `DAYFOLD_API`) move
  with it.
- **Bundled API artifact `apps/api/api/index.js`** bakes in the
  `familyai-cli` client-id and schema types → must be **regenerated /
  rebundled and redeployed to Vercel**, not hand-edited.
- **`codegen.mjs --package` flag** must change or CI's "codegen up to
  date" gate (`.github/workflows/ci.yml`) fails.
- **Runtime state orphaned** by the path rename → one re-login + fresh DB
  on the dogfood device (operator-accepted, no migration).
- Doc/spec sprawl: `com.familyai` / `familyai` appears across many
  `specs/`, `docs/`, `planning/`, `research/`, and historical ADRs. Sweep
  active docs (`agent-dev-loop.md`, `deploy-m0.md`, `next.md`, CLI specs);
  **leave historical ADRs/research as dated snapshots** (do not rewrite
  immutable records).
- SQLDelight package rename is **codegen-only — no DB migration, no
  `user_version` change** (the package isn't stored in the DB file).
- The `cl-integrate` worktree + `auth-signout-account` branch carry the
  old package and must cross the rename once (see Sequencing).

## Sequencing

Corrected by review — the landmark is **S2, not "one auth branch":**

1. Land `auth-account-delete-soft` (in flight; 0 ahead / 5 behind main —
   trivial merge).
2. **Immediately** do the rename next, on a dedicated
   `rename-sloopworks-dayfold` branch off fresh `main`, **strictly before
   S2 provisions any Google/Apple/Firebase identity** and before
   S5-slice2 / S6 / the content epic fork new branches. This is a clean,
   no-concurrent-feature-branch window and dodges all OAuth/Firebase
   re-registration cost.
3. After the rename merges, deliberately carry the two unmerged trees
   across it **by merging post-rename main into them once** (not
   per-commit rebase — package churn touches nearly every file):
   - the worktree at `.claude/worktrees/design-content-detail`, which is
     on branch **`cl-integrate`** (≈105-commit content epic, +1 ahead) —
     note: the worktree *directory* name ≠ its branch name;
   - `auth-signout-account` (+1 ahead, unmerged S6 UI).
4. Delete or recreate-from-main the ~8 stale 0-ahead branches so a later
   merge/cherry-pick can't resurrect `com.familyai`.
5. Add a **CI guard**: grep that fails the build on any `com.familyai`
   outside `adr/` history.
6. Resume S2 → S5-slice2 → S6 + content epic, all forked from
   post-rename main.

## Verification (Definition of Done)

Per ADR 0012 (test-green-before / verify-after), the rename is "verified"
only when **all** hold:

- [ ] `:client` + `:androidApp` build; `:cli` builds; `:schema` codegen
      runs and CI codegen gate passes.
- [ ] APK installs and **launches on a device** (new `applicationId` = a
      fresh install; the old app is orphaned on test devices — expected).
- [ ] Dev-token auth round-trips end-to-end.
- [ ] SQLDelight DB opens (fresh, at the new path).
- [ ] CLI builds and `dayfold validate` runs against generated schema.
- [ ] API rebundled + redeployed; `dayfold-cli` client-id served in prod.
- [ ] `grep -r "com\.familyai\|familyai\|family-ai" .` (excluding `adr/`
      history) returns zero in active code/config.

## Revisit Trigger

The product name "Dayfold" changes, the umbrella entity changes from
Sloopworks LLC, S2 identity provisioning is about to run before the rename
(escalate — gate violated), or the app is published to a store under the
old id (after which this becomes a supersede-only, listing-forfeiting
decision).
