package com.sloopworks.dayfold.client

// ADR 0020 R3 — process-wide API base for the headless refresh worker. The worker is built by
// WorkManager's default WorkerFactory (no Configuration.Provider — see the dependency comment in
// androidApp/build.gradle.kts), so it cannot receive the api-base string through a constructor the
// way DayfoldRuntimeFactory.create() does. DayfoldApp.onCreate sets this from BuildConfig.DAYFOLD_API
// before any other component in the process can run, so RefreshWorker always sees a non-null value.
//
// MainActivity then OVERWRITES it with the EFFECTIVE base — `DebugDrawer.backendUrl(...)`, which in
// a debug build may be a local host or a `fake://` scenario. That is not optional polish: the worker
// and the foreground graph share one process-global ContentStore, so a worker still pointed at prod
// during a local-backend dogfood session would write prod rows into the shared cache AND advance the
// shared sync cursor, leaving the operator's next `/sync?since=<prod-cursor>` meaningless to the
// local server. A `fake://` base means "no network at all this session" — RefreshWorker skips the
// sync step outright rather than resolving it as a URL (see runBackgroundRefresh).
object AndroidApiConfigHolder {
  @Volatile var apiBase: String? = null
}
