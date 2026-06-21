package com.familyai.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

// iOS entry — renders the SHARED FeedApp from the cached DB (bridge only). Sync
// config plumbing (api/family/secret) is deferred → no resume()/network this slice.
fun MainViewController(): UIViewController = ComposeUIViewController {
  val store = remember { createAppStore() }
  val engine = remember {
    SyncEngine(store, ContentStore(DriverFactory().createDriver()), SyncClient("", "", ""))
  }
  val actions = remember { com.familyai.client.cards.PlatformActions() }
  DisposableEffect(Unit) { engine.start(); onDispose { engine.stop() } }
  MaterialTheme { FeedApp(store, actions::perform) }
}
