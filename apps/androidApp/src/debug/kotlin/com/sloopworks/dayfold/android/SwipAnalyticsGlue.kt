package com.sloopworks.dayfold.android

import android.app.Application
import android.os.SystemClock
import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.createAppStore
import com.sloopworks.dayfold.swip.NoOpErrors
import com.sloopworks.dayfold.swip.dayfoldMappers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.reduxkotlin.Store
import org.reduxkotlin.StoreEnhancer
import org.reduxkotlin.applyMiddleware
import org.reduxkotlin.compose
import works.sloop.swip.CollectionMode
import works.sloop.swip.SwipInstance
import works.sloop.swip.lifecycle.SwipLifecycle
import works.sloop.swip.lifecycle.SwipLifecycleHandle
import works.sloop.swip.platform.AndroidSwipStorage
import works.sloop.swip.platform.HttpUrlConnectionPoster
import works.sloop.swip.pipeline.PostHogTransport
import works.sloop.swip.rk.ReplayGuard
import works.sloop.swip.rk.asSloopAnalytics
import works.sloop.swip.rk.swipMiddleware
import works.sloop.swip.schema.dayfold.DayfoldSwip
import kotlin.random.Random

// Debug/internal-only analytics (ADR 0055): live PostHog EU transport, count-only mapper
// table, composed into the SAME innermost store slot as the bug-reporter recorder. Release
// holds an inert same-signature mirror → zero swip-analytics bytes in the public APK.
private object SwipAnalyticsHolder {
  val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  var swip: SwipInstance? = null
  var storage: AndroidSwipStorage? = null
  val mappers = dayfoldMappers()
  var lifecycle: SwipLifecycleHandle? = null
}

private fun requireSwip(): SwipInstance =
  SwipAnalyticsHolder.swip ?: error("swipInit(app) must run before the store is created")

/** Init the analytics runtime once, BEFORE the store is created. */
fun swipInit(app: Application) {
  if (SwipAnalyticsHolder.swip != null) return
  val storage = AndroidSwipStorage(app).also { SwipAnalyticsHolder.storage = it }
  SwipAnalyticsHolder.swip = works.sloop.swip.Swip.init(
    DayfoldSwip.androidProd(),
    DayfoldSwip.platformDeps(
      transport = PostHogTransport(BuildConfig.POSTHOG_PROJECT_KEY, BuildConfig.POSTHOG_HOST, HttpUrlConnectionPoster()),
      storage = storage,
      appVersion = BuildConfig.VERSION_NAME,
      os = "android",
      nowMs = { System.currentTimeMillis() },
      monotonicNowMs = { SystemClock.elapsedRealtime() },
      random = { Random.nextDouble() },
      initialMode = CollectionMode.FULL,
    ),
    SwipAnalyticsHolder.scope,
  )
}

/** The ONE enhancer MainActivity passes to createAppStore: recorder (outer) ∘ analytics middleware. */
fun debugStoreEnhancer(): StoreEnhancer<AppState>? = compose(
  listOfNotNull(
    bugReporterEnhancer(),
    applyMiddleware(
      swipMiddleware<AppState>(
        analytics = requireSwip().analytics.asSloopAnalytics(),
        errors = NoOpErrors,
        mappers = SwipAnalyticsHolder.mappers,
        config = null,
        replayGuard = ReplayGuard.detectDevtools(isDebug = BuildConfig.DEBUG),
        consentGate = { requireSwip().analytics.collectionMode() in setOf(CollectionMode.FULL, CollectionMode.PSEUDONYMOUS) },
      ),
    ),
  ),
)

/** Foreground/background lifecycle + route-driven screen_view. Call AFTER the store exists. */
fun swipLifecycleInstall(app: Application, store: Store<AppState>) {
  val storage = SwipAnalyticsHolder.storage ?: return
  val handle = SwipLifecycle.install(app, requireSwip().analytics, storage)
  SwipAnalyticsHolder.lifecycle = handle
  var last: String? = null
  fun report() {
    val name = store.state.route.name
    if (name != last) { last = name; handle.screen(name) }
  }
  report()                       // initial screen
  store.subscribe { report() }   // dedup on route change
}
