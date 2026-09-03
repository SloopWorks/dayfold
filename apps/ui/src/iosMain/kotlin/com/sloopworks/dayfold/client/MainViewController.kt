package com.sloopworks.dayfold.client

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.reduxkotlin.compose.rememberSelectorStore
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIViewController

/** Native Firebase/Apple authentication boundary implemented by the Swift host. */
interface IosAuthHost {
  fun signIn(provider: String, completion: (token: String?, error: String?) -> Unit)
  fun prepareAccountDeletion(completion: (error: String?) -> Unit)
  fun finishAccountDeletion(completion: (error: String?) -> Unit)
}

// iOS entry — the SHARED FeedApp with the AUTH-S5 route gate. Session persists in
// the iOS Keychain (IosTokenStore); native provider UI returns Firebase ID tokens
// through IosAuthHost and the shared engine performs the Dayfold exchange.
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)   // Platform.isDebugBinary (release-gate DevTools)
fun MainViewController(authHost: IosAuthHost? = null): UIViewController {
  // One runtime graph belongs to exactly one controller invocation. Keeping construction outside
  // composition prevents a disposed/recreated composition from silently creating a second graph.
  // The database remains process-global for foreground/headless single-writer coordination.
  val contentStore = IosContentStoreHolder.get()
  val graph = DayfoldRuntimeFactory(
    api = "https://family-ai-dashboard.vercel.app",
    contentStore = contentStore,
    tokenStore = IosTokenStore(),
    notificationContext = mainNotificationContext(),
    debug = kotlin.native.Platform.isDebugBinary,
    calendarPort = IosCalendarPort(),
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
      authHost = authHost,
    )
  }
}

@Composable
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private fun IosControllerContent(
  graph: DayfoldRuntimeGraph,
  contentStore: ContentStore,
  runtimeHandleRegistration: suspend () -> Boolean,
  authHost: IosAuthHost?,
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
      // EventKit authorization/events can change in Settings or Calendar while Dayfold is away.
      // Disabled Calendar Check exits before reading events.
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
  val stablePlatformActions = remember(actions, locPerm, notifPerm, authHost, graph) {
    StablePlatformActions(
      platformActions = actions,
      onSignIn = { provider ->
        authHost?.signIn(provider) { token, error ->
          when {
            token != null -> graph.commands.signIn(provider, token)
            error != null -> graph.commands.dispatch(SignInFailed(error))
          }
        }
      },
      onDeleteAccount = {
        if (authHost == null) {
          graph.commands.deleteAccount()
        } else {
          store.dispatch(DeleteAccountRequested)
          authHost.prepareAccountDeletion { preparationError ->
            if (preparationError != null) {
              store.dispatch(DeleteAccountFailed(preparationError))
            } else {
              graph.commands.deleteAccount {
                suspendCancellableCoroutine { continuation ->
                  authHost.finishAccountDeletion { cleanupError ->
                    if (cleanupError == null) continuation.resume(Unit) { _, _, _ -> }
                    else continuation.resumeWith(Result.failure(IllegalStateException(cleanupError)))
                  }
                }
              }
            }
          }
        }
      },
      onDevSignIn = if (kotlin.native.Platform.isDebugBinary) graph.commands::devSignIn else null,
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
