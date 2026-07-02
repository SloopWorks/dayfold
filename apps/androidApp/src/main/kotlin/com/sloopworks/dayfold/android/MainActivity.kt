package com.sloopworks.dayfold.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sloopworks.dayfold.client.AndroidGeofenceController
import com.sloopworks.dayfold.client.AndroidLocalNotifier
import com.sloopworks.dayfold.client.AndroidLocationPermissionController
import com.sloopworks.dayfold.client.AndroidNotificationPermissionController
import com.sloopworks.dayfold.client.AndroidTokenStore
import com.sloopworks.dayfold.client.AuthClient
import com.sloopworks.dayfold.client.AuthEngine
import com.sloopworks.dayfold.client.ContentStore
import com.sloopworks.dayfold.client.DEFAULT_GEOFENCE_RADIUS_M
import com.sloopworks.dayfold.client.DriverFactory
import com.sloopworks.dayfold.client.FeedApp
import com.sloopworks.dayfold.client.GeoRegion
import com.sloopworks.dayfold.client.HubClient
import com.sloopworks.dayfold.client.HubEngine
import com.sloopworks.dayfold.client.LocationPermissionLoaded
import com.sloopworks.dayfold.client.NotificationPermissionLoaded
import com.sloopworks.dayfold.client.NowEngine
import com.sloopworks.dayfold.client.ANDROID_REGION_CAP
import com.sloopworks.dayfold.client.SyncClient
import com.sloopworks.dayfold.client.SyncEngine
import com.sloopworks.dayfold.client.createAppStore
import com.sloopworks.debugdrawer.Backend
import com.sloopworks.debugdrawer.BuildInfo
import com.sloopworks.debugdrawer.DebugDrawer
import com.sloopworks.debugdrawer.DebugDrawerConfig
import com.sloopworks.debugdrawer.DebugDrawerHost
import com.sloopworks.debugdrawer.log.DebugLog
import io.ktor.client.HttpClient
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

// Android shell — owns the store + AuthEngine + SyncEngine. AUTH-S5: the route
// gate drives sign-in/onboarding/feed; repeatOnLifecycle(STARTED) maps the
// Activity foreground/background to engine.resume()/pause().
class MainActivity : ComponentActivity() {
  private lateinit var authEngine: AuthEngine
  private lateinit var hubEngine: HubEngine
  // ADR 0044 Phase B — OS-permission controllers (OS-owned truth; refreshed on resume, never DB-cached).
  private val locationPermission by lazy { AndroidLocationPermissionController(applicationContext) }
  private val notificationPermission by lazy { AndroidNotificationPermissionController(applicationContext) }

  // ADR 0044 Phase B — in-app runtime prompts. POST_NOTIFICATIONS (API 33+) + while-using location are
  // requested through ONE RequestMultiplePermissions flow (Android shows the dialogs in sequence within
  // the single request — two back-to-back launch() calls would drop the second). Background "Always"
  // CANNOT be requested in a dialog (Android forces a Settings trip — correct, handled by the permission
  // row). After the result we re-read OS truth into the store.
  private val proximityPermLauncher = registerForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
  ) { locationPermission.refresh(); notificationPermission.refresh() }

  private fun granted(permission: String): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

  // Enabling background proximity → request the in-app-grantable runtime permissions (notifications +
  // while-using location) in one flow. Already-granted ones are omitted; "Always" stays a Settings step.
  private fun requestProximityPermissions() {
    val needed = buildList {
      if (android.os.Build.VERSION.SDK_INT >= 33 && !granted(android.Manifest.permission.POST_NOTIFICATIONS)) {
        add(android.Manifest.permission.POST_NOTIFICATIONS)
      }
      if (!granted(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
        add(android.Manifest.permission.ACCESS_FINE_LOCATION)
      }
    }
    if (needed.isNotEmpty()) proximityPermLauncher.launch(needed.toTypedArray())
  }

  // S6-D Tier 2: a verified App Link (https://<api-origin>/device?user_code=…) hands
  // the raw URL to the engine, which parses the code and either looks it up (signed
  // in) or stashes it to resume after sign-in. singleTask → warm taps arrive here.
  private fun handleDeepLink(intent: Intent?) {
    val data = intent?.data?.toString() ?: return
    lifecycleScope.launch { authEngine.openDeviceLink(data) }
  }

  // ADR 0044 Phase B — a tapped LOCAL notification relaunches us with the deep-link extras
  // (AndroidLocalNotifier). Route straight to the source hub block — the same OpenHub the in-feed tap
  // uses (container transform + arrival pulse). Tolerates a dangling target (openHub falls back to feed).
  private fun handleNotificationIntent(intent: Intent?) {
    val hubId = intent?.getStringExtra(AndroidLocalNotifier.EXTRA_HUB_ID) ?: return
    val blockId = intent.getStringExtra(AndroidLocalNotifier.EXTRA_BLOCK_ID)
    // consume the extras so a config-change re-create doesn't re-route.
    intent.removeExtra(AndroidLocalNotifier.EXTRA_HUB_ID)
    intent.removeExtra(AndroidLocalNotifier.EXTRA_BLOCK_ID)
    if (::hubEngine.isInitialized) lifecycleScope.launch { hubEngine.openHub(hubId, blockId) }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleDeepLink(intent)
    handleNotificationIntent(intent)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Edge-to-edge: draw behind the (transparent) status + navigation bars and let
    // Compose consume the insets. targetSdk 37 already enforces this on Android 15+;
    // calling it explicitly also sets decorFitsSystemWindows=false (so WindowInsets
    // report real values) and installs SystemBarStyle.auto → the bar icon contrast
    // tracks light/dark (same isSystemInDarkTheme source DayfoldTheme keys off, so
    // icons stay legible in both themes). Inset *padding* is applied in shared UI
    // (FeedApp safe-area wrapper + DetailScreen hero), not here.
    enableEdgeToEdge()
    // SloopWorks debug drawer (debug builds only; a no-op facade in release). Install
    // BEFORE any HTTP client is built so backendUrl() can reflect a chosen override.
    DebugDrawer.install(
      DebugDrawerConfig(
        buildInfo = BuildInfo(
          version = BuildConfig.VERSION_NAME ?: "dev",
          build = BuildConfig.VERSION_CODE.toString(),
          buildType = if (BuildConfig.DEBUG) "debug" else "release",
        ),
        backends = listOf(
          Backend("prod", "Production", "https://family-ai-dashboard.vercel.app"),
          Backend("emulator", "Local API (10.0.2.2)", "http://10.0.2.2:8799"),
        ) + fakeBackends(),   // debug: fake-backend scenarios (empty in release)
        plugins = debugDrawerPlugins(),   // redux DevTools panel in debug; none in release
      ),
      applicationContext,
    )
    // Bridge the client's logs (redux action log + [sync] refresh path) into the
    // drawer's Logs panel — DebugLog feeds the installed LogBuffer in debug, no-op
    // in release. Without this the Logs panel is empty (nothing fed the buffer).
    com.sloopworks.dayfold.client.ClientLog.sink = { tag, msg -> DebugLog.i(tag, msg) }
    // API base routes through the drawer's backend override (falls back to the
    // build-time DAYFOLD_API). Switching backend in the drawer applies on restart.
    val apiBase = DebugDrawer.backendUrl(BuildConfig.DAYFOLD_API)
    // Fake backend (debug UI testing): a `fake://<scenario>` selection routes ALL
    // transport through an in-process MockEngine instead of the network. fakeHttp is
    // null in release and for real URLs → the shared real HttpClient is used. The
    // clients only need a well-formed base for URL building (MockEngine routes by
    // path), so a fake scenario points them at a dummy host.
    val scenarioId = if (apiBase.startsWith("fake://")) apiBase.removePrefix("fake://") else null
    val fakeHttp = scenarioId?.let { fakeBackendClient(it) }
    val isFake = fakeHttp != null
    val http = fakeHttp ?: HttpClient()
    val clientApi = if (isFake) "http://fake.local" else apiBase
    // debug=false in release → no redux DevTools enhancer + no action-log middleware (each serializes
    // the full AppState per dispatch; both are dev-only). Was defaulting to true in all builds.
    val store = createAppStore(debug = BuildConfig.DEBUG)
    // Single process-shared store (ADR 0044 §S3) — the geofence/exact-alarm background receivers reuse
    // this same instance + driver (one WAL writer); foreground and background never open two connections.
    val cs = com.sloopworks.dayfold.client.AndroidContentStoreHolder.get(applicationContext)
    if (isFake) {
      // Start clean so leftover real/seed rows from a prior run don't bleed into the
      // fake scenario (the persistent DB survives the backend-switch restart); the
      // fake /sync repopulates from the scenario.
      cs.wipe()
    } else if (BuildConfig.DEBUG && BuildConfig.FAMILY_ID.isEmpty()) {
      // Debug-only: seed the DB with sample cards so the content UI (cards/detail/
      // transition) is exercisable on-device without a live API. Sync only adds/
      // tombstones, never wipes these. NOTE (post AUTH-S5): the route gate hides the
      // feed until Route.Feed (signed-in + active family) — sign in via the dev path
      // to reach the seeded feed.
      cs.applyDelta(com.sloopworks.dayfold.client.SampleData.cards, emptyList(), emptyList(), emptyList(), emptyList(), null, "2026-06-20T10:00:00Z")
    }
    val tokenStore = AndroidTokenStore(applicationContext)
    authEngine = AuthEngine(
      store, AuthClient(clientApi, http), tokenStore,
      // Fake mode forces the dev-token path: a non-null devSecret + no Firebase seam
      // means any provider tap → POST /auth/dev-token (mock-intercepted), landing on
      // the scenario's session deterministically.
      devSecret = if (isFake) "fake" else BuildConfig.DEV_AUTH_SECRET.ifEmpty { null },
      // S2 (ADR 0023/0027): real Google sign-in via Credential Manager + Firebase.
      // Activity context (this) is required for the account-picker UI. webClientId
      // comes from google-services.json (default_web_client_id). When the seam
      // yields a token, AuthEngine uses /auth/firebase; else it falls back to dev-token.
      firebaseSignIn = if (isFake) null else AndroidFirebaseSignIn(this, getString(R.string.default_web_client_id)),
      // Data-boundary: wipe the shared content DB on logout / dead session so one
      // identity's cards+hubs never bleed into the next (the DB→store bridge would
      // otherwise re-project them). Same wipe the fake-mode switch + ADR 0030 use.
      clearCache = { cs.wipe() },
    )
    val legacyFam = BuildConfig.FAMILY_ID; val legacySecret = BuildConfig.HOUSEHOLD_SECRET
    val syncEngine = SyncEngine(
      store, cs,
      SyncClient(
        clientApi,
        familyId = { store.state.activeFamilyId ?: legacyFam.ifEmpty { null } },
        token = { store.state.session?.access ?: legacySecret.ifEmpty { null } },
        http = http,
      ),
      authClient = AuthClient(clientApi, http),   // refresh-on-401 so the foreground sync survives the 5-min access token
      tokenStore = tokenStore,
    )

    syncEngine.start()
    lifecycleScope.launch { authEngine.restore() }
    handleDeepLink(intent)   // cold-start: did a /device App Link launch us? (stash/resume tolerates restore ordering)
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        syncEngine.resume()
        // Re-read OS permission truth on every foreground (Android has no permission-change broadcast;
        // the user may have toggled it in Settings while we were backgrounded). ADR 0044 §S3.
        locationPermission.refresh()
        notificationPermission.refresh()
        try { awaitCancellation() } finally { syncEngine.pause() }
      }
    }
    hubEngine = HubEngine(   // ADR 0006 render — PR2: DB-fed
      store, HubClient(clientApi, http),
      AuthClient(clientApi, http), tokenStore, cs, syncEngine,
    )
    val nowEngine = NowEngine(store, cs)  // ADR 0043 §2b — render-driven record-shown effect

    // ADR 0044 Phase B — wire the OS-permission state into the store (sole-writer bridge from the
    // controllers, mirroring SyncEngine's config bridge; OS-owned → re-read on resume below). Then react
    // to the device-local config: enabling background proximity registers geofences for the saved places
    // (capped); disabling de-registers them all. Live position never leaves the device.
    val geofence = AndroidGeofenceController(applicationContext)
    store.dispatch(LocationPermissionLoaded(locationPermission.currentState()))
    store.dispatch(NotificationPermissionLoaded(notificationPermission.currentState()))
    lifecycleScope.launch { locationPermission.state.collect { store.dispatch(LocationPermissionLoaded(it)) } }
    lifecycleScope.launch { notificationPermission.state.collect { store.dispatch(NotificationPermissionLoaded(it)) } }
    lifecycleScope.launch {
      cs.notifConfigFlow().collect { cfg ->
        if (cfg.enabled) {
          val regions = cs.activePlaces().take(ANDROID_REGION_CAP)
            .map { GeoRegion(it.id, it.lat, it.lng, it.radiusM?.toDouble() ?: DEFAULT_GEOFENCE_RADIUS_M) }
          geofence.register(regions)
          // arm exact alarms for known future instants (when.at / countdown / milestone).
          com.sloopworks.dayfold.client.reconcileExactSchedules(applicationContext)
        } else {
          geofence.deregisterAll()
        }
      }
    }
    // Re-register on CONTENT change too (a place added/removed via /sync, or new timed items) — keeps
    // the geofence set + exact alarms fresh while the feature is on (part of the re-registration matrix).
    lifecycleScope.launch {
      cs.nowContentFlow().collect {
        if (store.state.notifConfig.enabled) {
          val regions = cs.activePlaces().take(ANDROID_REGION_CAP)
            .map { GeoRegion(it.id, it.lat, it.lng, it.radiusM?.toDouble() ?: DEFAULT_GEOFENCE_RADIUS_M) }
          geofence.register(regions)
          com.sloopworks.dayfold.client.reconcileExactSchedules(applicationContext)
        }
      }
    }
    handleNotificationIntent(intent)   // cold-start: did a notification tap launch us?
    val actions = com.sloopworks.dayfold.client.cards.PlatformActions(applicationContext)
    setContent {
      // SloopWorks debug drawer: a floating bubble (debug) opens AppInfo / Backend-
      // switch / Logs / Redux DevTools panels. Pure passthrough in release (no-op).
      DebugDrawerHost {
        FeedApp(
          store,
          onPlatformAction = actions::perform,
          onOpenUri = actions::openUri,
          onSignIn = { provider -> lifecycleScope.launch { authEngine.signIn(provider); syncEngine.syncNow() } },
          // Debug-only fake sign-in: mints a local session (no network/Firebase) so
          // the app is enterable against any/unreachable backend. Null in release →
          // the button is absent.
          onDevSignIn = if (BuildConfig.DEBUG) ({ lifecycleScope.launch { authEngine.devSignIn(); syncEngine.syncNow() } }) else null,
          onCreateFamily = { name -> lifecycleScope.launch { authEngine.createFamily(name); syncEngine.syncNow() } },
          onSignOut = { lifecycleScope.launch { authEngine.signOut() } },
          onRetry = { lifecycleScope.launch { authEngine.restore() } },
          onRedeemInvite = { token -> lifecycleScope.launch { authEngine.redeemInvite(token) } },
          onLoadApprovals = { lifecycleScope.launch { store.state.activeFamilyId?.let { authEngine.loadApprovals(it) } } },
          onApproveMember = { uid -> lifecycleScope.launch { store.state.activeFamilyId?.let { authEngine.approveMember(it, uid) } } },
          onDeclineMember = { uid -> lifecycleScope.launch { store.state.activeFamilyId?.let { authEngine.declineMember(it, uid) } } },
          onLoadMembers = { lifecycleScope.launch { store.state.activeFamilyId?.let { authEngine.loadMembers(it) } } },
          onRemoveMember = { uid -> lifecycleScope.launch { store.state.activeFamilyId?.let { authEngine.removeMember(it, uid) } } },
          onLoadDevices = { lifecycleScope.launch { authEngine.loadDevices() } },
          onRevokeDevice = { id -> lifecycleScope.launch { authEngine.revokeDevice(id) } },
          onLookupDevice = { code -> lifecycleScope.launch { authEngine.lookupDevice(code) } },
          onApproveDevice = { fid -> lifecycleScope.launch { authEngine.approveDevice(fid, store.state.pendingDevice?.userCode ?: return@launch) } },
          onDenyDevice = { fid -> lifecycleScope.launch { authEngine.denyDevice(fid, store.state.pendingDevice?.userCode ?: return@launch) } },
          onRefresh = { lifecycleScope.launch { syncEngine.syncNow() } },
          onNowShown = { keys -> nowEngine.noteShown(keys) },               // ADR 0043 §2b — start the anti-nag clock
          onLoadHubs = { lifecycleScope.launch { syncEngine.syncNow() } },  // PR1: hub list is DB-fed via the bridge
          onOpenHub = { id, block -> lifecycleScope.launch { hubEngine.openHub(id, block) } },
          onCloseHub = { lifecycleScope.launch { hubEngine.closeHub() } },  // PR2: cancel tree subscription
          onLoadAudience = { id -> lifecycleScope.launch { hubEngine.loadAudience(id) } },
          onToggleItem = { blockId, itemId, done -> lifecycleScope.launch { hubEngine.toggleItem(blockId, itemId, done) } },  // Slice 4
          onRetryBlock = { blockId -> lifecycleScope.launch { hubEngine.retryBlock(blockId) } },
          // Slice 5b (ADR 0038 §W4/§W5): author-gated delete + local-only hide/unhide.
          onDeleteBlock = { blockId -> lifecycleScope.launch { hubEngine.deleteBlock(blockId) } },
          onHideBlock = { blockId -> lifecycleScope.launch { hubEngine.hideBlock(blockId) } },
          onUnhideBlock = { blockId -> lifecycleScope.launch { hubEngine.unhideBlock(blockId) } },
          // ADR 0044 Phase B — device-local config write (toggle/quiet/cap) → DB → flow → store; the
          // notifConfigFlow reaction above arms/disarms geofences + exact alarms. Off the main thread.
          // Enabling also requests the in-app-grantable runtime permissions (notifications + while-using).
          onSetNotifConfig = { cfg ->
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { cs.setNotifConfig(cfg) }
            if (cfg.enabled) requestProximityPermissions()
          },
        )
      }
    }
  }
}
