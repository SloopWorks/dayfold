package com.sloopworks.dayfold.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sloopworks.dayfold.client.AndroidTokenStore
import com.sloopworks.dayfold.client.AuthClient
import com.sloopworks.dayfold.client.AuthEngine
import com.sloopworks.dayfold.client.ContentStore
import com.sloopworks.dayfold.client.DriverFactory
import com.sloopworks.dayfold.client.FeedApp
import com.sloopworks.dayfold.client.HubClient
import com.sloopworks.dayfold.client.HubEngine
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

  // S6-D Tier 2: a verified App Link (https://<api-origin>/device?user_code=…) hands
  // the raw URL to the engine, which parses the code and either looks it up (signed
  // in) or stashes it to resume after sign-in. singleTask → warm taps arrive here.
  private fun handleDeepLink(intent: Intent?) {
    val data = intent?.data?.toString() ?: return
    lifecycleScope.launch { authEngine.openDeviceLink(data) }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleDeepLink(intent)
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
    val store = createAppStore()
    val cs = ContentStore(DriverFactory(applicationContext).createDriver())  // shared DB
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
        try { awaitCancellation() } finally { syncEngine.pause() }
      }
    }
    val hubEngine = HubEngine(   // ADR 0006 render — PR2: DB-fed
      store, HubClient(clientApi, http),
      AuthClient(clientApi, http), tokenStore, cs, syncEngine,
    )
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
          onLoadHubs = { lifecycleScope.launch { syncEngine.syncNow() } },  // PR1: hub list is DB-fed via the bridge
          onOpenHub = { id, block -> lifecycleScope.launch { hubEngine.openHub(id, block) } },
          onCloseHub = { lifecycleScope.launch { hubEngine.closeHub() } },  // PR2: cancel tree subscription
          onLoadAudience = { id -> lifecycleScope.launch { hubEngine.loadAudience(id) } },
        )
      }
    }
  }
}
