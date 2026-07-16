package com.sloopworks.dayfold.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sloopworks.dayfold.client.AndroidGeofenceController
import com.sloopworks.dayfold.client.AndroidLocalNotifier
import com.sloopworks.dayfold.client.AndroidLocationPermissionController
import com.sloopworks.dayfold.client.AndroidNotificationPermissionController
import com.sloopworks.dayfold.client.AndroidTokenStore
import com.sloopworks.dayfold.client.DEFAULT_GEOFENCE_RADIUS_M
import com.sloopworks.dayfold.client.DayfoldRuntimeFactory
import com.sloopworks.dayfold.client.DeepLinkTarget
import com.sloopworks.dayfold.client.FeedApp
import com.sloopworks.dayfold.client.GeoRegion
import com.sloopworks.dayfold.client.LocationPermissionLoaded
import com.sloopworks.dayfold.client.NotificationPermissionLoaded
import com.sloopworks.dayfold.client.StableDayfoldCommands
import com.sloopworks.dayfold.client.StablePlatformActions
import com.sloopworks.dayfold.client.ANDROID_REGION_CAP
import com.sloopworks.dayfold.client.mainNotificationContext
import com.sloopworks.debugdrawer.Backend
import com.sloopworks.debugdrawer.BuildInfo
import com.sloopworks.debugdrawer.DebugDrawer
import com.sloopworks.debugdrawer.DebugDrawerConfig
import com.sloopworks.debugdrawer.DebugDrawerHost
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.reduxkotlin.compose.rememberSelectorStore

// Android shell — owns Activity-scoped native UI only. The ViewModel retains the application-safe
// runtime/store across recreation; repeatOnLifecycle maps foreground state to runtime resume/pause.
class MainActivity : ComponentActivity() {
  private lateinit var runtimeViewModel: DayfoldRuntimeViewModel
  // Held as a field so onSaveInstanceState can snapshot nav state for process death. Configuration
  // changes keep the same store through runtimeViewModel and do not replay the saved stack.
  private lateinit var store: org.reduxkotlin.Store<com.sloopworks.dayfold.client.AppState>

  private companion object { const val KEY_DETAIL_STACK = "dayfold.detailStack" }
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

  // Verified App Links hand the raw URL to the engine, which parses it and either acts
  // now (signed in) or stashes it to resume after sign-in. singleTask → warm taps arrive
  // here. /invite/<token> → redeem (ADR 0048); /device?user_code=… → device-approval.
  private fun handleDeepLink(intent: Intent?) {
    val data = intent?.data?.toString() ?: return
    if (::runtimeViewModel.isInitialized) {
      if ("/invite/" in data) {
        runtimeViewModel.commands.openInviteLink(data)
      } else {
        runtimeViewModel.commands.openDeviceLink(data)
      }
    }
  }

  // ADR 0044 Phase B — a tapped LOCAL notification relaunches us with the deep-link extras
  // (AndroidLocalNotifier). Route straight to the source hub block — the same OpenHub the in-feed tap
  // uses (container transform + arrival pulse). Tolerates a dangling target (openHub falls back to feed).
  private fun handleNotificationIntent(intent: Intent?) {
    val target = intent?.consumeNotificationTarget() ?: return
    if (::runtimeViewModel.isInitialized) {
      runtimeViewModel.commands.openExternalHub(target)
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleDeepLink(intent)
    handleNotificationIntent(intent)
  }

  // Persist the detail nav across recreation. Runs on the MAIN thread; AppState +
  // detailStack are immutable (val List) and every store.dispatch originates from a
  // main-dispatcher coroutine, so reading store.state here can't race a reducer. Snapshot
  // into a fresh ArrayList (Bundle requirement + defensive copy). Restore is in onCreate.
  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    if (::store.isInitialized) outState.putStringArrayList(KEY_DETAIL_STACK, ArrayList(store.state.detailStack))
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
        plugins = debugDrawerPlugins(this),   // redux DevTools + SWIP inspector in debug; none in release
      ),
      applicationContext,
    )
    // Bind SloopLogging (console + debug-drawer writers) behind Log.sink — debug only;
    // release installLogging() is a no-op, leaving Log on its println fallback.
    installLogging(BuildConfig.DEBUG)
    // SWIP bug reporter (debug builds only; inert mirror in release). Installed AFTER
    // installLogging() above — the bug-reporter wraps the SloopLogging-bound sink to
    // feed the breadcrumb ring while still forwarding to console + drawer.
    bugReporterInstall(this)
    // SWIP init runs in DayfoldApp.onCreate (ADR 0060) — before any activity — so the crash
    // handler is installed early and requireSwip() below is ready. Nothing to do here.
    // API base routes through the drawer's backend override (falls back to the
    // build-time DAYFOLD_API). Switching backend in the drawer applies on restart.
    val apiBase = DebugDrawer.backendUrl(BuildConfig.DAYFOLD_API)
    val appContext = applicationContext
    // Fake backend (debug UI testing): a `fake://<scenario>` selection routes ALL
    // transport through an in-process MockEngine instead of the network. fakeHttp is
    // null in release and for real URLs → the shared real HttpClient is used. The
    // clients only need a well-formed base for URL building (MockEngine routes by
    // path), so a fake scenario points them at a dummy host.
    val scenarioId = if (apiBase.startsWith("fake://")) apiBase.removePrefix("fake://") else null
    val cs = com.sloopworks.dayfold.client.AndroidContentStoreHolder.get(appContext)
    val retainedFactory = RetainedDayfoldRuntimeFactory {
      // This closure is invoked only for a new ViewModel. It captures application-safe values and
      // creates the HTTP client lazily, so a recreated Activity does not allocate an unused client.
      val fakeHttp = scenarioId?.let { fakeBackendClient(it) }
      val isFake = fakeHttp != null
      val http = fakeHttp ?: HttpClient()
      val clientApi = if (isFake) "http://fake.local" else apiBase
      val graph = DayfoldRuntimeFactory(
        api = clientApi,
        contentStore = cs,
        tokenStore = AndroidTokenStore(appContext),
        notificationContext = mainNotificationContext(),
        httpClientFactory = { http },
        debug = BuildConfig.DEBUG,
        extraEnhancer = debugStoreEnhancer(),
        devSecret = if (isFake) "fake" else BuildConfig.DEV_AUTH_SECRET.ifEmpty { null },
      ).create()
      RetainedDayfoldRuntime(
        handle = GraphDayfoldRuntimeHandle(graph),
        isFakeBackend = isFake,
        beforeStart = if (isFake) {
          // Start clean off-main before runtime auth/sync can read the persistent database. This
          // retained startup hook runs once, so configuration changes never wipe the session.
          suspend { withContext(Dispatchers.IO) { cs.wipe() } }
        } else {
          suspend {}
        },
      )
    }
    runtimeViewModel = ViewModelProvider(
      this,
      DayfoldRuntimeViewModel.Factory(retainedFactory),
    )[DayfoldRuntimeViewModel::class.java]
    store = runtimeViewModel.store
    val isInitialHost = runtimeViewModel.consumeInitialStateRestore()
    val isFake = runtimeViewModel.isFakeBackend
    if (isInitialHost) {
      // A retained store already contains its navigation during a configuration change. A fresh
      // ViewModel after process death restores the Bundle before asynchronous auth restoration can
      // filter the stack against loaded cards.
      savedInstanceState?.getStringArrayList(KEY_DETAIL_STACK)?.takeIf { it.isNotEmpty() }?.let {
        store.dispatch(com.sloopworks.dayfold.client.RestoreDetailStack(it.toList()))
      }
    }
    // Runtime auth/bridge startup must follow synchronous process-death navigation restoration.
    // start() is ViewModel-owned and idempotent, so Activity recreation does not add another worker.
    runtimeViewModel.start()
    // SWIP lifecycle: foreground/background + route-driven screen_view (debug only; inert
    // in release). Store is fully constructed above — safe to subscribe now.
    swipLifecycleInstall(application, store)
    // NOTE: no standalone SampleData seed here. It used to run on every debug launch
    // (BuildConfig.FAMILY_ID is the build-time legacy const, always empty — NOT the
    // interactively signed-in activeFamilyId), so the sample cards (ids s_*) were
    // upserted into the SAME persistent DB that a real signed-in family syncs into.
    // Because the server never knows those ids it can never tombstone them, and the
    // incremental cursor sync never prunes rows it didn't deliver — so the fake cards
    // rendered permanently in the Now feed next to real prod content. On-device UI
    // exercise without a live API is still available via the `fake://` showcase
    // backend (reuses SampleData.cards, wipes on entry so it never coexists with real
    // data). See fix/debug-seed-pollutes-now-feed.
    // Only process a launch deep-link on a FRESH launch. On a recreation (config change
    // or process-death restore, savedInstanceState != null) this singleTask activity's
    // getIntent() still returns the ORIGINAL VIEW intent — re-processing it re-fired the
    // redeem every task-resume → a stuck "invite expired" screen. Genuine warm re-taps
    // arrive via onNewIntent (below), which is unaffected.
    if (savedInstanceState == null) handleDeepLink(intent)
    val runtimeHost = runtimeViewModel.attachHost()
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        runtimeViewModel.resume(runtimeHost)
        // Re-read OS permission truth on every foreground (Android has no permission-change broadcast;
        // the user may have toggled it in Settings while we were backgrounded). ADR 0044 §S3.
        locationPermission.refresh()
        notificationPermission.refresh()
        try {
          awaitCancellation()
        } finally {
          // repeatOnLifecycle cancels this child before finally runs. The bounded pause transition
          // must still acquire the owner mutex; generation checks keep an old Activity from pausing
          // a newer foreground owner.
          withContext(NonCancellable) { runtimeViewModel.pause(runtimeHost) }
        }
      }
    }

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
    val providerSignIn = if (isFake) null else AndroidFirebaseSignIn(
      this,
      getString(R.string.default_web_client_id),
    )
    setContent {
      val selectorStore = rememberSelectorStore(store)
      val stableCommands = remember(runtimeViewModel.commands) {
        StableDayfoldCommands(runtimeViewModel.commands)
      }
      val stablePlatformActions = remember(actions, providerSignIn, isFake) {
        StablePlatformActions(
          platformActions = actions,
          onSignIn = { provider ->
            if (isFake) {
              runtimeViewModel.commands.signInWithDevProvider(provider)
            } else {
              lifecycleScope.launch {
                // Cancellation/null is a host no-op. It never becomes a dev-token sign-in.
                val idToken = providerSignIn?.idToken(provider) ?: return@launch
                runtimeViewModel.commands.signIn(provider, idToken)
              }
            }
          },
          onDevSignIn = if (BuildConfig.DEBUG) runtimeViewModel.commands::devSignIn else null,
          onRequestProximityPermissions = ::requestProximityPermissions,
        )
      }
      // SloopWorks debug drawer: a floating bubble (debug) opens AppInfo / Backend-
      // switch / Logs / Redux DevTools panels. Pure passthrough in release (no-op).
      DebugDrawerHost {
        // Bug reporter overlay (edge tab + review/annotate sheets) wraps the app
        // content INSIDE the drawer host; pure passthrough in release.
        BugReporterWrapped {
        FeedApp(
          store = selectorStore,
          commands = stableCommands,
          platformActions = stablePlatformActions,
        )
        }
      }
    }
  }

}

/** Atomically extracts and consumes the one-shot local-notification navigation payload. */
internal fun Intent.consumeNotificationTarget(): DeepLinkTarget? {
  val hubId = getStringExtra(AndroidLocalNotifier.EXTRA_HUB_ID) ?: return null
  val blockId = getStringExtra(AndroidLocalNotifier.EXTRA_BLOCK_ID)
  removeExtra(AndroidLocalNotifier.EXTRA_HUB_ID)
  removeExtra(AndroidLocalNotifier.EXTRA_BLOCK_ID)
  return DeepLinkTarget(hubId = hubId, blockId = blockId)
}
