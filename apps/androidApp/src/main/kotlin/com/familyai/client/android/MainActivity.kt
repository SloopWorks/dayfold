package com.familyai.client.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.familyai.client.FeedScreen
import com.familyai.client.SyncClient
import com.familyai.client.createAppStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Android shell — hosts the SHARED FeedScreen + redux store + SyncClient
// (reused from apps/client). The desktop/iOS shells do the same.
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val store = createAppStore()
    setContent {
      var state by remember { mutableStateOf(store.state) }
      DisposableEffect(Unit) {
        val unsub = store.subscribe { state = store.state }
        onDispose { unsub() }
      }
      LaunchedEffect(Unit) {
        val api = BuildConfig.FAMILYAI_API
        val fam = BuildConfig.FAMILY_ID
        val sec = BuildConfig.HOUSEHOLD_SECRET
        if (api.isNotEmpty() && fam.isNotEmpty() && sec.isNotEmpty()) {
          withContext(Dispatchers.IO) { SyncClient(api, fam, sec).sync(store) }
        }
      }
      MaterialTheme { FeedScreen(state) }
    }
  }
}
