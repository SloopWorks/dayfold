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

## API (apps/api — TS/Hono/Postgres)
```
cd apps/api
export DATABASE_URL=postgres:///fad_test
psql -d fad_test -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" && psql -d fad_test -f migrations/0001_m0_init.sql
node scripts/provision.mjs "Fam"          # → FAMILY_ID / HOUSEHOLD_CREDENTIAL_ID / HOUSEHOLD_SECRET
npx vitest run                            # 19 tests vs live PG
node src/server.ts                        # local server :8787 (background)
```
Cloud (live): `https://family-ai-dashboard.vercel.app`. Redeploy:
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
  verify UI without a device. (Regenerated each run; gitignored. Golden-diff =
  next, ADR 0019.)

## Android (`:androidApp` — the real device target)
```
cd apps
SDK=~/Library/Android/sdk; DEV=$($SDK/platform-tools/adb devices | awk 'NR>1&&$2=="device"{print $1;exit}')
FAMILYAI_API=https://family-ai-dashboard.vercel.app FAMILY_ID=… HOUSEHOLD_SECRET=… \
  ANDROID_HOME=$SDK JAVA_HOME=<jdk17> ./gradlew :androidApp:assembleDebug
$SDK/platform-tools/adb -s $DEV install -r androidApp/build/outputs/apk/debug/familyai-android-debug.apk
$SDK/platform-tools/adb -s $DEV shell am start -n com.familyai.client/com.familyai.client.android.MainActivity
$SDK/platform-tools/adb -s $DEV exec-out screencap -p > /tmp/x.png   # then Read it
```
- Emulators are usually up (`emulator-5554/5556`); the physical **Pixel 10 Pro**
  comes and goes on USB — re-pick `$DEV` each time.
- BuildConfig bakes `FAMILYAI_API/FAMILY_ID/HOUSEHOLD_SECRET` at build time
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
- **DevTools drawer** (Android debug): a floating **BUBBLE** (shows action count)
  opens ACTIONS/STATE/DIFF/PIPELINE/OUTPUTS (time-travel). Needs a screenshot to
  read → use only when the text log isn't enough.
- **Snapshot PNGs** (above) for UI checks.

## Not available (don't hunt for these)
- **redux-kotlin CLI**: not published (npm/maven) — nothing to wire (INB-15).
- **screenshot/golden module**: none from reduxkotlin; golden-diff = Roborazzi
  (DIY, ADR 0019 remaining).
