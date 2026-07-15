@file:OptIn(works.sloop.swip.ExperimentalSwipDebugApi::class)

package com.sloopworks.dayfold.android

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.createAppStore
import com.sloopworks.dayfold.swip.ReplaceableStoreSubscription
import com.sloopworks.dayfold.swip.dayfoldMappers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.reduxkotlin.Store
import org.reduxkotlin.StoreEnhancer
import org.reduxkotlin.applyMiddleware
import org.reduxkotlin.compose
import works.sloop.swip.CollectionMode
import works.sloop.swip.ConsentDecision
import works.sloop.swip.ConsentScope
import works.sloop.swip.SwipInstance
import works.sloop.swip.db.SwipDb
import works.sloop.swip.errors.CrashReporter
import works.sloop.swip.lifecycle.SwipLifecycle
import works.sloop.swip.lifecycle.SwipLifecycleHandle
import works.sloop.swip.platform.AndroidSwipStorage
import works.sloop.swip.platform.HttpUrlConnectionPoster
import works.sloop.swip.platform.isMainProcess
import works.sloop.swip.platform.swipDbDriver
import works.sloop.swip.pipeline.SqlDelightPersistentQueue
import works.sloop.swip.pipeline.persistenceForProcess
import works.sloop.swip.rk.ReplayGuard
import works.sloop.swip.rk.asSloopAnalytics
import works.sloop.swip.rk.swipMiddleware
import works.sloop.swip.schema.dayfold.DayfoldSwip
import works.sloop.swip.sentry.SentryInitConfig
import works.sloop.swip.sentry.SentryRegion
import works.sloop.swip.sentry.initSentryAndroid
import kotlin.random.Random

// Debug/internal-only analytics (ADR 0055): live PostHog EU transport, count-only mapper
// table, composed into the SAME innermost store slot as the bug-reporter recorder. Release
// holds an inert same-signature mirror → zero swip-analytics bytes in the public APK.
// internal (not private): SwipInspectorGlue.kt (Task 4) shares this holder's scope +
// debugSink field to build ONE RingDebugSink for both Swip.init and plugin registration.
internal object SwipAnalyticsHolder {
  val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  var swip: SwipInstance? = null
  var storage: AndroidSwipStorage? = null
  val mappers = dayfoldMappers()
  var lifecycle: SwipLifecycleHandle? = null
  var screenSubscription: ReplaceableStoreSubscription<AppState, String>? = null
  var bgFlushInstalled = false
  var debugSink: works.sloop.swip.debug.RingDebugSink? = null
}

private fun requireSwip(): SwipInstance =
  SwipAnalyticsHolder.swip ?: error("swipInit(app) must run before the store is created")

private val TRACKING_MODES = setOf(CollectionMode.FULL, CollectionMode.PSEUDONYMOUS)

/** Init the analytics runtime once, BEFORE the store is created. */
fun swipInit(app: Application) {
  // Application.onCreate fires in EVERY process; only the main (UI) process runs the pipeline
  // + Sentry. A second process here would double-init Sentry and contend on the crash marker.
  if (!isMainProcess(app)) return
  if (SwipAnalyticsHolder.swip != null) return
  val storage = AndroidSwipStorage(app).also { SwipAnalyticsHolder.storage = it }
  // Crash/error vendor (ADR 0060). initSentryAndroid is suspend (prepares the crash-marker
  // file off-main + recovers a prior crash's marker), and MUST complete before Swip.init, so
  // it is awaited once here. Empty DSN (no Infisical) ⇒ no Sentry, NoOpCrashReporter default.
  // projectId is the KMP project, declared INDEPENDENTLY of the DSN so verifyDsn can catch a
  // wrong-DSN paste (the API's or the legacy project) by failing the boot.
  val crashReporter: CrashReporter = if (BuildConfig.SENTRY_KOTLIN_EU_DSN.isNotBlank()) {
    runBlocking(Dispatchers.IO) {
      initSentryAndroid(
        app,
        SentryInitConfig(
          dsn = BuildConfig.SENTRY_KOTLIN_EU_DSN,
          region = SentryRegion.EU,
          orgId = "o4511720596570112",
          projectId = "4511734711189584",
          release = BuildConfig.VERSION_NAME,
          dist = BuildConfig.VERSION_CODE.toString(),
          environment = "development",
          debug = BuildConfig.DEBUG,
        ),
      )
    }
  } else {
    works.sloop.swip.errors.NoOpCrashReporter
  }
  SwipAnalyticsHolder.swip = works.sloop.swip.Swip.init(
    DayfoldSwip.androidProd(),
    DayfoldSwip.platformDeps(
      transport = DayfoldSwip.androidProdTransport(BuildConfig.POSTHOG_PROJECT_KEY, BuildConfig.POSTHOG_HOST, HttpUrlConnectionPoster()),
      storage = storage,
      appVersion = BuildConfig.VERSION_NAME,
      os = "android",
      nowMs = { System.currentTimeMillis() },
      monotonicNowMs = { SystemClock.elapsedRealtime() },
      random = { Random.nextDouble() },
      initialMode = CollectionMode.FULL,
      // Durable queue (docs/02). Without it the pipeline is memory-only: anything not
      // flushed before the process dies is lost. Batches persist to SQLite (WAL), and
      // Swip.init recovers un-acked ones on next launch so they drain on the next flush.
      // persistenceForProcess enforces the main-process-only guard — a second writer on
      // swip.db is forbidden; non-main processes run memory-only.
      persistence = persistenceForProcess(
        isMainProcess(app),
        SqlDelightPersistentQueue(SwipDb(swipDbDriver(app))),
      ),
    ).copy(debugSink = SwipInspectorGlue.debugSink(), crashReporter = crashReporter),
    SwipAnalyticsHolder.scope,
  )
  // CollectionMode and per-scope consent are ORTHOGONAL in swip-core: initialMode=FULL alone
  // leaves every event parked in the pipeline's pre-consent buffer (scope defaults to UNKNOWN)
  // → enqueued but never batched/sent. ADR 0055 ratified product analytics for the operator's
  // own dogfood household, so grant ANALYTICS consent here to actually ship events. Debug-only
  // glue (release is inert); widening to real users stays a future ADR + real consent surface.
  SwipAnalyticsHolder.swip?.analytics?.setConsent(
    mapOf(
      ConsentScope.ANALYTICS to ConsentDecision.GRANTED,
      ConsentScope.ERRORS to ConsentDecision.GRANTED,
    ),
  )
}

/** The ONE enhancer MainActivity passes to createAppStore: recorder (outer) ∘ analytics middleware. */
fun debugStoreEnhancer(): StoreEnhancer<AppState>? = compose(
  listOfNotNull(
    bugReporterEnhancer(),
    applyMiddleware(
      swipMiddleware<AppState>(
        analytics = requireSwip().analytics.asSloopAnalytics(),
        errors = requireSwip().errors,
        mappers = SwipAnalyticsHolder.mappers,
        config = null,
        replayGuard = ReplayGuard.detectDevtools(isDebug = BuildConfig.DEBUG),
        consentGate = { requireSwip().analytics.collectionMode() in TRACKING_MODES },
      ),
    ),
  ),
)

/**
 * Foreground/background lifecycle + route-driven screen_view. Call AFTER the store exists.
 * Idempotent per-process: the SwipLifecycle observer installs once and is reused across
 * Activity recreations (rotation/config change); the screen_view subscription atomically re-binds
 * to the fresh store and unsubscribes the prior one so the process singleton cannot retain it.
 */
fun swipLifecycleInstall(app: Application, store: Store<AppState>) {
  val storage = SwipAnalyticsHolder.storage ?: return
  val handle = SwipAnalyticsHolder.lifecycle
    ?: SwipLifecycle.install(app, requireSwip().analytics, storage).also { SwipAnalyticsHolder.lifecycle = it }
  installBackgroundFlush()
  val screens = SwipAnalyticsHolder.screenSubscription
    ?: ReplaceableStoreSubscription<AppState, String>(
      select = { it.route.name },
      onChanged = handle::screen,
    ).also {
      SwipAnalyticsHolder.screenSubscription = it
    }
  screens.bind(store)
}

/**
 * Flush on background — BackgroundFlusher semantics (docs/02), no scheduled wakeups
 * (INVARIANT 14). Without this, buffered events only ship on the 30s ticker or at 30
 * queued events, so backgrounding the app stranded whatever was pending.
 *
 * `flush()` forms batches, persists them, then makes ONE send attempt inside the brief
 * window Android leaves the process alive after onStop. If the OS kills us mid-send the
 * batches stay on disk (PersistentQueue above) and `Swip.init` recovers them on the next
 * launch — so nothing is lost, it just ships late. Deliberately NOT WorkManager: a
 * scheduled wakeup would violate INVARIANT 14 and buys little once the queue is durable.
 *
 * Installed once per process (this runs again on Activity recreation); registered AFTER
 * SwipLifecycle's observer so its persist-on-background runs before our send attempt.
 */
private fun installBackgroundFlush() {
  if (SwipAnalyticsHolder.bgFlushInstalled) return
  SwipAnalyticsHolder.bgFlushInstalled = true
  ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
    override fun onStop(owner: LifecycleOwner) {
      SwipAnalyticsHolder.scope.launch { requireSwip().analytics.flush() }
    }
  })
}
