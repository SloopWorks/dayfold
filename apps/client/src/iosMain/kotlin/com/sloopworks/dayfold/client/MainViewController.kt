package com.sloopworks.dayfold.client

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIViewController

// iOS entry — the SHARED FeedApp with the AUTH-S5 route gate. Session persists via
// NSUserDefaults (IosTokenStore). The dev-token sign-in secret + a real API base
// stay unset here (iOS run config is operator-gated on Mac/Xcode), so sign-in is
// inert on-device this slice; the gate + onboarding UI + restore are all wired.
fun MainViewController(): UIViewController = ComposeUIViewController {
  val store = remember { createAppStore() }
  val tokenStore = remember { IosTokenStore() }
  // ADR 0044 §S3 — the SINGLE process-shared ContentStore (the region-enter delegate + BGTask reconcile
  // reuse this exact instance/driver; two connections would race the WAL writer).
  val cs = remember { IosContentStoreHolder.get() }
  // Data-boundary: drop the local cache on logout / dead session (see AuthEngine.clearCache).
  val authEngine = remember { AuthEngine(store, AuthClient(""), tokenStore, devSecret = null, clearCache = { cs.wipe() }) }
  val syncEngine = remember {
    SyncEngine(
      store, cs,
      SyncClient("", familyId = { store.state.activeFamilyId }, token = { store.state.session?.access }),
      authClient = AuthClient(""), tokenStore = tokenStore,
    )
  }
  val hubEngine = remember {  // ADR 0006 render — PR2: DB-fed
    HubEngine(store, HubClient(""), AuthClient(""), tokenStore, cs, syncEngine)
  }
  val nowEngine = remember { NowEngine(store, cs) }  // ADR 0043 §2b — render-driven record-shown effect
  val actions = remember { com.sloopworks.dayfold.client.cards.PlatformActions() }
  val scope = rememberCoroutineScope()
  LaunchedEffect(Unit) {
    // ADR 0044 iOS dev seed (ungated — real-backend sync auth is operator-gated): seed the shared
    // ContentStore with sample cards + a saved place so the feed renders and both notification lanes
    // (time + geofence) have content to fire on, without a network/session. Mirrors Android's debug seed.
    cs.applyDelta(
      SampleData.cards, emptyList(), emptyList(), emptyList(), emptyList(), null, "2026-06-20T10:00:00Z",
      changedPlaces = listOf(
        Place(id = "place-home", kind = "home", label = "Home", lat = 37.3349, lng = -122.0090, radiusM = 150),
      ),
    )
    syncEngine.start()
    authEngine.restore()
    syncEngine.resume()
  }
  // ADR 0044 — a tapped LOCAL notification emits its deep-link target on IosDeepLinkBus (the process-global
  // UN delegate); route it to the source hub block (same OpenHub the in-feed tap uses). replay=1 covers a
  // cold-start tap that fired before this collector was ready. Dangling target tolerated (openHub → feed).
  LaunchedEffect(Unit) {
    IosDeepLinkBus.taps.collect { hubEngine.openHub(it.hubId, it.blockId) }
  }
  // Pause the 45s poll when the app is backgrounded; resume when it returns to foreground.
  // Mirrors Android's repeatOnLifecycle(STARTED) pattern — stops fetching restricted hub
  // data while backgrounded. Uses NSNotificationCenter (no new deps; LifecycleOwner API
  // requires lifecycle-runtime-compose in iosMain which is not yet wired).
  DisposableEffect(syncEngine) {
    val nc = NSNotificationCenter.defaultCenter
    val mainQueue = NSOperationQueue.mainQueue
    val resumeToken = nc.addObserverForName(
      name = UIApplicationDidBecomeActiveNotification,
      `object` = null,
      queue = mainQueue,
    ) { _ -> scope.launch { syncEngine.resume() } }
    val pauseToken = nc.addObserverForName(
      name = UIApplicationWillResignActiveNotification,
      `object` = null,
      queue = mainQueue,
    ) { _ -> syncEngine.pause() }
    onDispose {
      nc.removeObserver(resumeToken)
      nc.removeObserver(pauseToken)
    }
  }
  FeedApp(
    store,
    onPlatformAction = actions::perform,
    onOpenUri = actions::openUri,
    onSignIn = { provider -> scope.launch { authEngine.signIn(provider); syncEngine.syncNow() } },
    // ADR 0044 iOS dev entry (ungated): mint a local session (no network/Firebase) so the seeded feed
    // is reachable past the AUTH-S5 route gate. Real Google/Apple sign-in stays operator-gated.
    onDevSignIn = { scope.launch { authEngine.devSignIn() } },
    onCreateFamily = { name -> scope.launch { authEngine.createFamily(name); syncEngine.syncNow() } },
    onSignOut = { scope.launch { authEngine.signOut() } },
    onRedeemInvite = { token -> scope.launch { authEngine.redeemInvite(token) } },
    onLoadApprovals = { scope.launch { store.state.activeFamilyId?.let { authEngine.loadApprovals(it) } } },
    onApproveMember = { uid -> scope.launch { store.state.activeFamilyId?.let { authEngine.approveMember(it, uid) } } },
    onDeclineMember = { uid -> scope.launch { store.state.activeFamilyId?.let { authEngine.declineMember(it, uid) } } },
    onLoadMembers = { scope.launch { store.state.activeFamilyId?.let { authEngine.loadMembers(it) } } },
    onRemoveMember = { uid -> scope.launch { store.state.activeFamilyId?.let { authEngine.removeMember(it, uid) } } },
    onLoadDevices = { scope.launch { authEngine.loadDevices() } },
    onRevokeDevice = { id -> scope.launch { authEngine.revokeDevice(id) } },
    onLookupDevice = { code -> scope.launch { authEngine.lookupDevice(code) } },
    onApproveDevice = { fid -> scope.launch { authEngine.approveDevice(fid, store.state.pendingDevice?.userCode ?: return@launch) } },
    onDenyDevice = { fid -> scope.launch { authEngine.denyDevice(fid, store.state.pendingDevice?.userCode ?: return@launch) } },
    onRefresh = { scope.launch { syncEngine.syncNow() } },
    onNowShown = { keys -> nowEngine.noteShown(keys) },      // ADR 0043 §2b — start the anti-nag clock
    onLoadHubs = { scope.launch { syncEngine.syncNow() } },  // PR1: hub list is DB-fed via the bridge
    onOpenHub = { id, block -> scope.launch { hubEngine.openHub(id, block) } },
    onCloseHub = { scope.launch { hubEngine.closeHub() } },  // PR2: cancel tree subscription
    onLoadAudience = { id -> scope.launch { hubEngine.loadAudience(id) } },
    onToggleItem = { blockId, itemId, done -> scope.launch { hubEngine.toggleItem(blockId, itemId, done) } },  // Slice 4
    onRetryBlock = { blockId -> scope.launch { hubEngine.retryBlock(blockId) } },
    // Slice 5b (ADR 0038 §W4/§W5): author-gated delete + local-only hide/unhide.
    onDeleteBlock = { blockId -> scope.launch { hubEngine.deleteBlock(blockId) } },
    onHideBlock = { blockId -> scope.launch { hubEngine.hideBlock(blockId) } },
    onUnhideBlock = { blockId -> scope.launch { hubEngine.unhideBlock(blockId) } },
  )
}
