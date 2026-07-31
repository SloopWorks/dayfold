package com.sloopworks.dayfold.client

// ADR 0020 R3 — process-wide API base for the headless refresh worker. The worker is built by
// WorkManager's default WorkerFactory (no Configuration.Provider — see the dependency comment in
// androidApp/build.gradle.kts), so it cannot receive the api-base string through a constructor the
// way DayfoldRuntimeFactory.create() does. DayfoldApp.onCreate sets this from BuildConfig.DAYFOLD_API
// before any other component in the process can run, so RefreshWorker always sees a non-null value.
//
// Deliberately NOT kept in sync with the debug-drawer backend override MainActivity may apply at
// runtime (fake-backend scenarios, the emulator-alias switch): that override is a debug-only
// convenience, and this path already promises no fixed cadence, so it is not worth a second source
// of truth to make a background wake respect a UI-only backend switch.
object AndroidApiConfigHolder {
  @Volatile var apiBase: String? = null
}
