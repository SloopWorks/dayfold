package com.familyai.client.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.familyai.client.FeedApp
import com.familyai.client.SyncClient
import com.familyai.client.createAppStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.reduxkotlin.devtools.inapp.DevToolsTrigger
import org.reduxkotlin.devtools.inapp.InAppConfig
import org.reduxkotlin.devtools.inapp.ReduxDevToolsHost

// Android shell — owns the store + the sync effect; UI = f(store.state) via the
// SHARED FeedApp (reused from apps/client). The desktop/iOS shells do the same.
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      val store = remember { createAppStore() }
      LaunchedEffect(Unit) {
        val api = BuildConfig.FAMILYAI_API
        val fam = BuildConfig.FAMILY_ID
        val sec = BuildConfig.HOUSEHOLD_SECRET
        if (api.isNotEmpty() && fam.isNotEmpty() && sec.isNotEmpty()) {
          withContext(Dispatchers.IO) { SyncClient(api, fam, sec).sync(store) }
        }
      }
      // In-app redux devtools drawer (debug build) — a BUBBLE trigger floats
      // over the UI; tap to inspect the action log / state / time-travel. The
      // release build links the no-op facade, so this compiles to nothing.
      ReduxDevToolsHost(InAppConfig(triggers = setOf(DevToolsTrigger.BUBBLE))) {
        MaterialTheme { FeedApp(store) }
      }
    }
  }
}
