package com.sloopworks.dayfold.client

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.sloopworks.dayfold.client.fake.fakeClientForApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch
import java.io.File

// Desktop shell — owns the store + AuthEngine + SyncEngine; UI = f(store.state)
// via FeedApp. AUTH-S5: the route gate drives sign-in/onboarding/feed; sync uses
// the session access token + active family (legacy env as a dev fallback).
fun main() = application {
  val api = System.getenv("DAYFOLD_API") ?: ""
  // Fake backend (debug UI testing): DAYFOLD_API=fake://<scenario> routes ALL
  // transport through an in-process MockEngine instead of the network. fakeHttp is
  // null for real URLs → the shared real HttpClient is used. Clients only need a
  // well-formed base for URL building (MockEngine routes by path).
  val fakeHttp = remember { fakeClientForApi(api) }
  val isFake = fakeHttp != null
  val http = remember { fakeHttp ?: HttpClient() }
  val clientApi = if (isFake) "http://fake.local" else api
  val store = remember { createAppStore() }
  val tokenStore = remember {
    FileTokenStore(File(System.getProperty("user.home"), ".dayfold/session.json"))
  }
  val cs = remember { ContentStore(DriverFactory().createDriver()) }  // shared DB across engines
  val authEngine = remember {
    // Fake mode forces the dev-token path (devSecret non-null; no Firebase on desktop
    // anyway) → any provider tap lands on the scenario's session.
    AuthEngine(store, AuthClient(clientApi, http), tokenStore,
      devSecret = if (isFake) "fake" else System.getenv("DEV_AUTH_SECRET"),
      // Data-boundary: wipe the local cache on logout / dead session (see AuthEngine.clearCache).
      clearCache = { cs.wipe() })
  }
  val syncEngine = remember {
    val legacyFam = System.getenv("FAMILY_ID"); val legacySecret = System.getenv("HOUSEHOLD_SECRET")
    val client = SyncClient(
      clientApi,
      familyId = { store.state.activeFamilyId ?: legacyFam },
      token = { store.state.session?.access ?: legacySecret },
      http = http,
    )
    SyncEngine(store, cs, client, authClient = AuthClient(clientApi, http), tokenStore = tokenStore)
  }
  val hubEngine = remember {  // ADR 0006 render — PR2: DB-fed via contentStore + syncEngine
    HubEngine(store, HubClient(clientApi, http), AuthClient(clientApi, http), tokenStore, cs, syncEngine)
  }
  val scope = rememberCoroutineScope()

  LaunchedEffect(Unit) {
    // Fake mode: start clean so a prior run's rows (the desktop DB persists under
    // ~/.dayfold) don't bleed into the scenario; the fake /sync repopulates.
    if (isFake) cs.wipe()
    syncEngine.start()                 // DB→store bridge (instant, offline)
    authEngine.restore()               // token store → whoami → route
    syncEngine.resume()                // immediate sync + 45s poll (idles until authed) — always-on intentional: no true background on desktop
  }
  val actions = remember { com.sloopworks.dayfold.client.cards.PlatformActions() }
  Window(onCloseRequest = ::exitApplication, title = "Dayfold") {
    FeedApp(
      store,
      onPlatformAction = actions::perform,
      onOpenUri = actions::openUri,
      onSignIn = { provider -> scope.launch { authEngine.signIn(provider); syncEngine.syncNow() } },
      // Desktop has no release variant (dev tool) → always offer the fake sign-in.
      onDevSignIn = { scope.launch { authEngine.devSignIn(); syncEngine.syncNow() } },
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
      onLoadHubs = { scope.launch { syncEngine.syncNow() } },  // PR1: hub list is DB-fed via the bridge
      onOpenHub = { id, block -> scope.launch { hubEngine.openHub(id, block) } },
      onCloseHub = { scope.launch { hubEngine.closeHub() } },  // PR2: cancel tree subscription
      onLoadAudience = { id -> scope.launch { hubEngine.loadAudience(id) } },
    )
  }
}
