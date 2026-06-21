package com.familyai.client

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.launch
import java.io.File

// Desktop shell — owns the store + AuthEngine + SyncEngine; UI = f(store.state)
// via FeedApp. AUTH-S5: the route gate drives sign-in/onboarding/feed; sync uses
// the session access token + active family (legacy env as a dev fallback).
fun main() = application {
  val api = System.getenv("FAMILYAI_API") ?: ""
  val store = remember { createAppStore() }
  val tokenStore = remember {
    FileTokenStore(File(System.getProperty("user.home"), ".family-ai-dashboard/session.json"))
  }
  val authEngine = remember {
    AuthEngine(store, AuthClient(api), tokenStore, devSecret = System.getenv("DEV_AUTH_SECRET"))
  }
  val syncEngine = remember {
    val cs = ContentStore(DriverFactory().createDriver())   // factory applies the schema
    val legacyFam = System.getenv("FAMILY_ID"); val legacySecret = System.getenv("HOUSEHOLD_SECRET")
    val client = SyncClient(
      api,
      familyId = { store.state.activeFamilyId ?: legacyFam },
      token = { store.state.session?.access ?: legacySecret },
    )
    SyncEngine(store, cs, client)
  }
  val scope = rememberCoroutineScope()

  LaunchedEffect(Unit) {
    syncEngine.start()                 // DB→store bridge (instant, offline)
    authEngine.restore()               // token store → whoami → route
    syncEngine.resume()                // immediate sync + 45s poll (idles until authed)
  }
  val actions = remember { com.familyai.client.cards.PlatformActions() }
  Window(onCloseRequest = ::exitApplication, title = "Dayfold") {
    FeedApp(
      store,
      onPlatformAction = actions::perform,
      onSignIn = { provider -> scope.launch { authEngine.signIn(provider); syncEngine.syncNow() } },
      onCreateFamily = { name -> scope.launch { authEngine.createFamily(name); syncEngine.syncNow() } },
      onSignOut = { scope.launch { authEngine.signOut() } },
      onRedeemInvite = { token -> scope.launch { authEngine.redeemInvite(token) } },
      onLoadApprovals = { scope.launch { store.state.activeFamilyId?.let { authEngine.loadApprovals(it) } } },
      onApproveMember = { uid -> scope.launch { store.state.activeFamilyId?.let { authEngine.approveMember(it, uid) } } },
      onDeclineMember = { uid -> scope.launch { store.state.activeFamilyId?.let { authEngine.declineMember(it, uid) } } },
      onLoadMembers = { scope.launch { store.state.activeFamilyId?.let { authEngine.loadMembers(it) } } },
      onRemoveMember = { uid -> scope.launch { store.state.activeFamilyId?.let { authEngine.removeMember(it, uid) } } },
    )
  }
}
