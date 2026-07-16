package com.sloopworks.dayfold.android

import android.content.Context
import android.hardware.SensorManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.Log
import com.sloopworks.dayfold.swip.dayfoldRecorder
import com.sloopworks.dayfold.swip.dayfoldSlices
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import org.reduxkotlin.StoreEnhancer
import works.sloop.swip.bugreport.BugReportsConfig
import works.sloop.swip.bugreport.ReportGate
import works.sloop.swip.bugreport.ReportIdGenerator
import works.sloop.swip.bugreport.SloopBugReports
import works.sloop.swip.bugreport.capture.BreadcrumbsProvider
import works.sloop.swip.bugreport.capture.CaptureSources
import works.sloop.swip.bugreport.capture.shake.ShakeDetector
import works.sloop.swip.bugreport.lane.Clock
import works.sloop.swip.bugreport.lane.ReportLane
import works.sloop.swip.bugreport.lane.SdkHealthCounter
import works.sloop.swip.bugreport.model.ContextBlock
import works.sloop.swip.bugreport.model.ReportType
import works.sloop.swip.bugreport.platform.AndroidScreenshotProvider
import works.sloop.swip.bugreport.platform.AndroidShakeSource
import works.sloop.swip.bugreport.ui.BugReporterController
import works.sloop.swip.bugreport.ui.BugReporterOverlay
import works.sloop.swip.bugreport.ui.EntryStyle
import works.sloop.swip.bugreport.ui.scrub.ScrubberOverlay
import works.sloop.swip.rk.recorder.ReduxTimelineRecorder
import works.sloop.swip.rk.recorder.ScrubberEngine

// Debug variant: the real swip bug reporter (shake/edge-tab → capture → annotate →
// review → on-device report lane; no upload — gateway is SWIP Phase 1). Mirrors the
// debugDrawerPlugins() idiom: src/main calls these three functions; src/release
// holds inert same-signature mirrors, so the release APK carries zero swip bytes.
private object BugReporterHolder {
  /** WeakReference: capturing the Activity directly would leak it and go stale after rotation. */
  var currentActivity: WeakReference<ComponentActivity>? = null
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val recorder: ReduxTimelineRecorder<AppState> by lazy {
    // Singleton across Activity recreations — the enhancer re-wraps each fresh store.
    dayfoldRecorder(scope, BuildConfig.VERSION_NAME, Clock { System.currentTimeMillis() })
  }
  var controller: BugReporterController? = null

  /** 32-deep breadcrumb ring fed by wrapping Log.sink. Guarded by itself. */
  val crumbs = ArrayDeque<String>()
  var crumbsWired = false
}

/** Store-construction enhancer (innermost slot) — the redux timeline recorder. */
fun bugReporterEnhancer(): StoreEnhancer<AppState>? = BugReporterHolder.recorder.enhancer()

/** Idempotent per-Activity install: singletons once, per-activity shake + lifecycle. */
fun bugReporterInstall(activity: ComponentActivity) {
  val holder = BugReporterHolder
  holder.currentActivity = WeakReference(activity)

  // Breadcrumbs: wrap the existing Log.sink ONCE (keep the DebugLog forwarding
  // MainActivity installed) and append "tag: msg" into the ring. Installed after the
  // MainActivity sink assignment, so `prior` is the drawer bridge.
  if (!holder.crumbsWired) {
    holder.crumbsWired = true
    val prior = Log.sink
    Log.sink = { l, t, m, c, e ->
      prior?.invoke(l, t, m, c, e)
      synchronized(holder.crumbs) {
        holder.crumbs.addLast("$t: ${works.sloop.swip.logging.scrubString(m)}")
        while (holder.crumbs.size > 32) holder.crumbs.removeFirst()
      }
    }
  }

  val controller = holder.controller ?: BugReporterController(
    SloopBugReports(
      BugReportsConfig(
        gate = ReportGate { BuildConfig.DEBUG },
        ids = ReportIdGenerator { "rpt_" + UUID.randomUUID().toString().replace("-", "").take(20) },
        context = {
          ContextBlock(
            appVersion = BuildConfig.VERSION_NAME, osName = "android",
            osVersion = Build.VERSION.RELEASE, device = Build.MODEL,
            locale = Locale.getDefault().toLanguageTag(), channel = "debug",
          )
        },
        identity = { null to null },       // ANONYMOUS — no swip identity stack in dayfold yet
        configState = { null to emptyMap() },
        sources = CaptureSources(
          screenshot = AndroidScreenshotProvider { holder.currentActivity?.get() },
          breadcrumbs = BreadcrumbsProvider { max -> synchronized(holder.crumbs) { holder.crumbs.toList() }.takeLast(max) },
          timeline = holder.recorder,
        ),
        lane = ReportLane(
          fs = FileSystem.SYSTEM,
          dir = works.sloop.swip.bugreport.platform.androidReportDir(activity.applicationContext),
          clock = Clock { System.currentTimeMillis() },
          health = SdkHealthCounter { }, // no-op — no swip telemetry stack in dayfold yet
        ),
      ),
    ),
    holder.scope,
    internalChannel = { true }, // dayfold debug builds are internal by definition (ADR-0021 layer 2)
  ).also { holder.controller = it }

  holder.recorder.activate()

  // Shake → open the reporter. Foreground-only (battery rule): start on RESUME,
  // stop on PAUSE (stop() also resets the detector). Per-activity source; shared controller.
  val sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  val shake = AndroidShakeSource(
    sensorManager,
    ShakeDetector { controller.open(ReportType.BUG, trigger = "shake") },
  )
  activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
    override fun onResume(owner: LifecycleOwner) = shake.start()
    override fun onPause(owner: LifecycleOwner) = shake.stop()
  })
}

/** Wraps app content in the reporter overlay (edge tab entry + review/annotate/scrub sheets). */
@Composable
fun BugReporterWrapped(content: @Composable () -> Unit) {
  val controller = BugReporterHolder.controller
  if (controller == null) {
    content()
    return
  }
  BugReporterOverlay(
    controller,
    dark = isSystemInDarkTheme(),
    showEntryPoint = true,
    entry = EntryStyle.EdgeTab,
    // C10 time travel: swip owns the journal + transport bar; the HOST owns the state
    // type, so it builds the typed engine (our SliceSpecs rehydrate the journal into a
    // real AppState) and renders the replay content. The content MUST be pure
    // presentation — replaying past states through an effectful composable would
    // re-fire its effects — so this is a read-only slice inspector, not FeedApp.
    scrubberContent = { journalJson, onClose ->
      val engine = remember(journalJson) {
        ScrubberEngine(journalJson, AppState(), dayfoldSlices())
      }
      ScrubberOverlay(engine = engine, onClose = onClose) { state -> ReplayedState(state) }
    },
  ) {
    content()
  }
}

/** Pure render of the replayed AppState — only the slices the recorder journals. */
@Composable
private fun ReplayedState(state: AppState) {
  Column(
    Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    for ((label, value) in listOf(
      "route" to state.navigation.route.name,
      "syncing" to state.content.syncing.toString(),
      "cards" to state.content.cards.size.toString(),
      "detailStack" to state.navigation.detailStack.toString(),
      "hubFilter" to state.hubs.filter,
    )) {
      Text("$label = $value", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
  }
}
