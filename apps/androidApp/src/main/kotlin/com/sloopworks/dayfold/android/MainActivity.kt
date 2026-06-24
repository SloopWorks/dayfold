package com.sloopworks.dayfold.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sloopworks.dayfold.client.AndroidTokenStore
import com.sloopworks.dayfold.client.AuthClient
import com.sloopworks.dayfold.client.AuthEngine
import com.sloopworks.dayfold.client.ContentStore
import com.sloopworks.dayfold.client.DriverFactory
import com.sloopworks.dayfold.client.FeedApp
import com.sloopworks.dayfold.client.SyncClient
import com.sloopworks.dayfold.client.SyncEngine
import com.sloopworks.dayfold.client.createAppStore
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.reduxkotlin.devtools.inapp.DevToolsTrigger
import org.reduxkotlin.devtools.inapp.InAppConfig
import org.reduxkotlin.devtools.inapp.ReduxDevToolsHost

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
    val store = createAppStore()
    val cs = ContentStore(DriverFactory(applicationContext).createDriver())
    // Debug-only: seed the DB with sample cards so the content UI (cards/detail/
    // transition) is exercisable on-device without a live API. Sync only adds/
    // tombstones, never wipes these. NOTE (post AUTH-S5): the route gate hides the
    // feed until Route.Feed (signed-in + active family) — sign in via the dev path
    // to reach the seeded feed.
    if (BuildConfig.DEBUG && BuildConfig.FAMILY_ID.isEmpty()) {
      cs.applyDelta(com.sloopworks.dayfold.client.SampleData.cards, emptyList(), null, "2026-06-20T10:00:00Z")
    }
    val tokenStore = AndroidTokenStore(applicationContext)
    authEngine = AuthEngine(
      store, AuthClient(BuildConfig.DAYFOLD_API), tokenStore,
      devSecret = BuildConfig.DEV_AUTH_SECRET.ifEmpty { null },
      // S2 (ADR 0023/0027): real Google sign-in via Credential Manager + Firebase.
      // Activity context (this) is required for the account-picker UI. webClientId
      // comes from google-services.json (default_web_client_id). When the seam
      // yields a token, AuthEngine uses /auth/firebase; else it falls back to dev-token.
      firebaseSignIn = AndroidFirebaseSignIn(this, getString(R.string.default_web_client_id)),
    )
    val legacyFam = BuildConfig.FAMILY_ID; val legacySecret = BuildConfig.HOUSEHOLD_SECRET
    val syncEngine = SyncEngine(
      store, cs,
      SyncClient(
        BuildConfig.DAYFOLD_API,
        familyId = { store.state.activeFamilyId ?: legacyFam.ifEmpty { null } },
        token = { store.state.session?.access ?: legacySecret.ifEmpty { null } },
      ),
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
    val actions = com.sloopworks.dayfold.client.cards.PlatformActions(applicationContext)
    setContent {
      // Debug in-app redux devtools drawer (restored: the matrix is now Compose-MP
      // 1.11.1, which the alpha01 host requires — the earlier 1.9.3 skew is gone).
      ReduxDevToolsHost(InAppConfig(triggers = setOf(DevToolsTrigger.BUBBLE))) {
        FeedApp(
          store,
          onPlatformAction = actions::perform,
          onSignIn = { provider -> lifecycleScope.launch { authEngine.signIn(provider); syncEngine.syncNow() } },
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
        )
      }
    }
  }
}
