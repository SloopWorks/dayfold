package com.familyai.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

// Desktop shell — owns the store + SyncEngine; UI = f(store.state) via FeedApp.
fun main() = application {
  val store = remember { createAppStore() }
  val engine = remember {
    val cs = ContentStore(DriverFactory().createDriver())   // factory applies the schema
    val api = System.getenv("FAMILYAI_API"); val fam = System.getenv("FAMILY_ID"); val sec = System.getenv("HOUSEHOLD_SECRET")
    SyncEngine(store, cs, SyncClient(api ?: "", fam ?: "", sec ?: ""))
  }
  DisposableEffect(Unit) {
    engine.start()
    if (System.getenv("FAMILYAI_API") != null) engine.resume()
    onDispose { engine.stop() }
  }
  val actions = remember { com.familyai.client.cards.PlatformActions() }
  Window(onCloseRequest = ::exitApplication, title = "family-ai-dashboard") {
    MaterialTheme { FeedApp(store, actions::perform) }
  }
}
