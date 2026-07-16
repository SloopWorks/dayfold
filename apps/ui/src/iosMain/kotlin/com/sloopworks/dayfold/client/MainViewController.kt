package com.sloopworks.dayfold.client

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.ComposeUIViewController
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
// NSUserDefaults (IosTokenStore). The dev-token sign-in secret + a real API base
// stay unset here (iOS run config is operator-gated on Mac/Xcode), so sign-in is
// inert on-device this slice; the gate + onboarding UI + restore are all wired.
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)   // Platform.isDebugBinary (release-gate DevTools)
fun MainViewController(): UIViewController {
  // One runtime graph belongs to exactly one controller invocation. Keeping construction outside
  // composition prevents a disposed/recreated composition from silently creating a second graph.
  // The database remains process-global for foreground/headless single-writer coordination.
  val contentStore = IosContentStoreHolder.get()
  val graph = DayfoldRuntimeFactory(
    api = "",
    contentStore = contentStore,
    tokenStore = IosTokenStore(),
    notificationContext = mainNotificationContext(),
    debug = kotlin.native.Platform.isDebugBinary,
  ).create()

  return ComposeUIViewController {
    IosControllerContent(graph = graph, contentStore = contentStore)
  }
}

@Composable
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private fun IosControllerContent(
  graph: DayfoldRuntimeGraph,
  contentStore: ContentStore,
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
      if (cfg.enabled) { reRegisterGeofences(); reconcileExactSchedules() } else { IosNotifGlue.geofence.deregisterAll() }
    }
  }
  LaunchedEffect(Unit) {
    contentStore.nowContentFlow().collect {
      if (contentStore.notifConfig().enabled) { reRegisterGeofences(); reconcileExactSchedules() }
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
        seedDebugContent(contentStore)
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
      nc.removeObserver(resumeToken)
      nc.removeObserver(pauseToken)
      lifecycle.dispose()
      // lifecycle.dispose closes Hub admission synchronously before another controller can claim
      // an unacknowledged replay item.
      IosDeepLinkBus.release(notificationTapOwner)
    }
  }
  val selectorStore = rememberSelectorStore(store)
  val stableCommands = remember(graph.commands) { StableDayfoldCommands(graph.commands) }
  val stablePlatformActions = remember(actions, locPerm, notifPerm) {
    StablePlatformActions(
      platformActions = actions,
      // Native provider UI is not implemented on iOS yet. A provider tap is therefore a no-op;
      // importantly it cannot fall through into the debug-token path.
      onSignIn = {},
      onDevSignIn = if (kotlin.native.Platform.isDebugBinary) graph.commands::devSignIn else null,
      onRequestProximityPermissions = { notifPerm.request(); locPerm.requestAlways() },
      onOpenAppSettings = locPerm::openOsSettings,
    )
  }
  FeedApp(
    store = selectorStore,
    commands = stableCommands,
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
