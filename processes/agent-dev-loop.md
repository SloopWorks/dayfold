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
- **redux-kotlin `1.0.0-alpha01`** gotchas: `selectorState`/`fieldState` are
  **extensions** → `store.selectorState{}` (not `selectorState(store)`); the
  compose module needs `redux-kotlin-granular` added **explicitly** (not pulled
  transitively); the android module pins `kotlin-stdlib` to 2.3.20.
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

## ⚠ Single Gradle build at `apps/` (TASK-KMP, 2026-06-19)
`apps/client` is now a **true KMP module** (`commonMain` = all shared logic+UI+
SQLDelight+ktor sync; `androidMain`/`desktopMain` = driver actual + entrypoint;
iOS target = pending). `apps/androidApp` is a **thin app** depending on `:client`
(no srcDir borrow, no excludes). **One Gradle root at `apps/`** (Gradle 9.4.1 +
AGP 9.2.1 since PR #26). Run from `apps/`:
`./gradlew :client:<task>` / `:androidApp:<task>`. Module-level `cd apps/client`
no longer works (no per-module wrapper/settings). ktor: cio desktop · okhttp
android · darwin iOS (when added). SyncClient is now `suspend` (no Dispatchers.IO).

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

## Client core + desktop (`:client` — KMP core + Compose desktop)
```
cd apps && JAVA_HOME=<jdk17> ./gradlew :client:desktopTest
```
- Reducer/selector/sync unit tests + **Compose snapshot tests** (all in
  `desktopTest`). 24 tests green post-TASK-SYNC.
- **JUnit gotcha:** a `@Test fun x() = runBlocking { … }` whose LAST expression
  isn't `Unit` (e.g. ends in `assertFailsWith` → returns `Throwable`) is
  **silently NOT run** (JUnit ignores non-void test methods). Use
  `runBlocking<Unit> { … }`. Verify test COUNTS, not just BUILD SUCCESSFUL.
- **Snapshots land in `apps/client/build/snapshots/*.png`** — `Read` them to
  verify UI without a device. (The hand-rolled `FeedSnapshotTest` writes raw
  PNGs, no diff.) **Golden-diff is now `rk snapshot` — see below; it supersedes
  the Roborazzi-DIY plan in ADR 0019's "remaining".**

## ⭐ rk snapshot — headless render + golden diff (the rapid-confirmation loop)
`rk snapshot` renders a **scene** (a Compose screen) from a **state** to a PNG
**off-screen in ms**, and verifies it against a **golden** — the fastest way for
an agent to *see* what a change produced and to catch visual regressions.

**Status (CL-SNAP, delivered):**
- Dep: `org.reduxkotlin:redux-kotlin-snapshot:1.0.0-alpha04` (Maven Central),
  scoped `desktopTest` in `apps/client/build.gradle.kts`.
- Scene registry: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/SnapshotScenes.kt`
  — `clientSnapshots` with 20 scenes covering every client surface: `feed`,
  `hub-detail`, `hub-list`, `detail`, `auth`, `account`, `join`, `members`,
  `devices`, `device-approval`, `scan`, `notif`, `privacy`, `places`,
  `proximity`, `permission`, `offline-banner`, `kit`, `timeline-card`,
  `timeline-detail` (run `--list` for presets). State fixtures come from
  `SnapshotStates.kt` (hand-built `AppState` literals — reuses the tests'
  existing fixtures, **not** `FakeScenarios`).
- 131 goldens committed in `apps/client/src/desktopTest/resources/snapshots/`
  (light + selected dark variants per surface).

**CLI entry (Gradle — NOT the brew `rk` binary):**
The brew `rk` binary only carries its own demo scenes. Our scenes run via the
Gradle task. **Args are OPTIONS ONLY — no leading `snapshot` token.**
(Old examples writing `--args="snapshot --scene …"` were wrong — the lib
command IS snapshot; the `rk snapshot` prefix applies only to the standalone
brew binary, which doesn't know our scenes.)

List all scenes/presets (JSON):
```
cd apps && ./gradlew :client:snapshotUi -PsnapshotArgs="--list"
```
Render one state → PNG, then `Read` it:
```
cd apps && ./gradlew :client:snapshotUi -PsnapshotArgs="--scene feed --preset busy --out /tmp/x.png"
```
Semantics smoke (Tier-0, **zero vision tokens** — confirmed working in alpha04):
```
cd apps && ./gradlew :client:snapshotUi -PsnapshotArgs="--scene feed --preset busy --semantics --out /tmp/x.png"
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
cd apps && ./gradlew :client:desktopTest --tests "*GoldenSnapshotTest" -Dsnapshot.record=true
# linux set (docker; Skiko needs libGL+libEGL, default heap OOM-kills gradle):
docker run --rm --memory=7g -v "$(git rev-parse --show-toplevel)":/repo -w /repo/apps \
  -e GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx2g" eclipse-temurin:17-jdk bash -c \
  'apt-get update -qq && apt-get install -y -qq libgl1 libegl1 libgles2 libglx-mesa0 fontconfig >/dev/null 2>&1; \
   ./gradlew --no-daemon :client:desktopTest --tests "*GoldenSnapshotTest*" -Dsnapshot.record=true'
```
Then **eyeball the changed PNGs** before committing.

**Golden dashboard (HTML):** render all 131 shots + verify against a golden
set + emit a self-contained `index.html` (per-shot image, verdict + diff%,
magenta diff overlay on mismatch, failures sorted first):
```
cd apps && ./gradlew :client:snapshotUi -PsnapshotArgs="--batch snapshot-shots.json --golden-dir src/desktopTest/resources/snapshots/macos --dashboard"
open client/.rk-snapshots/index.html
```
The shot list is the committed `apps/client/snapshot-shots.json`
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

## iOS (`:client` framework — TASK-KMP)
```
cd apps && JAVA_HOME=<jdk17> ./gradlew :client:compileKotlinIosArm64 \
  :client:linkDebugFrameworkIosSimulatorArm64    # → client/build/bin/iosSimulatorArm64/debugFramework/client.framework
```
- Targets: **iosArm64** (device) + **iosSimulatorArm64** (Apple-Silicon sim).
  **No iosX64** (intel sim) — redux-kotlin-granular alpha01 has no iosX64 publish.
- `MainViewController()` (iosMain) = `ComposeUIViewController { FeedApp(store) }`,
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
