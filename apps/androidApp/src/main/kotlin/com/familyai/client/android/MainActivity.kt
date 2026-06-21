package com.familyai.client.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.familyai.client.ContentStore
import com.familyai.client.DriverFactory
import com.familyai.client.FeedApp
import com.familyai.client.SyncClient
import com.familyai.client.SyncEngine
import com.familyai.client.createAppStore
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.reduxkotlin.devtools.inapp.DevToolsTrigger
import org.reduxkotlin.devtools.inapp.InAppConfig
import org.reduxkotlin.devtools.inapp.ReduxDevToolsHost

// Android shell — owns the store + SyncEngine. repeatOnLifecycle(STARTED) maps
// the Activity foreground/background to engine.resume()/pause().
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val store = createAppStore()
    val cs = ContentStore(DriverFactory(applicationContext).createDriver())
    // Debug-only: seed the DB with sample cards so the UI (cards/detail/transition)
    // is exercisable on-device without a live API. Sync (if reachable) only
    // adds/tombstones, never wipes these — so they persist when no server is up.
    if (BuildConfig.DEBUG && BuildConfig.FAMILY_ID.isEmpty()) {
      cs.applyDelta(com.familyai.client.SampleData.cards, emptyList(), null, "2026-06-20T10:00:00Z")
    }
    val engine = SyncEngine(
      store, cs,
      SyncClient(BuildConfig.FAMILYAI_API, BuildConfig.FAMILY_ID, BuildConfig.HOUSEHOLD_SECRET),
    )
    engine.start()
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        if (BuildConfig.FAMILYAI_API.isNotEmpty()) engine.resume()
        try { awaitCancellation() } finally { engine.pause() }
      }
    }
    val actions = com.familyai.client.cards.PlatformActions(applicationContext)
    setContent {
      ReduxDevToolsHost(InAppConfig(triggers = setOf(DevToolsTrigger.BUBBLE))) {
        MaterialTheme { FeedApp(store, actions::perform) }
      }
    }
  }
}
