package com.sloopworks.dayfold.client

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.ComposeUIViewController
import com.sloopworks.dayfold.client.fake.fakeClientForApi
import com.sloopworks.dayfold.client.fake.initialStateForFakeScenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.reduxkotlin.compose.rememberSelectorStore
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIViewController

// iOS entry — the SHARED FeedApp with the AUTH-S5 route gate. Session persists via
// NSUserDefaults (IosTokenStore). Release keeps the real API base operator-gated;
// DEBUG uses the same deterministic busy-family backend as Android's “Dev sign-in
// (fake)” affordance so that the labelled action is functional on the simulator.
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)   // Platform.isDebugBinary (release-gate DevTools)
fun MainViewController(): UIViewController {
  // One runtime graph belongs to exactly one controller invocation. Keeping construction outside
  // composition prevents a disposed/recreated composition from silently creating a second graph.
  // The database remains process-global for foreground/headless single-writer coordination.
  val debug = kotlin.native.Platform.isDebugBinary
  val scenarioId = "busy-family".takeIf { debug }
  val fakeHttp = scenarioId?.let { fakeClientForApi("fake://$it") }
  val usingFake = fakeHttp != null
  val contentStore = if (usingFake) IosDebugContentStoreHolder.get() else IosContentStoreHolder.get()
  // Headless notification callbacks must use the same selected cache. Simulator DEBUG uses the
  // isolated fake store; production and background-before-UI keep the production-holder default.
  IosNotificationContentStoreHolder.select(contentStore)
  val clearFamilyNotifications = IosNotifGlue::clearFamilyNotificationState
  val graph = DayfoldRuntimeFactory(
    api = if (fakeHttp != null) "http://fake.local" else "",
    contentStore = contentStore,
    tokenStore = IosTokenStore(key = if (usingFake) "dayfold_debug_fake_session" else "dayfold_session"),
    notificationContext = mainNotificationContext(),
    foregroundNotifier = IosNotifGlue.localNotifier,
    calendarPort = IosCalendarPort(),
    httpClientFactory = { fakeHttp ?: io.ktor.client.HttpClient() },
    devSecret = "fake".takeIf { fakeHttp != null },
    initialState = initialStateForFakeScenario(scenarioId),
    debug = debug,
    onFamilyDataCleared = clearFamilyNotifications,
    onFamilyDataClearFinished = IosNotifGlue::finishFamilyNotificationStateClear,
  ).create()
  // ADR 0020 R3 — register as soon as this runtime is live, mirroring the Android ViewModel's
  // RuntimeHandleHolder registration. A headless caller (the BGAppRefreshTask, same process) must
  // find this handle and delegate to it rather than build an independent refresher — see
  // RuntimeHandleHolder's doc for the reuse-detection sign-out this avoids. Cleared in this
  // controller's DisposableEffect teardown (IosControllerContent), never left to a delay. The
  // returned token is carried into the composition so that teardown clears THIS registration and
  // not a newer controller's — registration and disposal are far apart here, and a second
  // controller can register in between.
  val runtimeHandleRegistration = RuntimeHandleHolder.register { graph.requestBackgroundSync() }

  return ComposeUIViewController {
    IosControllerContent(
      graph = graph,
      contentStore = contentStore,
      runtimeHandleRegistration = runtimeHandleRegistration,
      seedSampleContent = !usingFake,
      resetFakeContent = usingFake,
      usingFake = usingFake,
      clearFamilyNotifications = clearFamilyNotifications,
    )
  }
}

@Composable
private fun IosControllerContent(
  graph: DayfoldRuntimeGraph,
  contentStore: ContentStore,
  runtimeHandleRegistration: suspend () -> Boolean,
  seedSampleContent: Boolean,
  resetFakeContent: Boolean,
  usingFake: Boolean,
  clearFamilyNotifications: () -> Unit,
) {
  // debug=false in release → no redux DevTools enhancer + no action-log middleware (each serializes the
  // full AppState per dispatch; both are dev-only). Was defaulting to true in all builds.
  val store = graph.store
  val actions = remember { com.sloopworks.dayfold.client.cards.PlatformActions() }
  val scope = rememberCoroutineScope()
  val notificationTapOwner = remember { Any() }
  // ADR 0044 — the process-global UN delegate retains the latest target until this controller claims
  // it. DayfoldCommands then holds it across async family restore and acknowledges only at OpenHub commit.
  LaunchedEffect(graph, notificationTapOwner) {
    IosDeepLinkBus.taps.collect { tap ->
      IosDeepLinkBus.claim(tap, notificationTapOwner)?.let { target ->
        graph.commands.openExternalHub(
          target = target,
          onAdmitted = {
            // HubEngine invokes this immediately after an admitted OpenHub commit, outside its
            // family gate. A controller replacement replays only targets that never committed.
            IosDeepLinkBus.acknowledge(tap, notificationTapOwner)
          },
          onDiscarded = {
            // Terminal identity/family boundaries consume the native replay too, so an old
            // tenant-less Hub id cannot be claimed after a later login or controller replacement.
            IosDeepLinkBus.acknowledge(tap, notificationTapOwner)
          },
        )
      }
    }
  }
  // ADR 0044 §S3 — OS-permission truth → store (OS-owned; re-read on resume, never DB-cached). Seed the
  // initial state + bridge changes; the CL delegate drives the location flow, getNotificationSettings the
  // notif flow (refreshed on resume below). Mirrors MainActivity's permission bridge.
  val locPerm = remember { IosNotifGlue.locationPermission }
  val notifPerm = remember { IosNotifGlue.notificationPermission }
  LaunchedEffect(Unit) {
    store.dispatch(LocationPermissionLoaded(locPerm.currentState()))
    store.dispatch(NotificationPermissionLoaded(notifPerm.currentState()))
  }
  LaunchedEffect(Unit) { locPerm.state.collect { store.dispatch(LocationPermissionLoaded(it)) } }
  LaunchedEffect(Unit) { notifPerm.state.collect { store.dispatch(NotificationPermissionLoaded(it)) } }
  // Device-local config reaction: enabling background proximity registers geofences (nearest-N, capped) +
  // arms exact schedules; disabling de-registers them. Re-register on CONTENT change while enabled (a
  // place added/removed, new timed items). Live position never leaves the device. Mirrors MainActivity.
  LaunchedEffect(Unit) {
    contentStore.notifConfigFlow().collect { cfg ->
      if (cfg.enabled) {
        reRegisterGeofences()
        reconcileExactSchedules()
      } else {
        IosNotifGlue.geofence.deregisterAll()
        IosExactNotificationScheduler().cancelAll()
        IosLocalNotifier().cancelAll()
      }
    }
  }
  LaunchedEffect(Unit) {
    contentStore.nowContentFlow().collect {
      if (contentStore.notifConfig().enabled) { reRegisterGeofences(); reconcileExactSchedules() }
    }
  }
  // A response-table write does not invalidate nowContentFlow. Observe it independently so a local
  // or canonical Done immediately retracts both pending and already-delivered notifications.
  LaunchedEffect(Unit) {
    contentStore.responsesFlow().collect {
      if (contentStore.notifConfig().enabled) reconcileExactSchedules()
    }
  }
  LaunchedEffect(Unit) {
    contentStore.activeCardsFlow().collect {
      if (contentStore.notifConfig().enabled) reconcileExactSchedules()
    }
  }
  // Pause the 45s poll when the app is backgrounded; resume when it returns to foreground.
  // Mirrors Android's repeatOnLifecycle(STARTED) pattern — stops fetching restricted hub
  // data while backgrounded. Uses NSNotificationCenter (no new deps; LifecycleOwner API
  // requires lifecycle-runtime-compose in iosMain which is not yet wired).
  DisposableEffect(graph, scope, locPerm, notifPerm, notificationTapOwner) {
    val nc = NSNotificationCenter.defaultCenter
    val mainQueue = NSOperationQueue.mainQueue
    val lifecycle = IosControllerRuntimeOwner(
      scope = scope,
      startRuntime = {
        // The fake transport is rebuilt in memory for every controller launch. Reset its
        // disposable cache at the same boundary so a prior simulated completion cannot outlive
        // the backend that acknowledged it and poison the next device-verification run.
        if (resetFakeContent) withContext(Dispatchers.Default) {
          clearFamilyNotifications()
          try {
            contentStore.wipe()
          } finally {
            IosNotifGlue.finishFamilyNotificationStateClear()
          }
        }
        if (seedSampleContent) seedDebugContent(contentStore)
        graph.start()
      },
      resumeRuntime = graph::resume,
      pauseRuntime = graph::pause,
      cancelRuntime = graph::cancel,
    )
    val resumeToken = nc.addObserverForName(
      name = UIApplicationDidBecomeActiveNotification,
      `object` = null,
      queue = mainQueue,
    ) { _ ->
      lifecycle.didBecomeActive()
      // Re-read OS permission truth on every foreground (iOS has no notif permission-change broadcast;
      // the user may have toggled it in Settings while backgrounded). ADR 0044 §S3.
      locPerm.refresh(); notifPerm.refresh()
      graph.commands.startCalendarCheck()
    }
    val pauseToken = nc.addObserverForName(
      name = UIApplicationWillResignActiveNotification,
      `object` = null,
      queue = mainQueue,
    ) { _ -> lifecycle.willResignActive() }

    // Register observers before sampling state so an activation that races cold startup is either
    // reflected in applicationState or queued as DidBecomeActive—never lost between the two.
    lifecycle.start(
      UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive,
    )

    onDispose {
      // Clear FIRST — a stale handle pointing at a runtime that is now cancelling/cancelled is
      // worse than none, since bgRefresh racing this teardown could delegate into a graph that will
      // never finish the pass. Compare-and-clear: if a replacement controller registered before
      // this one disposed, its live handle must survive this teardown.
      RuntimeHandleHolder.clear(runtimeHandleRegistration)
      nc.removeObserver(resumeToken)
      nc.removeObserver(pauseToken)
      lifecycle.dispose()
      // lifecycle.dispose closes Hub admission synchronously before another controller can claim
      // an unacknowledged replay item.
      IosDeepLinkBus.release(notificationTapOwner)
    }
  }
  val selectorStore = rememberSelectorStore(store)
  val stablePlatformActions = remember(actions, locPerm, notifPerm, usingFake) {
    StablePlatformActions(
      platformActions = actions,
      // Native provider UI is not implemented on iOS yet. A provider tap is therefore a no-op;
      // importantly it cannot fall through into the debug-token path.
      onSignIn = {},
      // iosArm64 intentionally has no fake transport and no configured production API yet. Hiding
      // this affordance there prevents a labelled sign-in action that can only fail/no-op.
      onDevSignIn = graph.commands::devSignIn.takeIf { usingFake },
      onRequestProximityPermissions = { notifPerm.request(); locPerm.requestAlways() },
      onOpenAppSettings = locPerm::openOsSettings,
    )
  }
  FeedApp(
    store = selectorStore,
    commands = graph.commands,
    platformActions = stablePlatformActions,
  )
}

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private suspend fun seedDebugContent(contentStore: ContentStore) {
  if (!kotlin.native.Platform.isDebugBinary) return

  // ADR 0044 iOS dev seed (DEBUG-only — real-backend sync auth is operator-gated): seed the shared
  // ContentStore off-main so the feed renders and both notification lanes have content to fire on.
  withContext(Dispatchers.Default) {
    contentStore.applyDelta(
      SampleData.cards,
      listOf(Hub(id = "hub-demo", type = "party-event", title = "Soccer Saturday", status = "active")),
      listOf(HubSection(id = "sec-demo", hubId = "hub-demo", title = "Game day", ord = 0)),
      // A geo-triggered block gives foreground ranking and notification tap routing a real target.
      listOf(
        HubBlock(
          id = "blk-geo",
          sectionId = "sec-demo",
          type = "text",
          bodyMd = "Pack jackets — showers expected right at pickup.",
          ord = 0,
          triggers = listOf(
            BlockTrigger(geo = TriggerGeo(placeRef = "place-soccer", label = "Soccer field")),
          ),
        ),
      ),
      emptyList(),
      null,
      "2026-06-20T10:00:00Z",
      changedPlaces = listOf(
        Place(
          id = "place-soccer",
          kind = "other",
          label = "Soccer field",
          lat = 37.3349,
          lng = -122.0090,
          radiusM = 150,
        ),
      ),
    )
  }
}
