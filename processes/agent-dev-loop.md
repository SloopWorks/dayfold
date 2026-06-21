# Agent Dev Loop — build, test, observe (read this before touching the apps)

For future sessions: the **cheap, repeatable feedback loop** for each module, so
you don't re-derive the toolchain (that's the token sink). Hypothesis (unproven,
worth measuring): the **text action log** + **snapshot PNGs** + **devtools** let
an agent verify changes with *text + on-demand image reads* instead of
device-screencap-every-iteration → faster, fewer tokens.

## Toolchain (fixed — don't re-discover)
- **JDK 17** for all Gradle builds: `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home`
  (Gradle's own daemon may be JDK 26; Kotlin needs 17). Each Kotlin module has a
  wrapper (`./gradlew`).
- **Kotlin 2.3.20** · Compose-MP 1.9.3 (desktop) · AGP 8.7.2 (android, wrapper
  Gradle 8.11.1) · apps Gradle 9.5.1 · Node 24 + local Postgres (`psql`) running.
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
set the `AUTH_*` env in Vercel first):
`npm run build:fn && vercel deploy --prod --yes --scope patrick-jacksons-projects-c406a118`.

## ⚠ Single Gradle build at `apps/` (TASK-KMP, 2026-06-19)
`apps/client` is now a **true KMP module** (`commonMain` = all shared logic+UI+
SQLDelight+ktor sync; `androidMain`/`desktopMain` = driver actual + entrypoint;
iOS target = pending). `apps/androidApp` is a **thin app** depending on `:client`
(no srcDir borrow, no excludes). **One Gradle root at `apps/`** (Gradle 8.11.1 +
AGP 8.7.2 — NOT 9.5.1; AGP 8.7 predates stable Gradle 9). Run from `apps/`:
`./gradlew :client:<task>` / `:androidApp:<task>`. Module-level `cd apps/client`
no longer works (no per-module wrapper/settings). ktor: cio desktop · okhttp
android · darwin iOS (when added). SyncClient is now `suspend` (no Dispatchers.IO).

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

**App side (one-time, = epic task CL-SNAP):** add a test-scoped
`redux-kotlin-snapshot` dep (⚠ not yet on Maven Central per the docs — operator
owns reduxkotlin; confirm the published coordinate/version at task time), define
a scene registry, and expose it as a Gradle task `:client:snapshotUi`:
```kotlin
val clientSnapshots = snapshotApp {
  defaults { width = 411; height = 891; density = 2f; theme = "light" }
  scene("feed") { presets("loaded", "empty", "loading")
    render { args -> DayfoldTheme(args.theme) { FeedScreen(stateOf(args)) } } }
  scene("card-invite") { presets("default", "urgent")
    render { args -> DayfoldTheme(args.theme) { CardItem(cardOf(args)) } } }
  // …one scene per card type + per detail type; presets = states; theme = light|dark
}
fun main(args: Array<String>) { clientSnapshots.runCli(args); kotlin.system.exitProcess(0) }
```
The brew `rk` binary only carries its own demo scenes (`rk snapshot --list` →
`counter`/`demo`) — **our scenes run through `./gradlew :client:snapshotUi --args="…"`**.

**Rapid single-shot (agent loop):** render one state → PNG, then `Read` it.
```
cd apps && ./gradlew :client:snapshotUi --args="snapshot --scene card-invite --preset urgent --theme dark --out /tmp/x.png"
```
**Golden verify (single):** `--verify golden.png` exits 1 on drift.
**Batch + golden CI:** a manifest of shots, verified against a golden dir, with
an HTML report:
```
./gradlew :client:snapshotUi --args="snapshot --batch shots.json --out-dir build/snapshots --golden-dir designs/goldens --dashboard --json"
```
`shots.json` = `{ "defaults": {…}, "shots": [ {"id","scene","preset"|"stateJson","theme"?} ] }`.
Exit 1 if any shot mismatches → **this is the golden-diff CI ADR 0019 deferred.**
JUnit path also exists: `SnapshotApp.assertGolden(...)` (goldens under
`src/test/resources/snapshots/`, record with `-Dsnapshot.record=true`).

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

## Now available (2026-06-19 — supersedes the old "not available" note)
- **redux-kotlin CLI `rk`**: **PUBLISHED** via Homebrew (`reduxkotlin/tap/rk`,
  1.0.0-alpha02). devtools + snapshot, both wired above. (Mind the broken brew
  symlink — see Toolchain.)
- **screenshot/golden module**: **`rk snapshot`** provides headless render +
  golden-diff + dashboard — no Roborazzi DIY needed. Realizes ADR 0019's
  remaining golden-diff + CLI items.
