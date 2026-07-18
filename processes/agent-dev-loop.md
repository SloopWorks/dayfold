# Agent Dev Loop — build, test, observe (read this before touching apps/client, apps/androidApp, or iOS)

For future sessions: the **cheap, repeatable feedback loop** for each module, so
you don't re-derive the toolchain (that's the token sink). Hypothesis (unproven,
worth measuring): the **text action log** + **snapshot PNGs** + **devtools** let
an agent verify changes with *text + on-demand image reads* instead of
device-screencap-every-iteration → faster, fewer tokens.

**Scope / jump table** — most of this file is Compose/KMP (client+Android+iOS)
detail. If you're only touching `apps/api`, read just `## API` below (~20
lines) and stop. If you're only touching `apps/cli`, you don't need this file
at all — it's a plain Gradle/JVM module (`./gradlew test`); see
`processes/cli-release.md` for packaging.

## Toolchain (fixed — don't re-discover)
- **Cloud/remote sandbox sessions:** if `./gradlew` can't reach
  `services.gradle.org` (proxy 403 / no egress), you're in a network-restricted
  environment — there's no local Gradle distribution + Kotlin-plugin cache
  pre-seeded for `apps/cli`'s pinned Gradle 9.5.1 there. Make Kotlin changes
  carefully by inspection (grep every call site, mirror an already-proven
  pattern) and rely on CI to compile-verify; don't spend the session fighting
  the sandbox network.
- **JDK 17** for all Gradle builds: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
  (the version-independent brew symlink — use this, NOT the `Cellar/openjdk@17/17.0.x`
  path, which breaks on every patch bump, e.g. 17.0.18→17.0.19). Gradle's own daemon may
  be JDK 26; Kotlin needs 17. Each Kotlin module has a wrapper (`./gradlew`).
- **Kotlin 2.3.20** · Compose-MP 1.9.3 (desktop) · **AGP 9.2.1** · **Gradle 9.4.1**
  (the single `apps/` wrapper; PR #26 upgraded from the old 8.7.2/8.11.1) · compileSdk
  37 (Android 16) · Node 24 + local Postgres (`psql`) running.
- **redux-kotlin `1.0.0-alpha05`:** create one `SelectorStore` with
  `rememberSelectorStore(rawStore)` at each Compose root, pass it through the
  bound subtree, and use `store.selectorState { ... }` / `store.fieldState(...)`.
  Do not escape back to the raw store with `StableStore.value`, and use the
  keyed selector overload for projections that capture changing Compose values.
  Compose hosts pass the method-only `DayfoldCommandPort`; do not recreate a
  forwarding stable-command wrapper. Its stability promise is configured in
  `apps/ui/compose-stability.conf` and guarded by `RenderIsolationTest`.
  The compose artifact brings granular bindings transitively; keep the explicit
  granular dependency only where non-Compose granular APIs are used.
- **`rk` CLI `1.0.0-alpha02`** (NOW PUBLISHED — Homebrew `reduxkotlin/tap/rk`):
  the unified redux-kotlin CLI = **devtools + snapshot**. Alpha — pin like the
  redux-kotlin alpha bet. **Brew symlink is broken** (the formula points at
  `libexec/rk.app/...` but the keg lays the binary at
  `…/Cellar/rk/1.0.0-alpha02/libexec/Contents/MacOS/rk`, and keg `bin/` is
  empty) → `rk` is NOT on PATH after install. **Workaround (done on this Mac):**
  `ln -sf "$(brew --prefix)/Cellar/rk/1.0.0-alpha02/libexec/Contents/MacOS/rk" ~/.local/bin/rk`.
  Verify: `rk --version`. JDK 17+ (it's a bundled-JVM jpackage app, ~219MB).

## API (apps/api — TS/Hono/Postgres)
```
cd apps/api
export DATABASE_URL=postgres:///fad_test
psql -d fad_test -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" && psql -d fad_test -f migrations/0001_m0_init.sql
psql -d fad_test -f migrations/0002_auth.sql   # AUTH-S1 tenancy tables (ADR 0021)
node scripts/provision.mjs "Fam"          # → FAMILY_ID / HOUSEHOLD_CREDENTIAL_ID / HOUSEHOLD_SECRET (legacy path)
npx vitest run                            # vs live PG (content + auth suites)
node src/server.ts                        # local server :8787 (background)
```

**Auth (AUTH-S1, ADR 0021) — real tokens without hardcoding (LOCAL/test only).**
The API now mints its own EdDSA tokens + enforces per-request tenancy. Env needed:
`AUTH_SIGNING_KEY` (Ed25519 private JWK w/ `kid`), `AUTH_ISS`, `AUTH_AUD`. To get a
token locally without Firebase, enable the **gated dev-token** endpoint (refuses in
prod/preview): `ENABLE_DEV_AUTH=1 DEV_AUTH_SECRET=… node src/server.ts`, then
`POST /auth/dev-token` (Bearer `$DEV_AUTH_SECRET`, body `{provider:"dev",provider_uid:"alice"}`)
→ `{access, refresh}`. `POST /families {name}` with that access JWT → mints a family
(creator=owner) + binds the cred. Use `access` as `Bearer` on `/families/{fid}/*`.
**The legacy `HOUSEHOLD_SECRET` still works on content routes until the S3 cutover.**
Cloud/device (Pixel) hardcoding fully dies at **AUTH-S3** (CLI device grant).

**Error reporting (SWIP → PostHog + Sentry, ADR 0058).** `npm ci` needs GitHub Packages
auth for `@sloopworks/swip-*`: `export NODE_AUTH_TOKEN=$(gh auth token)`. SWIP is OFF
locally unless `SENTRY_NODE_EU_DSN` is set — `node src/server.ts` stays SWIP-free. To run
it WITH reporting you must use the **bundled** server (SWIP ships TS sources and Node
refuses to type-strip anything under `node_modules`):

```
npm run build:server                                   # → dist/server.js
cd ~/workspace && infisical run --path=/dayfold -- env \
  VERCEL_ENV=development SENTRY_RELEASE=local-$(git rev-parse --short HEAD) \
  ENABLE_DEV_ERRORS=1 DATABASE_URL=postgres:///fad_test \
  node dayfold/apps/api/dist/server.js
curl localhost:8787/debug/boom                         # a thrown route error → both vendors
curl localhost:8787/debug/wtf                          # a deliberate non-crash report
```

`/debug/*` are gated (`ENABLE_DEV_ERRORS=1`, refused in production/preview) exactly like
`/auth/dev-token`. **The flush is the thing to protect:** a Vercel container freezes when
the response returns, so `swipErrors()` awaits `swip.flush(2000)` in a `finally`. Break
that and every test still passes while every event vanishes.

Cloud (live): `https://family-ai-dashboard.vercel.app`. Redeploy (operator-gated;
the `AUTH_*` env must be set in Vercel — see below):
`npm run build:fn && vercel deploy --prod --yes --scope patrick-jacksons-projects-c406a118`.

**Prod config state (2026-06-26):** prod now has the full DB schema (all 11
migrations) + `AUTH_SIGNING_KEY`/`AUTH_ISS`/`AUTH_AUD` + `FIREBASE_PROJECT_ID` set;
real Google sign-in + foreground sync work on-device. This was NOT always true — prod
ran only the legacy `HOUSEHOLD_SECRET` content path for a long time (the AUTH epic +
fanout migrations and the token-signing env were never applied), which 500'd the first
real sign-in (`firebase HTTP 500`) + device-login. **Before any redeploy or when
diagnosing a prod auth 500, run the preflight** (it's why it exists):
`DATABASE_URL=<prod> npm run preflight` (= `env:check` — required env incl. a valid
`AUTH_SIGNING_KEY` JWK; then `db:check` — schema-drift vs `migrations/`). Note the
prod Neon `DATABASE_URL` is a **Sensitive** Vercel var (unreadable via `env pull` /
dashboard) — get the connection string from the Neon console. The durable fix for the
manual-apply process is **ADR 0033** (tracked migration runner; Proposed).

## ⚠ Two-module KMP build at `apps/` (TASK-KMP 2026-06-19 + TASK-CLIENT-MODULARIZE 2026-07-02)

`apps/` contains two shared KMP modules plus a thin Android app:

| Module | `apps/` path | Contents | Compose? | iOS framework? |
|--------|-------------|----------|----------|----------------|
| `:client` | `apps/client/` | **Compose-free core:** reducers, selectors, engines (Now/Sync/Hub/Outbox), data clients (AuthClient, SyncClient), ContentStore, SQLDelight, store, `cards/` logic (CardAction, DetailMeta, TypedCardLogic), fake backend | **No** | Targets only (no framework) |
| `:ui` | `apps/ui/` | **Compose layer:** composables, theme, `cards/` Compose, entry points (Main.kt, MainViewController.kt), `expect/actual` seams (QrScanner, PlatformActions, rememberReduceMotion), composeResources (fonts) | **Yes** | `client.framework` (static, emitted by `:ui`) |
| `:androidApp` | `apps/androidApp/` | Thin Android app — depends on `:ui` | Via `:ui` | N/A |

**Dependency:** `:ui` `api(project(":client"))` — `:ui` sees all of `:client`; `:client` has no Compose dependency.

**Build from `apps/`** (one Gradle root, Gradle 9.4.1 + AGP 9.2.1):
- `:client` logic/data edit → only `:client` recompiles (~7,348 lines, ~2.4s)
- `:ui` composable edit → `:ui` recompiles (~7,434 lines, ~2.6s); `:client` stays UP-TO-DATE for main-only builds; recompiles when targeting `compileTestKotlinDesktop` due to Gradle upstream jar-dependency chain
- Both modules: KT-62686 still fires (full recompile within module) — KMP + Kotlin 2.3.20 project-level issue, not Compose-specific

```
./gradlew :client:<task>   # logic/data layer
./gradlew :ui:<task>       # Compose layer + iOS framework
./gradlew :androidApp:<task>
```
Module-level `cd apps/client` no longer works (no per-module wrapper). ktor: cio desktop · okhttp android · darwin iOS.

## On-device demo (real Compose UI + seeded data, one command)
```
apps/scripts/ondevice-demo.sh          # seed DB + start API + build/install/launch on the phone
apps/scripts/ondevice-demo.sh --down   # teardown
```
Distilled from the first hub-render on-device run. The five gotchas it handles so
you don't re-discover them:
1. **Port 8799 is squatted** by other dev servers (workerd/wrangler) → the script
   auto-picks a free API port; the device still talks to `:8799` via `adb reverse`.
2. **LAN IP is unreachable** (wifi client-isolation / mac firewall) → use
   **`adb reverse` over USB**, never the laptop's LAN IP.
3. **A stale session wedges** on "Couldn't reach Dayfold" → `pm clear` before install.
4. **Sign in with "Continue with Apple"** (not Google): the Android Firebase seam
   returns null for non-google → the app falls back to `/auth/dev-token`. Google
   needs real Firebase config; Apple→dev is the reliable local path.
5. **Node 24 runs the `.ts` API directly** (type stripping), no build step.
The seed (`ondevice-seed.sql`) creates a `dev`/`dev-user` identity matching the
app's dev sign-in, so it lands on a family that already has hubs (one family-
visible, one restricted with the 🔒 lock — exercises the ADR 0030 treatment).

## ⭐ Fake backend (debug-only — render any UI state with NO server/DB/network)
A selectable **in-process MockEngine backend** that serves canned scenarios so the
real UI (auth gate → feed → detail → hubs → members/devices) can be exercised in
debug builds with zero live API. It injects a `HttpClient(MockEngine)` into the
existing transport seams (`SyncClient`/`HubClient`/`AuthClient` all accept an
`http:` param), so the WHOLE dataflow runs — reducers, the DB→store bridge, the
route gate — not just a DB seed. Scenarios serialize the real `@Serializable` wire
models (so field names are correct by construction).

- **Where:** scenarios + the pure router live in `:client` commonMain
  (`client/.../fake/FakeBackend.kt` + `FakeScenarios.kt`, no ktor → release-safe,
  unit-tested in `FakeBackendTest`). The thin MockEngine adapter is debug/desktop-
  only (`androidApp/src/debug/.../FakeBackend.kt` mirrored by an inert `src/release`
  copy, same pattern as `DebugDrawerPlugins.kt`; `ktor-client-mock` is
  `debugImplementation` on Android + `desktopMain` on desktop — never release).
- **Select (Android):** debug drawer → **Backend** panel → pick a `Fake · …` entry →
  Apply & Restart. Then tap any provider on the sign-in screen (fake mode forces the
  `/auth/dev-token` path) → lands on the scenario.
- **Select (desktop):** `DAYFOLD_API=fake://<scenario-id>` (e.g. `fake://busy-family`).
- **Scenarios:** `busy-family` (6 typed cards + 3 hubs incl. a restricted one +
  members + devices), `empty-new` (empty states), `needs-family` (→ CreateFamily),
  `owner-approvals` (pending members + a CLI device grant), `sync-error` (feed
  error). Add one by appending to `FakeScenarios.all`.
- **Gotchas baked in:** `/sync` returns `has_more:false` (else the drain loop spins);
  hub DETAIL content rides in the `/sync` delta (sections+blocks), NOT `/tree` (the
  app is DB-fed); the DB is wiped on entry so prior real/seed rows don't bleed in.

## Client core + desktop (`:client` + `:ui` — KMP modules, post-split 2026-07-02)
```
cd apps && JAVA_HOME=<jdk17> ./gradlew :client:desktopTest   # 440 tests: logic/data/reducers
cd apps && JAVA_HOME=<jdk17> ./gradlew :ui:desktopTest       # 329 tests: Compose snapshots + UI (incl. CL-SNAP golden suite)
cd apps && JAVA_HOME=<jdk17> ./gradlew :swip-wiring:desktopTest  # swip bugreport slice registry + MANDATORY sanitizer leak test (ADR 0054)
```
- **`:client` tests:** reducer/selector/sync/engine unit tests — Compose-free. Run when editing logic, reducers, engines, data clients, store, ContentStore.
- **`:ui` tests:** Compose snapshot tests + UI behavior tests. Run when editing composables, theme, entry points.
- **`:swip-wiring` tests:** the product-owned PII leak test over the swip redux
  timeline recorder (salted real AppState). Run when touching the slice registry
  or sanitizer in `apps/swip-wiring/`. Resolving `works.sloop.swip:*` needs GitHub
  Packages credentials: `gpr.user`/`gpr.token` in `~/.gradle/gradle.properties`
  (read:packages PAT) or `SLOOPWORKS_PACKAGES_TOKEN` env (CI secret).
- Edit guidance: touching `:client` only → `./gradlew :client:desktopTest` (~2.4s compile + tests); touching `:ui` → `./gradlew :ui:desktopTest` (~2.6s compile + tests, :client recompiled first due to jar dependency). Post-merge of CL-SNAP (#277): 440 + 329 = 769 (CL-SNAP's 18 snapshot tests relocated to `:ui`).
- **JUnit gotcha:** a `@Test fun x() = runBlocking { … }` whose LAST expression
  isn't `Unit` (e.g. ends in `assertFailsWith` → returns `Throwable`) is
  **silently NOT run** (JUnit ignores non-void test methods). Use
  `runBlocking<Unit> { … }`. Verify test COUNTS, not just BUILD SUCCESSFUL.
- **Snapshots land in `apps/ui/build/snapshots/*.png`** — `Read` them to
  verify UI without a device. (The hand-rolled `FeedSnapshotTest` writes raw
  PNGs, no diff.) **Golden-diff is now `rk snapshot` — see below; it supersedes
  the Roborazzi-DIY plan in ADR 0019's "remaining".**

## ⭐ rk snapshot — headless render + golden diff (the rapid-confirmation loop)
`rk snapshot` renders a **scene** (a Compose screen) from a **state** to a PNG
**off-screen in ms**, and verifies it against a **golden** — the fastest way for
an agent to *see* what a change produced and to catch visual regressions.

**Status (CL-SNAP, delivered):**
- Dep: `org.reduxkotlin:redux-kotlin-snapshot:1.0.0-alpha04` (Maven Central),
  scoped `desktopTest` in `apps/ui/build.gradle.kts` (relocated `:client`→`:ui`
  in the modularize merge — the scenes render Compose, which lives in `:ui`).
- Scene registry: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/SnapshotScenes.kt`
  — `clientSnapshots` with 20 scenes covering every client surface: `feed`,
  `hub-detail`, `hub-list`, `detail`, `auth`, `account`, `join`, `members`,
  `devices`, `device-approval`, `scan`, `notif`, `privacy`, `places`,
  `proximity`, `permission`, `offline-banner`, `kit`, `timeline-card`,
  `timeline-detail` (run `--list` for presets). State fixtures come from
  `SnapshotStates.kt` (hand-built `AppState` literals — reuses the tests'
  existing fixtures, **not** `FakeScenarios`).
- 131 goldens committed in `apps/ui/src/desktopTest/resources/snapshots/`
  (light + selected dark variants per surface).

**CLI entry (Gradle — NOT the brew `rk` binary):**
The brew `rk` binary only carries its own demo scenes. Our scenes run via the
Gradle task. **Args are OPTIONS ONLY — no leading `snapshot` token.**
(Old examples writing `--args="snapshot --scene …"` were wrong — the lib
command IS snapshot; the `rk snapshot` prefix applies only to the standalone
brew binary, which doesn't know our scenes.)

List all scenes/presets (JSON):
```
cd apps && ./gradlew :ui:snapshotUi -PsnapshotArgs="--list"
```
Render one state → PNG, then `Read` it:
```
cd apps && ./gradlew :ui:snapshotUi -PsnapshotArgs="--scene feed --preset busy --out /tmp/x.png"
```
Semantics smoke (Tier-0, **zero vision tokens** — confirmed working in alpha04):
```
cd apps && ./gradlew :ui:snapshotUi -PsnapshotArgs="--scene feed --preset busy --semantics --out /tmp/x.png"
```
Prints the semantic node tree (roles, text, descriptions, selected state) to
stdout. Use this first for content/refactor changes — no image read needed.

**Tiered agent loop:**
- **Tier 0 — semantics text** (`--semantics`): content + accessibility asserts,
  zero vision tokens. Start here for content/refactor changes.
- **Tier 1 — golden verdict** (`GoldenSnapshotTest` / batch `report.json`):
  text pass/fail from `:client:desktopTest`. Start here for regression checks.
- **Tier 2 — read the PNG**: only when a shot drifted or after a deliberate
  visual change you want to eyeball.

**Golden gate (CI):** `GoldenSnapshotTest` in `desktopTest` verifies the
committed goldens at `maxDiffPercent = 4.0` against a **per-OS golden set**
(`snapshots/macos/` for the local dev/agent loop, `snapshots/linux/` for CI —
picked by `os.name`). Why per-OS: macOS (CoreText) and linux (FreeType)
rasterize the same fonts with slightly different glyph advances — bold-dense
scenes drift 2–3% and long paragraphs can flip a line-wrap, shifting whole
layouts (measured up to 22%) — no single tolerance gates that honestly. The
4% only absorbs same-OS / cross-arch Skiko AA. Snapshot renders pin the clock
(`SNAPSHOT_NOW` / `TIMELINE_NOW`) so dates can't go stale. Re-record BOTH
sets after an intentional visual change:
```
# macOS set (local):
cd apps && ./gradlew :ui:desktopTest --tests "*GoldenSnapshotTest" -Dsnapshot.record=true
# linux set (docker; Skiko needs libGL+libEGL, default heap OOM-kills gradle):
docker run --rm --memory=7g -v "$(git rev-parse --show-toplevel)":/repo -w /repo/apps \
  -e GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx2g" eclipse-temurin:17-jdk bash -c \
  'apt-get update -qq && apt-get install -y -qq libgl1 libegl1 libgles2 libglx-mesa0 fontconfig >/dev/null 2>&1; \
   ./gradlew --no-daemon :ui:desktopTest --tests "*GoldenSnapshotTest*" -Dsnapshot.record=true'
```
Then **eyeball the changed PNGs** before committing.

**Golden dashboard (HTML):** render all 131 shots + verify against a golden
set + emit a self-contained `index.html` (per-shot image, verdict + diff%,
magenta diff overlay on mismatch, failures sorted first):
```
cd apps && ./gradlew :ui:snapshotUi -PsnapshotArgs="--batch snapshot-shots.json --golden-dir src/desktopTest/resources/snapshots/macos --dashboard"
open ui/.rk-snapshots/index.html
```
The shot list is the committed `apps/ui/snapshot-shots.json`
(`SnapshotScenesTest.batchManifestMatchesGoldens` keeps it in sync with the
goldens). CI runs the same dashboard against `snapshots/linux/` when the
golden gate fails and uploads `.rk-snapshots/` as the `snapshot-dashboard`
artifact — clickable visual diffs straight from the failed PR run.

**Headless caveat:** no async image loading → enriched presets render the
icon+accent fallback, not the hero image. Expected behavior.

## Android (`:androidApp` — the real device target)
```
cd apps
SDK=~/Library/Android/sdk; DEV=$($SDK/platform-tools/adb devices | awk 'NR>1&&$2=="device"{print $1;exit}')
DAYFOLD_API=https://family-ai-dashboard.vercel.app FAMILY_ID=… HOUSEHOLD_SECRET=… \
  ANDROID_HOME=$SDK JAVA_HOME=<jdk17> ./gradlew :androidApp:assembleDebug
$SDK/platform-tools/adb -s $DEV install -r androidApp/build/outputs/apk/debug/dayfold-android-debug.apk
$SDK/platform-tools/adb -s $DEV shell am start -n com.sloopworks.dayfold/com.sloopworks.dayfold.android.MainActivity
$SDK/platform-tools/adb -s $DEV exec-out screencap -p > /tmp/x.png   # then Read it
```
- Emulators are usually up (`emulator-5554/5556`); the physical **Pixel 10 Pro**
  comes and goes on USB — re-pick `$DEV` each time.
- BuildConfig bakes `DAYFOLD_API/FAMILY_ID/HOUSEHOLD_SECRET` at build time
  (emulator→host = `http://10.0.2.2:8799`).

## iOS (`:ui` framework — TASK-KMP + TASK-CLIENT-MODULARIZE)
```
cd apps && JAVA_HOME=<jdk17> ./gradlew :ui:compileKotlinIosArm64 \
  :ui:linkDebugFrameworkIosSimulatorArm64    # → ui/build/bin/iosSimulatorArm64/debugFramework/client.framework
```
- The iOS framework is now emitted by **`:ui`** (not `:client` — split 2026-07-02, ADR 0047).
  Header path: `apps/ui/build/bin/iosSimulatorArm64/debugFramework/client.framework/Headers/client.h`
- Targets: **iosArm64** (device) + **iosSimulatorArm64** (Apple-Silicon sim).
  **No iosX64** (intel sim) — redux-kotlin-granular alpha01 has no iosX64 publish.
- `MainViewController()` (iosMain in `:ui`) = `ComposeUIViewController { FeedApp(store) }`,
  the entry a Swift `@main` app embeds. **No Xcode project yet** — the runnable
  iosApp shell (Swift host + signing + sim run) + iOS sync-config = operator-gated
  / TASK-SYNC. Xcode 26.2 + Kotlin/Native 2.3.20 confirmed present on this Mac.

## Observe the redux loop (cheap, text-first)
- **Action log → stdout/logcat** (the `[redux] <Action> → cards=… syncing=… error=…`
  line from `createAppStore`'s middleware): on Android
  `adb -s $DEV logcat -s System.out:I | grep redux`; on desktop it's the run
  stdout. Use this FIRST — it's text, no vision tokens.
- **`rk devtools` (text-first, scriptable — preferred over the drawer):** wire a
  debug-only `BridgeOutput` into the store init (alongside the existing
  `devTools(...)` enhancer) → the store streams to `127.0.0.1:9090`:
  ```kotlin
  // debug builds only:
  DevToolsHub.registerOutput(BridgeOutput(BridgeConfig(
    host="127.0.0.1", port=9090, startEnabled=true, clientLabel="dayfold-client")))
  ```
  Then, in a side terminal: `rk devtools serve` (writes `.rk-devtools/<store>.jsonl`).
  Inspect from the CLI — all **text**, no vision tokens:
  `rk devtools actions --last 5 --type '*Card*'` · `rk devtools diff --since N --until N --pretty`
  (per-field `{op,path,before,after}`) · `rk devtools state --at N --pretty` ·
  `rk devtools tail --follow --type '*Detail*'` (live) · `rk devtools stores`.
  Captures are `.jsonl` → committable to a bug report and agent-readable directly.
  **Use this to confirm reducer behavior** (e.g. `OpenDetail`/`CloseDetail`, the
  M0 display-only RSVP, sync deltas) without a screenshot.
- **DevTools drawer** (Android debug): a floating **BUBBLE** (action count) opens
  ACTIONS/STATE/DIFF/PIPELINE/OUTPUTS (time-travel). Needs a screenshot to read →
  use only when the text log + `rk devtools` aren't enough.
- **Snapshot PNGs / `rk snapshot`** (above) for UI checks.

## Now available (updated 2026-07-02 — supersedes the old "not available" note)
- **redux-kotlin CLI `rk`**: **PUBLISHED** via Homebrew (`reduxkotlin/tap/rk`,
  1.0.0-alpha02). devtools + snapshot, both wired above. (Mind the broken brew
  symlink — see Toolchain.)
- **screenshot/golden module**: **`redux-kotlin-snapshot:1.0.0-alpha04`** is on
  Maven Central. Headless render + committed-golden CI gate are **DELIVERED**
  (CL-SNAP) — no Roborazzi DIY needed. Realizes ADR 0019 "Remaining" items #4
  and #6. See the `⭐ rk snapshot` section above for the exact CLI and golden
  workflow.
